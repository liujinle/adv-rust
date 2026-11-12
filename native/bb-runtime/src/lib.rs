//! Virtual instance lifecycle.
//!
//! This crate owns two things and nothing else:
//!
//! 1. the *bookkeeping* of instances (metadata, reference counts, events), and
//! 2. enforcement of the transition table from [`bb_common::InstanceState`].
//!
//! It does **not** start activities or services. When the Android layer reports
//! "the guest process is up", it calls [`Runtime::start`]; when the last guest
//! component dies it calls [`Runtime::stop`]. Rust therefore never lies about
//! the process state, and the state machine can be tested without a device.

use std::collections::{BTreeMap, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard};

use bb_common::error::{BbError, Result};
use bb_common::time::now_millis;
use bb_common::{ErrorCode, InstanceId, InstanceState, PackageName, bb_ensure};
use bb_config::InstancePolicy;
use bb_storage::{InstanceRow, InstanceStore};
use serde::Serialize;

/// Cap on retained lifecycle events. The UI replays from a sequence number;
/// events older than the window are simply gone.
pub const DEFAULT_EVENT_WINDOW: usize = 512;

#[derive(Debug, Clone)]
pub struct InstanceSpec {
    pub instance_id: InstanceId,
    pub package_name: PackageName,
    pub apk_path: PathBuf,
    /// `None` falls back to the engine's default policy.
    pub policy: Option<InstancePolicy>,
    pub apk_version_code: Option<u64>,
    pub signer_fingerprint: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct InstanceRecord {
    pub instance_id: InstanceId,
    pub package_name: PackageName,
    pub apk_path: PathBuf,
    pub policy: InstancePolicy,
    pub state: InstanceState,
    /// Number of live guest components. Not persisted: after a process death
    /// nothing is alive, so the value must start at zero.
    pub refs: u32,
    pub created_at_ms: u64,
    pub updated_at_ms: u64,
    pub last_error: Option<String>,
    pub apk_version_code: Option<u64>,
    pub signer_fingerprint: Option<String>,
}

impl InstanceRecord {
    fn from_row(row: InstanceRow) -> Result<Self> {
        let state = row.state()?;
        Ok(Self {
            instance_id: InstanceId::parse(row.instance_id)
                .map_err(|err| BbError::corrupt(format!("stored instance id invalid: {err}")))?,
            package_name: PackageName::parse(row.package_name)
                .map_err(|err| BbError::corrupt(format!("stored package invalid: {err}")))?,
            apk_path: PathBuf::from(row.apk_path),
            policy: row.policy,
            state,
            refs: 0,
            created_at_ms: row.created_at_ms,
            updated_at_ms: row.updated_at_ms,
            last_error: row.last_error,
            apk_version_code: row.apk_version_code,
            signer_fingerprint: row.signer_fingerprint,
        })
    }

    fn to_row(&self) -> InstanceRow {
        InstanceRow {
            instance_id: self.instance_id.to_string(),
            package_name: self.package_name.to_string(),
            apk_path: self.apk_path.to_string_lossy().to_string(),
            state: self.state.as_i32(),
            created_at_ms: self.created_at_ms,
            updated_at_ms: self.updated_at_ms,
            last_error: self.last_error.clone(),
            policy: self.policy.clone(),
            apk_version_code: self.apk_version_code,
            signer_fingerprint: self.signer_fingerprint.clone(),
        }
    }
}

/// Re-exported from `bb-common` on purpose: the event schema is shared with
/// `bb-ipc`, so producer and transport can never drift apart.
pub use bb_common::RuntimeEvent;

#[derive(Debug, Clone, Copy)]
pub struct RuntimeLimits {
    pub max_instances: usize,
    pub event_window: usize,
}

impl Default for RuntimeLimits {
    fn default() -> Self {
        Self {
            max_instances: 64,
            event_window: DEFAULT_EVENT_WINDOW,
        }
    }
}

/// Thread-safe registry of virtual instances.
pub struct Runtime {
    instances: RwLock<BTreeMap<String, InstanceRecord>>,
    store: Arc<dyn InstanceStore>,
    limits: RuntimeLimits,
    events: Mutex<VecDeque<RuntimeEvent>>,
    seq: AtomicU64,
}

impl std::fmt::Debug for Runtime {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Runtime")
            .field(
                "instances",
                &self.instances.read().map(|map| map.len()).unwrap_or(0),
            )
            .field("limits", &self.limits)
            .finish_non_exhaustive()
    }
}

