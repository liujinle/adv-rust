//! Latency harness for the native core.
//!
//! Criterion is the nicer tool, but it is a dev-dependency tree we cannot fetch
//! in offline/CI-restricted environments. This harness covers the same question
//! with explicit iteration counts: what is the median and p95 cost of the
//! operations the Android layer performs?
//!
//! It measures *module* cost, not guest app startup. End-to-end numbers need a
//! device (`docs/benchmark/plan.md`): the JNI hop, the guest process spawn and
//! ART class loading dominate the total.
//!
//! ```text
//! cargo run -p bb-bench --offline -- --iterations 5000
//! cargo run -p bb-bench --offline -- --json
//! ```
//!
//! It also prints an RSS probe for the whole engine lifecycle (`--json` includes
//! it under `memory`). RSS is not PSS: on Android the authoritative measurement
//! is `dumpsys meminfo <pkg>` / `meminfo --pss`, because the guest and host share
//! a process.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use bb_common::testing::TempDir;
use bb_common::{InstanceId, PackageName};
use bb_config::EngineConfig;
use bb_ipc::{CreateInstanceSpec, InstanceStatus, Message, Request, Response, decode, encode};
use bb_native::BbEngine;
use bb_runtime::{InstanceSpec, Runtime, RuntimeLimits};
use bb_scheduler::{Scheduler, SchedulerLimits};
use bb_storage::MemoryStore;
use bb_vfs::Vfs;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
struct MemoryReport {
    instances: usize,
    baseline_kib: Option<u64>,
    after_engine_init_kib: Option<u64>,
    after_instances_kib: Option<u64>,
    after_shutdown_kib: Option<u64>,
    engine_overhead_kib: Option<i64>,
    instances_overhead_kib: Option<i64>,
}

fn delta(after: Option<u64>, before: Option<u64>) -> Option<i64> {
    Some(after? as i64 - before? as i64)
}

fn memory_probe(dir: &TempDir, instances: usize) -> MemoryReport {
    let baseline = rss_kib();
    let apk_path = dir.join("mem-probe.apk");
    if std::fs::write(&apk_path, bb_apk::fixtures::minimal_apk()).is_err() {
        return MemoryReport {
            instances: 0,
            baseline_kib: baseline,
            after_engine_init_kib: None,
            after_instances_kib: None,
            after_shutdown_kib: None,
            engine_overhead_kib: None,
            instances_overhead_kib: None,
        };
    }

    let engine = BbEngine::new();
    if engine
        .initialize(EngineConfig::new(dir.join("mem-root")))
        .is_err()
    {
        return MemoryReport {
            instances: 0,
            baseline_kib: baseline,
            after_engine_init_kib: None,
            after_instances_kib: None,
            after_shutdown_kib: None,
            engine_overhead_kib: None,
            instances_overhead_kib: None,
        };
    }
    let after_init = rss_kib();
    let mut created = 0usize;
    for index in 0..instances {
        let spec = CreateInstanceSpec {
            instance_id: format!("mem_{index:03}"),
            package_name: "com.example.app".to_string(),
            apk_path: apk_path.to_string_lossy().to_string(),
            policy: None,
        };
        if engine.create_instance(spec).is_err() {
            break;
        }
        created += 1;
    }
    let after_instances = rss_kib();
    let _ = engine.shutdown();
    let after_shutdown = rss_kib();

    MemoryReport {
        instances: created,
        baseline_kib: baseline,
        after_engine_init_kib: after_init,
        after_instances_kib: after_instances,
        after_shutdown_kib: after_shutdown,
        engine_overhead_kib: delta(after_init, baseline),
        instances_overhead_kib: delta(after_instances, after_init),
    }
}

/// Resident set size in KiB of the current process.
///
/// Windows uses `K32GetProcessMemoryInfo` from kernel32 (no extra crate);
/// Linux/Android read `/proc/self/status`. Other platforms return `None` instead
/// of guessing.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn rss_kib() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("VmRSS:") {
            return rest.split_whitespace().next()?.parse().ok();
        }
    }
    None
}

#[cfg(windows)]
fn rss_kib() -> Option<u64> {
    use std::os::raw::c_void;

    #[repr(C)]
    #[derive(Default)]
    struct ProcessMemoryCounters {
        cb: u32,
        page_fault_count: u32,
        peak_working_set_size: usize,
        working_set_size: usize,
        quota_peak_paged_pool_usage: usize,
        quota_paged_pool_usage: usize,
        quota_peak_non_paged_pool_usage: usize,
        quota_non_paged_pool_usage: usize,
        pagefile_usage: usize,
        peak_pagefile_usage: usize,
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentProcess() -> *mut c_void;
        fn K32GetProcessMemoryInfo(
            process: *mut c_void,
            counters: *mut ProcessMemoryCounters,
            cb: u32,
        ) -> i32;
    }

    let mut counters = ProcessMemoryCounters {
        cb: std::mem::size_of::<ProcessMemoryCounters>() as u32,
        ..Default::default()
    };
    // SAFETY: documented Win32 call; the struct matches PROCESS_MEMORY_COUNTERS.
    let ok = unsafe { K32GetProcessMemoryInfo(GetCurrentProcess(), &mut counters, counters.cb) };
    if ok == 0 {
        None
    } else {
        Some((counters.working_set_size / 1024) as u64)
    }
}

