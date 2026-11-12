//! `bb-bridge` is the only crate that knows about FFI.
//!
//! * [`engine`] composes the core crates into one object with a small, JSON
//!   friendly API;
//! * [`ffi`] is the stable C ABI (same symbols work from Kotlin/JNI, C and
//!   command-line experiments);
//! * [`jni`] is the hand-written JNI surface used by
//!   `com.adv.bb.nativebridge.NativeBridge`.
//!
//! The Kotlin layer talks to [`engine`] exclusively through JSON, which keeps the
//! boundary debuggable (`adb logcat`, `bb-bench`, unit tests) without pretending
//! that JSON is free: high-frequency operations (state polling) are one integer
//! or one short string, and long operations use task ids instead of blocking.

pub mod engine;
pub mod ffi;
#[cfg(feature = "jni")]
pub mod jni;

pub use engine::{BbEngine, TaskCommand, state_name};
pub use ffi::{
    bb_abi_version, bb_engine_cancel_task, bb_engine_create, bb_engine_destroy,
    bb_engine_initialize_json, bb_engine_last_error, bb_engine_metrics_json,
    bb_engine_request_json, bb_engine_submit_task_json, bb_engine_summary_json,
    bb_engine_task_result_json, bb_string_free,
};
