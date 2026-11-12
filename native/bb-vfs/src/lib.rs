//! Userspace virtual file system.
//!
//! Scope, stated explicitly because it is easy to over-promise:
//!
//! * This maps an *instance-relative* guest path onto a host directory and
//!   enforces a portable path policy (no `..`, no absolute paths, no `:`/control
//!   characters, no trailing dots or spaces).
//! * It does **not** create a security boundary. The guest app runs in the host
//!   process with the host UID. A guest that opens an absolute path directly
//!   bypasses this module unless the Kotlin layer intercepts the call. Path
//!   isolation is data-management isolation, not a sandbox.
//! * No FUSE, no bind mounts, no root: everything is `std::fs` plus lexical
//!   normalisation and symlink checks where the platform allows it.
//!
//! On-disk layout (one directory per instance, keyed by the instance id so the
//! same package can exist twice):
//!
//! ```text
//! <root>/
//!   instances/
//!     instance_001/
//!       files/ cache/ databases/ shared_prefs/ code_cache/ no_backup/ external/
//! ```

use std::path::{Component, Path, PathBuf};

use bb_common::error::{BbError, Result};
use bb_common::{ErrorCode, InstanceId, MAX_RELATIVE_PATH_LEN, bb_ensure};

/// Directories created for every instance. Mirrors the layout the Android
/// framework exposes through `Context#getFilesDir()` & friends.
pub const INSTANCE_DIRS: [&str; 7] = [
    "files",
    "cache",
    "databases",
    "shared_prefs",
    "code_cache",
    "no_backup",
    "external",
];

