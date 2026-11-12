//! Byte-budgeted resource cache.
//!
//! Two concrete uses:
//! * decoded/prepared resources of a virtual instance (layouts, fonts, AXML
//!   decodes) that are expensive to recompute;
//! * extracted APK entries that must not be re-inflated on every launch.
//!
//! The cache is strictly a cache: losing the index on process death is allowed,
//! values are re-readable from disk, and eviction never deletes guest data (it
//! only removes files inside its own directory).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use bb_common::error::Result;
use bb_common::time::now_millis;
use bb_common::{ErrorCode, bb_ensure};
use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct CacheStats {
    pub entries: usize,
    pub total_bytes: u64,
    pub budget_bytes: u64,
}

#[derive(Debug, Clone)]
struct Entry {
    kind: String,
    path: PathBuf,
    size: u64,
    created_ms: u64,
    last_access_ms: u64,
    last_used_seq: u64,
}

#[derive(Debug, Default)]
struct State {
    entries: BTreeMap<String, Entry>,
    total_bytes: u64,
    seq: u64,
}

impl State {
    /// Drops any entry pointing at `path`, regardless of how it was keyed.
    ///
    /// Reindexing after a restart can only recover file names, not the original
    /// cache keys, so a later `get`/`put` adopts the file and removes the stale
    /// alias. Without this, one file would be accounted twice.
    fn drop_path(&mut self, path: &Path) {
        let stale = self
            .entries
            .iter()
            .find(|(_, entry)| entry.path == path)
            .map(|(key, _)| key.clone());
        if let Some(key) = stale {
            if let Some(entry) = self.entries.remove(&key) {
                self.total_bytes = self.total_bytes.saturating_sub(entry.size);
            }
        }
    }
}

/// Cache scoped to one engine root.
#[derive(Debug)]
pub struct ResourceCache {
    root: PathBuf,
    budget_bytes: u64,
    ttl_ms: u64,
    inner: Mutex<State>,
}

