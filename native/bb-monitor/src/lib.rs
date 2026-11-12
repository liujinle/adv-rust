//! Engine metrics.
//!
//! The point is to answer "where did the launch time go?" without a profiler
//! attached: counters for volume, gauges for instantaneous values, and bounded
//! latency histograms for the operations we care about (path resolution, APK
//! parsing, IPC encode/decode, scheduler wait).
//!
//! Everything is behind one `Mutex`. That is intentional: metrics must never
//! become a contention source of their own, and the critical sections here are a
//! few map operations plus one push into a ring buffer.

use std::collections::{BTreeMap, VecDeque};
use std::sync::Mutex;
use std::time::Duration;

use bb_common::error::{BbError, Result};
use bb_common::time::{Stopwatch, now_millis};
use serde::Serialize;

/// Default number of retained latency samples per metric name.
pub const DEFAULT_HISTORY: usize = 256;

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct HistogramSummary {
    pub count: u64,
    pub min_us: u32,
    pub max_us: u32,
    pub mean_us: f64,
    pub p50_us: u32,
    pub p95_us: u32,
    pub p99_us: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct MetricsSnapshot {
    pub captured_at_ms: u64,
    pub counters: BTreeMap<String, u64>,
    pub gauges: BTreeMap<String, f64>,
    pub histograms: BTreeMap<String, HistogramSummary>,
}

#[derive(Debug)]
struct Histogram {
    samples: VecDeque<u32>,
    count: u64,
    min_us: u32,
    max_us: u32,
    total_us: u64,
}

impl Default for Histogram {
    fn default() -> Self {
        Self::new()
    }
}

impl Histogram {
    fn new() -> Self {
        Self {
            samples: VecDeque::new(),
            count: 0,
            min_us: u32::MAX,
            max_us: 0,
            total_us: 0,
        }
    }

    fn record(&mut self, value_us: u32, capacity: usize) {
        self.count += 1;
        self.min_us = self.min_us.min(value_us);
        self.max_us = self.max_us.max(value_us);
        self.total_us = self.total_us.saturating_add(value_us as u64);
        if self.samples.len() == capacity {
            self.samples.pop_front();
        }
        self.samples.push_back(value_us);
    }

    fn summarize(&self) -> HistogramSummary {
        let mut sorted: Vec<u32> = self.samples.iter().copied().collect();
        sorted.sort_unstable();
        HistogramSummary {
            count: self.count,
            min_us: if self.count == 0 { 0 } else { self.min_us },
            max_us: self.max_us,
            mean_us: if self.count == 0 {
                0.0
            } else {
                self.total_us as f64 / self.count as f64
            },
            p50_us: percentile(&sorted, 50),
            p95_us: percentile(&sorted, 95),
            p99_us: percentile(&sorted, 99),
        }
    }
}

/// Nearest-rank percentile over an ascending slice.
fn percentile(sorted: &[u32], percent: usize) -> u32 {
    if sorted.is_empty() {
        return 0;
    }
    let rank = ((percent as f64 / 100.0) * sorted.len() as f64).ceil() as usize;
    let index = rank.saturating_sub(1).min(sorted.len() - 1);
    sorted[index]
}

#[derive(Debug, Default)]
struct Inner {
    counters: BTreeMap<String, u64>,
    gauges: BTreeMap<String, f64>,
    histograms: BTreeMap<String, Histogram>,
}

pub struct Metrics {
    inner: Mutex<Inner>,
    history: usize,
}

impl std::fmt::Debug for Metrics {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Metrics")
            .field("history", &self.history)
            .finish_non_exhaustive()
    }
}

impl Metrics {
    pub fn new(history: usize) -> Self {
        Self {
            inner: Mutex::new(Inner::default()),
            history: history.max(16),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        // A poisoned metrics lock must never take the engine down: metrics are
        // diagnostics, and the data behind the lock is still structurally sound.
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn incr(&self, name: &str, delta: u64) {
        let mut inner = self.lock();
        let counter = inner.counters.entry(name.to_string()).or_insert(0);
        *counter = counter.saturating_add(delta);
    }

    pub fn counter(&self, name: &str) -> u64 {
        self.lock().counters.get(name).copied().unwrap_or(0)
    }

    pub fn set_gauge(&self, name: &str, value: f64) {
        self.lock().gauges.insert(name.to_string(), value);
    }

    pub fn gauge(&self, name: &str) -> Option<f64> {
        self.lock().gauges.get(name).copied()
    }

    pub fn observe_us(&self, name: &str, value_us: u32) {
        let mut inner = self.lock();
        let history = self.history;
        inner
            .histograms
            .entry(name.to_string())
            .or_default()
            .record(value_us, history);
    }

    pub fn observe_duration(&self, name: &str, duration: Duration) {
        self.observe_us(name, duration.as_micros().min(u32::MAX as u128) as u32);
    }

    /// Measures a closure. The sample is recorded even if the closure panics,
    /// because `Drop` runs during unwinding.
    pub fn time<T>(&self, name: &str, operation: impl FnOnce() -> T) -> T {
        struct Guard<'a> {
            metrics: &'a Metrics,
            name: &'a str,
            watch: Stopwatch,
        }
        impl Drop for Guard<'_> {
            fn drop(&mut self) {
                self.metrics.observe_us(
                    self.name,
                    self.watch.elapsed_micros().min(u32::MAX as u64) as u32,
                );
            }
        }
        let _guard = Guard {
            metrics: self,
            name,
            watch: Stopwatch::start(),
        };
        operation()
    }

