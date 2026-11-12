//! Small helpers shared by tests across the workspace.
//!
//! Intentionally dependency-free and always compiled: keeping test scaffolding
//! in one place avoids the classic "each crate rolls its own temp dir" drift.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static COUNTER: AtomicU64 = AtomicU64::new(0);

/// Self-cleaning temporary directory.
#[derive(Debug)]
pub struct TempDir {
    path: PathBuf,
}

impl TempDir {
    pub fn new(tag: &str) -> Self {
        let seq = COUNTER.fetch_add(1, Ordering::Relaxed);
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let path =
            std::env::temp_dir().join(format!("bb-{tag}-{}-{nanos}-{seq}", std::process::id()));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(&path).expect("create temp dir");
        Self { path }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn join(&self, relative: &str) -> PathBuf {
        self.path.join(relative)
    }

    pub fn child(&self, name: &str) -> Self {
        let path = self.path.join(name);
        std::fs::create_dir_all(&path).expect("create child temp dir");
        Self { path }
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}

/// Deterministic pseudo-random byte stream (xorshift64*), so fuzz-ish tests are
/// reproducible and don't need the `rand` crate.
pub struct XorShift64 {
    state: u64,
}

impl XorShift64 {
    pub fn new(seed: u64) -> Self {
        Self {
            state: if seed == 0 {
                0x9E37_79B9_7F4A_7C15
            } else {
                seed
            },
        }
    }

    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    pub fn next_u8(&mut self) -> u8 {
        (self.next_u64() >> 24) as u8
    }

    pub fn next_in_range(&mut self, upper_exclusive: u64) -> u64 {
        if upper_exclusive == 0 {
            return 0;
        }
        self.next_u64() % upper_exclusive
    }

    pub fn bytes(&mut self, len: usize) -> Vec<u8> {
        (0..len).map(|_| self.next_u8()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn temp_dir_is_cleaned_up() {
        let path = {
            let dir = TempDir::new("unit");
            let path = dir.path().to_path_buf();
            std::fs::write(dir.join("marker.txt"), b"x").unwrap();
            assert!(path.exists());
            path
        };
        assert!(!path.exists(), "temp dir should be removed on drop");
    }

    #[test]
    fn prng_is_deterministic_and_not_stuck() {
        let mut a = XorShift64::new(7);
        let mut b = XorShift64::new(7);
        let first: Vec<u8> = a.bytes(64);
        assert_eq!(first, b.bytes(64));
        assert!(first.iter().any(|byte| *byte != first[0]));
        assert_eq!(XorShift64::new(1).next_in_range(0), 0);
    }
}
