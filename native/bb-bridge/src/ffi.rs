//! Stable C ABI.
//!
//! Rules this module enforces (they are the reason it exists as a separate
//! layer):
//!
//! 1. **No panic ever crosses the boundary.** Every entry point wraps its body
//!    in `catch_unwind`; a panic becomes `PANIC` (-13).
//! 2. **Handles are validated.** `bb_engine_create` registers the pointer in a
//!    live-handle set; `bb_engine_destroy` only frees registered pointers, which
//!    makes a double destroy a no-op instead of a use-after-free.
//! 3. **Strings have one owner.** Functions returning `char*` transfer ownership
//!    to the caller, who must release it with `bb_string_free`.
//! 4. **Errors are numbers plus a message.** Numeric codes are the contract;
//!    `bb_engine_last_error` returns detail for logging only.
//!
//! Everything is `extern "C"` + `#[unsafe(no_mangle)]` so the same symbols work
//! from Kotlin/JNI, C tooling and `adb shell` experiments.

use std::collections::HashSet;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, OnceLock};

use bb_common::ABI_VERSION;
use bb_common::error::{BbError, ErrorCode, Result};

use crate::engine::BbEngine;

fn live_handles() -> &'static Mutex<HashSet<usize>> {
    static HANDLES: OnceLock<Mutex<HashSet<usize>>> = OnceLock::new();
    HANDLES.get_or_init(|| Mutex::new(HashSet::new()))
}

fn register_handle(handle: *mut c_void) {
    if let Ok(mut handles) = live_handles().lock() {
        handles.insert(handle as usize);
    }
}

fn unregister_handle(handle: *mut c_void) -> bool {
    live_handles()
        .lock()
        .map(|mut handles| handles.remove(&(handle as usize)))
        .unwrap_or(false)
}

fn is_registered(handle: *mut c_void) -> bool {
    live_handles()
        .lock()
        .map(|handles| handles.contains(&(handle as usize)))
        .unwrap_or(false)
}

/// Monotonic create/destroy counters used by the leak regression tests.
///
/// Deltas are used instead of the live-set size because Rust runs tests in
/// parallel: another test may legitimately hold a handle at the same time, but
/// it cannot change *this* test's delta.
#[cfg(test)]
static CREATED_HANDLES: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
#[cfg(test)]
static DESTROYED_HANDLES: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

#[cfg(test)]
pub(crate) fn handle_counters() -> (usize, usize) {
    (
        CREATED_HANDLES.load(std::sync::atomic::Ordering::Relaxed),
        DESTROYED_HANDLES.load(std::sync::atomic::Ordering::Relaxed),
    )
}

/// # Safety
/// `handle` must come from [`bb_engine_create`] and must still be alive.
unsafe fn engine_from<'a>(handle: *mut c_void) -> Option<&'a BbEngine> {
    if handle.is_null() || !is_registered(handle) {
        return None;
    }
    // SAFETY: the registry guarantees the pointer came from `Box::into_raw` of a
    // `BbEngine` and has not been freed yet.
    Some(unsafe { &*(handle as *const BbEngine) })
}

fn invalid_handle() -> BbError {
    BbError::invalid_argument("invalid or already destroyed engine handle")
}

fn to_owned_c_string(value: &str) -> *mut c_char {
    match CString::new(value) {
        Ok(text) => text.into_raw(),
        // Interior NUL: should be impossible for our JSON output, but returning
        // null is still better than panicking across FFI.
        Err(_) => std::ptr::null_mut(),
    }
}

fn record_error(handle: *mut c_void, error: &BbError) {
    // SAFETY: `engine_from` validates the handle; failures here are ignorable
    // because the error is also returned as a numeric code.
    if let Some(engine) = unsafe { engine_from(handle) } {
        engine.set_last_error(error.to_string());
    }
}

/// # Safety
/// `ptr` must be null or a NUL-terminated UTF-8 string.
unsafe fn read_c_string(ptr: *const c_char) -> Result<String> {
    if ptr.is_null() {
        return Err(BbError::invalid_argument("null string argument"));
    }
    let text = unsafe { CStr::from_ptr(ptr) };
    text.to_str()
        .map(|value| value.to_owned())
        .map_err(|_| BbError::invalid_argument("argument is not valid UTF-8"))
}

