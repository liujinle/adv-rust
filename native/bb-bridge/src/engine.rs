//! Engine composition root.
//!
//! Everything the FFI layer needs lives here; `ffi.rs` and `jni.rs` are thin
//! adapters. The engine is composed of independently tested crates, so this file
//! is mostly wiring plus the few cross-cutting rules that involve more than one
//! crate:
//!
//! * an instance may only be created for an APK whose manifest package matches
//!   the requested package name;
//! * creating metadata and creating the on-disk layout succeed or fail together;
//! * removing an instance deletes metadata first, then directories;
//! * quota checks happen at launch, not at write time (writes are not proxied).

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use bb_apk::ApkInfo;
use bb_common::error::{BbError, Result};
use bb_common::logging;
use bb_common::time::now_millis;
use bb_common::{ErrorCode, InstanceId, InstanceState, PackageName, bb_ensure};
use bb_config::{EngineConfig, InstancePolicy};
use bb_ipc::{ApkSummary, CreateInstanceSpec, InstanceStatus, Request, Response, check_protocol};
use bb_monitor::Metrics;
use bb_resource::ResourceCache;
use bb_runtime::{InstanceRecord, InstanceSpec, Runtime, RuntimeLimits};
use bb_scheduler::{Scheduler, SchedulerLimits, TaskContext};
use bb_storage::{InstanceStore, JsonFileStore};
use bb_vfs::Vfs;
use serde::Deserialize;
use serde_json::json;

/// Commands accepted by [`BbEngine::submit_task`]. Each one is a JSON object with
/// a `command` tag, so Kotlin does not have to build a binary payload for
/// background work.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
pub enum TaskCommand {
    ParseApk { path: String },
    CreateInstance { spec: CreateInstanceSpec },
    RemoveInstance { instance_id: String },
    StartInstance { instance_id: String },
    StopInstance { instance_id: String },
    ListInstances,
    CleanupOrphans,
}

impl TaskCommand {
    fn name(&self) -> &'static str {
        match self {
            Self::ParseApk { .. } => "parse_apk",
            Self::CreateInstance { .. } => "create_instance",
            Self::RemoveInstance { .. } => "remove_instance",
            Self::StartInstance { .. } => "start_instance",
            Self::StopInstance { .. } => "stop_instance",
            Self::ListInstances => "list_instances",
            Self::CleanupOrphans => "cleanup_orphans",
        }
    }
}

struct EngineInner {
    config: EngineConfig,
    vfs: Vfs,
    store: Arc<JsonFileStore>,
    runtime: Arc<Runtime>,
    scheduler: Arc<Scheduler>,
    cache: ResourceCache,
    metrics: Arc<Metrics>,
    started_at_ms: u64,
    recovered_instances: usize,
    /// Set when a corrupt metadata document had to be quarantined at startup.
    store_quarantined: Option<PathBuf>,
}

impl EngineInner {
    fn open(config: EngineConfig) -> Result<Self> {
        config.validate()?;
        let root = config.root_path.clone();
        std::fs::create_dir_all(&root)?;
        logging::set_level(config.log_level);

        let vfs = Vfs::new(root.join("virtual"))?;
        // A torn write is survivable: the document is moved aside (never deleted)
        // and the engine starts with empty metadata, which `cleanup_orphans` can
        // reconcile against the directories on disk.
        let (store, recovery) =
            JsonFileStore::open_recovering(root.join("metadata").join("instances.json"))?;
        let store_quarantined = recovery.quarantined.clone();
        if let Some(path) = &store_quarantined {
            logging::log(
                logging::Level::Warn,
                "storage",
                &format!(
                    "metadata document was corrupt and has been quarantined at {}",
                    path.display()
                ),
            );
        }
        let store = Arc::new(store);
        let store_trait: Arc<dyn InstanceStore> = store.clone();
        let runtime = Arc::new(Runtime::new(
            store_trait,
            RuntimeLimits {
                max_instances: config.max_instances,
                event_window: 512,
            },
        )?);
        // A previous process may have died while an instance was running.
        let recovered_instances = runtime.recover_inconsistent()?;
        let scheduler = Arc::new(Scheduler::with_limits(SchedulerLimits {
            threads: config.io_threads,
            max_queued: 1024,
            max_retained: 256,
        })?);
        let cache = ResourceCache::open(
            root.join("cache"),
            config.cache_budget_bytes,
            config.cache_ttl_ms,
        )?;
        let metrics = Arc::new(Metrics::new(config.metrics_history));

        metrics.set_gauge("instances.total", runtime.count() as f64);
        if store_quarantined.is_some() {
            metrics.incr("store.corrupt_quarantined", 1);
        }
        Ok(Self {
            config,
            vfs,
            store,
            runtime,
            scheduler,
            cache,
            metrics,
            started_at_ms: now_millis(),
            recovered_instances,
            store_quarantined,
        })
    }