    pub fn snapshot(&self) -> MetricsSnapshot {
        let inner = self.lock();
        MetricsSnapshot {
            captured_at_ms: now_millis(),
            counters: inner.counters.clone(),
            gauges: inner.gauges.clone(),
            histograms: inner
                .histograms
                .iter()
                .map(|(name, histogram)| (name.clone(), histogram.summarize()))
                .collect(),
        }
    }

    pub fn snapshot_json(&self) -> Result<String> {
        serde_json::to_string(&self.snapshot()).map_err(BbError::from)
    }

    pub fn reset(&self) {
        let mut inner = self.lock();
        inner.counters.clear();
        inner.gauges.clear();
        inner.histograms.clear();
    }
}

impl Default for Metrics {
    fn default() -> Self {
        Self::new(DEFAULT_HISTORY)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counters_and_gauges() {
        let metrics = Metrics::new(32);
        metrics.incr("installs", 1);
        metrics.incr("installs", 2);
        assert_eq!(metrics.counter("installs"), 3);
        assert_eq!(metrics.counter("missing"), 0);
        metrics.set_gauge("instances", 4.0);
        assert_eq!(metrics.gauge("instances"), Some(4.0));
        assert_eq!(metrics.gauge("missing"), None);

        let snapshot = metrics.snapshot();
        assert_eq!(snapshot.counters.get("installs"), Some(&3));
        assert_eq!(snapshot.gauges.get("instances"), Some(&4.0));
    }

    #[test]
    fn percentiles_are_nearest_rank() {
        let metrics = Metrics::new(1000);
        for value in 1..=100u32 {
            metrics.observe_us("op", value);
        }
        let summary = metrics.snapshot().histograms.remove("op").unwrap();
        assert_eq!(summary.count, 100);
        assert_eq!(summary.min_us, 1);
        assert_eq!(summary.max_us, 100);
        assert_eq!(summary.p50_us, 50);
        assert_eq!(summary.p95_us, 95);
        assert_eq!(summary.p99_us, 99);
        assert!((summary.mean_us - 50.5).abs() < 0.001);
    }

    #[test]
    fn history_is_bounded_but_totals_are_not() {
        let metrics = Metrics::new(16);
        for value in 0..1_000u32 {
            metrics.observe_us("op", value);
        }
        let summary = metrics.snapshot().histograms.remove("op").unwrap();
        assert_eq!(summary.count, 1_000);
        // min/max/mean are all-time aggregates; percentiles only see the
        // retained window (the last 16 samples: 984..=999).
        assert_eq!(summary.min_us, 0);
        assert_eq!(summary.max_us, 999);
        assert_eq!(summary.p50_us, 991);
        assert_eq!(summary.p99_us, 999);
    }

    #[test]
    fn timer_records_even_when_the_closure_panics() {
        let metrics = Metrics::new(16);
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            metrics.time("panicky", || panic!("boom"));
        }));
        assert!(result.is_err());
        assert_eq!(
            metrics
                .snapshot()
                .histograms
                .get("panicky")
                .map(|h| h.count),
            Some(1)
        );
    }

    #[test]
    fn concurrent_updates_are_not_lost() {
        let metrics = std::sync::Arc::new(Metrics::new(64));
        let mut handles = Vec::new();
        for _ in 0..8 {
            let metrics = metrics.clone();
            handles.push(std::thread::spawn(move || {
                for _ in 0..1_000 {
                    metrics.incr("ops", 1);
                    metrics.observe_us("latency", 10);
                }
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(metrics.counter("ops"), 8_000);
        assert_eq!(
            metrics
                .snapshot()
                .histograms
                .get("latency")
                .map(|h| h.count),
            Some(8_000)
        );
    }

    #[test]
    fn snapshot_is_json_serializable_and_reset_clears() {
        let metrics = Metrics::new(32);
        metrics.incr("a", 1);
        metrics.set_gauge("b", 1.5);
        metrics.observe_duration("c", Duration::from_micros(1234));
        let json = metrics.snapshot_json().unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed["counters"]["a"], 1);
        assert_eq!(parsed["histograms"]["c"]["count"], 1);
        assert_eq!(parsed["histograms"]["c"]["p50_us"], 1234);

        metrics.reset();
        let snapshot = metrics.snapshot();
        assert!(snapshot.counters.is_empty());
        assert!(snapshot.histograms.is_empty());
    }

    #[test]
    fn empty_histogram_summarizes_to_zero() {
        let metrics = Metrics::new(16);
        metrics.observe_us("empty", 0);
        let summary = metrics.snapshot().histograms.remove("empty").unwrap();
        assert_eq!(summary.min_us, 0);
        assert_eq!(percentile(&[], 95), 0);
    }
}
