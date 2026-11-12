//! Deterministic APK fixtures.
//!
//! Shipping a multi-megabyte binary APK in the repository is bad practice (and
//! useless for code review), so tests and benchmarks synthesise one instead:
//! a real ZIP container with a real binary `AndroidManifest.xml`. The builder is
//! also the reference implementation for the AXML encoder the migration tooling
//! will need.

use super::axml::{
    ANDROID_NAMESPACE, ATTR_COMPILE_SDK, ATTR_DEBUGGABLE, ATTR_EXPORTED, ATTR_LABEL, ATTR_MIN_SDK,
    ATTR_NAME, ATTR_PERMISSION, ATTR_TARGET_SDK, ATTR_USES_CLEARTEXT, ATTR_VERSION_CODE,
    ATTR_VERSION_NAME, NO_ENTRY,
};
use super::zip::ZipWriter;

pub enum AxmlValue<'a> {
    Str(&'a str),
    Int(i32),
    Bool(bool),
}

pub struct AxmlAttr<'a> {
    pub name: &'a str,
    pub namespace: Option<&'a str>,
    pub value: AxmlValue<'a>,
    pub resource_id: Option<u32>,
}

impl<'a> AxmlAttr<'a> {
    pub fn android(name: &'a str, value: AxmlValue<'a>) -> Self {
        Self {
            name,
            namespace: Some(ANDROID_NAMESPACE),
            value,
            resource_id: android_attr_id(name),
        }
    }

    pub fn plain(name: &'a str, value: AxmlValue<'a>) -> Self {
        Self {
            name,
            namespace: None,
            value,
            resource_id: None,
        }
    }
}

fn android_attr_id(name: &str) -> Option<u32> {
    Some(match name {
        "label" => ATTR_LABEL,
        "name" => ATTR_NAME,
        "permission" => ATTR_PERMISSION,
        "debuggable" => ATTR_DEBUGGABLE,
        "exported" => ATTR_EXPORTED,
        "minSdkVersion" => ATTR_MIN_SDK,
        "versionCode" => ATTR_VERSION_CODE,
        "versionName" => ATTR_VERSION_NAME,
        "targetSdkVersion" => ATTR_TARGET_SDK,
        "usesCleartextTraffic" => ATTR_USES_CLEARTEXT,
        "compileSdkVersion" => ATTR_COMPILE_SDK,
        _ => return None,
    })
}

const CHUNK_XML: u16 = 0x0003;
const CHUNK_STRING_POOL: u16 = 0x0001;
const CHUNK_RESOURCE_MAP: u16 = 0x0180;
const CHUNK_START_NAMESPACE: u16 = 0x0100;
const CHUNK_START_ELEMENT: u16 = 0x0102;
const CHUNK_END_ELEMENT: u16 = 0x0103;

const TYPE_STRING: u8 = 0x03;
const TYPE_INT_DEC: u8 = 0x10;
const TYPE_INT_BOOLEAN: u8 = 0x12;

pub struct AxmlBuilder {
    strings: Vec<String>,
    android_ids: Vec<(u32, u32)>,
    body: Vec<u8>,
    android_namespace: Option<(u32, u32)>,
    utf8_pool: bool,
}

impl Default for AxmlBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl AxmlBuilder {
    pub fn new() -> Self {
        Self {
            strings: Vec::new(),
            android_ids: Vec::new(),
            body: Vec::new(),
            android_namespace: None,
            // `aapt2` writes UTF-8 pools, but platform tooling and some vendors
            // still emit UTF-16 ones, so both must be covered by tests.
            utf8_pool: true,
        }
    }

    /// Emit a UTF-16 string pool instead of the default UTF-8 one.
    pub fn use_utf16_pool(&mut self) -> &mut Self {
        self.utf8_pool = false;
        self
    }

    fn intern(&mut self, value: &str) -> u32 {
        if let Some(index) = self.strings.iter().position(|existing| existing == value) {
            return index as u32;
        }
        self.strings.push(value.to_string());
        (self.strings.len() - 1) as u32
    }

