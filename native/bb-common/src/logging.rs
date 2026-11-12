//! Tiny logging facade.
//!
//! Rationale: `log`/`tracing` are nice on the host, but the core ships inside an
//! Android `.so` where the only useful sink is logcat. A 60-line facade avoids
//! a dependency tree and guarantees the same behaviour for host tests.

use std::sync::atomic::{AtomicU8, Ordering};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[repr(u8)]
pub enum Level {
    Error = 1,
    Warn = 2,
    Info = 3,
    Debug = 4,
    Trace = 5,
}

impl Level {
    pub const fn as_u8(self) -> u8 {
        self as u8
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Error => "E",
            Self::Warn => "W",
            Self::Info => "I",
            Self::Debug => "D",
            Self::Trace => "V",
        }
    }

    pub const fn from_u8(value: u8) -> Option<Self> {
        Some(match value {
            1 => Self::Error,
            2 => Self::Warn,
            3 => Self::Info,
            4 => Self::Debug,
            5 => Self::Trace,
            _ => return None,
        })
    }

    #[cfg(target_os = "android")]
    const fn android_priority(self) -> i32 {
        match self {
            Self::Error => 6,
            Self::Warn => 5,
            Self::Info => 4,
            Self::Debug => 3,
            Self::Trace => 2,
        }
    }
}

static MAX_LEVEL: AtomicU8 = AtomicU8::new(Level::Info as u8);

pub fn set_level(level: Level) {
    MAX_LEVEL.store(level.as_u8(), Ordering::Relaxed);
}

pub fn current_level() -> Level {
    Level::from_u8(MAX_LEVEL.load(Ordering::Relaxed)).unwrap_or(Level::Info)
}

pub fn is_enabled(level: Level) -> bool {
    level.as_u8() <= current_level().as_u8()
}

/// Emits one log line. Never panics: logging must not be able to take the
/// engine down.
pub fn log(level: Level, target: &str, message: &str) {
    if !is_enabled(level) {
        return;
    }
    platform::emit(level, target, message);
}

#[cfg(target_os = "android")]
mod platform {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    use super::Level;

    unsafe extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    pub fn emit(level: Level, target: &str, message: &str) {
        let tag = format!("bb/{target}");
        let (Ok(tag), Ok(msg)) = (CString::new(tag), CString::new(message)) else {
            return;
        };
        // SAFETY: both pointers are NUL-terminated and outlive the call.
        unsafe {
            __android_log_write(level.android_priority(), tag.as_ptr(), msg.as_ptr());
        }
    }
}

#[cfg(not(target_os = "android"))]
mod platform {
    use super::Level;

    pub fn emit(level: Level, target: &str, message: &str) {
        eprintln!("[bb][{}][{target}] {message}", level.as_str());
    }
}

#[macro_export]
macro_rules! bb_log {
    ($level:expr, $tag:expr, $($arg:tt)*) => {
        $crate::logging::log($level, $tag, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! bb_error {
    ($tag:expr, $($arg:tt)*) => { $crate::bb_log!($crate::logging::Level::Error, $tag, $($arg)*) };
}

#[macro_export]
macro_rules! bb_warn {
    ($tag:expr, $($arg:tt)*) => { $crate::bb_log!($crate::logging::Level::Warn, $tag, $($arg)*) };
}

#[macro_export]
macro_rules! bb_info {
    ($tag:expr, $($arg:tt)*) => { $crate::bb_log!($crate::logging::Level::Info, $tag, $($arg)*) };
}

#[macro_export]
macro_rules! bb_debug {
    ($tag:expr, $($arg:tt)*) => { $crate::bb_log!($crate::logging::Level::Debug, $tag, $($arg)*) };
}

#[macro_export]
macro_rules! bb_trace {
    ($tag:expr, $($arg:tt)*) => { $crate::bb_log!($crate::logging::Level::Trace, $tag, $($arg)*) };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn level_ordering_matches_verbosity() {
        assert!(Level::Error < Level::Warn);
        assert!(Level::Warn < Level::Info);
        assert!(Level::Info < Level::Debug);
        assert!(Level::Debug < Level::Trace);
        for level in [
            Level::Error,
            Level::Warn,
            Level::Info,
            Level::Debug,
            Level::Trace,
        ] {
            assert_eq!(Level::from_u8(level.as_u8()), Some(level));
        }
        assert_eq!(Level::from_u8(0), None);
        assert_eq!(Level::from_u8(42), None);
    }

    #[test]
    fn filter_respects_configured_level() {
        let previous = current_level();
        set_level(Level::Warn);
        assert!(is_enabled(Level::Error));
        assert!(is_enabled(Level::Warn));
        assert!(!is_enabled(Level::Info));
        assert!(!is_enabled(Level::Trace));
        set_level(Level::Trace);
        assert!(is_enabled(Level::Trace));
        set_level(previous);
    }

    #[test]
    fn log_never_panics_on_embedded_nul() {
        log(Level::Error, "test", "message with \0 nul");
    }
}