#[unsafe(no_mangle)]
pub extern "C" fn bb_abi_version() -> u32 {
    ABI_VERSION
}

/// Creates an uninitialized engine. Returns null only if allocation panics.
#[unsafe(no_mangle)]
pub extern "C" fn bb_engine_create() -> *mut c_void {
    catch_unwind(|| {
        let engine = Box::new(BbEngine::new());
        let handle = Box::into_raw(engine) as *mut c_void;
        register_handle(handle);
        #[cfg(test)]
        CREATED_HANDLES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        handle
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Frees an engine. Idempotent: unknown or already-freed handles are ignored.
///
/// # Safety
/// `handle` must be a pointer previously returned by [`bb_engine_create`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_destroy(handle: *mut c_void) {
    if handle.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        if unregister_handle(handle) {
            drop(Box::from_raw(handle as *mut BbEngine));
            #[cfg(test)]
            DESTROYED_HANDLES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }));
}

/// Initializes the engine from a JSON [`bb_config::EngineConfig`].
/// Returns 0 on success or a negative [`ErrorCode`].
///
/// # Safety
/// `handle` must be valid; `config_json` must be a NUL-terminated UTF-8 string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_initialize_json(
    handle: *mut c_void,
    config_json: *const c_char,
) -> i32 {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        let json = unsafe { read_c_string(config_json) }?;
        let config = bb_config::EngineConfig::from_json(&json)?;
        engine.initialize(config)
    }));
    match outcome {
        Ok(Ok(())) => ErrorCode::Ok.as_i32(),
        Ok(Err(error)) => {
            record_error(handle, &error);
            error.code().as_i32()
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            error.code().as_i32()
        }
    }
}

/// Handles one JSON request (`bb_ipc::Request`) and returns an owned JSON
/// response (`bb_ipc::Response`) that the caller must free with
/// [`bb_string_free`]. Returns null on transport-level failure.
///
/// # Safety
/// `handle` must be valid; `request_json` must be NUL-terminated UTF-8.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_request_json(
    handle: *mut c_void,
    request_json: *const c_char,
) -> *mut c_char {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        let json = unsafe { read_c_string(request_json) }?;
        engine.request_json(&json)
    }));
    match outcome {
        Ok(Ok(json)) => to_owned_c_string(&json),
        Ok(Err(error)) => {
            record_error(handle, &error);
            std::ptr::null_mut()
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            std::ptr::null_mut()
        }
    }
}

/// Queues a background task (`bb_bridge::engine::TaskCommand` JSON).
///
/// Returns a positive task id, or a negative [`ErrorCode`] when the task could
/// not be queued at all. Task-level failures are reported by
/// [`bb_engine_task_result_json`].
///
/// # Safety
/// `handle` must be valid; `command_json` must be NUL-terminated UTF-8.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_submit_task_json(
    handle: *mut c_void,
    command_json: *const c_char,
) -> i64 {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<u64> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        let json = unsafe { read_c_string(command_json) }?;
        engine.submit_task(&json)
    }));
    match outcome {
        Ok(Ok(task_id)) => task_id as i64,
        Ok(Err(error)) => {
            record_error(handle, &error);
            error.code().as_i32() as i64
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            error.code().as_i32() as i64
        }
    }
}

/// Returns the JSON snapshot of a task, or null while the id is unknown.
///
/// # Safety
/// `handle` must be valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_task_result_json(
    handle: *mut c_void,
    task_id: i64,
) -> *mut c_char {
    if task_id <= 0 {
        return std::ptr::null_mut();
    }
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Option<String>> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        engine.task_result_json(task_id as u64)
    }));
    match outcome {
        Ok(Ok(Some(json))) => to_owned_c_string(&json),
        Ok(Ok(None)) => std::ptr::null_mut(),
        Ok(Err(error)) => {
            record_error(handle, &error);
            std::ptr::null_mut()
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            std::ptr::null_mut()
        }
    }
}

/// Requests cancellation of a task. Returns 0 or a negative [`ErrorCode`].
///
/// # Safety
/// `handle` must be valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_cancel_task(handle: *mut c_void, task_id: i64) -> i32 {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<bool> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        engine.cancel_task(task_id as u64)
    }));
    match outcome {
        Ok(Ok(true)) => ErrorCode::Ok.as_i32(),
        Ok(Ok(false)) => ErrorCode::NotFound.as_i32(),
        Ok(Err(error)) => {
            record_error(handle, &error);
            error.code().as_i32()
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            error.code().as_i32()
        }
    }
}

