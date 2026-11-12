//! Stable error codes shared by every layer.
//!
//! The numeric values are part of the ABI: Kotlin (and any other caller of the
//! C ABI) switches on them, exactly like errno. Never renumber a variant, only
//! append new ones at the end of the list.

use std::fmt;

/// Numeric error code crossing the FFI boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(i32)]
pub enum ErrorCode {
    Ok = 0,
    InvalidArgument = -1,
    NotFound = -2,
    AlreadyExists = -3,
    InvalidState = -4,
    Io = -5,
    Corrupt = -6,
    Unsupported = -7,
    Busy = -8,
    TimedOut = -9,
    PermissionDenied = -10,
    TooLarge = -11,
    NotInitialized = -12,
    Panic = -13,
    Internal = -14,
}

impl ErrorCode {
    pub const fn as_i32(self) -> i32 {
        self as i32
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Ok => "OK",
            Self::InvalidArgument => "INVALID_ARGUMENT",
            Self::NotFound => "NOT_FOUND",
            Self::AlreadyExists => "ALREADY_EXISTS",
            Self::InvalidState => "INVALID_STATE",
            Self::Io => "IO",
            Self::Corrupt => "CORRUPT",
            Self::Unsupported => "UNSUPPORTED",
            Self::Busy => "BUSY",
            Self::TimedOut => "TIMED_OUT",
            Self::PermissionDenied => "PERMISSION_DENIED",
            Self::TooLarge => "TOO_LARGE",
            Self::NotInitialized => "NOT_INITIALIZED",
            Self::Panic => "PANIC",
            Self::Internal => "INTERNAL",
        }
    }

    pub const fn from_i32(value: i32) -> Option<Self> {
        Some(match value {
            0 => Self::Ok,
            -1 => Self::InvalidArgument,
            -2 => Self::NotFound,
            -3 => Self::AlreadyExists,
            -4 => Self::InvalidState,
            -5 => Self::Io,
            -6 => Self::Corrupt,
            -7 => Self::Unsupported,
            -8 => Self::Busy,
            -9 => Self::TimedOut,
            -10 => Self::PermissionDenied,
            -11 => Self::TooLarge,
            -12 => Self::NotInitialized,
            -13 => Self::Panic,
            -14 => Self::Internal,
            _ => return None,
        })
    }

    pub const fn is_ok(self) -> bool {
        matches!(self, Self::Ok)
    }

    pub const fn is_error(self) -> bool {
        !self.is_ok()
    }
}

impl fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// Error value that is always safe to hand to Kotlin: a stable code plus a
/// human readable message (never a raw panic payload or pointer).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BbError {
    code: ErrorCode,
    message: String,
}

impl BbError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn code(&self) -> ErrorCode {
        self.code
    }

    pub fn message(&self) -> &str {
        &self.message
    }

    pub fn into_message(self) -> String {
        self.message
    }

    pub fn invalid_argument(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::InvalidArgument, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::NotFound, message)
    }

    pub fn already_exists(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::AlreadyExists, message)
    }

    pub fn invalid_state(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::InvalidState, message)
    }

    pub fn io(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Io, message)
    }

    pub fn corrupt(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Corrupt, message)
    }

    pub fn unsupported(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Unsupported, message)
    }

    pub fn busy(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Busy, message)
    }

    pub fn timed_out(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::TimedOut, message)
    }

    pub fn permission_denied(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::PermissionDenied, message)
    }

    pub fn too_large(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::TooLarge, message)
    }

    pub fn not_initialized(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::NotInitialized, message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Internal, message)
    }

    pub fn panic(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Panic, message)
    }

    /// Converts a `catch_unwind` payload into an error without ever exposing
    /// the payload's memory.
    pub fn from_panic_payload(payload: &(dyn std::any::Any + Send)) -> Self {
        let detail = if let Some(s) = payload.downcast_ref::<&'static str>() {
            (*s).to_string()
        } else if let Some(s) = payload.downcast_ref::<String>() {
            s.clone()
        } else {
            "unknown panic payload".to_string()
        };
        Self::panic(format!("native core panicked: {detail}"))
    }
}

impl fmt::Display for BbError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code.as_str(), self.message)
    }
}

impl std::error::Error for BbError {}

