//! Persistence of instance metadata.
//!
//! The engine needs the *same* answer after a process restart, so metadata is
//! written through [`InstanceStore`] instead of living in a `HashMap`.
//!
//! Backend choice: a small atomically-rewritten JSON document. It is boring,
//! inspectable with `adb shell cat`, and requires no native dependency (the
//! bundled SQLite amalgamation is a real cross-compilation cost). Instances are
//! bounded (`max_instances`), so document size stays in the tens of kilobytes.
//! The trait exists so a SQLite/Room-backed implementation can be added later
//! without touching `bb-runtime`.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use bb_common::error::{BbError, Result};
use bb_common::time::now_millis;
use bb_common::{ErrorCode, InstanceId, InstanceState, PackageName, bb_ensure};
use bb_config::InstancePolicy;
use serde::{Deserialize, Serialize};

pub const STORE_SCHEMA_VERSION: u32 = 1;

/// One persisted instance. Plain owned fields on purpose: this type crosses
/// module boundaries and gets serialized, so no borrowed data.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InstanceRow {
    pub instance_id: String,
    pub package_name: String,
    pub apk_path: String,
    pub state: i32,
    pub created_at_ms: u64,
    pub updated_at_ms: u64,
    pub last_error: Option<String>,
    pub policy: InstancePolicy,
    /// `versionCode` from the inspected manifest, when known.
    pub apk_version_code: Option<u64>,
    /// Opaque signer fingerprint supplied by the Android layer. Part of the
    /// instance identity: same package + different signer are different
    /// instances.
    pub signer_fingerprint: Option<String>,
}

impl InstanceRow {
    pub fn new(instance_id: &str, package_name: &str, apk_path: &str) -> Self {
        let now = now_millis();
        Self {
            instance_id: instance_id.to_string(),
            package_name: package_name.to_string(),
            apk_path: apk_path.to_string(),
            state: InstanceState::Created.as_i32(),
            created_at_ms: now,
            updated_at_ms: now,
            last_error: None,
            policy: InstancePolicy::default(),
            apk_version_code: None,
            signer_fingerprint: None,
        }
    }

    /// Structural validation; used when loading untrusted files from disk.
    pub fn validate(&self) -> Result<()> {
        let id = InstanceId::parse(self.instance_id.clone())
            .map_err(|err| BbError::corrupt(format!("invalid instance id: {err}")))?;
        PackageName::parse(self.package_name.clone())
            .map_err(|err| BbError::corrupt(format!("instance {id}: invalid package: {err}")))?;
        bb_ensure!(
            InstanceState::from_i32(self.state).is_some(),
            ErrorCode::Corrupt,
            "instance {id}: invalid state code {}",
            self.state
        );
        bb_ensure!(
            !self.apk_path.is_empty(),
            ErrorCode::Corrupt,
            "instance {id}: empty apk_path"
        );
        self.policy
            .validate()
            .map_err(|err| BbError::corrupt(format!("instance {id}: bad policy: {err}")))?;
        bb_ensure!(
            self.created_at_ms <= self.updated_at_ms,
            ErrorCode::Corrupt,
            "instance {id}: created_at_ms is newer than updated_at_ms"
        );
        Ok(())
    }

    pub fn state(&self) -> Result<InstanceState> {
        InstanceState::from_i32(self.state)
            .ok_or_else(|| BbError::corrupt(format!("invalid state code {}", self.state)))
    }
}