/// Returns the metrics snapshot as JSON (caller frees).
///
/// # Safety
/// `handle` must be valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_metrics_json(handle: *mut c_void) -> *mut c_char {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        engine.metrics_json()
    }));
    match outcome {
        Ok(Ok(json)) => to_owned_c_string(&json),
        Ok(Err(error)) => {
            record_error(handle, &error);
            std::ptr::null_mut()
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            std::ptr::null_mut()
        }
    }
}

/// Returns the engine status as JSON (caller frees).
///
/// # Safety
/// `handle` must be valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_summary_json(handle: *mut c_void) -> *mut c_char {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        let engine = unsafe { engine_from(handle) }.ok_or_else(invalid_handle)?;
        engine.summary_json()
    }));
    match outcome {
        Ok(Ok(json)) => to_owned_c_string(&json),
        Ok(Err(error)) => {
            record_error(handle, &error);
            std::ptr::null_mut()
        }
        Err(payload) => {
            let error = BbError::from_panic_payload(payload.as_ref());
            record_error(handle, &error);
            std::ptr::null_mut()
        }
    }
}

/// Last error message for this engine (caller frees). Never null for a valid
/// handle; may be an empty string.
///
/// # Safety
/// `handle` must be valid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_engine_last_error(handle: *mut c_void) -> *mut c_char {
    match catch_unwind(AssertUnwindSafe(|| unsafe { engine_from(handle) })) {
        Ok(Some(engine)) => to_owned_c_string(&engine.last_error()),
        _ => std::ptr::null_mut(),
    }
}