impl Runtime {
    /// Loads existing metadata from `store`. Rows are validated there, so an
    /// unreadable store fails here instead of silently losing instances.
    pub fn new(store: Arc<dyn InstanceStore>, limits: RuntimeLimits) -> Result<Self> {
        bb_ensure!(
            limits.max_instances > 0,
            ErrorCode::InvalidArgument,
            "max_instances must be greater than zero"
        );
        let mut instances = BTreeMap::new();
        for row in store.list()? {
            let id = row.instance_id.clone();
            let record = InstanceRecord::from_row(row)?;
            instances.insert(id, record);
        }
        Ok(Self {
            instances: RwLock::new(instances),
            store,
            limits,
            events: Mutex::new(VecDeque::new()),
            seq: AtomicU64::new(1),
        })
    }

    pub fn count(&self) -> usize {
        self.read().len()
    }

    pub fn get(&self, instance_id: &str) -> Option<InstanceRecord> {
        self.read().get(instance_id).cloned()
    }

    pub fn list(&self) -> Vec<InstanceRecord> {
        self.read().values().cloned().collect()
    }

    pub fn refs(&self, instance_id: &str) -> Option<u32> {
        self.read().get(instance_id).map(|record| record.refs)
    }

    /// Registers a new instance in `Created` state.
    pub fn create(&self, spec: InstanceSpec) -> Result<InstanceRecord> {
        if let Some(policy) = &spec.policy {
            policy.validate()?;
        }
        bb_ensure!(
            !spec.apk_path.as_os_str().is_empty(),
            ErrorCode::InvalidArgument,
            "apk_path must not be empty"
        );
        let key = spec.instance_id.to_string();
        let mut guard = self.write();
        bb_ensure!(
            !guard.contains_key(&key),
            ErrorCode::AlreadyExists,
            "instance {key} already exists"
        );
        bb_ensure!(
            guard.len() < self.limits.max_instances,
            ErrorCode::Busy,
            "instance limit of {} reached",
            self.limits.max_instances
        );
        let now = now_millis();
        let record = InstanceRecord {
            instance_id: spec.instance_id,
            package_name: spec.package_name,
            apk_path: spec.apk_path,
            policy: spec.policy.unwrap_or_default(),
            state: InstanceState::Created,
            refs: 0,
            created_at_ms: now,
            updated_at_ms: now,
            last_error: None,
            apk_version_code: spec.apk_version_code,
            signer_fingerprint: spec.signer_fingerprint,
        };
        self.store.upsert(record.to_row())?;
        guard.insert(key, record.clone());
        Ok(record)
    }

    /// Moves an instance to `to`, rejecting transitions the table forbids.
    pub fn transition(
        &self,
        instance_id: &str,
        to: InstanceState,
        reason: &str,
    ) -> Result<InstanceState> {
        let mut guard = self.write();
        let record = guard
            .get_mut(instance_id)
            .ok_or_else(|| BbError::not_found(format!("instance {instance_id} does not exist")))?;
        let from = record.state;
        bb_ensure!(
            from.can_transition(to),
            ErrorCode::InvalidState,
            "instance {instance_id}: {} -> {} is not allowed",
            from.as_str(),
            to.as_str()
        );
        let before = record.clone();
        record.state = to;
        record.updated_at_ms = now_millis();
        if to == InstanceState::Failed {
            record.last_error = Some(reason.to_string());
        } else if from == InstanceState::Failed {
            record.last_error = None;
        }
        if let Err(err) = self.store.upsert(record.to_row()) {
            *record = before;
            return Err(err);
        }
        let at_ms = record.updated_at_ms;
        drop(guard);
        self.push_event(instance_id, Some(from), to, reason, at_ms);
        Ok(to)
    }