impl ResourceCache {
    /// Opens (and creates) the cache directory.
    ///
    /// `budget_bytes == 0` means "unlimited"; `ttl_ms == 0` disables expiry.
    pub fn open(root: impl Into<PathBuf>, budget_bytes: u64, ttl_ms: u64) -> Result<Self> {
        let root = root.into();
        bb_ensure!(
            !root.as_os_str().is_empty(),
            ErrorCode::InvalidArgument,
            "cache root must not be empty"
        );
        std::fs::create_dir_all(&root)?;
        let cache = Self {
            root,
            budget_bytes,
            ttl_ms,
            inner: Mutex::new(State::default()),
        };
        cache.reindex()?;
        Ok(cache)
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn stats(&self) -> CacheStats {
        let state = self.lock();
        CacheStats {
            entries: state.entries.len(),
            total_bytes: state.total_bytes,
            budget_bytes: self.budget_bytes,
        }
    }

    pub fn put(&self, kind: &str, key: &str, bytes: &[u8]) -> Result<PathBuf> {
        self.put_at(kind, key, bytes, now_millis())
    }

    /// `put` with an explicit clock, for tests and for import tooling.
    pub fn put_at(&self, kind: &str, key: &str, bytes: &[u8], now_ms: u64) -> Result<PathBuf> {
        let path = self.path_for(kind, key)?;
        let size = bytes.len() as u64;
        if self.budget_bytes > 0 {
            bb_ensure!(
                size <= self.budget_bytes,
                ErrorCode::TooLarge,
                "cache entry of {size} bytes exceeds the {0} byte budget",
                self.budget_bytes
            );
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let temp = path.with_extension("tmp");
        std::fs::write(&temp, bytes)?;
        std::fs::rename(&temp, &path)?;

        {
            let mut state = self.lock();
            state.seq += 1;
            let seq = state.seq;
            // The previous version of this file may be indexed under either the
            // real key or a reindexed file name; drop it before re-adding.
            state.drop_path(&path);
            state.entries.remove(&entry_key(kind, key));
            state.total_bytes = state.total_bytes.saturating_add(size);
            state.entries.insert(
                entry_key(kind, key),
                Entry {
                    kind: kind.to_string(),
                    path: path.clone(),
                    size,
                    created_ms: now_ms,
                    last_access_ms: now_ms,
                    last_used_seq: seq,
                },
            );
        }
        self.evict_to_budget()?;
        Ok(path)
    }

    pub fn get(&self, kind: &str, key: &str) -> Option<Vec<u8>> {
        self.get_at(kind, key, now_millis())
    }

    pub fn get_at(&self, kind: &str, key: &str, now_ms: u64) -> Option<Vec<u8>> {
        let map_key = entry_key(kind, key);
        let path = {
            let mut state = self.lock();
            state.seq += 1;
            let seq = state.seq;
            match state.entries.get_mut(&map_key) {
                Some(entry) => {
                    entry.last_access_ms = now_ms;
                    entry.last_used_seq = seq;
                    entry.path.clone()
                }
                None => {
                    // Index may be cold after a restart; recompute the path.
                    self.path_for(kind, key).ok()?
                }
            }
        };
        let bytes = std::fs::read(&path).ok()?;
        {
            let mut state = self.lock();
            if !state.entries.contains_key(&map_key) {
                state.seq += 1;
                let seq = state.seq;
                state.drop_path(&path);
                state.total_bytes = state.total_bytes.saturating_add(bytes.len() as u64);
                state.entries.insert(
                    map_key,
                    Entry {
                        kind: kind.to_string(),
                        path,
                        size: bytes.len() as u64,
                        created_ms: now_ms,
                        last_access_ms: now_ms,
                        last_used_seq: seq,
                    },
                );
            }
        }
        Some(bytes)
    }

    pub fn contains(&self, kind: &str, key: &str) -> bool {
        self.path_for(kind, key)
            .map(|path| path.is_file())
            .unwrap_or(false)
    }

    pub fn remove(&self, kind: &str, key: &str) -> Result<bool> {
        let map_key = entry_key(kind, key);
        let mut state = self.lock();
        let entry = match state.entries.remove(&map_key) {
            Some(entry) => entry,
            None => return Ok(false),
        };
        state.total_bytes = state.total_bytes.saturating_sub(entry.size);
        drop(state);
        match std::fs::remove_file(&entry.path) {
            Ok(()) => Ok(true),
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(err) => Err(err.into()),
        }
    }

    /// Drops expired entries. Returns the number of removed entries.
    pub fn purge_expired(&self) -> Result<usize> {
        self.purge_expired_at(now_millis())
    }

    pub fn purge_expired_at(&self, now_ms: u64) -> Result<usize> {
        if self.ttl_ms == 0 {
            return Ok(0);
        }
        let expired: Vec<String> = {
            let state = self.lock();
            state
                .entries
                .iter()
                .filter(|(_, entry)| now_ms.saturating_sub(entry.created_ms) > self.ttl_ms)
                .map(|(key, _)| key.clone())
                .collect()
        };
        let mut removed = 0;
        for map_key in expired {
            let entry = {
                let mut state = self.lock();
                let entry = state.entries.remove(&map_key);
                if let Some(entry) = &entry {
                    state.total_bytes = state.total_bytes.saturating_sub(entry.size);
                }
                entry
            };
            if let Some(entry) = entry {
                let _ = std::fs::remove_file(&entry.path);
                removed += 1;
            }
        }
        Ok(removed)
    }

    /// Removes every entry (optionally only one kind).
    pub fn clear(&self, kind: Option<&str>) -> Result<usize> {
        let removed: Vec<Entry> = {
            let mut state = self.lock();
            let keys: Vec<String> = state
                .entries
                .iter()
                .filter(|(_, entry)| kind.is_none_or(|kind| entry.kind == kind))
                .map(|(key, _)| key.clone())
                .collect();
            let mut removed = Vec::with_capacity(keys.len());
            for key in keys {
                if let Some(entry) = state.entries.remove(&key) {
                    state.total_bytes = state.total_bytes.saturating_sub(entry.size);
                    removed.push(entry);
                }
            }
            removed
        };
        let count = removed.len();
        for entry in removed {
            let _ = std::fs::remove_file(&entry.path);
        }
        Ok(count)
    }

    /// Evicts least-recently-used entries until the byte budget holds.
    pub fn evict_to_budget(&self) -> Result<usize> {
        if self.budget_bytes == 0 {
            return Ok(0);
        }
        let mut evicted = Vec::new();
        {
            let mut state = self.lock();
            while state.total_bytes > self.budget_bytes {
                let victim = state
                    .entries
                    .iter()
                    .min_by_key(|(_, entry)| entry.last_used_seq)
                    .map(|(key, _)| key.clone());
                let Some(victim) = victim else { break };
                if let Some(entry) = state.entries.remove(&victim) {
                    state.total_bytes = state.total_bytes.saturating_sub(entry.size);
                    evicted.push(entry);
                }
            }
        }
        let count = evicted.len();
        for entry in evicted {
            let _ = std::fs::remove_file(&entry.path);
        }
        Ok(count)
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn path_for(&self, kind: &str, key: &str) -> Result<PathBuf> {
        bb_ensure!(
            !kind.is_empty(),
            ErrorCode::InvalidArgument,
            "cache kind is empty"
        );
        bb_ensure!(
            !key.is_empty(),
            ErrorCode::InvalidArgument,
            "cache key is empty"
        );
        bb_ensure!(
            key.len() <= 4096,
            ErrorCode::TooLarge,
            "cache key longer than 4096 bytes"
        );
        Ok(self.root.join(sanitize(kind)).join(file_name_for(key)))
    }

    /// Rebuilds the index from disk; also accounts for orphan files.
    fn reindex(&self) -> Result<()> {
        let mut state = self.lock();
        state.entries.clear();
        state.total_bytes = 0;
        for kind_entry in std::fs::read_dir(&self.root)? {
            let kind_entry = kind_entry?;
            if !kind_entry.file_type()?.is_dir() {
                continue;
            }
            let kind = kind_entry.file_name().to_string_lossy().to_string();
            let mut files: Vec<PathBuf> = Vec::new();
            for file in std::fs::read_dir(kind_entry.path())? {
                let file = file?;
                // Ignore leftover temp files from an interrupted write.
                if file.file_type()?.is_file() && file.path().extension().is_none() {
                    files.push(file.path());
                }
            }
            files.sort();
            for path in files {
                let size = std::fs::metadata(&path)?.len();
                state.seq += 1;
                let seq = state.seq;
                let name = path
                    .file_name()
                    .map(|name| name.to_string_lossy().to_string())
                    .unwrap_or_default();
                state.total_bytes = state.total_bytes.saturating_add(size);
                state.entries.insert(
                    entry_key(&kind, &name),
                    Entry {
                        kind: kind.clone(),
                        path,
                        size,
                        created_ms: 0,
                        last_access_ms: 0,
                        last_used_seq: seq,
                    },
                );
            }
        }
        drop(state);
        self.evict_to_budget()?;
        Ok(())
    }
}

fn entry_key(kind: &str, key: &str) -> String {
    let mut combined = String::with_capacity(kind.len() + key.len() + 1);
    combined.push_str(kind);
    combined.push('\u{1f}');
    combined.push_str(key);
    combined
}

fn sanitize(value: &str) -> String {
    let mut out = String::with_capacity(value.len().min(48));
    for ch in value.chars().take(48) {
        if ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' {
            out.push(ch);
        } else {
            out.push('_');
        }
    }
    if out.is_empty() {
        out.push('_');
    }
    out
}

fn file_name_for(key: &str) -> String {
    // FNV-1a: stability across processes matters, cryptographic strength does
    // not (the key is never user-supplied trust boundary).
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in key.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:016x}-{}", sanitize(key))
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;

    fn cache(dir: &TempDir, budget: u64) -> ResourceCache {
        ResourceCache::open(dir.join("cache"), budget, 0).unwrap()
    }

    #[test]
    fn put_get_remove_round_trip() {
        let dir = TempDir::new("resource");
        let cache = cache(&dir, 1024 * 1024);
        cache
            .put("axml", "com.example.app/AndroidManifest", b"decoded")
            .unwrap();
        assert_eq!(
            cache
                .get("axml", "com.example.app/AndroidManifest")
                .unwrap(),
            b"decoded"
        );
        assert!(cache.contains("axml", "com.example.app/AndroidManifest"));
        assert!(
            cache
                .remove("axml", "com.example.app/AndroidManifest")
                .unwrap()
        );
        assert!(
            !cache
                .remove("axml", "com.example.app/AndroidManifest")
                .unwrap()
        );
        assert!(
            cache
                .get("axml", "com.example.app/AndroidManifest")
                .is_none()
        );
    }

    #[test]
    fn keys_with_path_characters_stay_inside_the_cache_root() {
        let dir = TempDir::new("resource");
        let cache = cache(&dir, 1024 * 1024);
        let path = cache.put("kind", "../../etc/passwd", b"nope").unwrap();
        assert!(path.starts_with(cache.root()));
        assert!(path.exists());
        assert_eq!(cache.get("kind", "../../etc/passwd").unwrap(), b"nope");
        assert!(cache.put("", "k", b"x").is_err());
        assert!(cache.put("k", "", b"x").is_err());
    }

    #[test]
    fn budget_evicts_least_recently_used() {
        let dir = TempDir::new("resource");
        let cache = cache(&dir, 100);
        cache.put_at("blob", "a", &[1u8; 40], 10).unwrap();
        cache.put_at("blob", "b", &[2u8; 40], 20).unwrap();
        // Touch "a" so "b" becomes the LRU victim.
        assert!(cache.get_at("blob", "a", 30).is_some());
        cache.put_at("blob", "c", &[3u8; 40], 40).unwrap();

        assert!(cache.get("blob", "a").is_some());
        assert!(
            cache.get("blob", "b").is_none(),
            "b should have been evicted"
        );
        assert!(cache.get("blob", "c").is_some());
        assert!(cache.stats().total_bytes <= 100);
    }

    #[test]
    fn oversized_entry_is_rejected() {
        let dir = TempDir::new("resource");
        let cache = cache(&dir, 64);
        let err = cache.put("blob", "big", &[0u8; 65]).unwrap_err();
        assert_eq!(err.code(), ErrorCode::TooLarge);
        assert_eq!(cache.stats().entries, 0);
    }

    #[test]
    fn ttl_expiry_purges_entries() {
        let dir = TempDir::new("resource");
        let cache = ResourceCache::open(dir.join("cache"), 1024, 1_000).unwrap();
        cache.put_at("blob", "old", b"x", 1_000).unwrap();
        cache.put_at("blob", "fresh", b"y", 5_000).unwrap();
        assert_eq!(cache.purge_expired_at(4_500).unwrap(), 1);
        assert!(cache.get_at("blob", "old", 4_500).is_none());
        assert!(cache.get_at("blob", "fresh", 4_500).is_some());
    }

    #[test]
    fn index_survives_reopen() {
        let dir = TempDir::new("resource");
        {
            let cache = cache(&dir, 1024 * 1024);
            cache.put("kind", "key one", b"payload").unwrap();
        }
        let reopened = cache(&dir, 1024 * 1024);
        assert_eq!(reopened.get("kind", "key one").unwrap(), b"payload");
        assert_eq!(reopened.stats().entries, 1);
        assert_eq!(reopened.stats().total_bytes, 7);
    }

    #[test]
    fn clear_can_target_one_kind() {
        let dir = TempDir::new("resource");
        let cache = cache(&dir, 1024 * 1024);
        cache.put("a", "1", b"x").unwrap();
        cache.put("a", "2", b"x").unwrap();
        cache.put("b", "1", b"x").unwrap();
        assert_eq!(cache.clear(Some("a")).unwrap(), 2);
        assert_eq!(cache.stats().entries, 1);
        assert_eq!(cache.clear(None).unwrap(), 1);
        assert_eq!(cache.stats().entries, 0);
        assert_eq!(cache.stats().total_bytes, 0);
    }

    #[test]
    fn concurrent_put_and_get_are_safe() {
        let dir = TempDir::new("resource");
        let cache = std::sync::Arc::new(cache(&dir, 1024 * 1024));
        let mut handles = Vec::new();
        for worker in 0..4u32 {
            let cache = cache.clone();
            handles.push(std::thread::spawn(move || {
                for index in 0..25 {
                    let key = format!("{worker}-{index}");
                    cache.put("blob", &key, key.as_bytes()).unwrap();
                    assert_eq!(cache.get("blob", &key).unwrap(), key.as_bytes());
                }
            }));
        }
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(cache.stats().entries, 100);
    }
}
