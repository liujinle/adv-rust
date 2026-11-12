//! Configuration values shared by Kotlin and the native core.
//!
//! Design notes:
//! * `#[serde(default)]` everywhere (no `deny_unknown_fields`): a newer Kotlin
//!   layer must be able to pass extra keys to an older core without hard
//!   failing. Strict validation of *known* values happens in [`EngineConfig::validate`].
//! * Paths stay UTF-8 (`PathBuf` deserializes from a JSON string). Android paths
//!   are UTF-8 in practice, and the Kotlin side always has a `String` at hand.
//! * The root path is supplied by the Android layer, never guessed here: the
//!   core does not know about `/data/user/0/...`.

use std::path::{Path, PathBuf};

use bb_common::error::{BbError, Result};
use bb_common::logging::Level;
use bb_common::{ErrorCode, bb_ensure};
use serde::{Deserialize, Serialize};

/// Bumped when the meaning of a field changes. Readers refuse older/newer
/// documents instead of guessing.
pub const SCHEMA_VERSION: u32 = 1;

pub const MAX_INSTANCES_LIMIT: usize = 4096;
pub const MAX_IO_THREADS: usize = 32;

/// Security-relevant switches for one virtual instance.
///
/// These are *policy* flags consumed by the Android layer when it builds system
/// service proxies. They are not an enforcement mechanism by themselves: a
/// virtual app still shares the host UID, so this is product-level isolation,
/// never a security boundary.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct InstancePolicy {
    pub allow_network: bool,
    pub allow_external_storage: bool,
    /// Whether `file://` URIs may be handed to components outside the instance.
    pub allow_file_uri_exposure: bool,
    /// Soft quota for the instance directory, in bytes. `0` means unlimited.
    pub max_storage_bytes: u64,
    /// Whether the guest may be started while the host is in the background.
    pub allow_background_start: bool,
}

impl Default for InstancePolicy {
    fn default() -> Self {
        Self {
            allow_network: true,
            allow_external_storage: false,
            allow_file_uri_exposure: false,
            max_storage_bytes: 512 * 1024 * 1024,
            allow_background_start: false,
        }
    }
}

impl InstancePolicy {
    /// Fully locked-down profile, useful for unknown/untrusted APKs.
    pub fn restricted() -> Self {
        Self {
            allow_network: false,
            allow_external_storage: false,
            allow_file_uri_exposure: false,
            max_storage_bytes: 64 * 1024 * 1024,
            allow_background_start: false,
        }
    }

    pub fn validate(&self) -> Result<()> {
        const HARD_CAP: u64 = 1 << 40; // 1 TiB: catches "accidentally bytes vs. GiB" bugs
        bb_ensure!(
            self.max_storage_bytes <= HARD_CAP,
            ErrorCode::InvalidArgument,
            "max_storage_bytes ({}) is implausibly large",
            self.max_storage_bytes
        );
        Ok(())
    }
}

/// Engine-wide configuration.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct EngineConfig {
    pub schema_version: u32,
    /// Host directory that owns every virtual instance (`.../virtual`).
    pub root_path: PathBuf,
    pub max_instances: usize,
    /// Worker threads used by `bb-scheduler`. Deliberately small: the Android
    /// layer already has coroutines for UI-facing concurrency.
    pub io_threads: usize,
    pub log_level: Level,
    /// Number of latency samples retained per metric name.
    pub metrics_history: usize,
    /// Policy applied to instances created without an explicit override.
    pub default_policy: InstancePolicy,
    /// Resource cache budget in bytes.
    pub cache_budget_bytes: u64,
    /// Cache entry TTL in milliseconds; `0` disables expiry.
    pub cache_ttl_ms: u64,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            root_path: PathBuf::new(),
            max_instances: 64,
            io_threads: 2,
            log_level: Level::Info,
            metrics_history: 256,
            default_policy: InstancePolicy::default(),
            cache_budget_bytes: 128 * 1024 * 1024,
            cache_ttl_ms: 7 * 24 * 60 * 60 * 1000,
        }
    }
}

impl EngineConfig {
    pub fn new(root_path: impl Into<PathBuf>) -> Self {
        Self {
            root_path: root_path.into(),
            ..Self::default()
        }
    }

    pub fn validate(&self) -> Result<()> {
        bb_ensure!(
            self.schema_version == SCHEMA_VERSION,
            ErrorCode::Unsupported,
            "unsupported config schema_version {}, expected {}",
            self.schema_version,
            SCHEMA_VERSION
        );
        bb_ensure!(
            !self.root_path.as_os_str().is_empty(),
            ErrorCode::InvalidArgument,
            "root_path must be set"
        );
        bb_ensure!(
            self.root_path.is_absolute(),
            ErrorCode::InvalidArgument,
            "root_path must be absolute: {}",
            self.root_path.display()
        );
        bb_ensure!(
            !self.root_path.to_string_lossy().contains('\0'),
            ErrorCode::InvalidArgument,
            "root_path contains a NUL byte"
        );
        bb_ensure!(
            (1..=MAX_INSTANCES_LIMIT).contains(&self.max_instances),
            ErrorCode::InvalidArgument,
            "max_instances must be within 1..={MAX_INSTANCES_LIMIT}, got {}",
            self.max_instances
        );
        bb_ensure!(
            (1..=MAX_IO_THREADS).contains(&self.io_threads),
            ErrorCode::InvalidArgument,
            "io_threads must be within 1..={MAX_IO_THREADS}, got {}",
            self.io_threads
        );
        bb_ensure!(
            (16..=8192).contains(&self.metrics_history),
            ErrorCode::InvalidArgument,
            "metrics_history must be within 16..=8192, got {}",
            self.metrics_history
        );
        bb_ensure!(
            self.cache_budget_bytes <= (1 << 40),
            ErrorCode::InvalidArgument,
            "cache_budget_bytes is implausibly large"
        );
        self.default_policy.validate()?;
        Ok(())
    }