    /// `Created -> Preparing -> Installed`. Run after the APK was inspected by
    /// the caller (the install policy itself stays on the Android side).
    pub fn install(&self, instance_id: &str) -> Result<InstanceState> {
        if self.get(instance_id).is_none() {
            return Err(BbError::not_found(format!(
                "instance {instance_id} does not exist"
            )));
        }
        self.transition(instance_id, InstanceState::Preparing, "install requested")?;
        self.transition(instance_id, InstanceState::Installed, "layout ready")
    }

    /// Starts a guest. Idempotent while running: every call increments the
    /// component reference count, which is what the Android layer needs.
    pub fn start(&self, instance_id: &str) -> Result<InstanceState> {
        let mut guard = self.write();
        let record = guard
            .get_mut(instance_id)
            .ok_or_else(|| BbError::not_found(format!("instance {instance_id} does not exist")))?;
        match record.state {
            InstanceState::Running => {
                record.refs = record.refs.saturating_add(1);
                return Ok(InstanceState::Running);
            }
            InstanceState::Starting | InstanceState::Stopping => {
                return Err(BbError::busy(format!(
                    "instance {instance_id} is {}",
                    record.state.as_str()
                )));
            }
            state if state.is_launchable() => {}
            state => {
                return Err(BbError::invalid_state(format!(
                    "instance {instance_id} cannot start from {}",
                    state.as_str()
                )));
            }
        }
        let from_state = record.state;
        let before = record.clone();
        let now = now_millis();
        record.state = InstanceState::Starting;
        record.updated_at_ms = now;
        record.last_error = None;
        if let Err(err) = self.store.upsert(record.to_row()) {
            *record = before;
            return Err(err);
        }
        record.state = InstanceState::Running;
        record.refs = 1;
        record.updated_at_ms = now_millis();
        let at_ms = record.updated_at_ms;
        if let Err(err) = self.store.upsert(record.to_row()) {
            *record = before;
            return Err(err);
        }
        drop(guard);
        self.push_event(
            instance_id,
            Some(from_state),
            InstanceState::Starting,
            "launch requested",
            now,
        );
        self.push_event(
            instance_id,
            Some(InstanceState::Starting),
            InstanceState::Running,
            "guest process up",
            at_ms,
        );
        Ok(InstanceState::Running)
    }

    /// Releases one component reference. The instance only moves to `Stopped`
    /// when the last component goes away.
    pub fn stop(&self, instance_id: &str) -> Result<InstanceState> {
        let mut guard = self.write();
        let record = guard
            .get_mut(instance_id)
            .ok_or_else(|| BbError::not_found(format!("instance {instance_id} does not exist")))?;
        match record.state {
            InstanceState::Running => {
                record.refs = record.refs.saturating_sub(1);
                if record.refs > 0 {
                    return Ok(InstanceState::Running);
                }
            }
            InstanceState::Stopping => return Ok(InstanceState::Stopping),
            InstanceState::Stopped => return Ok(InstanceState::Stopped),
            state => {
                return Err(BbError::invalid_state(format!(
                    "instance {instance_id} cannot stop from {}",
                    state.as_str()
                )));
            }
        }
        let before = record.clone();
        let now = now_millis();
        record.state = InstanceState::Stopping;
        record.updated_at_ms = now;
        if let Err(err) = self.store.upsert(record.to_row()) {
            *record = before;
            return Err(err);
        }
        record.state = InstanceState::Stopped;
        record.refs = 0;
        record.updated_at_ms = now_millis();
        let at_ms = record.updated_at_ms;
        if let Err(err) = self.store.upsert(record.to_row()) {
            *record = before;
            return Err(err);
        }
        drop(guard);
        self.push_event(
            instance_id,
            Some(InstanceState::Running),
            InstanceState::Stopping,
            "last component gone",
            now,
        );
        self.push_event(
            instance_id,
            Some(InstanceState::Stopping),
            InstanceState::Stopped,
            "stopped",
            at_ms,
        );
        Ok(InstanceState::Stopped)
    }