/// Releases a string returned by this library.
///
/// # Safety
/// `text` must be null or a pointer previously returned by this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn bb_string_free(text: *mut c_char) {
    if text.is_null() {
        return;
    }
    // SAFETY: contract of the function; `CString::from_raw` reconstructs the
    // allocation created in `to_owned_c_string`.
    drop(unsafe { CString::from_raw(text) });
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::TempDir;
    use std::ffi::CString;

    /// The handle registry is process-global, so the tests that observe it must
    /// not overlap with tests that create handles. This lock serialises the whole
    /// module instead of relying on timing.
    fn serial() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: Mutex<()> = Mutex::new(());
        LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn c(value: &str) -> CString {
        CString::new(value).unwrap()
    }

    fn initialized(handle: *mut c_void, dir: &TempDir) {
        let json = format!(
            r#"{{"root_path":{}}}"#,
            serde_json::to_string(&dir.path().to_string_lossy().to_string()).unwrap()
        );
        let code = unsafe { bb_engine_initialize_json(handle, c(&json).as_ptr()) };
        assert_eq!(code, 0, "initialize failed: {code}");
    }

    fn take_string(ptr: *mut c_char) -> String {
        assert!(!ptr.is_null());
        let text = unsafe { CStr::from_ptr(ptr) }
            .to_string_lossy()
            .into_owned();
        unsafe { bb_string_free(ptr) };
        text
    }

    #[test]
    fn abi_version_is_exposed() {
        let _serial = serial();
        assert_eq!(bb_abi_version(), ABI_VERSION);
    }

    #[test]
    fn create_destroy_and_double_destroy() {
        let _serial = serial();
        let handle = bb_engine_create();
        assert!(!handle.is_null());
        unsafe {
            bb_engine_destroy(handle);
            // Second destroy must be a no-op, not a use-after-free.
            bb_engine_destroy(handle);
            bb_engine_destroy(std::ptr::null_mut());
        }
    }

    #[test]
    fn null_and_bogus_handles_are_rejected() {
        let _serial = serial();
        let code = unsafe { bb_engine_initialize_json(std::ptr::null_mut(), c("{}").as_ptr()) };
        assert_eq!(code, ErrorCode::InvalidArgument.as_i32());

        // A pointer that was never registered must not be dereferenced.
        let bogus = 0xDEAD_BEEF as *mut c_void;
        let code = unsafe { bb_engine_initialize_json(bogus, c("{}").as_ptr()) };
        assert_eq!(code, ErrorCode::InvalidArgument.as_i32());
        assert!(unsafe { bb_engine_request_json(bogus, c("{}").as_ptr()) }.is_null());
    }

    #[test]
    fn invalid_json_reports_argument_error() {
        let _serial = serial();
        let handle = bb_engine_create();
        let code = unsafe { bb_engine_initialize_json(handle, c("{ not json").as_ptr()) };
        assert_eq!(code, ErrorCode::InvalidArgument.as_i32());
        let message = take_string(unsafe { bb_engine_last_error(handle) });
        assert!(!message.is_empty());
        unsafe { bb_engine_destroy(handle) };
    }

    #[test]
    fn request_response_round_trip() {
        let _serial = serial();
        let dir = TempDir::new("ffi");
        let handle = bb_engine_create();
        initialized(handle, &dir);
        let response = take_string(unsafe {
            bb_engine_request_json(handle, c(r#"{"type":"list_instances"}"#).as_ptr())
        });
        assert!(response.contains("\"instances\""));
        assert!(response.contains("list_instances") || response.contains("\"type\":\"instances\""));

        let metrics = take_string(unsafe { bb_engine_metrics_json(handle) });
        assert!(metrics.contains("counters"));
        let summary = take_string(unsafe { bb_engine_summary_json(handle) });
        assert!(summary.contains("abi_version"));
        unsafe { bb_engine_destroy(handle) };
    }

    #[test]
    fn tasks_are_submitted_and_polled() {
        let _serial = serial();
        let dir = TempDir::new("ffi");
        let handle = bb_engine_create();
        initialized(handle, &dir);
        let task_id = unsafe {
            bb_engine_submit_task_json(handle, c(r#"{"command":"list_instances"}"#).as_ptr())
        };
        assert!(task_id > 0, "submit returned {task_id}");

        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            let ptr = unsafe { bb_engine_task_result_json(handle, task_id) };
            if !ptr.is_null() {
                let json = take_string(ptr);
                let value: serde_json::Value = serde_json::from_str(&json).unwrap();
                if value["state"] == "succeeded" || value["state"] == "failed" {
                    assert_eq!(value["state"], "succeeded");
                    break;
                }
            }
            assert!(std::time::Instant::now() < deadline, "task never finished");
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert!(unsafe { bb_engine_task_result_json(handle, 9999) }.is_null());
        assert!(unsafe { bb_engine_task_result_json(handle, -1) }.is_null());
        unsafe { bb_engine_destroy(handle) };
    }

    #[test]
    fn handles_do_not_leak_across_create_destroy_cycles() {
        let _serial = serial();
        let (created_before, destroyed_before) = handle_counters();
        for _ in 0..2_000 {
            let handle = bb_engine_create();
            assert!(!handle.is_null());
            unsafe { bb_engine_destroy(handle) };
        }
        let (created_after, destroyed_after) = handle_counters();
        assert_eq!(
            created_after - created_before,
            2_000,
            "every create must be counted"
        );
        assert_eq!(
            destroyed_after - destroyed_before,
            2_000,
            "every destroy must release its handle (no leak)"
        );
    }

    #[test]
    fn failed_initialization_does_not_leak_the_handle() {
        let _serial = serial();
        let dir = TempDir::new("ffi");
        let (created_before, destroyed_before) = handle_counters();
        let handle = bb_engine_create();
        initialized(handle, &dir);
        // Second initialize fails but the handle stays valid and must be freed.
        let code = unsafe {
            bb_engine_initialize_json(
                handle,
                c(&format!(
                    r#"{{"root_path":{}}}"#,
                    serde_json::to_string(&dir.path().to_string_lossy().to_string()).unwrap()
                ))
                .as_ptr(),
            )
        };
        assert_eq!(code, ErrorCode::AlreadyExists.as_i32());
        unsafe { bb_engine_destroy(handle) };
        let (created_after, destroyed_after) = handle_counters();
        assert_eq!(created_after - created_before, 1);
        assert_eq!(destroyed_after - destroyed_before, 1);
    }

    #[test]
    fn null_strings_are_rejected() {
        let _serial = serial();
        let dir = TempDir::new("ffi");
        let handle = bb_engine_create();
        initialized(handle, &dir);
        let response = unsafe { bb_engine_request_json(handle, std::ptr::null()) };
        assert!(response.is_null());
        let message = take_string(unsafe { bb_engine_last_error(handle) });
        assert!(message.contains("null string"), "{message}");
        unsafe { bb_engine_destroy(handle) };
    }
}