impl From<std::io::Error> for BbError {
    fn from(err: std::io::Error) -> Self {
        use std::io::ErrorKind;
        let code = match err.kind() {
            ErrorKind::NotFound => ErrorCode::NotFound,
            ErrorKind::PermissionDenied => ErrorCode::PermissionDenied,
            ErrorKind::AlreadyExists => ErrorCode::AlreadyExists,
            ErrorKind::TimedOut => ErrorCode::TimedOut,
            _ => ErrorCode::Io,
        };
        Self::new(code, err.to_string())
    }
}

impl From<std::string::FromUtf8Error> for BbError {
    fn from(err: std::string::FromUtf8Error) -> Self {
        Self::corrupt(format!("invalid UTF-8: {err}"))
    }
}

impl From<std::str::Utf8Error> for BbError {
    fn from(err: std::str::Utf8Error) -> Self {
        Self::corrupt(format!("invalid UTF-8: {err}"))
    }
}

impl From<serde_json::Error> for BbError {
    fn from(err: serde_json::Error) -> Self {
        // JSON arrives either from Kotlin (caller-supplied) or from a file the
        // caller pointed at. Both are argument problems; storage backends that
        // read their own documents remap to `Corrupt` explicitly.
        Self::new(ErrorCode::InvalidArgument, err.to_string())
    }
}

pub type Result<T> = std::result::Result<T, BbError>;

/// Returns `Err` when the condition does not hold. Keeps validation code dense
/// and keeps every failure on the typed-error path.
#[macro_export]
macro_rules! bb_ensure {
    ($cond:expr, $code:expr, $($arg:tt)*) => {
        if !$cond {
            return ::std::result::Result::Err($crate::BbError::new($code, format!($($arg)*)));
        }
    };
}

#[macro_export]
macro_rules! bb_bail {
    ($code:expr, $($arg:tt)*) => {
        return ::std::result::Result::Err($crate::BbError::new($code, format!($($arg)*)))
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_codes_round_trip() {
        for code in [
            ErrorCode::Ok,
            ErrorCode::InvalidArgument,
            ErrorCode::NotFound,
            ErrorCode::AlreadyExists,
            ErrorCode::InvalidState,
            ErrorCode::Io,
            ErrorCode::Corrupt,
            ErrorCode::Unsupported,
            ErrorCode::Busy,
            ErrorCode::TimedOut,
            ErrorCode::PermissionDenied,
            ErrorCode::TooLarge,
            ErrorCode::NotInitialized,
            ErrorCode::Panic,
            ErrorCode::Internal,
        ] {
            assert_eq!(ErrorCode::from_i32(code.as_i32()), Some(code));
            assert!(!code.as_str().is_empty());
        }
        assert_eq!(ErrorCode::from_i32(1), None);
        assert_eq!(ErrorCode::from_i32(-999), None);
    }

    #[test]
    fn io_errors_are_mapped_to_specific_codes() {
        let missing = std::io::Error::new(std::io::ErrorKind::NotFound, "gone");
        assert_eq!(BbError::from(missing).code(), ErrorCode::NotFound);
        let denied = std::io::Error::new(std::io::ErrorKind::PermissionDenied, "denied");
        assert_eq!(BbError::from(denied).code(), ErrorCode::PermissionDenied);
        let timeout = std::io::Error::new(std::io::ErrorKind::TimedOut, "timeout");
        assert_eq!(BbError::from(timeout).code(), ErrorCode::TimedOut);
        let other = std::io::Error::other("boom");
        assert_eq!(BbError::from(other).code(), ErrorCode::Io);
    }

    #[test]
    fn panic_payload_becomes_typed_error() {
        let payload: Box<dyn std::any::Any + Send> = Box::new("kaboom");
        let err = BbError::from_panic_payload(payload.as_ref());
        assert_eq!(err.code(), ErrorCode::Panic);
        assert!(err.message().contains("kaboom"));

        let owned: Box<dyn std::any::Any + Send> = Box::new(String::from("owned panic"));
        let err = BbError::from_panic_payload(owned.as_ref());
        assert!(err.message().contains("owned panic"));

        let weird: Box<dyn std::any::Any + Send> = Box::new(42u8);
        let err = BbError::from_panic_payload(weird.as_ref());
        assert!(err.message().contains("unknown panic payload"));
    }

    #[test]
    fn display_is_stable() {
        let err = BbError::new(ErrorCode::Busy, "instance is starting");
        assert_eq!(err.to_string(), "BUSY: instance is starting");
    }
}