    fn status(&self, record: &InstanceRecord) -> InstanceStatus {
        InstanceStatus {
            instance_id: record.instance_id.to_string(),
            package_name: record.package_name.to_string(),
            state: record.state.as_i32(),
            state_name: record.state.as_str().to_string(),
            refs: record.refs,
            updated_at_ms: record.updated_at_ms,
            last_error: record.last_error.clone(),
        }
    }

    fn inspect_apk(&self, path: &Path) -> Result<ApkInfo> {
        self.metrics.incr("apk.inspect.requests", 1);
        let result = self.metrics.time("apk.inspect", || bb_apk::inspect(path));
        match &result {
            Ok(info) => {
                self.metrics.incr("apk.inspect.ok", 1);
                logging::log(
                    logging::Level::Info,
                    "apk",
                    &format!(
                        "inspected {} ({} bytes, {} components)",
                        info.package,
                        info.file_size,
                        info.component_count()
                    ),
                );
            }
            Err(err) => {
                self.metrics.incr("apk.inspect.errors", 1);
                logging::log(logging::Level::Warn, "apk", &err.to_string());
            }
        }
        result
    }

    fn create_instance(&self, spec: &CreateInstanceSpec) -> Result<InstanceStatus> {
        let instance_id = InstanceId::parse(spec.instance_id.clone())?;
        let package_name = PackageName::parse(spec.package_name.clone())?;
        let policy = spec
            .policy
            .clone()
            .unwrap_or_else(|| self.config.default_policy.clone());
        policy.validate()?;

        let apk_path = PathBuf::from(&spec.apk_path);
        bb_ensure!(
            apk_path.is_file(),
            ErrorCode::NotFound,
            "APK not found: {}",
            apk_path.display()
        );
        let info = self.inspect_apk(&apk_path)?;
        bb_ensure!(
            info.package == package_name.as_str(),
            ErrorCode::InvalidArgument,
            "APK belongs to '{}' but '{}' was requested",
            info.package,
            package_name
        );

        let record = self.runtime.create(InstanceSpec {
            instance_id: instance_id.clone(),
            package_name,
            apk_path: apk_path.clone(),
            policy: Some(policy),
            apk_version_code: Some(info.version_code),
            signer_fingerprint: None,
        })?;

        if let Err(err) = self.vfs.ensure_layout(&instance_id) {
            let _ = self.runtime.remove(instance_id.as_str());
            return Err(err);
        }
        if let Err(err) = self.runtime.install(instance_id.as_str()) {
            let _ = self.runtime.remove(instance_id.as_str());
            let _ = self.vfs.remove_instance(&instance_id);
            return Err(err);
        }

        self.metrics.incr("instance.created", 1);
        self.metrics
            .set_gauge("instances.total", self.runtime.count() as f64);
        let record = self.runtime.get(instance_id.as_str()).unwrap_or(record);
        Ok(self.status(&record))
    }

    fn remove_instance(&self, instance_id: &str) -> Result<bool> {
        let id = InstanceId::parse(instance_id)?;
        let removed = self.runtime.remove(instance_id)?;
        if !removed {
            return Ok(false);
        }
        match self.vfs.remove_instance(&id) {
            Ok(stats) => {
                self.metrics.incr("instance.removed", 1);
                self.metrics.incr("instance.removed_bytes", stats.bytes);
            }
            // Metadata is gone; a missing directory is already the desired state.
            Err(err) if err.code() == ErrorCode::NotFound => {}
            Err(err) => return Err(err),
        }
        self.metrics
            .set_gauge("instances.total", self.runtime.count() as f64);
        Ok(true)
    }