pub const INSTANCES_DIR: &str = "instances";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstanceLayout {
    pub root: PathBuf,
    pub files: PathBuf,
    pub cache: PathBuf,
    pub databases: PathBuf,
    pub shared_prefs: PathBuf,
    pub code_cache: PathBuf,
    pub no_backup: PathBuf,
    pub external: PathBuf,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Usage {
    pub bytes: u64,
    pub files: u64,
    pub dirs: u64,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RemovalStats {
    pub bytes: u64,
    pub files: u64,
    pub dirs: u64,
}

#[derive(Debug, Clone)]
pub struct Vfs {
    root: PathBuf,
}

impl Vfs {
    /// Creates the root directory if it does not exist yet.
    pub fn new(root: impl Into<PathBuf>) -> Result<Self> {
        let root = root.into();
        bb_ensure!(
            !root.as_os_str().is_empty(),
            ErrorCode::InvalidArgument,
            "vfs root must not be empty"
        );
        bb_ensure!(
            !root.to_string_lossy().contains('\0'),
            ErrorCode::InvalidArgument,
            "vfs root contains a NUL byte"
        );
        std::fs::create_dir_all(&root)?;
        // Store an absolute root: every later containment check compares
        // canonical paths against it.
        let root = root.canonicalize().unwrap_or(root);
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn instances_root(&self) -> PathBuf {
        self.root.join(INSTANCES_DIR)
    }

    pub fn instance_root(&self, instance: &InstanceId) -> PathBuf {
        self.instances_root().join(instance.as_str())
    }

    /// Paths only, no I/O. Useful when the layout is only needed for metadata.
    pub fn layout(&self, instance: &InstanceId) -> InstanceLayout {
        let root = self.instance_root(instance);
        InstanceLayout {
            files: root.join("files"),
            cache: root.join("cache"),
            databases: root.join("databases"),
            shared_prefs: root.join("shared_prefs"),
            code_cache: root.join("code_cache"),
            no_backup: root.join("no_backup"),
            external: root.join("external"),
            root,
        }
    }

    /// Creates the full layout (idempotent).
    pub fn ensure_layout(&self, instance: &InstanceId) -> Result<InstanceLayout> {
        let layout = self.layout(instance);
        std::fs::create_dir_all(&layout.root)?;
        for dir in INSTANCE_DIRS {
            std::fs::create_dir_all(layout.root.join(dir))?;
        }
        Ok(layout)
    }

    /// Resolves a guest-relative path inside one instance, rejecting anything
    /// that could alias outside of it.
    pub fn resolve(&self, instance: &InstanceId, relative: &str) -> Result<PathBuf> {
        let segments = normalize_relative(relative)?;
        let mut path = self.instance_root(instance);
        for segment in segments {
            path.push(segment);
        }
        Ok(path)
    }

    /// Like [`Vfs::resolve`], but additionally canonicalises the existing path
    /// and verifies it is still inside the instance (defeats symlinked
    /// directories planted by a guest).
    pub fn resolve_existing(&self, instance: &InstanceId, relative: &str) -> Result<PathBuf> {
        let path = self.resolve(instance, relative)?;
        let canonical = path.canonicalize()?;
        let root = self.instance_root(instance).canonicalize()?;
        bb_ensure!(
            canonical.starts_with(&root),
            ErrorCode::PermissionDenied,
            "path escapes the instance directory after resolving links"
        );
        Ok(canonical)
    }

    pub fn write_file(
        &self,
        instance: &InstanceId,
        relative: &str,
        contents: &[u8],
    ) -> Result<PathBuf> {
        let path = self.resolve(instance, relative)?;
        // Materialise the instance root first: the containment check compares
        // canonical paths, and an absent directory has nothing to canonicalise.
        self.ensure_layout(instance)?;
        self.ensure_inside_existing_ancestor(instance, &path)?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, contents)?;
        Ok(path)
    }

    pub fn read_file(&self, instance: &InstanceId, relative: &str) -> Result<Vec<u8>> {
        let path = self.resolve_existing(instance, relative)?;
        Ok(std::fs::read(path)?)
    }

    /// Recursively measures an instance directory. Does not follow symlinks.
    pub fn usage(&self, instance: &InstanceId) -> Result<Usage> {
        let root = self.instance_root(instance);
        if !root.exists() {
            return Ok(Usage::default());
        }
        measure(&root)
    }

    /// Product-level quota check; returns the measured usage on success.
    pub fn enforce_quota(&self, instance: &InstanceId, limit_bytes: u64) -> Result<Usage> {
        let usage = self.usage(instance)?;
        if limit_bytes > 0 {
            bb_ensure!(
                usage.bytes <= limit_bytes,
                ErrorCode::TooLarge,
                "instance {} uses {} bytes, quota is {} bytes",
                instance,
                usage.bytes,
                limit_bytes
            );
        }
        Ok(usage)
    }

    pub fn remove_instance(&self, instance: &InstanceId) -> Result<RemovalStats> {
        let root = self.instance_root(instance);
        if !root.exists() {
            return Err(BbError::new(
                ErrorCode::NotFound,
                format!("instance directory for {instance} does not exist"),
            ));
        }
        let usage = measure(&root)?;
        std::fs::remove_dir_all(&root)?;
        Ok(RemovalStats {
            bytes: usage.bytes,
            files: usage.files,
            dirs: usage.dirs,
        })
    }

    /// Instance ids that currently have a directory. Lets the engine detect and
    /// clean up orphans left behind by a crash.
    pub fn list_instance_dirs(&self) -> Result<Vec<String>> {
        let root = self.instances_root();
        if !root.exists() {
            return Ok(Vec::new());
        }
        let mut found = Vec::new();
        for entry in std::fs::read_dir(&root)? {
            let entry = entry?;
            if entry.file_type()?.is_dir() {
                if let Some(name) = entry.file_name().to_str() {
                    found.push(name.to_string());
                }
            }
        }
        found.sort();
        Ok(found)
    }

    /// Total size of the whole virtual root (diagnostics / UI).
    pub fn total_usage(&self) -> Result<Usage> {
        measure(&self.root)
    }

    fn ensure_inside_existing_ancestor(&self, instance: &InstanceId, path: &Path) -> Result<()> {
        let instance_root = self.instance_root(instance);
        let canonical_root = if instance_root.exists() {
            instance_root.canonicalize()?
        } else {
            self.instances_root().join(instance.as_str())
        };
        let mut probe = path;
        loop {
            if probe.exists() {
                let canonical = probe.canonicalize()?;
                bb_ensure!(
                    canonical.starts_with(&canonical_root),
                    ErrorCode::PermissionDenied,
                    "write target escapes the instance directory"
                );
                return Ok(());
            }
            match probe.parent() {
                Some(parent) => probe = parent,
                None => {
                    return Err(BbError::new(
                        ErrorCode::InvalidArgument,
                        "write target has no existing ancestor",
                    ));
                }
            }
        }
    }
}

/// Splits and validates a guest-relative path.
///
/// Returns owned-ish borrowed segments so callers can rebuild the path without
/// allocating intermediate strings.
pub fn normalize_relative(relative: &str) -> Result<Vec<&str>> {
    bb_ensure!(
        !relative.is_empty(),
        ErrorCode::InvalidArgument,
        "path must not be empty"
    );
    bb_ensure!(
        relative.len() <= MAX_RELATIVE_PATH_LEN,
        ErrorCode::TooLarge,
        "path longer than {MAX_RELATIVE_PATH_LEN} bytes"
    );
    bb_ensure!(
        !relative.starts_with('/') && !relative.starts_with('\\'),
        ErrorCode::PermissionDenied,
        "absolute paths are not allowed"
    );
    // Windows drive letters (`C:\...`) and NTFS alternate data streams (`a:b`).
    bb_ensure!(
        !relative.contains(':'),
        ErrorCode::PermissionDenied,
        "drive letters and alternate data streams are not allowed"
    );
    bb_ensure!(
        !relative.chars().any(|c| c == '\0' || c.is_control()),
        ErrorCode::InvalidArgument,
        "path contains control characters"
    );

    let mut segments = Vec::new();
    for segment in relative.split(['/', '\\']) {
        match segment {
            "" | "." => continue,
            ".." => {
                return Err(BbError::new(
                    ErrorCode::PermissionDenied,
                    "parent directory traversal is not allowed",
                ));
            }
            other => {
                // Windows silently strips these, which would alias two different
                // guest paths onto one host path.
                bb_ensure!(
                    !other.ends_with('.') && !other.ends_with(' '),
                    ErrorCode::InvalidArgument,
                    "path segment '{other}' ends with a dot or space"
                );
                bb_ensure!(
                    other.len() <= 255,
                    ErrorCode::TooLarge,
                    "path segment longer than 255 bytes"
                );
                segments.push(other);
            }
        }
    }
    bb_ensure!(
        !segments.is_empty(),
        ErrorCode::InvalidArgument,
        "path resolves to the instance root; be explicit"
    );
    Ok(segments)
}

fn measure(root: &Path) -> Result<Usage> {
    let mut usage = Usage::default();
    let mut stack = vec![root.to_path_buf()];
    while let Some(current) = stack.pop() {
        let entries = match std::fs::read_dir(&current) {
            Ok(entries) => entries,
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => continue,
            Err(err) => return Err(err.into()),
        };
        for entry in entries {
            let entry = entry?;
            // Never follow symlinks: a link to the host's data directory must not
            // be attributed to (or deleted as part of) the instance.
            let metadata = std::fs::symlink_metadata(entry.path())?;
            if metadata.is_dir() {
                usage.dirs += 1;
                stack.push(entry.path());
            } else if metadata.is_file() {
                usage.files += 1;
                usage.bytes = usage.bytes.saturating_add(metadata.len());
            }
        }
    }
    Ok(usage)
}

/// Guests sometimes hand us `/data/user/0/<pkg>/files/x`. Without rewriting,
/// the absolute prefix would be rejected; this helper maps a known prefix to an
/// instance-relative suffix. The Kotlin layer supplies the prefix it decided on.
pub fn strip_guest_prefix<'a>(absolute: &'a str, guest_root: &str) -> Result<&'a str> {
    bb_ensure!(
        !guest_root.is_empty(),
        ErrorCode::InvalidArgument,
        "guest root must not be empty"
    );
    let trimmed_root = guest_root.trim_end_matches('/');
    let suffix = absolute
        .strip_prefix(trimmed_root)
        .ok_or_else(|| BbError::not_found(format!("{absolute} is not inside {guest_root}")))?;
    let suffix = suffix.trim_start_matches('/');
    bb_ensure!(
        !suffix.is_empty(),
        ErrorCode::InvalidArgument,
        "path points at the guest root itself"
    );
    Ok(suffix)
}

