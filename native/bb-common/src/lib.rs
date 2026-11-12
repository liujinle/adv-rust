//! Foundation crate of the BlackBox native core.
//!
//! This crate deliberately contains **no Android knowledge**: it is a plain
//! Rust library that can be unit tested on the host, cross compiled for any
//! Android ABI, and reused by tooling. Everything that needs the Android
//! framework lives in the Kotlin compatibility layer (`Bcore/compatibility`).
//!
//! Layering rule enforced by the workspace:
//!
//! ```text
//! bb-common                 no dependencies
//!   ├── bb-config           policy / configuration values
//!   ├── bb-storage          metadata persistence
//!   ├── bb-vfs              virtual path mapping
//!   ├── bb-ipc              message protocol
//!   ├── bb-scheduler        task queue
//!   ├── bb-apk              APK / binary XML inspection
//!   ├── bb-resource         cache accounting
//!   ├── bb-monitor          metrics
//!   └── bb-runtime          lifecycle state machine (uses storage)
//!         └── bb-bridge     C ABI + JNI surface (uses everything)
//! ```

pub mod error;
pub mod event;
pub mod ids;
pub mod logging;
pub mod state;
pub mod testing;
pub mod time;

pub use error::{BbError, ErrorCode, Result};
pub use event::RuntimeEvent;
pub use ids::{InstanceId, PackageName};
pub use state::InstanceState;

/// Version of the C ABI exported by `bb-bridge`. Bumped whenever an exported
/// symbol changes signature or semantics. Kotlin refuses to talk to a core
/// with a different major version instead of failing at runtime.
pub const ABI_VERSION: u32 = 1;

/// Version of the internal IPC request/response schema (`bb-ipc`).
pub const PROTOCOL_VERSION: u32 = 1;

/// Longest accepted relative virtual path, in bytes.
pub const MAX_RELATIVE_PATH_LEN: usize = 4096;