    fn start_instance(&self, instance_id: &str) -> Result<InstanceStatus> {
        let record = self
            .runtime
            .get(instance_id)
            .ok_or_else(|| BbError::not_found(format!("instance {instance_id} does not exist")))?;
        self.vfs
            .enforce_quota(&record.instance_id, record.policy.max_storage_bytes)?;
        self.runtime.start(instance_id)?;
        self.metrics.incr("instance.started", 1);
        let record = self
            .runtime
            .get(instance_id)
            .ok_or_else(|| BbError::not_found("instance disappeared during start"))?;
        Ok(self.status(&record))
    }

    fn stop_instance(&self, instance_id: &str) -> Result<InstanceStatus> {
        self.runtime.stop(instance_id)?;
        self.metrics.incr("instance.stopped", 1);
        let record = self
            .runtime
            .get(instance_id)
            .ok_or_else(|| BbError::not_found("instance disappeared during stop"))?;
        Ok(self.status(&record))
    }

    fn get_instance(&self, instance_id: &str) -> Result<InstanceStatus> {
        let record = self
            .runtime
            .get(instance_id)
            .ok_or_else(|| BbError::not_found(format!("instance {instance_id} does not exist")))?;
        Ok(self.status(&record))
    }

    fn list_instances(&self) -> Vec<InstanceStatus> {
        self.runtime
            .list()
            .iter()
            .map(|record| self.status(record))
            .collect()
    }

    fn cleanup_orphans(&self) -> Result<usize> {
        let known: BTreeSet<String> = self
            .runtime
            .list()
            .iter()
            .map(|record| record.instance_id.to_string())
            .collect();
        let mut removed = 0;
        for name in self.vfs.list_instance_dirs()? {
            if known.contains(&name) {
                continue;
            }
            if let Ok(id) = InstanceId::parse(name.clone()) {
                match self.vfs.remove_instance(&id) {
                    Ok(_) => removed += 1,
                    Err(err) if err.code() == ErrorCode::NotFound => {}
                    Err(err) => return Err(err),
                }
            } else {
                logging::log(
                    logging::Level::Warn,
                    "vfs",
                    &format!("ignoring orphan directory with unsafe name: {name}"),
                );
            }
        }
        if removed > 0 {
            self.metrics
                .incr("instance.orphans_removed", removed as u64);
        }
        Ok(removed)
    }

    fn dispatch(&self, request: Request) -> Result<Response> {
        match request {
            Request::Hello { protocol } => {
                check_protocol(protocol)?;
                Ok(Response::Hello {
                    protocol: bb_common::PROTOCOL_VERSION,
                    abi_version: bb_common::ABI_VERSION,
                })
            }
            Request::CreateInstance { spec } => Ok(Response::Instance {
                instance: self.create_instance(&spec)?,
            }),
            Request::RemoveInstance { instance_id } => Ok(Response::Ok {
                message: Some(if self.remove_instance(&instance_id)? {
                    "removed".to_string()
                } else {
                    "not found".to_string()
                }),
            }),
            Request::StartInstance { instance_id } => Ok(Response::Instance {
                instance: self.start_instance(&instance_id)?,
            }),
            Request::StopInstance { instance_id } => Ok(Response::Instance {
                instance: self.stop_instance(&instance_id)?,
            }),
            Request::GetInstance { instance_id } => Ok(Response::Instance {
                instance: self.get_instance(&instance_id)?,
            }),
            Request::ListInstances => Ok(Response::Instances {
                instances: self.list_instances(),
            }),
            Request::InspectApk { apk_path } => {
                let info = self.inspect_apk(&PathBuf::from(apk_path))?;
                Ok(Response::Apk {
                    apk: ApkSummary {
                        package: info.package,
                        version_code: info.version_code,
                        version_name: info.version_name,
                        min_sdk: info.min_sdk,
                        target_sdk: info.target_sdk,
                        permissions: info.permissions,
                        launcher_activity: info.launcher_activity,
                        native_abis: info.native_abis,
                        activity_count: info.activities.len() as u32,
                        file_size: info.file_size,
                    },
                })
            }
            Request::Metrics => Ok(Response::Metrics {
                snapshot_json: self.metrics.snapshot_json()?,
            }),
            Request::Events { since_seq } => Ok(Response::Events {
                events: self.runtime.events(since_seq),
            }),
            Request::Shutdown => Ok(Response::Ok {
                message: Some("scheduler drained".to_string()),
            }),
        }
    }