#[cfg(not(any(windows, target_os = "linux", target_os = "android")))]
fn rss_kib() -> Option<u64> {
    None
}

#[derive(Debug, Clone, Serialize)]
struct Report {
    name: &'static str,
    iterations: usize,
    median_us: f64,
    p95_us: f64,
    max_us: f64,
    ops_per_second: f64,
}

fn percentile(sorted: &[f64], percent: f64) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let rank = ((percent / 100.0) * sorted.len() as f64).ceil() as usize;
    sorted[rank.saturating_sub(1).min(sorted.len() - 1)]
}

fn measure<F: FnMut()>(name: &'static str, iterations: usize, mut operation: F) -> Report {
    // Warm-up: first-touch page faults and lazy statics must not be attributed to
    // the measurement.
    for _ in 0..(iterations / 10).max(1) {
        operation();
    }
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let start = Instant::now();
        operation();
        samples.push(start.elapsed().as_secs_f64() * 1_000_000.0);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let total: f64 = samples.iter().sum();
    Report {
        name,
        iterations,
        median_us: percentile(&samples, 50.0),
        p95_us: percentile(&samples, 95.0),
        max_us: *samples.last().unwrap(),
        ops_per_second: if total > 0.0 {
            iterations as f64 / (total / 1_000_000.0)
        } else {
            0.0
        },
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let json_output = args.iter().any(|arg| arg == "--json");
    let iterations = args
        .iter()
        .position(|arg| arg == "--iterations")
        .and_then(|index| args.get(index + 1))
        .and_then(|value| value.parse::<usize>().ok())
        .unwrap_or(2_000)
        .max(50);

    let dir = TempDir::new("bench");
    let mut reports = Vec::new();

    // --- config ------------------------------------------------------------
    let config_json = EngineConfig::new(dir.path()).to_json().unwrap();
    reports.push(measure("config.load_json", iterations, || {
        let config = EngineConfig::from_json(&config_json).unwrap();
        std::hint::black_box(config);
    }));

    // --- vfs ---------------------------------------------------------------
    let vfs = Vfs::new(dir.join("virtual")).unwrap();
    let id = InstanceId::parse("instance_001").unwrap();
    vfs.ensure_layout(&id).unwrap();
    reports.push(measure("vfs.resolve", iterations, || {
        let path = vfs.resolve(&id, "files/documents/report-2026.pdf").unwrap();
        std::hint::black_box(path);
    }));

    vfs.write_file(&id, "cache/blob.bin", &[7u8; 16 * 1024])
        .unwrap();
    reports.push(measure("vfs.read_file_16k", iterations, || {
        let bytes = vfs.read_file(&id, "cache/blob.bin").unwrap();
        std::hint::black_box(bytes);
    }));
    reports.push(measure("vfs.usage_scan", iterations, || {
        let usage = vfs.usage(&id).unwrap();
        std::hint::black_box(usage);
    }));

    // --- ipc ---------------------------------------------------------------
    let request = Message::request(Request::CreateInstance {
        spec: CreateInstanceSpec {
            instance_id: "instance_001".to_string(),
            package_name: "com.example.app".to_string(),
            apk_path: "/data/user/0/com.adv.bb/files/app.apk".to_string(),
            policy: None,
        },
    });
    let encoded_request = encode(&request).unwrap();
    reports.push(measure("ipc.encode_request", iterations, || {
        let bytes = encode(&request).unwrap();
        std::hint::black_box(bytes);
    }));
    reports.push(measure("ipc.decode_request", iterations, || {
        let message = decode(&encoded_request).unwrap();
        std::hint::black_box(message);
    }));

    let statuses: Vec<InstanceStatus> = (0..32)
        .map(|index| InstanceStatus {
            instance_id: format!("instance_{index:03}"),
            package_name: "com.example.app".to_string(),
            state: 5,
            state_name: "running".to_string(),
            refs: 2,
            updated_at_ms: 1_792_000_000_000,
            last_error: None,
        })
        .collect();
    let response = Message::response(Response::Instances {
        instances: statuses,
    });
    let encoded_response = encode(&response).unwrap();
    reports.push(measure("ipc.encode_32_instances", iterations, || {
        let bytes = encode(&response).unwrap();
        std::hint::black_box(bytes);
    }));
    reports.push(measure("ipc.decode_32_instances", iterations, || {
        let message = decode(&encoded_response).unwrap();
        std::hint::black_box(message);
    }));
    // --- runtime -----------------------------------------------------------
    let runtime = Runtime::new(Arc::new(MemoryStore::new()), RuntimeLimits::default()).unwrap();
    for index in 0..64 {
        runtime
            .create(InstanceSpec {
                instance_id: InstanceId::parse(format!("instance_{index:03}")).unwrap(),
                package_name: PackageName::parse("com.example.app").unwrap(),
                apk_path: PathBuf::from("/sdcard/app.apk"),
                policy: None,
                apk_version_code: Some(1),
                signer_fingerprint: None,
            })
            .unwrap();
    }
    reports.push(measure("runtime.get_instance", iterations, || {
        let record = runtime.get("instance_032").unwrap();
        std::hint::black_box(record);
    }));
    reports.push(measure("runtime.list_64_instances", iterations, || {
        let records = runtime.list();
        std::hint::black_box(records);
    }));

    // --- apk ---------------------------------------------------------------
    let apk_bytes = bb_apk::fixtures::minimal_apk();
    let apk_path = dir.join("bench.apk");
    std::fs::write(&apk_path, &apk_bytes).unwrap();
    reports.push(measure(
        "apk.inspect_from_disk",
        iterations.min(500),
        || {
            let info = bb_apk::inspect(&apk_path).unwrap();
            std::hint::black_box(info);
        },
    ));
    reports.push(measure(
        "apk.inspect_from_memory",
        iterations.min(500),
        || {
            let info = bb_apk::inspect_bytes(apk_bytes.clone()).unwrap();
            std::hint::black_box(info);
        },
    ));

    // --- resource cache ----------------------------------------------------
    let cache = bb_resource::ResourceCache::open(dir.join("cache"), 64 * 1024 * 1024, 0).unwrap();
    cache.put("axml", "manifest", &[3u8; 4096]).unwrap();
    reports.push(measure("cache.get_4k", iterations, || {
        let bytes = cache.get("axml", "manifest").unwrap();
        std::hint::black_box(bytes);
    }));

    // --- metrics -----------------------------------------------------------
    let metrics = bb_monitor::Metrics::new(256);
    reports.push(measure("metrics.incr", iterations, || {
        metrics.incr("bench.counter", 1);
    }));
    reports.push(measure("metrics.snapshot", iterations.min(500), || {
        let snapshot = metrics.snapshot();
        std::hint::black_box(snapshot.counters.len());
    }));

    // --- scheduler ---------------------------------------------------------
    let scheduler = Scheduler::with_limits(SchedulerLimits {
        threads: 2,
        max_queued: 4096,
        max_retained: 1024,
    })
    .unwrap();
    reports.push(measure(
        "scheduler.submit_and_wait",
        iterations.min(1_000),
        || {
            let task = scheduler.submit("bench", |_| Ok(Vec::new())).unwrap();
            let outcome = scheduler.wait(task, Duration::from_secs(5)).unwrap();
            std::hint::black_box(outcome.state);
        },
    ));
    scheduler.shutdown();

    // --- engine memory -----------------------------------------------------
    // Kept small: every instance registers APK metadata and VFS state, so this
    // answers "cost per instance", not "stress the engine".
    let memory = memory_probe(&dir, 8);

    if json_output {
        let payload = serde_json::json!({
            "iterations": iterations,
            "reports": reports,
            "memory": memory,
        });
        println!("{}", serde_json::to_string_pretty(&payload).unwrap());
    } else {
        print_table(&reports);
        print_memory(&memory);
    }
}

fn print_memory(report: &MemoryReport) {
    println!();
    println!("engine memory (RSS, KiB)");
    println!("{}", "-".repeat(48));
    match report.baseline_kib {
        None => {
            println!("RSS probe unsupported on this platform");
            return;
        }
        Some(_) => {}
    }
    let row = |label: &str, value: Option<u64>| match value {
        Some(kib) => println!("{label:<26} {kib:>10}"),
        None => println!("{label:<26} {:>10}", "n/a"),
    };
    row("baseline", report.baseline_kib);
    row("after engine init", report.after_engine_init_kib);
    row(
        &format!("after {} instances", report.instances),
        report.after_instances_kib,
    );
    row("after shutdown", report.after_shutdown_kib);
    let signed = |label: &str, value: Option<i64>| match value {
        Some(kib) => println!("{label:<26} {kib:>+10}"),
        None => println!("{label:<26} {:>10}", "n/a"),
    };
    signed("engine overhead", report.engine_overhead_kib);
    signed("instances overhead", report.instances_overhead_kib);
    println!();
    println!("note: RSS != PSS; on Android use `dumpsys meminfo <pkg>`");
}

fn print_table(reports: &[Report]) {
    let mut widths: BTreeMap<&str, usize> = BTreeMap::new();
    widths.insert("metric", 26);
    println!(
        "{:<26} {:>8} {:>12} {:>12} {:>12} {:>12}",
        "metric", "iters", "median_us", "p95_us", "max_us", "ops/s"
    );
    println!("{}", "-".repeat(88));
    for report in reports {
        println!(
            "{:<26} {:>8} {:>12.2} {:>12.2} {:>12.2} {:>12.0}",
            report.name,
            report.iterations,
            report.median_us,
            report.p95_us,
            report.max_us,
            report.ops_per_second
        );
    }
}