    /// Convenience: a config rooted at `root` with a small instance budget,
    /// used by tests and by the first launch of the host app.
    pub fn for_root(root_path: impl Into<PathBuf>) -> Result<Self> {
        let config = Self::new(root_path);
        config.validate()?;
        Ok(config)
    }

    pub fn to_json(&self) -> Result<String> {
        serde_json::to_string(self).map_err(BbError::from)
    }

    pub fn to_json_pretty(&self) -> Result<String> {
        serde_json::to_string_pretty(self).map_err(BbError::from)
    }

    pub fn from_json(json: &str) -> Result<Self> {
        let config: Self = serde_json::from_str(json)?;
        config.validate()?;
        Ok(config)
    }

    pub fn load(path: &Path) -> Result<Self> {
        let raw = std::fs::read_to_string(path)?;
        Self::from_json(&raw)
    }

    /// Writes through a sibling temp file and renames, so a crash mid-write
    /// cannot leave a half-written configuration behind.
    pub fn save(&self, path: &Path) -> Result<()> {
        self.validate()?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let temp = path.with_extension("json.tmp");
        std::fs::write(&temp, self.to_json_pretty()?)?;
        if let Ok(file) = std::fs::File::open(&temp) {
            let _ = file.sync_all();
        }
        std::fs::rename(&temp, path)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;

    fn valid_root(dir: &TempDir) -> PathBuf {
        dir.path().to_path_buf()
    }

    #[test]
    fn default_config_needs_a_root() {
        assert_eq!(
            EngineConfig::default().validate().unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn accepts_sane_config() {
        let dir = TempDir::new("config");
        let config = EngineConfig::for_root(valid_root(&dir)).unwrap();
        assert_eq!(config.schema_version, SCHEMA_VERSION);
        assert_eq!(config.io_threads, 2);
    }

    #[test]
    fn rejects_out_of_range_values() {
        let dir = TempDir::new("config");
        let mut config = EngineConfig::new(valid_root(&dir));

        config.max_instances = 0;
        assert!(config.validate().is_err());

        config.max_instances = MAX_INSTANCES_LIMIT + 1;
        assert!(config.validate().is_err());

        config.max_instances = 8;
        config.io_threads = 0;
        assert!(config.validate().is_err());

        config.io_threads = MAX_IO_THREADS + 1;
        assert!(config.validate().is_err());

        config.io_threads = 2;
        config.metrics_history = 1;
        assert!(config.validate().is_err());

        config.metrics_history = 256;
        config.default_policy.max_storage_bytes = u64::MAX;
        assert!(config.validate().is_err());

        config.default_policy.max_storage_bytes = 1 << 30;
        config.schema_version = 99;
        assert_eq!(
            config.validate().unwrap_err().code(),
            ErrorCode::Unsupported
        );
    }

    #[test]
    fn rejects_relative_root() {
        let config = EngineConfig::new("relative/path");
        let err = config.validate().unwrap_err();
        assert_eq!(err.code(), ErrorCode::InvalidArgument);
    }

    #[test]
    fn json_round_trip_keeps_every_field() {
        let dir = TempDir::new("config");
        let mut config = EngineConfig::new(valid_root(&dir));
        config.max_instances = 12;
        config.log_level = Level::Debug;
        config.default_policy = InstancePolicy::restricted();

        let json = config.to_json().unwrap();
        let parsed = EngineConfig::from_json(&json).unwrap();
        assert_eq!(parsed, config);
    }

    #[test]
    fn unknown_keys_are_forward_compatible() {
        let dir = TempDir::new("config");
        let root = valid_root(&dir);
        let json = format!(
            r#"{{"root_path": {}, "schema_version": 1, "future_field": {{"a": 1}}}}"#,
            serde_json::to_string(&root.to_string_lossy().to_string()).unwrap()
        );
        let parsed = EngineConfig::from_json(&json).unwrap();
        assert_eq!(parsed.root_path, root);
    }

    #[test]
    fn save_and_load_are_atomic_and_readable() {
        let dir = TempDir::new("config");
        let path = dir.join("nested/engine.json");
        let config = EngineConfig::new(valid_root(&dir));
        config.save(&path).unwrap();
        let loaded = EngineConfig::load(&path).unwrap();
        assert_eq!(loaded, config);
        assert!(!dir.join("nested/engine.json.tmp").exists());

        std::fs::write(&path, "{ not json").unwrap();
        assert_eq!(
            EngineConfig::load(&path).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn restricted_policy_disables_network_and_storage() {
        let policy = InstancePolicy::restricted();
        assert!(!policy.allow_network);
        assert!(!policy.allow_external_storage);
        policy.validate().unwrap();
    }
}
