//! Validated identifiers.
//!
//! Both types are used to build filesystem paths (see `bb-vfs`), so validation
//! is a security control, not cosmetic input checking: a crafted instance id
//! must never be able to escape its own directory.

use std::fmt;
use std::str::FromStr;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::error::{BbError, ErrorCode, Result};

fn is_token_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.')
}

/// Stable, filesystem-safe identifier of one virtual instance.
///
/// Deliberately *independent from the package name*: two instances of the same
/// APK (different version, different signature) must be able to coexist.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct InstanceId(String);

impl InstanceId {
    pub const MAX_LEN: usize = 64;

    pub fn parse(raw: impl Into<String>) -> Result<Self> {
        let raw = raw.into();
        if raw.is_empty() {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "instance id must not be empty",
            ));
        }
        if raw.len() > Self::MAX_LEN {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                format!("instance id longer than {} bytes", Self::MAX_LEN),
            ));
        }
        if !raw.chars().all(is_token_char) {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "instance id may only contain ASCII letters, digits, '_', '-' and '.'",
            ));
        }
        // "." and ".." are legal token strings but illegal path components, and
        // a leading dot hides the directory from tooling.
        if raw.starts_with('.') || raw == "." || raw == ".." {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "instance id must not start with '.'",
            ));
        }
        if raw.eq_ignore_ascii_case("virtual") || raw.eq_ignore_ascii_case("instances") {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "instance id collides with a reserved directory name",
            ));
        }
        Ok(Self(raw))
    }

    /// Convenience constructor for tests and internal call sites that already
    /// validated the value.
    pub fn from_validated(raw: impl Into<String>) -> Self {
        Self(raw.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for InstanceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl AsRef<str> for InstanceId {
    fn as_ref(&self) -> &str {
        &self.0
    }
}

impl FromStr for InstanceId {
    type Err = BbError;

    fn from_str(s: &str) -> Result<Self> {
        Self::parse(s)
    }
}

impl Serialize for InstanceId {
    fn serialize<S: Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for InstanceId {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
        let raw = <String as Deserialize>::deserialize(deserializer)?;
        Self::parse(raw).map_err(serde::de::Error::custom)
    }
}

/// Android package name (`com.example.app`), validated with the install-time
/// rules rather than "whatever Java accepted once".
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PackageName(String);

impl PackageName {
    pub const MAX_LEN: usize = 255;
    pub const MAX_SEGMENT_LEN: usize = 64;

    pub fn parse(raw: impl Into<String>) -> Result<Self> {
        let raw = raw.into();
        if raw.is_empty() {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "package name must not be empty",
            ));
        }
        if raw.len() > Self::MAX_LEN {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                format!("package name longer than {} bytes", Self::MAX_LEN),
            ));
        }
        let segments: Vec<&str> = raw.split('.').collect();
        if segments.len() < 2 {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "package name needs at least two segments",
            ));
        }
        if segments.iter().any(|segment| segment.is_empty()) {
            return Err(BbError::new(
                ErrorCode::InvalidArgument,
                "package name contains an empty segment",
            ));
        }
        for segment in &segments {
            if segment.len() > Self::MAX_SEGMENT_LEN {
                return Err(BbError::new(
                    ErrorCode::InvalidArgument,
                    "package name segment too long",
                ));
            }
            let mut chars = segment.chars();
            let first = chars.next().expect("segment is non-empty");
            if !(first.is_ascii_alphabetic() || first == '_') {
                return Err(BbError::new(
                    ErrorCode::InvalidArgument,
                    format!("package segment '{segment}' must start with a letter or '_'"),
                ));
            }
            if !chars.all(|c| c.is_ascii_alphanumeric() || c == '_') {
                return Err(BbError::new(
                    ErrorCode::InvalidArgument,
                    format!("package segment '{segment}' contains an illegal character"),
                ));
            }
        }
        Ok(Self(raw))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for PackageName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl AsRef<str> for PackageName {
    fn as_ref(&self) -> &str {
        &self.0
    }
}

impl FromStr for PackageName {
    type Err = BbError;

    fn from_str(s: &str) -> Result<Self> {
        Self::parse(s)
    }
}

impl Serialize for PackageName {
    fn serialize<S: Serializer>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for PackageName {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Self, D::Error> {
        let raw = <String as Deserialize>::deserialize(deserializer)?;
        Self::parse(raw).map_err(serde::de::Error::custom)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_typical_instance_ids() {
        for id in ["instance_001", "app-2", "0a1b2c", "a.b.c", "A_b-9"] {
            assert!(InstanceId::parse(id).is_ok(), "should accept {id}");
        }
    }

    #[test]
    fn rejects_path_traversal_and_odd_ids() {
        for id in [
            "",
            ".",
            "..",
            "../etc",
            "a/b",
            "a\\b",
            "a\0b",
            ".hidden",
            "with space",
            "ümlaut",
        ] {
            assert!(InstanceId::parse(id).is_err(), "should reject {id:?}");
        }
        let too_long = "a".repeat(InstanceId::MAX_LEN + 1);
        assert!(InstanceId::parse(too_long).is_err());
        assert!(InstanceId::parse("instances").is_err());
        assert!(InstanceId::parse("VIRTUAL").is_err());
    }

    #[test]
    fn accepts_typical_package_names() {
        for pkg in [
            "com.example.app",
            "a.b",
            "com.ex_ample.app2",
            "_private.app",
        ] {
            assert!(PackageName::parse(pkg).is_ok(), "should accept {pkg}");
        }
    }

    #[test]
    fn rejects_malformed_package_names() {
        for pkg in [
            "",
            "com",
            "com.",
            ".com",
            "com..app",
            "com.1app",
            "com.app-2",
            "com.example.app/evil",
        ] {
            assert!(PackageName::parse(pkg).is_err(), "should reject {pkg:?}");
        }
        let too_long = format!("com.{}", "a".repeat(300));
        assert!(PackageName::parse(too_long).is_err());
    }

    #[test]
    fn serde_validates_identifiers() {
        let id: InstanceId = serde_json::from_str("\"instance_001\"").unwrap();
        assert_eq!(id.as_str(), "instance_001");
        let bad = serde_json::from_str::<InstanceId>("\"../escape\"");
        assert!(bad.is_err());
        assert_eq!(serde_json::to_string(&id).unwrap(), "\"instance_001\"");
    }
}
