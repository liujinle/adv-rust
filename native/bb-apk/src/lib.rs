//! APK inspection that runs entirely off-device.
//!
//! What this crate answers, without Android APIs and without executing guest
//! code:
//! * is the file a well-formed ZIP/APK?
//! * what package, version, min/target SDK does the binary manifest declare?
//! * which components (activities/services/receivers/providers) and permissions
//!   exist, which activity is the launcher, which ABIs are bundled?
//!
//! What it deliberately does **not** do: signature verification, resource
//! resolution, dex verification, or installation. Those stay on the Android
//! side (`PackageManager`/`PackageInstaller`), because reimplementing them in
//! Rust would be both enormous and less trustworthy than the platform.

mod axml;
mod crc32;
#[cfg(any(test, feature = "fixtures"))]
pub mod fixtures;
mod zip;

use std::collections::BTreeSet;
use std::io::{Read, Seek};

use bb_common::error::{BbError, Result};
use bb_common::{ErrorCode, PackageName, bb_ensure};
use serde::Serialize;

pub use axml::{
    ANDROID_NAMESPACE, ATTR_COMPILE_SDK, ATTR_DEBUGGABLE, ATTR_EXPORTED, ATTR_LABEL, ATTR_MIN_SDK,
    ATTR_NAME, ATTR_PERMISSION, ATTR_TARGET_SDK, ATTR_USES_CLEARTEXT, ATTR_VERSION_CODE,
    ATTR_VERSION_NAME, NO_ENTRY, XmlAttribute, XmlDocument, XmlElement, XmlValue,
};
pub use zip::{DEFAULT_MAX_ENTRY_BYTES, ZipArchive, ZipEntry};

pub const MANIFEST_ENTRY: &str = "AndroidManifest.xml";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ApkInfo {
    pub package: String,
    pub version_code: u64,
    pub version_name: Option<String>,
    pub compile_sdk: Option<u32>,
    pub min_sdk: u32,
    pub target_sdk: u32,
    pub permissions: Vec<String>,
    pub activities: Vec<String>,
    pub services: Vec<String>,
    pub receivers: Vec<String>,
    pub providers: Vec<String>,
    pub launcher_activity: Option<String>,
    pub application_label: Option<String>,
    pub debuggable: bool,
    pub uses_cleartext_traffic: Option<bool>,
    pub native_abis: Vec<String>,
    pub file_size: u64,
}

impl ApkInfo {
    /// An APK without a launcher activity can still be installed (service-only
    /// apps exist), but it cannot be started from the launcher UI.
    pub fn has_launcher(&self) -> bool {
        self.launcher_activity.is_some()
    }

    pub fn component_count(&self) -> usize {
        self.activities.len() + self.services.len() + self.receivers.len() + self.providers.len()
    }
}

/// Inspects an APK on disk. Streams the container, so a 200 MiB APK does not get
/// copied into memory.
pub fn inspect(path: impl AsRef<std::path::Path>) -> Result<ApkInfo> {
    let archive = ZipArchive::open(path.as_ref())?;
    let file_size = archive.file_size();
    inspect_archive(archive, file_size)
}

/// Inspects an APK already in memory (tests, benchmarks, download preview).
pub fn inspect_bytes(bytes: Vec<u8>) -> Result<ApkInfo> {
    let file_size = bytes.len() as u64;
    let archive = ZipArchive::open_bytes(bytes)?;
    inspect_archive(archive, file_size)
}