/// Pluggable metadata backend.
///
/// All methods are synchronous and short: they run under a short-lived mutex in
/// `bb-runtime`, never on the UI thread. Implementations must be `Send + Sync`.
pub trait InstanceStore: Send + Sync {
    fn list(&self) -> Result<Vec<InstanceRow>>;
    fn get(&self, instance_id: &str) -> Result<Option<InstanceRow>>;
    fn upsert(&self, row: InstanceRow) -> Result<()>;
    fn remove(&self, instance_id: &str) -> Result<bool>;
    fn count(&self) -> Result<usize>;
    /// Durability barrier. No-op for backends that already write through.
    fn flush(&self) -> Result<()> {
        Ok(())
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct StoreDocument {
    schema_version: u32,
    saved_at_ms: u64,
    instances: Vec<InstanceRow>,
}

#[derive(Debug, Default)]
struct StoreState {
    instances: BTreeMap<String, InstanceRow>,
}

/// Atomically rewritten JSON document backend.
///
/// Failure model: writes go to `<path>.tmp`, are `sync_all`-ed and then renamed
/// over the target. A crash therefore yields either the old or the new document,
/// never a truncated one.
#[derive(Debug)]
pub struct JsonFileStore {
    path: PathBuf,
    state: Mutex<StoreState>,
}

/// What `open_recovering` had to repair.
#[derive(Debug, Clone, Default)]
pub struct StoreRecovery {
    /// Set when a corrupt document was moved aside instead of being dropped.
    /// The file is kept because it is the only record and may still be salvaged.
    pub quarantined: Option<PathBuf>,
}

impl JsonFileStore {
    pub fn open(path: impl Into<PathBuf>) -> Result<Self> {
        let path = path.into();
        let state = if path.exists() {
            let raw = std::fs::read_to_string(&path)?;
            let document: StoreDocument = serde_json::from_str(&raw).map_err(|err| {
                BbError::corrupt(format!(
                    "metadata file {} is not readable: {err}",
                    path.display()
                ))
            })?;
            bb_ensure!(
                document.schema_version == STORE_SCHEMA_VERSION,
                ErrorCode::Unsupported,
                "metadata schema {} is not supported (expected {})",
                document.schema_version,
                STORE_SCHEMA_VERSION
            );
            let mut instances = BTreeMap::new();
            for row in document.instances {
                row.validate()?;
                bb_ensure!(
                    instances.insert(row.instance_id.clone(), row).is_none(),
                    ErrorCode::Corrupt,
                    "duplicate instance id in metadata file"
                );
            }
            StoreState { instances }
        } else {
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            StoreState::default()
        };
        Ok(Self {
            path,
            state: Mutex::new(state),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Opens the store, quarantining a *corrupt* document instead of failing.
    ///
    /// Only `CORRUPT` is recovered: an unsupported schema or an IO error is
    /// propagated, because continuing would either lose a newer format or hide a
    /// permission problem. The quarantined file is never deleted.
    pub fn open_recovering(path: impl Into<PathBuf>) -> Result<(Self, StoreRecovery)> {
        let path = path.into();
        match Self::open(path.clone()) {
            Ok(store) => Ok((store, StoreRecovery::default())),
            Err(err) if err.code() == ErrorCode::Corrupt => {
                std::fs::create_dir_all(
                    path.parent().unwrap_or_else(|| Path::new(".")),
                )?;
                let quarantine = unique_quarantine_path(&path);
                std::fs::rename(&path, &quarantine)?;
                let store = Self::open(path)?;
                Ok((
                    store,
                    StoreRecovery {
                        quarantined: Some(quarantine),
                    },
                ))
            }
            Err(err) => Err(err),
        }
    }

    fn write_locked(&self, state: &StoreState) -> Result<()> {
        let document = StoreDocument {
            schema_version: STORE_SCHEMA_VERSION,
            saved_at_ms: now_millis(),
            instances: state.instances.values().cloned().collect(),
        };
        let encoded = serde_json::to_vec_pretty(&document)?;
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let temp = self.path.with_extension("json.tmp");
        {
            use std::io::Write;
            let mut file = std::fs::File::create(&temp)?;
            file.write_all(&encoded)?;
            file.sync_all()?;
        }
        std::fs::rename(&temp, &self.path)?;
        // 文件 sync_all 只保证数据块落盘；rename 之后若掉电，目录项本身可能还没
        // 写进磁盘，重启会看到旧文件（甚至空目录项）。Unix 可以对目录 fd 做
        // fsync；Windows 打不开目录，跳过即可（本机 cargo test 走这条）。
        // 失败不阻断写入：主文档已经是完整的，只是丢了 durability 保证。
        #[cfg(unix)]
        if let Some(parent) = self.path.parent() {
            if let Ok(dir) = std::fs::File::open(parent) {
                let _ = dir.sync_all();
            }
        }
        Ok(())
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, StoreState>> {
        self.state
            .lock()
            .map_err(|_| BbError::internal("metadata store lock poisoned"))
    }
}

impl InstanceStore for JsonFileStore {
    fn list(&self) -> Result<Vec<InstanceRow>> {
        let state = self.lock()?;
        Ok(state.instances.values().cloned().collect())
    }

    fn get(&self, instance_id: &str) -> Result<Option<InstanceRow>> {
        let state = self.lock()?;
        Ok(state.instances.get(instance_id).cloned())
    }

    fn upsert(&self, row: InstanceRow) -> Result<()> {
        row.validate()?;
        let key = row.instance_id.clone();
        let mut state = self.lock()?;
        let previous = state.instances.insert(key.clone(), row);
        if let Err(err) = self.write_locked(&state) {
            // Keep memory and disk consistent: undo the in-memory change.
            match previous {
                Some(old) => {
                    state.instances.insert(key, old);
                }
                None => {
                    state.instances.remove(&key);
                }
            }
            return Err(err);
        }
        Ok(())
    }

    fn remove(&self, instance_id: &str) -> Result<bool> {
        let mut state = self.lock()?;
        let removed = match state.instances.remove(instance_id) {
            Some(row) => row,
            None => return Ok(false),
        };
        if let Err(err) = self.write_locked(&state) {
            state.instances.insert(removed.instance_id.clone(), removed);
            return Err(err);
        }
        Ok(true)
    }

    fn count(&self) -> Result<usize> {
        let state = self.lock()?;
        Ok(state.instances.len())
    }
}

/// In-memory backend: used by unit tests and by "dry run" tooling.
#[derive(Debug, Default)]
pub struct MemoryStore {
    state: Mutex<BTreeMap<String, InstanceRow>>,
}

impl MemoryStore {
    pub fn new() -> Self {
        Self::default()
    }

    fn lock(&self) -> Result<std::sync::MutexGuard<'_, BTreeMap<String, InstanceRow>>> {
        self.state
            .lock()
            .map_err(|_| BbError::internal("memory store lock poisoned"))
    }
}

impl InstanceStore for MemoryStore {
    fn list(&self) -> Result<Vec<InstanceRow>> {
        Ok(self.lock()?.values().cloned().collect())
    }

    fn get(&self, instance_id: &str) -> Result<Option<InstanceRow>> {
        Ok(self.lock()?.get(instance_id).cloned())
    }

    fn upsert(&self, row: InstanceRow) -> Result<()> {
        row.validate()?;
        self.lock()?.insert(row.instance_id.clone(), row);
        Ok(())
    }

    fn remove(&self, instance_id: &str) -> Result<bool> {
        Ok(self.lock()?.remove(instance_id).is_some())
    }

    fn count(&self) -> Result<usize> {
        Ok(self.lock()?.len())
    }
}

fn unique_quarantine_path(path: &Path) -> PathBuf {
    let stamp = now_millis();
    for attempt in 0..1_000u32 {
        let candidate = if attempt == 0 {
            path.with_extension(format!("json.corrupt-{stamp}"))
        } else {
            path.with_extension(format!("json.corrupt-{stamp}-{attempt}"))
        };
        if !candidate.exists() {
            return candidate;
        }
    }
    path.with_extension(format!("json.corrupt-{stamp}-overflow"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;

    fn row(id: &str) -> InstanceRow {
        InstanceRow::new(id, "com.example.app", "/sdcard/app.apk")
    }

    #[test]
    fn healthy_store_reports_no_recovery() {
        let dir = TempDir::new("storage");
        let path = dir.join("instances.json");
        {
            let store = JsonFileStore::open(&path).unwrap();
            store.upsert(row("instance_001")).unwrap();
        }
        let (store, recovery) = JsonFileStore::open_recovering(&path).unwrap();
        assert!(recovery.quarantined.is_none());
        assert_eq!(store.count().unwrap(), 1);
    }

    #[test]
    fn corrupt_document_is_quarantined_not_deleted() {
        let dir = TempDir::new("storage");
        let path = dir.join("instances.json");
        std::fs::write(&path, b"{ this is not json").unwrap();

        let (store, recovery) = JsonFileStore::open_recovering(&path).unwrap();
        let quarantined = recovery.quarantined.expect("corrupt file must be moved aside");
        assert!(quarantined.exists(), "quarantined file must be kept");
        assert_eq!(
            std::fs::read(&quarantined).unwrap(),
            b"{ this is not json",
            "quarantine must preserve the original bytes"
        );
        assert_eq!(store.count().unwrap(), 0);

        // The recovered store is immediately usable and writes a fresh document.
        store.upsert(row("instance_001")).unwrap();
        let reopened = JsonFileStore::open(&path).unwrap();
        assert_eq!(reopened.count().unwrap(), 1);
    }

    #[test]
    fn truncated_document_is_quarantined() {
        let dir = TempDir::new("storage");
        let path = dir.join("instances.json");
        let document = r#"{"schema_version":1,"saved_at_ms":1,"instances":[]}"#;
        std::fs::write(&path, &document[..document.len() / 2]).unwrap();
        let (store, recovery) = JsonFileStore::open_recovering(&path).unwrap();
        assert!(recovery.quarantined.is_some());
        assert_eq!(store.count().unwrap(), 0);
    }

    #[test]
    fn unsupported_schema_is_not_quarantined() {
        let dir = TempDir::new("storage");
        let path = dir.join("instances.json");
        std::fs::write(
            &path,
            r#"{"schema_version":999,"saved_at_ms":1,"instances":[]}"#,
        )
        .unwrap();
        let err = JsonFileStore::open_recovering(&path).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Unsupported);
        assert!(path.exists(), "a newer format must not be moved aside");
    }

    #[test]
    fn repeated_recovery_never_overwrites_quarantine_files() {
        let dir = TempDir::new("storage");
        let path = dir.join("instances.json");
        for round in 0..3 {
            std::fs::write(&path, format!("garbage-{round}")).unwrap();
            let (_, recovery) = JsonFileStore::open_recovering(&path).unwrap();
            let quarantined = recovery.quarantined.unwrap();
            assert_eq!(
                std::fs::read_to_string(&quarantined).unwrap(),
                format!("garbage-{round}")
            );
        }
    }

    #[test]
    fn upsert_get_list_remove() {
        let store = MemoryStore::new();
        store.upsert(row("instance_001")).unwrap();
        store.upsert(row("instance_002")).unwrap();
        assert_eq!(store.count().unwrap(), 2);
        assert_eq!(store.list().unwrap().len(), 2);
        assert_eq!(
            store.get("instance_001").unwrap().unwrap().package_name,
            "com.example.app"
        );
        assert!(store.remove("instance_001").unwrap());
        assert!(!store.remove("instance_001").unwrap());
        assert_eq!(store.count().unwrap(), 1);
    }

    #[test]
    fn rejects_invalid_rows() {
        let store = MemoryStore::new();
        let mut bad = row("instance_001");
        bad.state = 999;
        let err = store.upsert(bad).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Corrupt);

        let mut bad_id = row("..");
        bad_id.instance_id = "../escape".to_string();
        assert!(store.upsert(bad_id).is_err());

        let mut bad_pkg = row("instance_002");
        bad_pkg.package_name = "not a package".to_string();
        assert!(store.upsert(bad_pkg).is_err());
    }

    #[test]
    fn json_store_persists_across_reopen() {
        let dir = TempDir::new("store");
        let path = dir.join("metadata/instances.json");
        {
            let store = JsonFileStore::open(&path).unwrap();
            store.upsert(row("instance_001")).unwrap();
            let mut second = row("instance_002");
            second.last_error = Some("boom".to_string());
            second.state = InstanceState::Failed.as_i32();
            store.upsert(second).unwrap();
        }
        let reopened = JsonFileStore::open(&path).unwrap();
        assert_eq!(reopened.count().unwrap(), 2);
        let second = reopened.get("instance_002").unwrap().unwrap();
        assert_eq!(second.last_error.as_deref(), Some("boom"));
        assert_eq!(second.state().unwrap(), InstanceState::Failed);
        assert!(!path.with_extension("json.tmp").exists());
    }

    #[test]
    fn repeated_atomic_rewrite_leaves_no_temp_and_no_partial_document() {
        let dir = TempDir::new("store");
        let path = dir.join("instances.json");
        let temp = path.with_extension("json.tmp");
        let store = JsonFileStore::open(&path).unwrap();
        for round in 0..8 {
            store.upsert(row(&format!("instance_{round}"))).unwrap();
            assert!(!temp.exists(), "round {round}: temp file must be renamed away");
            // 每次写完都必须能立刻被另一个 reader 完整解析，绝不出现半截 JSON。
            let reopened = JsonFileStore::open(&path).unwrap();
            assert_eq!(reopened.count().unwrap(), round + 1);
        }
    }

    #[test]
    fn concurrent_writers_keep_a_consistent_document() {
        let dir = TempDir::new("store");
        let path = dir.join("instances.json");
        let store = std::sync::Arc::new(JsonFileStore::open(&path).unwrap());
        let mut handles = Vec::new();
        for worker in 0..4 {
            let store = store.clone();
            handles.push(std::thread::spawn(move || {
                for index in 0..10 {
                    let id = format!("instance_{worker}{index:02}");
                    store.upsert(row(&id)).unwrap();
                }
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(store.count().unwrap(), 40);
        let reopened = JsonFileStore::open(&path).unwrap();
        assert_eq!(reopened.count().unwrap(), 40);
        let document: StoreDocument =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(document.instances.len(), 40);
        assert_eq!(document.schema_version, STORE_SCHEMA_VERSION);
    }

    #[test]
    fn corrupt_document_is_reported_not_ignored() {
        let dir = TempDir::new("store");
        let path = dir.join("instances.json");
        std::fs::write(&path, "{ definitely not json").unwrap();
        let err = JsonFileStore::open(&path).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Corrupt);

        std::fs::write(
            &path,
            r#"{"schema_version":7,"saved_at_ms":0,"instances":[]}"#,
        )
        .unwrap();
        let err = JsonFileStore::open(&path).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Unsupported);

        std::fs::write(
            &path,
            r#"{"schema_version":1,"saved_at_ms":0,"instances":[{"instance_id":"../x","package_name":"com.a.b","apk_path":"/a.apk","state":1,"created_at_ms":1,"updated_at_ms":1,"last_error":null,"policy":{},"apk_version_code":null,"signer_fingerprint":null}]}"#,
        )
        .unwrap();
        assert_eq!(
            JsonFileStore::open(&path).unwrap_err().code(),
            ErrorCode::Corrupt
        );
    }

    #[test]
    fn overwrite_keeps_latest_state() {
        let dir = TempDir::new("store");
        let store = JsonFileStore::open(dir.join("instances.json")).unwrap();
        let mut latest = row("instance_001");
        store.upsert(latest.clone()).unwrap();
        latest.state = InstanceState::Installed.as_i32();
        latest.updated_at_ms += 5;
        store.upsert(latest.clone()).unwrap();
        let loaded = store.get("instance_001").unwrap().unwrap();
        assert_eq!(loaded.state().unwrap(), InstanceState::Installed);
        assert_eq!(store.count().unwrap(), 1);
    }
}