    fn run_task(&self, command: TaskCommand, context: &TaskContext) -> Result<Vec<u8>> {
        context.report_progress(10);
        let payload = match command {
            TaskCommand::ParseApk { path } => {
                let info = self.inspect_apk(&PathBuf::from(path))?;
                serde_json::to_vec(&info)?
            }
            TaskCommand::CreateInstance { spec } => {
                let status = self.create_instance(&spec)?;
                serde_json::to_vec(&status)?
            }
            TaskCommand::RemoveInstance { instance_id } => {
                let removed = self.remove_instance(&instance_id)?;
                serde_json::to_vec(&json!({ "removed": removed }))?
            }
            TaskCommand::StartInstance { instance_id } => {
                let status = self.start_instance(&instance_id)?;
                serde_json::to_vec(&status)?
            }
            TaskCommand::StopInstance { instance_id } => {
                let status = self.stop_instance(&instance_id)?;
                serde_json::to_vec(&status)?
            }
            TaskCommand::ListInstances => serde_json::to_vec(&self.list_instances())?,
            TaskCommand::CleanupOrphans => {
                let removed = self.cleanup_orphans()?;
                serde_json::to_vec(&json!({ "removed": removed }))?
            }
        };
        context.report_progress(95);
        Ok(payload)
    }

    fn summary(&self) -> serde_json::Value {
        json!({
            "schema_version": bb_config::SCHEMA_VERSION,
            "root_path": self.config.root_path.to_string_lossy(),
            "abi_version": bb_common::ABI_VERSION,
            "protocol_version": bb_common::PROTOCOL_VERSION,
            "core_version": env!("CARGO_PKG_VERSION"),
            "started_at_ms": self.started_at_ms,
            "instances": self.runtime.count(),
            "recovered_instances": self.recovered_instances,
            "store_quarantined_file": self
                .store_quarantined
                .as_ref()
                .map(|path| path.to_string_lossy().into_owned()),
            "tasks_pending": self.scheduler.queued(),
            "tasks_active": self.scheduler.active(),
            "cache": {
                "entries": self.cache.stats().entries,
                "bytes": self.cache.stats().total_bytes,
                "budget_bytes": self.cache.stats().budget_bytes,
            },
            "virtual_root": self.vfs.root().to_string_lossy(),
        })
    }
}

/// Public engine handle. Cheap to clone? No: it is owned by the FFI layer and
/// guarded by a `Mutex` for the initialized/uninitialized transition only.
pub struct BbEngine {
    inner: Mutex<Option<Arc<EngineInner>>>,
    last_error: Mutex<String>,
    created_at_ms: u64,
}

impl std::fmt::Debug for BbEngine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BbEngine")
            .field("initialized", &self.is_initialized())
            .finish()
    }
}