fn inspect_archive<R: Read + Seek>(mut archive: ZipArchive<R>, file_size: u64) -> Result<ApkInfo> {
    let entry_names: Vec<String> = archive.names().map(|name| name.to_string()).collect();
    bb_ensure!(
        entry_names.iter().any(|name| name == MANIFEST_ENTRY),
        ErrorCode::NotFound,
        "APK does not contain {MANIFEST_ENTRY}"
    );

    let manifest = archive.read_entry(MANIFEST_ENTRY, DEFAULT_MAX_ENTRY_BYTES)?;
    let document = axml::parse(&manifest)?;
    let root = &document.root;
    bb_ensure!(
        root.name == "manifest",
        ErrorCode::Corrupt,
        "root element is '{}', expected 'manifest'",
        root.name
    );

    let raw_package = root
        .attr_string(NO_ENTRY, "package")
        .ok_or_else(|| BbError::corrupt("manifest has no package attribute"))?;
    // Validate with the same rules the storage layer enforces.
    let package = PackageName::parse(raw_package.clone())
        .map_err(|err| BbError::corrupt(format!("manifest package is invalid: {err}")))?;
    let package = package.as_str().to_string();

    let version_code = root.attr_u32(ATTR_VERSION_CODE, "versionCode").unwrap_or(0) as u64;
    let version_name = root.attr_string(ATTR_VERSION_NAME, "versionName");
    // `compileSdkVersion` appears on `<manifest>` in AOSP output and on
    // `<uses-sdk>` in some vendor/tooling outputs; accept both.
    let uses_sdk_element = root.child("uses-sdk");
    let compile_sdk = root
        .attr_u32(ATTR_COMPILE_SDK, "compileSdkVersion")
        .or_else(|| {
            uses_sdk_element.and_then(|e| e.attr_u32(ATTR_COMPILE_SDK, "compileSdkVersion"))
        });

    let (min_sdk, target_sdk) = match uses_sdk_element {
        Some(element) => (
            element.attr_u32(ATTR_MIN_SDK, "minSdkVersion").unwrap_or(1),
            element
                .attr_u32(ATTR_TARGET_SDK, "targetSdkVersion")
                .unwrap_or(1),
        ),
        None => (1, 1),
    };

    let mut permissions = Vec::new();
    for name in ["uses-permission", "uses-permission-sdk-23"] {
        for element in root.children_named(name) {
            if let Some(value) = element.attr_string(ATTR_NAME, "name") {
                if !permissions.contains(&value) {
                    permissions.push(value);
                }
            }
        }
    }

    let application = root.child("application");
    let application_label =
        application.and_then(|element| element.attr_string(ATTR_LABEL, "label"));
    let debuggable = application
        .and_then(|element| element.attr_bool(ATTR_DEBUGGABLE, "debuggable"))
        .unwrap_or(false);
    let uses_cleartext_traffic = application
        .and_then(|element| element.attr_bool(ATTR_USES_CLEARTEXT, "usesCleartextTraffic"));

    let mut activities = Vec::new();
    let mut services = Vec::new();
    let mut receivers = Vec::new();
    let mut providers = Vec::new();
    let mut launcher_activity = None;

    if let Some(application) = application {
        for activity in application.children_named("activity") {
            if let Some(name) = activity.attr_string(ATTR_NAME, "name") {
                let resolved = resolve_component(&package, &name);
                if is_launcher(activity) && launcher_activity.is_none() {
                    launcher_activity = Some(resolved.clone());
                }
                activities.push(resolved);
            }
        }
        for service in application.children_named("service") {
            if let Some(name) = service.attr_string(ATTR_NAME, "name") {
                services.push(resolve_component(&package, &name));
            }
        }
        for receiver in application.children_named("receiver") {
            if let Some(name) = receiver.attr_string(ATTR_NAME, "name") {
                receivers.push(resolve_component(&package, &name));
            }
        }
        for provider in application.children_named("provider") {
            if let Some(name) = provider.attr_string(ATTR_NAME, "name") {
                providers.push(resolve_component(&package, &name));
            }
        }
    }

    let native_abis: Vec<String> = entry_names
        .iter()
        .filter_map(|name| name.strip_prefix("lib/"))
        .filter_map(|rest| rest.split_once('/').map(|(abi, _)| abi.to_string()))
        .collect::<BTreeSet<String>>()
        .into_iter()
        .collect();

    Ok(ApkInfo {
        package,
        version_code,
        version_name,
        compile_sdk,
        min_sdk,
        target_sdk,
        permissions,
        activities,
        services,
        receivers,
        providers,
        launcher_activity,
        application_label,
        debuggable,
        uses_cleartext_traffic,
        native_abis,
        file_size,
    })
}

fn is_launcher(activity: &XmlElement) -> bool {
    activity.children_named("intent-filter").any(|filter| {
        let has_main = filter.children_named("action").any(|action| {
            action
                .attr_string(ATTR_NAME, "name")
                .is_some_and(|name| name == "android.intent.action.MAIN")
        });
        let has_launcher = filter.children_named("category").any(|category| {
            category
                .attr_string(ATTR_NAME, "name")
                .is_some_and(|name| name == "android.intent.category.LAUNCHER")
        });
        has_main && has_launcher
    })
}

