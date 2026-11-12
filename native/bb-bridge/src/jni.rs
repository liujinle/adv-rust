//! Hand-written JNI bindings (no `jni` crate).
//!
//! Why not the `jni` crate:
//! * one less dependency to cross compile for four Android ABIs;
//! * the surface is seven functions, so the entire ABI is visible in one file;
//! * the JNI function table indices are stable by specification, and are
//!   verified against the JDK's `jni.h` (NewStringUTF = 167,
//!   GetStringUTFChars = 169, ReleaseStringUTFChars = 170).
//!
//! Threading: a `JNIEnv*` is only ever valid on the thread that entered the JVM.
//! Nothing here stores one, and no `JNIEnv` is captured by the scheduler.
//!
//! Symbol names must match `com.adv.bb.nativebridge.NativeBridge`; the Kotlin
//! file in `android/bbcore` declares exactly these `external fun`s.
//!
//! # Safety contract (all exported symbols)
//!
//! These functions are `unsafe extern "system"` because only the JVM may call
//! them: `env` must be a valid `JNIEnv*` for the calling thread, and every
//! `jstring` must be a live local reference. The handle is validated against the
//! registry in `ffi`, so an unknown or already destroyed handle returns an error
//! instead of dereferencing freed memory.
#![allow(clippy::missing_safety_doc)]

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};

use bb_common::ErrorCode;

use crate::ffi;

pub type JniEnvPtr = *mut JniEnv;
pub type JObject = *mut c_void;
pub type JString = *mut c_void;

/// `JNIEnv*` is a pointer to the function table.
#[repr(C)]
pub struct JniEnv {
    pub functions: *const *const c_void,
}

type NewStringUtf = unsafe extern "system" fn(JniEnvPtr, *const c_char) -> JString;
type GetStringUtfChars = unsafe extern "system" fn(JniEnvPtr, JString, *mut u8) -> *const c_char;
type ReleaseStringUtfChars = unsafe extern "system" fn(JniEnvPtr, JString, *const c_char);

// Indices into JNINativeInterface_ (see JDK jni.h, 0-based including the four
// reserved slots).
const FN_NEW_STRING_UTF: usize = 167;
const FN_GET_STRING_UTF_CHARS: usize = 169;
const FN_RELEASE_STRING_UTF_CHARS: usize = 170;

/// Resolves one JNI function by table index.
///
/// # Safety
/// `env` must be a valid `JNIEnv*` for the current thread.
unsafe fn function<T: Copy>(env: JniEnvPtr, index: usize) -> Option<T> {
    if env.is_null() {
        return None;
    }
    // SAFETY: JNIEnv is a pointer to a table that outlives the call.
    let table = unsafe { (*env).functions };
    if table.is_null() {
        return None;
    }
    // SAFETY: indices are fixed by the JVM ABI; the table has 234 entries.
    let entry = unsafe { *table.add(index) };
    if entry.is_null() {
        return None;
    }
    Some(unsafe { std::mem::transmute_copy(&entry) })
}

/// RAII guard for `GetStringUTFChars`.
///
/// Without it, an allocation failure between "get" and "release" would unwind
/// past the release call and leak a JNI reference/character buffer — precisely
/// the JNI leak class this project has to avoid. `Drop` runs during unwinding,
/// and every exported function catches the panic afterwards.
struct JavaStringChars<'a> {
    env: JniEnvPtr,
    text: JString,
    chars: *const c_char,
    release: ReleaseStringUtfChars,
    _lifetime: std::marker::PhantomData<&'a ()>,
}

impl Drop for JavaStringChars<'_> {
    fn drop(&mut self) {
        if !self.chars.is_null() {
            // SAFETY: the pair was produced by `GetStringUTFChars` on this thread
            // and has not been released yet.
            unsafe { (self.release)(self.env, self.text, self.chars) };
        }
    }
}

/// Copies a Java string into Rust. The character buffer is released by
/// [`JavaStringChars`] before this function returns, even on panic; no JNI
/// reference is retained beyond the call.
///
/// # Safety
/// `env` must be valid on this thread and `value` must be a valid `jstring` or null.
unsafe fn to_rust_string(env: JniEnvPtr, value: JString) -> Option<String> {
    if value.is_null() {
        return None;
    }
    let get: GetStringUtfChars = unsafe { function(env, FN_GET_STRING_UTF_CHARS) }?;
    let release: ReleaseStringUtfChars = unsafe { function(env, FN_RELEASE_STRING_UTF_CHARS) }?;
    let chars = unsafe { get(env, value, std::ptr::null_mut()) };
    if chars.is_null() {
        return None;
    }
    let guard = JavaStringChars {
        env,
        text: value,
        chars,
        release,
        _lifetime: std::marker::PhantomData,
    };
    // SAFETY: JNI guarantees a NUL-terminated UTF-8 buffer valid until release,
    // which happens when `guard` drops at the end of this scope.
    let text = unsafe { CStr::from_ptr(guard.chars) }
        .to_string_lossy()
        .into_owned();
    drop(guard);
    Some(text)
}