impl Default for BbEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl BbEngine {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(None),
            last_error: Mutex::new(String::new()),
            created_at_ms: now_millis(),
        }
    }

    pub fn created_at_ms(&self) -> u64 {
        self.created_at_ms
    }

    pub fn is_initialized(&self) -> bool {
        self.lock_inner().is_some()
    }

    pub fn initialize(&self, config: EngineConfig) -> Result<()> {
        let mut slot = self.lock_inner();
        bb_ensure!(
            slot.is_none(),
            ErrorCode::AlreadyExists,
            "engine is already initialized"
        );
        let inner = EngineInner::open(config)?;
        *slot = Some(Arc::new(inner));
        Ok(())
    }

    /// Stops accepting tasks and joins the worker threads. Idempotent.
    pub fn shutdown(&self) -> Result<()> {
        let inner = self.lock_inner().take();
        if let Some(inner) = inner {
            inner.scheduler.shutdown();
            inner.store.flush()?;
        }
        Ok(())
    }

    fn with_inner<T>(&self, operation: impl FnOnce(&EngineInner) -> Result<T>) -> Result<T> {
        let inner = self.snapshot()?;
        operation(&inner)
    }

    fn snapshot(&self) -> Result<Arc<EngineInner>> {
        self.lock_inner()
            .clone()
            .ok_or_else(|| BbError::not_initialized("engine has not been initialized"))
    }

    pub fn create_instance(&self, spec: CreateInstanceSpec) -> Result<InstanceStatus> {
        self.with_inner(|inner| inner.create_instance(&spec))
    }

    pub fn remove_instance(&self, instance_id: &str) -> Result<bool> {
        self.with_inner(|inner| inner.remove_instance(instance_id))
    }

    pub fn start_instance(&self, instance_id: &str) -> Result<InstanceStatus> {
        self.with_inner(|inner| inner.start_instance(instance_id))
    }

    pub fn stop_instance(&self, instance_id: &str) -> Result<InstanceStatus> {
        self.with_inner(|inner| inner.stop_instance(instance_id))
    }

    pub fn get_instance(&self, instance_id: &str) -> Result<InstanceStatus> {
        self.with_inner(|inner| inner.get_instance(instance_id))
    }

    pub fn list_instances(&self) -> Result<Vec<InstanceStatus>> {
        self.with_inner(|inner| Ok(inner.list_instances()))
    }

    pub fn inspect_apk(&self, path: &str) -> Result<ApkSummary> {
        self.with_inner(|inner| {
            let info = inner.inspect_apk(&PathBuf::from(path))?;
            Ok(ApkSummary {
                package: info.package,
                version_code: info.version_code,
                version_name: info.version_name,
                min_sdk: info.min_sdk,
                target_sdk: info.target_sdk,
                permissions: info.permissions,
                launcher_activity: info.launcher_activity,
                native_abis: info.native_abis,
                activity_count: info.activities.len() as u32,
                file_size: info.file_size,
            })
        })
    }

    pub fn metrics_json(&self) -> Result<String> {
        self.with_inner(|inner| inner.metrics.snapshot_json())
    }

    /// Lifecycle events newer than `since_seq`, serialised with the shared
    /// `bb_common::RuntimeEvent` schema (state names, not numeric codes).
    pub fn events_json(&self, since_seq: u64) -> Result<String> {
        self.with_inner(|inner| {
            Ok(serde_json::to_string(&inner.runtime.events(since_seq))?)
        })
    }

    /// Engine status blob used by the Kotlin diagnostics screen.
    pub fn summary_json(&self) -> Result<String> {
        let inner = self.snapshot()?;
        serde_json::to_string(&inner.summary()).map_err(BbError::from)
    }

    /// Generic JSON request/response path. Errors are encoded *inside* the JSON
    /// response (`{"type":"error", ...}`), matching `bb_ipc::Response`.
    pub fn request_json(&self, request_json: &str) -> Result<String> {
        let request: Request = serde_json::from_str(request_json)?;
        let response = match self.snapshot() {
            Ok(inner) => match inner.dispatch(request) {
                Ok(response) => response,
                Err(err) => Response::from_error(&err),
            },
            Err(err) => Response::from_error(&err),
        };
        serde_json::to_string(&response).map_err(BbError::from)
    }

    pub fn submit_task(&self, command_json: &str) -> Result<u64> {
        let command: TaskCommand = serde_json::from_str(command_json)
            .map_err(|err| BbError::invalid_argument(format!("invalid task command: {err}")))?;
        let inner = self.snapshot()?;
        let name = command.name();
        let metrics = inner.metrics.clone();
        metrics.incr("task.submitted", 1);
        let task_inner = inner.clone();
        let task_id = inner.scheduler.submit(name, move |context| {
            metrics.incr("task.started", 1);
            let result = task_inner.run_task(command, context);
            match &result {
                Ok(_) => metrics.incr("task.succeeded", 1),
                Err(_) => metrics.incr("task.failed", 1),
            }
            result
        })?;
        Ok(task_id)
    }

    /// Returns `None` while the task id is unknown or was pruned.
    pub fn task_result_json(&self, task_id: u64) -> Result<Option<String>> {
        let inner = self.snapshot()?;
        let Some(outcome) = inner.scheduler.status(task_id) else {
            return Ok(None);
        };
        let payload = outcome
            .payload
            .as_ref()
            .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(bytes).ok());
        let value = json!({
            "task_id": outcome.task_id,
            "name": outcome.name,
            "state": outcome.state.as_str(),
            "progress": outcome.progress,
            "result": payload,
            "error": outcome.error_code.map(|code| json!({
                "code": code,
                "name": outcome.error_name,
                "message": outcome.error_message,
            })),
            "submitted_at_ms": outcome.submitted_at_ms,
            "started_at_ms": outcome.started_at_ms,
            "finished_at_ms": outcome.finished_at_ms,
        });
        Ok(Some(serde_json::to_string(&value)?))
    }

    pub fn cancel_task(&self, task_id: u64) -> Result<bool> {
        self.with_inner(|inner| Ok(inner.scheduler.cancel(task_id)))
    }

    pub fn policy_for(&self, instance_id: &str) -> Result<InstancePolicy> {
        self.with_inner(|inner| {
            inner
                .runtime
                .get(instance_id)
                .map(|record| record.policy)
                .ok_or_else(|| BbError::not_found(format!("instance {instance_id} does not exist")))
        })
    }

    /// Last error message produced by the FFI layer (for Kotlin-side logging when
    /// only a numeric code is returned).
    pub fn last_error(&self) -> String {
        self.last_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    pub fn set_last_error(&self, message: impl Into<String>) {
        let mut slot = self
            .last_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *slot = message.into();
    }

    fn lock_inner(&self) -> MutexGuard<'_, Option<Arc<EngineInner>>> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Drop for BbEngine {
    fn drop(&mut self) {
        let _ = self.shutdown();
    }
}