    /// Records a failure, keeping the diagnosis for the UI.
    pub fn fail(&self, instance_id: &str, reason: impl AsRef<str>) -> Result<InstanceState> {
        self.transition(instance_id, InstanceState::Failed, reason.as_ref())
    }

    /// Removes metadata. The caller is responsible for deleting the instance
    /// directories *after* this returns `Ok(true)` (see `bb-bridge`, which pairs
    /// the two operations).
    pub fn remove(&self, instance_id: &str) -> Result<bool> {
        let mut guard = self.write();
        let Some(record) = guard.get(instance_id).cloned() else {
            return Ok(false);
        };
        bb_ensure!(
            !record.state.is_active() && record.refs == 0,
            ErrorCode::Busy,
            "instance {instance_id} is {} and cannot be removed",
            record.state.as_str()
        );
        let removed = self.store.remove(instance_id)?;
        guard.remove(instance_id);
        Ok(removed)
    }

    /// Called once at engine start: any state that implies a live process is a
    /// leftover from a previous run, because this process is the only one that
    /// could own it. Deliberately bypasses the transition table.
    pub fn recover_inconsistent(&self) -> Result<usize> {
        let mut recovered = 0;
        let mut guard = self.write();
        let ids: Vec<String> = guard
            .iter()
            .filter(|(_, record)| record.state.is_active())
            .map(|(id, _)| id.clone())
            .collect();
        for id in ids {
            if let Some(record) = guard.get_mut(&id) {
                let from = record.state;
                let before = record.clone();
                record.state = InstanceState::Stopped;
                record.refs = 0;
                record.updated_at_ms = now_millis();
                record.last_error = Some("recovered after unclean shutdown".to_string());
                let at_ms = record.updated_at_ms;
                if let Err(err) = self.store.upsert(record.to_row()) {
                    *record = before;
                    return Err(err);
                }
                recovered += 1;
                self.push_event(
                    &id,
                    Some(from),
                    InstanceState::Stopped,
                    "recovered after unclean shutdown",
                    at_ms,
                );
            }
        }
        Ok(recovered)
    }

    pub fn events(&self, since_seq: u64) -> Vec<RuntimeEvent> {
        let events = self.lock_events();
        events
            .iter()
            .filter(|event| event.seq > since_seq)
            .cloned()
            .collect()
    }

    pub fn last_event_seq(&self) -> u64 {
        self.seq.load(Ordering::Relaxed)
    }

    fn push_event(
        &self,
        instance_id: &str,
        from: Option<InstanceState>,
        to: InstanceState,
        reason: &str,
        at_ms: u64,
    ) {
        let seq = self.seq.fetch_add(1, Ordering::Relaxed);
        let mut events = self.lock_events();
        if events.len() == self.limits.event_window {
            events.pop_front();
        }
        events.push_back(RuntimeEvent {
            seq,
            at_ms,
            instance_id: instance_id.to_string(),
            from,
            to,
            reason: reason.to_string(),
        });
    }

    fn lock_events(&self) -> std::sync::MutexGuard<'_, VecDeque<RuntimeEvent>> {
        self.events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn read(&self) -> RwLockReadGuard<'_, BTreeMap<String, InstanceRecord>> {
        self.instances
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn write(&self) -> RwLockWriteGuard<'_, BTreeMap<String, InstanceRecord>> {
        self.instances
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;
    use bb_storage::{JsonFileStore, MemoryStore};

    fn spec(id: &str) -> InstanceSpec {
        InstanceSpec {
            instance_id: InstanceId::parse(id).unwrap(),
            package_name: PackageName::parse("com.example.app").unwrap(),
            apk_path: PathBuf::from("/sdcard/app.apk"),
            policy: None,
            apk_version_code: Some(1),
            signer_fingerprint: None,
        }
    }