/// # Safety
/// `env` must be valid on this thread.
unsafe fn to_jstring(env: JniEnvPtr, value: &str) -> JString {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let Some(new_string) = (unsafe { function::<NewStringUtf>(env, FN_NEW_STRING_UTF) }) else {
        return std::ptr::null_mut();
    };
    let Ok(text) = CString::new(value) else {
        return std::ptr::null_mut();
    };
    unsafe { new_string(env, text.as_ptr()) }
}

/// Consumes a C string produced by the core and returns a Java string.
///
/// # Safety
/// `env` must be valid; `ptr` must be null or an owned core allocation.
unsafe fn consume_to_jstring(env: JniEnvPtr, ptr: *mut c_char) -> JString {
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let text = unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned();
    unsafe { ffi::bb_string_free(ptr) };
    unsafe { to_jstring(env, &text) }
}

fn handle_from_jlong(handle: i64) -> *mut c_void {
    handle as usize as *mut c_void
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeCreate(
    _env: JniEnvPtr,
    _class: JObject,
) -> i64 {
    match catch_unwind(ffi::bb_engine_create) {
        Ok(handle) => handle as usize as i64,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeDestroy(
    _env: JniEnvPtr,
    _class: JObject,
    handle: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        ffi::bb_engine_destroy(handle_from_jlong(handle));
    }));
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeInitializeJson(
    env: JniEnvPtr,
    _class: JObject,
    handle: i64,
    config_json: JString,
) -> i32 {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> i32 {
        let Some(json) = (unsafe { to_rust_string(env, config_json) }) else {
            return ErrorCode::InvalidArgument.as_i32();
        };
        let Ok(config) = CString::new(json) else {
            return ErrorCode::InvalidArgument.as_i32();
        };
        unsafe { ffi::bb_engine_initialize_json(handle_from_jlong(handle), config.as_ptr()) }
    }));
    outcome.unwrap_or(ErrorCode::Panic.as_i32())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeRequestJson(
    env: JniEnvPtr,
    _class: JObject,
    handle: i64,
    request_json: JString,
) -> JString {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> JString {
        let Some(json) = (unsafe { to_rust_string(env, request_json) }) else {
            return std::ptr::null_mut();
        };
        let Ok(request) = CString::new(json) else {
            return std::ptr::null_mut();
        };
        let response =
            unsafe { ffi::bb_engine_request_json(handle_from_jlong(handle), request.as_ptr()) };
        unsafe { consume_to_jstring(env, response) }
    }));
    outcome.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeSubmitTaskJson(
    env: JniEnvPtr,
    _class: JObject,
    handle: i64,
    command_json: JString,
) -> i64 {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> i64 {
        let Some(json) = (unsafe { to_rust_string(env, command_json) }) else {
            return ErrorCode::InvalidArgument.as_i32() as i64;
        };
        let Ok(command) = CString::new(json) else {
            return ErrorCode::InvalidArgument.as_i32() as i64;
        };
        unsafe { ffi::bb_engine_submit_task_json(handle_from_jlong(handle), command.as_ptr()) }
    }));
    outcome.unwrap_or(ErrorCode::Panic.as_i32() as i64)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeTaskResultJson(
    env: JniEnvPtr,
    _class: JObject,
    handle: i64,
    task_id: i64,
) -> JString {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> JString {
        let result = unsafe { ffi::bb_engine_task_result_json(handle_from_jlong(handle), task_id) };
        unsafe { consume_to_jstring(env, result) }
    }));
    outcome.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeSummaryJson(
    env: JniEnvPtr,
    _class: JObject,
    handle: i64,
) -> JString {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> JString {
        let summary = unsafe { ffi::bb_engine_summary_json(handle_from_jlong(handle)) };
        unsafe { consume_to_jstring(env, summary) }
    }));
    outcome.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeLastError(
    env: JniEnvPtr,
    _class: JObject,
    handle: i64,
) -> JString {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> JString {
        let error = unsafe { ffi::bb_engine_last_error(handle_from_jlong(handle)) };
        unsafe { consume_to_jstring(env, error) }
    }));
    outcome.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_adv_bb_nativebridge_NativeBridge_nativeAbiVersion(
    _env: JniEnvPtr,
    _class: JObject,
) -> i32 {
    ffi::bb_abi_version() as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_round_trip() {
        let pointer = 0x1234_5678usize as *mut c_void;
        assert_eq!(handle_from_jlong(pointer as usize as i64), pointer);
    }

    #[test]
    fn null_env_is_handled_without_crashing() {
        // A real JVM is required to exercise the happy path; these calls only
        // assert the defensive branches.
        assert!(unsafe { to_jstring(std::ptr::null_mut(), "x") }.is_null());
        assert!(unsafe { to_rust_string(std::ptr::null_mut(), std::ptr::null_mut()) }.is_none());
        assert!(
            unsafe { consume_to_jstring(std::ptr::null_mut(), std::ptr::null_mut()) }.is_null()
        );
    }

    #[test]
    fn abi_version_matches_core() {
        let value = unsafe {
            Java_com_adv_bb_nativebridge_NativeBridge_nativeAbiVersion(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        assert_eq!(value as u32, bb_common::ABI_VERSION);
    }
}