/// Expands the three legal component-name forms (`.Main`, `Main`, full name).
pub fn resolve_component(package: &str, name: &str) -> String {
    if name.starts_with('.') {
        format!("{package}{name}")
    } else if name.contains('.') {
        name.to_string()
    } else {
        format!("{package}.{name}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixtures::{ApkFixture, build_apk, minimal_apk};

    #[test]
    fn inspects_a_typical_apk() {
        let info = inspect_bytes(minimal_apk()).unwrap();
        assert_eq!(info.package, "com.example.app");
        assert_eq!(info.version_code, 42);
        assert_eq!(info.version_name.as_deref(), Some("4.2"));
        assert_eq!(info.min_sdk, 26);
        assert_eq!(info.target_sdk, 34);
        assert_eq!(info.compile_sdk, Some(34));
        assert_eq!(
            info.permissions,
            vec![
                "android.permission.INTERNET".to_string(),
                "android.permission.ACCESS_NETWORK_STATE".to_string()
            ]
        );
        assert_eq!(
            info.activities,
            vec![
                "com.example.app.Main".to_string(),
                "com.example.app.Settings".to_string()
            ]
        );
        assert_eq!(
            info.services,
            vec!["com.example.app.SyncService".to_string()]
        );
        assert_eq!(
            info.receivers,
            vec!["com.example.app.BootReceiver".to_string()]
        );
        assert_eq!(
            info.providers,
            vec!["com.example.app.DataProvider".to_string()]
        );
        assert_eq!(
            info.launcher_activity.as_deref(),
            Some("com.example.app.Main")
        );
        assert_eq!(info.native_abis, vec!["arm64-v8a".to_string()]);
        assert_eq!(info.application_label.as_deref(), Some("Fixture"));
        assert!(!info.debuggable);
        assert_eq!(info.uses_cleartext_traffic, Some(false));
        assert!(info.file_size > 0);
        assert!(info.has_launcher());
        assert_eq!(info.component_count(), 5);
    }

    #[test]
    fn handles_stored_and_deflated_manifests() {
        for compress in [true, false] {
            let fixture = ApkFixture {
                compress_manifest: compress,
                ..ApkFixture::default()
            };
            let info = inspect_bytes(build_apk(&fixture)).unwrap();
            assert_eq!(info.package, "com.example.app", "compress={compress}");
        }
    }

    #[test]
    fn handles_utf16_string_pools() {
        // Real APKs built by platform tooling often use a UTF-16 pool, where
        // each string has a single length prefix. Reading a second prefix here
        // used to reject every such manifest.
        let fixture = ApkFixture {
            utf8_pool: false,
            ..ApkFixture::default()
        };
        let info = inspect_bytes(build_apk(&fixture)).expect("utf16 pool must parse");
        assert_eq!(info.package, "com.example.app");
        assert_eq!(info.version_code, 42);
        assert_eq!(info.version_name.as_deref(), Some("4.2"));
        assert_eq!(info.min_sdk, 26);
        assert_eq!(info.target_sdk, 34);
        assert!(info.has_launcher());
        assert_eq!(
            info.permissions,
            vec![
                "android.permission.INTERNET".to_string(),
                "android.permission.ACCESS_NETWORK_STATE".to_string()
            ]
        );
    }

    #[test]
    fn multi_abi_apk_is_reported_sorted() {
        let fixture = ApkFixture {
            abis: vec![
                "armeabi-v7a".to_string(),
                "arm64-v8a".to_string(),
                "x86_64".to_string(),
            ],
            ..ApkFixture::default()
        };
        let info = inspect_bytes(build_apk(&fixture)).unwrap();
        assert_eq!(
            info.native_abis,
            vec![
                "arm64-v8a".to_string(),
                "armeabi-v7a".to_string(),
                "x86_64".to_string()
            ]
        );
    }

    #[test]
    fn apk_without_manifest_is_rejected() {
        let fixture = ApkFixture {
            include_manifest: false,
            ..ApkFixture::default()
        };
        let err = inspect_bytes(build_apk(&fixture)).unwrap_err();
        assert_eq!(err.code(), ErrorCode::NotFound);
    }

    #[test]
    fn apk_with_broken_manifest_is_rejected() {
        // Stored (not deflated) so the tampering lands inside the manifest bytes
        // rather than inside a compressed stream.
        let fixture = ApkFixture {
            compress_manifest: false,
            ..ApkFixture::default()
        };
        let mut bytes = build_apk(&fixture);
        let position = bytes
            .windows(8)
            .position(|window| window == b"manifest")
            .expect("manifest string is present in the AXML string pool");
        bytes[position] ^= 0xFF;
        let err = inspect_bytes(bytes).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Corrupt);
    }

    #[test]
    fn component_names_are_expanded() {
        assert_eq!(resolve_component("com.a.b", ".Main"), "com.a.b.Main");
        assert_eq!(resolve_component("com.a.b", "Main"), "com.a.b.Main");
        assert_eq!(
            resolve_component("com.a.b", "org.other.Main"),
            "org.other.Main"
        );
    }

    #[test]
    fn inspect_reads_from_disk() {
        let dir = bb_common::testing::TempDir::new("apk");
        let path = dir.join("app.apk");
        std::fs::write(&path, minimal_apk()).unwrap();
        let info = inspect(&path).unwrap();
        assert_eq!(info.package, "com.example.app");
        assert_eq!(
            inspect(dir.join("missing.apk")).unwrap_err().code(),
            ErrorCode::NotFound
        );
    }
}