    fn runtime() -> Runtime {
        Runtime::new(Arc::new(MemoryStore::new()), RuntimeLimits::default()).unwrap()
    }

    #[test]
    fn create_registers_created_state() {
        let runtime = runtime();
        let record = runtime.create(spec("instance_001")).unwrap();
        assert_eq!(record.state, InstanceState::Created);
        assert_eq!(runtime.count(), 1);
        assert_eq!(runtime.refs("instance_001"), Some(0));
        assert_eq!(
            runtime.get("instance_001").unwrap().package_name.as_str(),
            "com.example.app"
        );
    }

    #[test]
    fn duplicate_and_limit_are_rejected() {
        let limits = RuntimeLimits {
            max_instances: 2,
            event_window: 16,
        };
        let runtime = Runtime::new(Arc::new(MemoryStore::new()), limits).unwrap();
        runtime.create(spec("instance_001")).unwrap();
        let err = runtime.create(spec("instance_001")).unwrap_err();
        assert_eq!(err.code(), ErrorCode::AlreadyExists);
        runtime.create(spec("instance_002")).unwrap();
        let err = runtime.create(spec("instance_003")).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Busy);
    }

    #[test]
    fn install_and_launch_happy_path() {
        let runtime = runtime();
        runtime.create(spec("instance_001")).unwrap();
        assert_eq!(
            runtime.install("instance_001").unwrap(),
            InstanceState::Installed
        );
        assert_eq!(
            runtime.start("instance_001").unwrap(),
            InstanceState::Running
        );
        assert_eq!(runtime.refs("instance_001"), Some(1));
        assert_eq!(
            runtime.stop("instance_001").unwrap(),
            InstanceState::Stopped
        );
        assert_eq!(runtime.refs("instance_001"), Some(0));
        // Idempotent stop.
        assert_eq!(
            runtime.stop("instance_001").unwrap(),
            InstanceState::Stopped
        );
    }

    #[test]
    fn reference_count_keeps_instance_alive() {
        let runtime = runtime();
        runtime.create(spec("instance_001")).unwrap();
        runtime.install("instance_001").unwrap();
        runtime.start("instance_001").unwrap();
        runtime.start("instance_001").unwrap();
        assert_eq!(runtime.refs("instance_001"), Some(2));
        assert_eq!(
            runtime.stop("instance_001").unwrap(),
            InstanceState::Running
        );
        assert_eq!(runtime.refs("instance_001"), Some(1));
        assert_eq!(
            runtime.stop("instance_001").unwrap(),
            InstanceState::Stopped
        );
    }

    #[test]
    fn illegal_transitions_do_not_mutate_state() {
        let runtime = runtime();
        runtime.create(spec("instance_001")).unwrap();
        let err = runtime
            .transition("instance_001", InstanceState::Running, "wishful thinking")
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::InvalidState);
        assert_eq!(
            runtime.get("instance_001").unwrap().state,
            InstanceState::Created
        );
        let err = runtime.start("instance_001").unwrap_err();
        assert_eq!(err.code(), ErrorCode::InvalidState);
        assert_eq!(
            runtime.get("instance_001").unwrap().state,
            InstanceState::Created
        );
    }

    #[test]
    fn failure_keeps_diagnosis_and_can_retry() {
        let runtime = runtime();
        runtime.create(spec("instance_001")).unwrap();
        runtime
            .transition("instance_001", InstanceState::Preparing, "go")
            .unwrap();
        runtime.fail("instance_001", "manifest unreadable").unwrap();
        let record = runtime.get("instance_001").unwrap();
        assert_eq!(record.state, InstanceState::Failed);
        assert_eq!(record.last_error.as_deref(), Some("manifest unreadable"));
        runtime.install("instance_001").unwrap();
        assert_eq!(runtime.get("instance_001").unwrap().last_error, None);
    }

    #[test]
    fn running_instance_cannot_be_removed() {
        let runtime = runtime();
        runtime.create(spec("instance_001")).unwrap();
        runtime.install("instance_001").unwrap();
        runtime.start("instance_001").unwrap();
        let err = runtime.remove("instance_001").unwrap_err();
        assert_eq!(err.code(), ErrorCode::Busy);
        runtime.stop("instance_001").unwrap();
        assert!(runtime.remove("instance_001").unwrap());
        assert!(!runtime.remove("instance_001").unwrap());
        assert_eq!(runtime.count(), 0);
    }

    #[test]
    fn metadata_survives_a_restart_and_active_states_are_recovered() {
        let dir = TempDir::new("runtime");
        let path = dir.join("instances.json");
        {
            let store = Arc::new(JsonFileStore::open(&path).unwrap());
            let runtime = Runtime::new(store, RuntimeLimits::default()).unwrap();
            runtime.create(spec("instance_001")).unwrap();
            runtime.install("instance_001").unwrap();
            runtime.start("instance_001").unwrap();
        }
        let store = Arc::new(JsonFileStore::open(&path).unwrap());
        let runtime = Runtime::new(store, RuntimeLimits::default()).unwrap();
        assert_eq!(
            runtime.get("instance_001").unwrap().state,
            InstanceState::Running
        );
        assert_eq!(
            runtime.refs("instance_001"),
            Some(0),
            "refs must reset on restart"
        );
        assert_eq!(runtime.recover_inconsistent().unwrap(), 1);
        let record = runtime.get("instance_001").unwrap();
        assert_eq!(record.state, InstanceState::Stopped);
        assert_eq!(
            record.last_error.as_deref(),
            Some("recovered after unclean shutdown")
        );
        // Recovered instance is launchable again.
        assert_eq!(
            runtime.start("instance_001").unwrap(),
            InstanceState::Running
        );
    }

    #[test]
    fn events_are_ordered_and_advance_the_sequence() {
        let runtime = runtime();
        runtime.create(spec("instance_001")).unwrap();
        runtime.install("instance_001").unwrap();
        runtime.start("instance_001").unwrap();
        let events = runtime.events(0);
        assert!(events.len() >= 4);
        let mut previous = 0;
        for event in &events {
            assert!(event.seq > previous, "sequence must increase");
            previous = event.seq;
        }
        assert_eq!(events.last().unwrap().to, InstanceState::Running);
        let tail = runtime.events(events[events.len() - 2].seq);
        assert_eq!(tail.len(), 1);
    }

    #[test]
    fn event_window_is_bounded() {
        let limits = RuntimeLimits {
            max_instances: 4,
            event_window: 4,
        };
        let runtime = Runtime::new(Arc::new(MemoryStore::new()), limits).unwrap();
        runtime.create(spec("instance_001")).unwrap();
        runtime.install("instance_001").unwrap();
        runtime.start("instance_001").unwrap();
        runtime.stop("instance_001").unwrap();
        assert!(runtime.events(0).len() <= 4);
    }

    #[test]
    fn concurrent_start_stop_keeps_counters_consistent() {
        let runtime = Arc::new(runtime());
        runtime.create(spec("instance_001")).unwrap();
        runtime.install("instance_001").unwrap();

        let mut handles = Vec::new();
        for _ in 0..8 {
            let runtime = runtime.clone();
            handles.push(std::thread::spawn(move || {
                runtime.start("instance_001").unwrap();
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(runtime.refs("instance_001"), Some(8));
        assert_eq!(
            runtime.get("instance_001").unwrap().state,
            InstanceState::Running
        );

        let mut handles = Vec::new();
        for _ in 0..8 {
            let runtime = runtime.clone();
            handles.push(std::thread::spawn(move || {
                runtime.stop("instance_001").unwrap();
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(runtime.refs("instance_001"), Some(0));
        assert_eq!(
            runtime.get("instance_001").unwrap().state,
            InstanceState::Stopped
        );
    }
}