    fn intern_namespace(&mut self, uri: &str) -> u32 {
        if self.android_namespace.is_none() {
            let prefix = self.intern("android");
            let uri_index = self.intern(uri);
            self.android_namespace = Some((prefix, uri_index));
        }
        self.android_namespace
            .map(|(_, uri_index)| uri_index)
            .unwrap()
    }

    pub fn start(&mut self, name: &str, attributes: &[AxmlAttr<'_>]) -> &mut Self {
        let name_index = self.intern(name);
        let mut encoded: Vec<(u32, u32, i32, u8, u32)> = Vec::with_capacity(attributes.len());
        for attribute in attributes {
            let namespace_index = match attribute.namespace {
                Some(uri) => self.intern_namespace(uri),
                None => NO_ENTRY,
            };
            let name_index = self.intern(attribute.name);
            if let Some(resource_id) = attribute.resource_id {
                self.android_ids.push((name_index, resource_id));
            }
            let (type_code, raw_index, data) = match attribute.value {
                AxmlValue::Str(value) => {
                    let index = self.intern(value);
                    (TYPE_STRING, index as i32, index)
                }
                AxmlValue::Int(value) => (TYPE_INT_DEC, -1, value as u32),
                AxmlValue::Bool(value) => (TYPE_INT_BOOLEAN, -1, value as u32),
            };
            encoded.push((namespace_index, name_index, raw_index, type_code, data));
        }

        let size = 36 + 20 * encoded.len();
        let mut chunk = Vec::with_capacity(size);
        chunk.extend_from_slice(&CHUNK_START_ELEMENT.to_le_bytes());
        chunk.extend_from_slice(&16u16.to_le_bytes());
        chunk.extend_from_slice(&(size as u32).to_le_bytes());
        chunk.extend_from_slice(&0u32.to_le_bytes()); // line
        chunk.extend_from_slice(&NO_ENTRY.to_le_bytes()); // comment
        chunk.extend_from_slice(&NO_ENTRY.to_le_bytes()); // namespace
        chunk.extend_from_slice(&name_index.to_le_bytes());
        chunk.extend_from_slice(&20u16.to_le_bytes()); // attribute start
        chunk.extend_from_slice(&20u16.to_le_bytes()); // attribute size
        chunk.extend_from_slice(&(encoded.len() as u16).to_le_bytes());
        chunk.extend_from_slice(&0u16.to_le_bytes()); // id index
        chunk.extend_from_slice(&0u16.to_le_bytes()); // class index
        chunk.extend_from_slice(&0u16.to_le_bytes()); // style index
        for (namespace_index, name_index, raw_index, type_code, data) in encoded {
            chunk.extend_from_slice(&namespace_index.to_le_bytes());
            chunk.extend_from_slice(&name_index.to_le_bytes());
            chunk.extend_from_slice(&(raw_index as u32).to_le_bytes());
            chunk.extend_from_slice(&8u16.to_le_bytes()); // typed value size
            chunk.push(0); // res0
            chunk.push(type_code);
            chunk.extend_from_slice(&data.to_le_bytes());
        }
        self.body.extend_from_slice(&chunk);
        self
    }

    pub fn end(&mut self, name: &str) -> &mut Self {
        let name_index = self.intern(name);
        self.body
            .extend_from_slice(&CHUNK_END_ELEMENT.to_le_bytes());
        self.body.extend_from_slice(&16u16.to_le_bytes());
        self.body.extend_from_slice(&24u32.to_le_bytes());
        self.body.extend_from_slice(&0u32.to_le_bytes());
        self.body.extend_from_slice(&NO_ENTRY.to_le_bytes());
        self.body.extend_from_slice(&NO_ENTRY.to_le_bytes());
        self.body.extend_from_slice(&name_index.to_le_bytes());
        self
    }

    pub fn finish(self) -> Vec<u8> {
        // The body references string indices, so the pool is serialised last.
        let pool = build_string_pool(&self.strings, self.utf8_pool);
        let resource_map = build_resource_map(&self.android_ids);

        let mut namespace_chunk = Vec::new();
        if let Some((prefix, uri)) = self.android_namespace {
            namespace_chunk.extend_from_slice(&CHUNK_START_NAMESPACE.to_le_bytes());
            namespace_chunk.extend_from_slice(&16u16.to_le_bytes());
            namespace_chunk.extend_from_slice(&24u32.to_le_bytes());
            namespace_chunk.extend_from_slice(&0u32.to_le_bytes());
            namespace_chunk.extend_from_slice(&NO_ENTRY.to_le_bytes());
            namespace_chunk.extend_from_slice(&prefix.to_le_bytes());
            namespace_chunk.extend_from_slice(&uri.to_le_bytes());
        }

        let total = 8 + pool.len() + resource_map.len() + namespace_chunk.len() + self.body.len();
        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(&CHUNK_XML.to_le_bytes());
        out.extend_from_slice(&8u16.to_le_bytes());
        out.extend_from_slice(&(total as u32).to_le_bytes());
        out.extend_from_slice(&pool);
        out.extend_from_slice(&resource_map);
        out.extend_from_slice(&namespace_chunk);
        out.extend_from_slice(&self.body);
        out
    }
}

fn build_string_pool(strings: &[String], utf8: bool) -> Vec<u8> {
    let mut data = Vec::new();
    let mut offsets = Vec::with_capacity(strings.len());
    for value in strings {
        offsets.push(data.len() as u32);
        if utf8 {
            write_utf8_string(value, &mut data);
        } else {
            write_utf16_string(value, &mut data);
        }
    }
    while data.len() % 4 != 0 {
        data.push(0);
    }
    let header_size = 28u32;
    let strings_start = header_size + 4 * strings.len() as u32;
    let size = strings_start + data.len() as u32;

    let mut chunk = Vec::with_capacity(size as usize);
    chunk.extend_from_slice(&CHUNK_STRING_POOL.to_le_bytes());
    chunk.extend_from_slice(&(header_size as u16).to_le_bytes());
    chunk.extend_from_slice(&size.to_le_bytes());
    chunk.extend_from_slice(&(strings.len() as u32).to_le_bytes());
    chunk.extend_from_slice(&0u32.to_le_bytes()); // style count
    let flags = if utf8 { 0x100u32 } else { 0u32 };
    chunk.extend_from_slice(&flags.to_le_bytes());
    chunk.extend_from_slice(&strings_start.to_le_bytes());
    chunk.extend_from_slice(&0u32.to_le_bytes()); // styles start
    for offset in offsets {
        chunk.extend_from_slice(&offset.to_le_bytes());
    }
    chunk.extend_from_slice(&data);
    chunk
}

/// UTF-16 pools carry a single length prefix, counted in code units.
fn write_utf16_string(value: &str, out: &mut Vec<u8>) {
    let units: Vec<u16> = value.encode_utf16().collect();
    write_len16(units.len(), out);
    for unit in units {
        out.extend_from_slice(&unit.to_le_bytes());
    }
    out.extend_from_slice(&0u16.to_le_bytes());
}

fn write_len16(value: usize, out: &mut Vec<u8>) {
    if value > 0x7FFF {
        out.extend_from_slice(&((0x8000u16) | ((value >> 16) as u16)).to_le_bytes());
        out.extend_from_slice(&(value as u16).to_le_bytes());
    } else {
        out.extend_from_slice(&(value as u16).to_le_bytes());
    }
}

fn write_utf8_string(value: &str, out: &mut Vec<u8>) {
    let char_len = value.encode_utf16().count();
    let bytes = value.as_bytes();
    write_len8(char_len, out);
    write_len8(bytes.len(), out);
    out.extend_from_slice(bytes);
    out.push(0);
}

fn write_len8(value: usize, out: &mut Vec<u8>) {
    if value > 0x7F {
        out.push(0x80 | ((value >> 8) as u8));
        out.push((value & 0xFF) as u8);
    } else {
        out.push(value as u8);
    }
}

fn build_resource_map(entries: &[(u32, u32)]) -> Vec<u8> {
    if entries.is_empty() {
        return Vec::new();
    }
    let size = entries
        .iter()
        .map(|(index, _)| *index as usize + 1)
        .max()
        .unwrap_or(0);
    let mut ids = vec![0u32; size];
    for (index, resource_id) in entries {
        if let Some(slot) = ids.get_mut(*index as usize) {
            *slot = *resource_id;
        }
    }
    let mut chunk = Vec::with_capacity(8 + ids.len() * 4);
    chunk.extend_from_slice(&CHUNK_RESOURCE_MAP.to_le_bytes());
    chunk.extend_from_slice(&8u16.to_le_bytes());
    chunk.extend_from_slice(&((8 + ids.len() * 4) as u32).to_le_bytes());
    for id in ids {
        chunk.extend_from_slice(&id.to_le_bytes());
    }
    chunk
}

/// Knobs for the synthetic APK. Defaults describe a plausible modern app.
#[derive(Debug, Clone)]
pub struct ApkFixture {
    pub package: String,
    pub version_code: i32,
    pub version_name: String,
    pub compile_sdk: Option<i32>,
    pub min_sdk: i32,
    pub target_sdk: i32,
    pub permissions: Vec<String>,
    pub activities: Vec<String>,
    pub services: Vec<String>,
    pub receivers: Vec<String>,
    pub providers: Vec<String>,
    pub launcher_activity: Option<String>,
    pub abis: Vec<String>,
    pub debuggable: bool,
    pub uses_cleartext_traffic: Option<bool>,
    pub compress_manifest: bool,
    pub include_manifest: bool,
    /// `true` for the `aapt2` style UTF-8 string pool, `false` for UTF-16.
    pub utf8_pool: bool,
    pub extra_entries: Vec<(String, Vec<u8>)>,
}

impl Default for ApkFixture {
    fn default() -> Self {
        Self {
            package: "com.example.app".to_string(),
            version_code: 42,
            version_name: "4.2".to_string(),
            compile_sdk: Some(34),
            min_sdk: 26,
            target_sdk: 34,
            permissions: vec![
                "android.permission.INTERNET".to_string(),
                "android.permission.ACCESS_NETWORK_STATE".to_string(),
            ],
            activities: vec![".Main".to_string(), ".Settings".to_string()],
            services: vec![".SyncService".to_string()],
            receivers: vec![".BootReceiver".to_string()],
            providers: vec![".DataProvider".to_string()],
            launcher_activity: Some(".Main".to_string()),
            abis: vec!["arm64-v8a".to_string()],
            debuggable: false,
            uses_cleartext_traffic: Some(false),
            compress_manifest: true,
            include_manifest: true,
            utf8_pool: true,
            extra_entries: Vec::new(),
        }
    }
}

pub fn minimal_apk() -> Vec<u8> {
    build_apk(&ApkFixture::default())
}

/// Builds a ZIP container with a binary manifest and optional native libs.
pub fn build_apk(fixture: &ApkFixture) -> Vec<u8> {
    let mut writer = ZipWriter::new();
    if fixture.include_manifest {
        let manifest = build_manifest(fixture);
        writer.add("AndroidManifest.xml", &manifest, fixture.compress_manifest);
    }
    writer.add("classes.dex", b"dex\n035\0synthetic-fixture-dex", false);
    writer.add("resources.arsc", &[0u8; 16], false);
    for abi in &fixture.abis {
        writer.add(&format!("lib/{abi}/libfixture.so"), b"\x7fELF", true);
    }
    for (name, contents) in &fixture.extra_entries {
        writer.add(name, contents, true);
    }
    writer.finish()
}

pub fn build_manifest(fixture: &ApkFixture) -> Vec<u8> {
    let mut builder = AxmlBuilder::new();
    if !fixture.utf8_pool {
        builder.use_utf16_pool();
    }
    let version_code = fixture.version_code;
    let version_name = fixture.version_name.clone();
    let package = fixture.package.clone();
    builder.start(
        "manifest",
        &[
            AxmlAttr::plain("package", AxmlValue::Str(&package)),
            AxmlAttr::android("versionCode", AxmlValue::Int(version_code)),
            AxmlAttr::android("versionName", AxmlValue::Str(&version_name)),
        ],
    );

    let min_sdk = fixture.min_sdk;
    let target_sdk = fixture.target_sdk;
    match fixture.compile_sdk {
        Some(compile_sdk) => builder.start(
            "uses-sdk",
            &[
                AxmlAttr::android("minSdkVersion", AxmlValue::Int(min_sdk)),
                AxmlAttr::android("targetSdkVersion", AxmlValue::Int(target_sdk)),
                AxmlAttr::android("compileSdkVersion", AxmlValue::Int(compile_sdk)),
            ],
        ),
        None => builder.start(
            "uses-sdk",
            &[
                AxmlAttr::android("minSdkVersion", AxmlValue::Int(min_sdk)),
                AxmlAttr::android("targetSdkVersion", AxmlValue::Int(target_sdk)),
            ],
        ),
    };
    builder.end("uses-sdk");

    for permission in &fixture.permissions {
        builder.start(
            "uses-permission",
            &[AxmlAttr::android("name", AxmlValue::Str(permission))],
        );
        builder.end("uses-permission");
    }

    let mut application_attributes = vec![
        AxmlAttr::android("label", AxmlValue::Str("Fixture")),
        AxmlAttr::android("debuggable", AxmlValue::Bool(fixture.debuggable)),
    ];
    if let Some(cleartext) = fixture.uses_cleartext_traffic {
        application_attributes.push(AxmlAttr::android(
            "usesCleartextTraffic",
            AxmlValue::Bool(cleartext),
        ));
    }
    builder.start("application", &application_attributes);

    for activity in &fixture.activities {
        builder.start(
            "activity",
            &[
                AxmlAttr::android("name", AxmlValue::Str(activity)),
                AxmlAttr::android("exported", AxmlValue::Bool(true)),
            ],
        );
        if fixture.launcher_activity.as_deref() == Some(activity.as_str()) {
            builder.start("intent-filter", &[]);
            builder.start(
                "action",
                &[AxmlAttr::android(
                    "name",
                    AxmlValue::Str("android.intent.action.MAIN"),
                )],
            );
            builder.end("action");
            builder.start(
                "category",
                &[AxmlAttr::android(
                    "name",
                    AxmlValue::Str("android.intent.category.LAUNCHER"),
                )],
            );
            builder.end("category");
            builder.end("intent-filter");
        }
        builder.end("activity");
    }
    for service in &fixture.services {
        builder.start(
            "service",
            &[AxmlAttr::android("name", AxmlValue::Str(service))],
        );
        builder.end("service");
    }
    for receiver in &fixture.receivers {
        builder.start(
            "receiver",
            &[AxmlAttr::android("name", AxmlValue::Str(receiver))],
        );
        builder.end("receiver");
    }
    for provider in &fixture.providers {
        builder.start(
            "provider",
            &[
                AxmlAttr::android("name", AxmlValue::Str(provider)),
                AxmlAttr::android("permission", AxmlValue::Str("com.example.permission.DATA")),
            ],
        );
        builder.end("provider");
    }
    builder.end("application");
    builder.end("manifest");
    builder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::axml;

    #[test]
    fn manifest_fixture_parses_back() {
        let fixture = ApkFixture::default();
        let document = axml::parse(&build_manifest(&fixture)).unwrap();
        assert_eq!(document.root.name, "manifest");
        assert_eq!(
            document.root.attr_string(NO_ENTRY, "package").as_deref(),
            Some("com.example.app")
        );
        let application = document.root.child("application").unwrap();
        assert_eq!(application.children.len(), 5);
    }

    #[test]
    fn apk_fixture_contains_expected_entries() {
        let bytes = minimal_apk();
        let archive = crate::zip::ZipArchive::open_bytes(bytes).unwrap();
        let names: Vec<&str> = archive.names().collect();
        assert!(names.contains(&"AndroidManifest.xml"));
        assert!(names.contains(&"classes.dex"));
        assert!(names.contains(&"lib/arm64-v8a/libfixture.so"));
    }

    #[test]
    fn entries_can_be_stored_or_deflated() {
        let fixture = ApkFixture {
            compress_manifest: false,
            ..ApkFixture::default()
        };
        let mut archive = crate::zip::ZipArchive::open_bytes(build_apk(&fixture)).unwrap();
        let manifest = archive
            .read_entry("AndroidManifest.xml", 1024 * 1024)
            .unwrap();
        assert_eq!(&manifest[0..4], &[0x03, 0x00, 0x08, 0x00]);
        assert!(!archive.find("AndroidManifest.xml").unwrap().is_compressed());
    }
}