/// Human readable state name, exposed so Kotlin does not have to duplicate the
/// numeric mapping.
pub fn state_name(state: i32) -> String {
    InstanceState::from_i32(state)
        .map(|state| state.as_str().to_string())
        .unwrap_or_else(|| format!("unknown({state})"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;
    use bb_ipc::CreateInstanceSpec;

    fn config(dir: &TempDir) -> EngineConfig {
        let mut config = EngineConfig::new(dir.path());
        config.max_instances = 8;
        config.io_threads = 2;
        config
    }

    fn apk(dir: &TempDir) -> String {
        let path = dir.join("app.apk");
        std::fs::write(&path, bb_apk::fixtures::minimal_apk()).unwrap();
        path.to_string_lossy().to_string()
    }

    fn spec(apk_path: &str) -> CreateInstanceSpec {
        CreateInstanceSpec {
            instance_id: "instance_001".to_string(),
            package_name: "com.example.app".to_string(),
            apk_path: apk_path.to_string(),
            policy: None,
        }
    }

    fn engine(dir: &TempDir) -> BbEngine {
        let engine = BbEngine::new();
        engine.initialize(config(dir)).unwrap();
        engine
    }

    #[test]
    fn create_start_stop_remove_cycle() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        let engine = engine(&dir);

        let status = engine.create_instance(spec(&apk_path)).unwrap();
        assert_eq!(status.state_name, "installed");
        assert_eq!(status.package_name, "com.example.app");
        assert!(dir.join("virtual/instances/instance_001/files").is_dir());

        let status = engine.start_instance("instance_001").unwrap();
        assert_eq!(status.state_name, "running");
        assert_eq!(status.refs, 1);
        let status = engine.stop_instance("instance_001").unwrap();
        assert_eq!(status.state_name, "stopped");
        assert_eq!(status.refs, 0);

        assert!(engine.remove_instance("instance_001").unwrap());
        assert!(!dir.join("virtual/instances/instance_001").exists());
        assert!(engine.list_instances().unwrap().is_empty());
        assert_eq!(
            engine.get_instance("instance_001").unwrap_err().code(),
            ErrorCode::NotFound
        );
    }

    #[test]
    fn package_mismatch_is_rejected_without_side_effects() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        let engine = engine(&dir);
        let mut bad = spec(&apk_path);
        bad.package_name = "com.other.app".to_string();
        let err = engine.create_instance(bad).unwrap_err();
        assert_eq!(err.code(), ErrorCode::InvalidArgument);
        assert!(engine.list_instances().unwrap().is_empty());
    }

    #[test]
    fn missing_apk_is_rejected() {
        let dir = TempDir::new("bridge");
        let engine = engine(&dir);
        let err = engine
            .create_instance(spec("/definitely/not/here.apk"))
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::NotFound);
    }

    #[test]
    fn uninitialized_engine_reports_state() {
        let engine = BbEngine::new();
        assert!(!engine.is_initialized());
        let err = engine.list_instances().unwrap_err();
        assert_eq!(err.code(), ErrorCode::NotInitialized);
        let response = engine.request_json(r#"{"type":"list_instances"}"#).unwrap();
        assert!(response.contains("NOT_INITIALIZED"), "{response}");
    }

    #[test]
    fn json_requests_and_tasks_work() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        let engine = engine(&dir);

        let response = engine
            .request_json(&format!(
                r#"{{"type":"inspect_apk","apk_path":{}}}"#,
                serde_json::to_string(&apk_path).unwrap()
            ))
            .unwrap();
        assert!(
            response.contains("\"package\":\"com.example.app\""),
            "{response}"
        );

        let request = json!({
            "type": "create_instance",
            "spec": {
                "instance_id": "instance_002",
                "package_name": "com.example.app",
                "apk_path": apk_path,
            }
        });
        let response = engine.request_json(&request.to_string()).unwrap();
        assert!(response.contains("instance_002"), "{response}");

        let task = engine
            .submit_task(r#"{"command":"list_instances"}"#)
            .unwrap();
        let outcome = loop {
            let json = engine.task_result_json(task).unwrap().unwrap();
            let value: serde_json::Value = serde_json::from_str(&json).unwrap();
            if value["state"] == "succeeded" || value["state"] == "failed" {
                break value;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        };
        assert_eq!(outcome["state"], "succeeded");
        assert!(outcome["result"].as_array().is_some());
        engine.shutdown().unwrap();
        assert!(!engine.is_initialized());
    }

    #[test]
    fn metadata_survives_reopen_and_orphans_are_cleaned() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        {
            let engine = engine(&dir);
            engine.create_instance(spec(&apk_path)).unwrap();
        }
        // Orphan directory: metadata gone, directory left behind.
        std::fs::create_dir_all(dir.join("virtual/instances/orphan_001/files")).unwrap();

        let engine = engine(&dir);
        let instances = engine.list_instances().unwrap();
        assert_eq!(instances.len(), 1);
        assert_eq!(instances[0].instance_id, "instance_001");

        let task = engine
            .submit_task(r#"{"command":"cleanup_orphans"}"#)
            .unwrap();
        let outcome = loop {
            let json = engine.task_result_json(task).unwrap().unwrap();
            let value: serde_json::Value = serde_json::from_str(&json).unwrap();
            if value["state"] == "succeeded" {
                break value;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        };
        assert_eq!(outcome["result"]["removed"], 1);
        assert!(!dir.join("virtual/instances/orphan_001").exists());
    }

    #[test]
    fn initialize_is_single_shot() {
        let dir = TempDir::new("bridge");
        let engine = engine(&dir);
        let err = engine.initialize(config(&dir)).unwrap_err();
        assert_eq!(err.code(), ErrorCode::AlreadyExists);
    }

    #[test]
    fn metrics_and_summary_are_json() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        let engine = engine(&dir);
        engine.create_instance(spec(&apk_path)).unwrap();
        let metrics: serde_json::Value =
            serde_json::from_str(&engine.metrics_json().unwrap()).unwrap();
        assert_eq!(metrics["counters"]["instance.created"], 1);
        assert!(
            metrics["histograms"]["apk.inspect"]["count"]
                .as_u64()
                .unwrap()
                >= 1
        );

        let summary: serde_json::Value =
            serde_json::from_str(&engine.summary_json().unwrap()).unwrap();
        assert_eq!(summary["instances"], 1);
        assert_eq!(summary["abi_version"], bb_common::ABI_VERSION);
    }

    #[test]
    fn concurrent_starts_are_reference_counted() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        let engine = Arc::new(engine(&dir));
        engine.create_instance(spec(&apk_path)).unwrap();
        let mut handles = Vec::new();
        for _ in 0..4 {
            let engine = engine.clone();
            handles.push(std::thread::spawn(move || {
                engine.start_instance("instance_001").unwrap();
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        let status = engine.get_instance("instance_001").unwrap();
        assert_eq!(status.refs, 4);
        assert_eq!(status.state_name, "running");
    }

    #[test]
    fn state_names_are_stable() {
        assert_eq!(state_name(InstanceState::Running.as_i32()), "running");
        assert_eq!(state_name(99), "unknown(99)");
    }

    #[test]
    fn events_are_exposed_through_the_shared_schema() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        let engine = engine(&dir);
        engine.create_instance(spec(&apk_path)).unwrap();
        engine.start_instance("instance_001").unwrap();

        let json = engine.events_json(0).unwrap();
        let events: serde_json::Value = serde_json::from_str(&json).unwrap();
        let array = events.as_array().unwrap();
        assert!(!array.is_empty());
        // State names, not numeric codes: that is the anti-drift contract.
        assert_eq!(array.last().unwrap()["to"], "running");
        assert!(array.iter().all(|event| event["instance_id"] == "instance_001"));

        let last_seq = array.last().unwrap()["seq"].as_u64().unwrap();
        let tail: serde_json::Value =
            serde_json::from_str(&engine.events_json(last_seq).unwrap()).unwrap();
        assert!(tail.as_array().unwrap().is_empty());

        // The same data is reachable through the generic request path.
        let response = engine
            .request_json(r#"{"type":"events","since_seq":0}"#)
            .unwrap();
        assert!(response.contains("\"events\""));
        assert!(response.contains("\"running\""));
    }

    #[test]
    fn corrupt_metadata_is_quarantined_and_reported() {
        let dir = TempDir::new("bridge");
        std::fs::create_dir_all(dir.join("metadata")).unwrap();
        std::fs::write(dir.join("metadata/instances.json"), b"{ broken").unwrap();

        let engine = engine(&dir);
        assert!(engine.list_instances().unwrap().is_empty());

        let summary: serde_json::Value =
            serde_json::from_str(&engine.summary_json().unwrap()).unwrap();
        let quarantined = summary["store_quarantined_file"]
            .as_str()
            .expect("summary must report the quarantined file");
        assert!(PathBuf::from(quarantined).exists());

        let metrics: serde_json::Value =
            serde_json::from_str(&engine.metrics_json().unwrap()).unwrap();
        assert_eq!(metrics["counters"]["store.corrupt_quarantined"], 1);
    }

    #[test]
    fn failed_layout_creation_rolls_back_metadata() {
        let dir = TempDir::new("bridge");
        let apk_path = apk(&dir);
        // A regular file where the instance directory should go makes
        // `ensure_layout` fail after the metadata row was written.
        std::fs::create_dir_all(dir.join("virtual/instances")).unwrap();
        std::fs::write(dir.join("virtual/instances/instance_001"), b"blocker").unwrap();

        let engine = engine(&dir);
        let err = engine.create_instance(spec(&apk_path)).unwrap_err();
        // Windows reports ALREADY_EXISTS, Unix reports NOT_A_DIRECTORY; both map
        // to a filesystem error and both must trigger the same rollback.
        assert!(
            matches!(err.code(), ErrorCode::Io | ErrorCode::AlreadyExists),
            "unexpected error: {err:?}"
        );
        assert!(
            engine.list_instances().unwrap().is_empty(),
            "metadata must be rolled back when the layout cannot be created"
        );
        let document = std::fs::read_to_string(dir.join("metadata/instances.json")).unwrap();
        assert!(!document.contains("instance_001"), "{document}");
    }
}