/// Ensures the path is composed only of normal components. Used by callers that
/// build paths themselves.
pub fn is_normalized(path: &Path) -> bool {
    path.components()
        .all(|component| matches!(component, Component::Normal(_) | Component::CurDir))
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;

    fn fixture() -> (TempDir, Vfs, InstanceId) {
        let dir = TempDir::new("vfs");
        let vfs = Vfs::new(dir.join("virtual")).unwrap();
        let id = InstanceId::parse("instance_001").unwrap();
        (dir, vfs, id)
    }

    #[test]
    fn creates_full_layout() {
        let (_dir, vfs, id) = fixture();
        let layout = vfs.ensure_layout(&id).unwrap();
        for dir in INSTANCE_DIRS {
            let path = layout.root.join(dir);
            assert!(path.is_dir(), "{} should exist", path.display());
        }
        assert!(layout.files.ends_with("files"));
        // Idempotent.
        vfs.ensure_layout(&id).unwrap();
    }

    #[test]
    fn resolve_stays_inside_instance() {
        let (_dir, vfs, id) = fixture();
        let root = vfs.instance_root(&id);
        assert_eq!(
            vfs.resolve(&id, "files/a/b.txt").unwrap(),
            root.join("files").join("a").join("b.txt")
        );
        assert_eq!(
            vfs.resolve(&id, "./files//b.txt").unwrap(),
            root.join("files/b.txt")
        );
        assert_eq!(
            vfs.resolve(&id, "files\\b.txt").unwrap(),
            root.join("files/b.txt")
        );
        assert!(vfs.resolve(&id, "files/文档 1.txt").is_ok());
        assert!(vfs.resolve(&id, ".hidden").is_ok());
    }

    #[test]
    fn resolve_rejects_escapes() {
        let (_dir, vfs, id) = fixture();
        for bad in [
            "",
            "..",
            "../etc/passwd",
            "files/../../host",
            "/data/user/0/com.example",
            "\\windows\\system32",
            "C:\\Windows",
            "files/a:b",
            "files/a.",
            "files/a ",
            "files/\0name",
            "files/a\nb",
        ] {
            let err = vfs.resolve(&id, bad).expect_err(bad);
            assert!(
                matches!(
                    err.code(),
                    ErrorCode::PermissionDenied | ErrorCode::InvalidArgument | ErrorCode::TooLarge
                ),
                "unexpected code for {bad:?}: {err:?}"
            );
        }
        let long_segment = "a".repeat(256);
        assert_eq!(
            vfs.resolve(&id, &long_segment).unwrap_err().code(),
            ErrorCode::TooLarge
        );
        let long_path = "a/".repeat(MAX_RELATIVE_PATH_LEN);
        assert_eq!(
            vfs.resolve(&id, &long_path).unwrap_err().code(),
            ErrorCode::TooLarge
        );
    }

    #[test]
    fn write_read_round_trip() {
        let (_dir, vfs, id) = fixture();
        let path = vfs
            .write_file(&id, "shared_prefs/prefs.xml", b"<map/>")
            .unwrap();
        assert!(path.exists());
        assert_eq!(
            vfs.read_file(&id, "shared_prefs/prefs.xml").unwrap(),
            b"<map/>"
        );
        assert_eq!(
            vfs.read_file(&id, "shared_prefs/missing.xml")
                .unwrap_err()
                .code(),
            ErrorCode::NotFound
        );
    }

    #[test]
    fn usage_and_removal_are_accurate() {
        let (_dir, vfs, id) = fixture();
        vfs.write_file(&id, "files/a.txt", &[0u8; 100]).unwrap();
        vfs.write_file(&id, "cache/b.bin", &[0u8; 50]).unwrap();
        let usage = vfs.usage(&id).unwrap();
        assert_eq!(usage.files, 2);
        assert_eq!(usage.bytes, 150);
        assert!(usage.dirs >= INSTANCE_DIRS.len() as u64);

        let removed = vfs.remove_instance(&id).unwrap();
        assert_eq!(removed.bytes, 150);
        assert_eq!(removed.files, 2);
        assert!(!vfs.instance_root(&id).exists());
        assert_eq!(
            vfs.remove_instance(&id).unwrap_err().code(),
            ErrorCode::NotFound
        );
    }

    #[test]
    fn quota_is_enforced() {
        let (_dir, vfs, id) = fixture();
        vfs.write_file(&id, "files/big.bin", &[7u8; 2048]).unwrap();
        assert!(vfs.enforce_quota(&id, 4096).is_ok());
        let err = vfs.enforce_quota(&id, 1024).unwrap_err();
        assert_eq!(err.code(), ErrorCode::TooLarge);
        // 0 means unlimited.
        assert!(vfs.enforce_quota(&id, 0).is_ok());
    }

    #[test]
    fn lists_instance_directories() {
        let (_dir, vfs, _id) = fixture();
        let first = InstanceId::parse("instance_001").unwrap();
        let second = InstanceId::parse("instance_002").unwrap();
        vfs.ensure_layout(&first).unwrap();
        vfs.ensure_layout(&second).unwrap();
        assert_eq!(
            vfs.list_instance_dirs().unwrap(),
            vec!["instance_001".to_string(), "instance_002".to_string()]
        );
    }

    #[test]
    fn strips_guest_absolute_prefixes() {
        assert_eq!(
            strip_guest_prefix(
                "/data/user/0/com.example.app/files/db/x",
                "/data/user/0/com.example.app"
            )
            .unwrap(),
            "files/db/x"
        );
        assert_eq!(
            strip_guest_prefix(
                "/data/user/0/com.other/files/x",
                "/data/user/0/com.example.app"
            )
            .unwrap_err()
            .code(),
            ErrorCode::NotFound
        );
        assert!(
            strip_guest_prefix(
                "/data/user/0/com.example.app/",
                "/data/user/0/com.example.app"
            )
            .is_err()
        );
    }

    #[test]
    fn normalized_predicate_flags_odd_paths() {
        assert!(is_normalized(Path::new("files/a")));
        assert!(!is_normalized(Path::new("../files/a")));
        assert!(!is_normalized(Path::new("/files/a")));
    }

    #[cfg(unix)]
    #[test]
    fn symlink_escape_is_detected() {
        use std::os::unix::fs::symlink;
        let (dir, vfs, id) = fixture();
        vfs.ensure_layout(&id).unwrap();
        let outside = dir.join("outside");
        std::fs::create_dir_all(&outside).unwrap();
        std::fs::write(outside.join("secret.txt"), b"host data").unwrap();
        symlink(&outside, vfs.instance_root(&id).join("files/link")).unwrap();

        let err = vfs.read_file(&id, "files/link/secret.txt").unwrap_err();
        assert_eq!(err.code(), ErrorCode::PermissionDenied);
        let err = vfs
            .write_file(&id, "files/link/planted.txt", b"x")
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::PermissionDenied);
    }
}
