//! Android binary XML (`AndroidManifest.xml` inside an APK).
//!
//! ResChunk layout reference:
//! * every chunk is `{ type: u16, header_size: u16, size: u32 }` little-endian;
//! * a string pool chunk holds a UTF-8 or UTF-16 string table;
//! * a resource-map chunk maps attribute *string indices* to framework resource
//!   ids, which is how `android:name` is distinguished from a custom `name`;
//! * start/end element chunks drive the tree, attributes carry a typed value.
//!
//! Everything is bounds checked and string indices are validated, because the
//! manifest is attacker-controlled input.

use bb_common::error::{BbError, Result};
use bb_common::{ErrorCode, bb_ensure};

pub const ANDROID_NAMESPACE: &str = "http://schemas.android.com/apk/res/android";

pub const NO_ENTRY: u32 = 0xFFFF_FFFF;

// Framework attribute resource ids that the engine reasons about.
pub const ATTR_LABEL: u32 = 0x0101_0001;
pub const ATTR_NAME: u32 = 0x0101_0003;
pub const ATTR_PERMISSION: u32 = 0x0101_0006;
pub const ATTR_DEBUGGABLE: u32 = 0x0101_000F;
pub const ATTR_EXPORTED: u32 = 0x0101_0010;
pub const ATTR_MIN_SDK: u32 = 0x0101_020C;
pub const ATTR_VERSION_CODE: u32 = 0x0101_021B;
pub const ATTR_VERSION_NAME: u32 = 0x0101_021C;
pub const ATTR_TARGET_SDK: u32 = 0x0101_0270;
pub const ATTR_USES_CLEARTEXT: u32 = 0x0101_04EC;
pub const ATTR_COMPILE_SDK: u32 = 0x0101_0572;

const CHUNK_STRING_POOL: u16 = 0x0001;
const CHUNK_XML: u16 = 0x0003;
const CHUNK_START_NAMESPACE: u16 = 0x0100;
const CHUNK_END_NAMESPACE: u16 = 0x0101;
const CHUNK_START_ELEMENT: u16 = 0x0102;
const CHUNK_END_ELEMENT: u16 = 0x0103;
const CHUNK_CDATA: u16 = 0x0104;
const CHUNK_RESOURCE_MAP: u16 = 0x0180;

const TYPE_NULL: u8 = 0x00;
const TYPE_REFERENCE: u8 = 0x01;
const TYPE_STRING: u8 = 0x03;
const TYPE_FLOAT: u8 = 0x04;
const TYPE_INT_DEC: u8 = 0x10;
const TYPE_INT_HEX: u8 = 0x11;
const TYPE_INT_BOOLEAN: u8 = 0x12;

#[derive(Debug, Clone, PartialEq)]
pub enum XmlValue {
    Null,
    String(String),
    Int(i32),
    Boolean(bool),
    Reference(u32),
    Float(f32),
    Raw { type_code: u8, data: u32 },
}

impl XmlValue {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Self::String(value) => Some(value),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Self::Int(value) => Some(*value as i64),
            Self::Reference(value) => Some(*value as i64),
            Self::String(value) => value.trim().parse::<i64>().ok(),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Boolean(value) => Some(*value),
            Self::Int(value) => Some(*value != 0),
            Self::String(value) => match value.as_str() {
                "true" => Some(true),
                "false" => Some(false),
                _ => None,
            },
            _ => None,
        }
    }

    pub fn as_u32(&self) -> Option<u32> {
        self.as_i64().and_then(|value| u32::try_from(value).ok())
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct XmlAttribute {
    pub namespace: Option<String>,
    pub name: String,
    pub resource_id: Option<u32>,
    pub value: XmlValue,
}

impl XmlAttribute {
    pub fn is_android(&self) -> bool {
        self.namespace.as_deref() == Some(ANDROID_NAMESPACE)
    }

    /// Matches on the framework resource id first (robust against renamed
    /// prefixes), then on the local name.
    pub fn matches(&self, resource_id: u32, local_name: &str) -> bool {
        self.resource_id == Some(resource_id)
            || (self.name == local_name && self.resource_id.is_none())
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct XmlElement {
    pub namespace: Option<String>,
    pub name: String,
    pub attributes: Vec<XmlAttribute>,
    pub children: Vec<XmlElement>,
}

impl XmlElement {
    pub fn attribute(&self, resource_id: u32, local_name: &str) -> Option<&XmlAttribute> {
        self.attributes
            .iter()
            .find(|attribute| attribute.matches(resource_id, local_name))
    }

    pub fn attr_string(&self, resource_id: u32, local_name: &str) -> Option<String> {
        self.attribute(resource_id, local_name)
            .and_then(|attribute| {
                attribute
                    .value
                    .as_str()
                    .map(|value| value.to_string())
                    .or_else(|| attribute.value.as_i64().map(|value| value.to_string()))
            })
    }

    pub fn attr_u32(&self, resource_id: u32, local_name: &str) -> Option<u32> {
        self.attribute(resource_id, local_name)
            .and_then(|attribute| attribute.value.as_u32())
    }

    pub fn attr_bool(&self, resource_id: u32, local_name: &str) -> Option<bool> {
        self.attribute(resource_id, local_name)
            .and_then(|attribute| attribute.value.as_bool())
    }

    pub fn children_named<'a>(&'a self, name: &'a str) -> impl Iterator<Item = &'a XmlElement> {
        self.children.iter().filter(move |child| child.name == name)
    }

    pub fn child(&self, name: &str) -> Option<&XmlElement> {
        self.children.iter().find(|child| child.name == name)
    }
}

#[derive(Debug, Clone)]
pub struct XmlDocument {
    pub strings: Vec<String>,
    pub root: XmlElement,
}

/// Parses a binary XML document.
pub fn parse(buffer: &[u8]) -> Result<XmlDocument> {
    bb_ensure!(
        buffer.len() >= 8,
        ErrorCode::Corrupt,
        "binary XML is shorter than its header"
    );
    let file_kind = read_u16(buffer, 0)?;
    bb_ensure!(
        file_kind == CHUNK_XML,
        ErrorCode::Corrupt,
        "unexpected binary XML type 0x{file_kind:04x}"
    );
    let file_header_size = read_u16(buffer, 2)? as usize;
    let file_size = read_u32(buffer, 4)? as usize;
    bb_ensure!(
        file_header_size >= 8 && file_size <= buffer.len(),
        ErrorCode::Corrupt,
        "binary XML header is inconsistent"
    );

    let mut strings: Vec<String> = Vec::new();
    let mut resource_ids: Vec<u32> = Vec::new();
    let mut stack: Vec<XmlElement> = Vec::new();
    let mut root: Option<XmlElement> = None;

    let mut offset = file_header_size;
    while offset + 8 <= file_size {
        let kind = read_u16(buffer, offset)?;
        let header_size = read_u16(buffer, offset + 2)? as usize;
        let chunk_size = read_u32(buffer, offset + 4)? as usize;
        bb_ensure!(
            chunk_size >= 8 && header_size >= 8 && header_size <= chunk_size,
            ErrorCode::Corrupt,
            "chunk at {offset} has an invalid size"
        );
        bb_ensure!(
            offset + chunk_size <= file_size,
            ErrorCode::Corrupt,
            "chunk at {offset} overruns the document"
        );
        let chunk = &buffer[offset..offset + chunk_size];

        match kind {
            CHUNK_STRING_POOL => {
                strings = parse_string_pool(chunk, header_size)?;
            }
            CHUNK_RESOURCE_MAP => {
                resource_ids = parse_resource_map(chunk, header_size)?;
            }
            // Namespace declarations are not needed for matching: attributes
            // carry the URI index directly and the engine matches on resource ids.
            CHUNK_START_NAMESPACE | CHUNK_END_NAMESPACE => {}
            CHUNK_START_ELEMENT => {
                let element = parse_start_element(chunk, &strings, &resource_ids)?;
                stack.push(element);
            }
            CHUNK_END_ELEMENT => {
                let element = stack
                    .pop()
                    .ok_or_else(|| BbError::corrupt("end element without a matching start"))?;
                match stack.last_mut() {
                    Some(parent) => parent.children.push(element),
                    None => {
                        bb_ensure!(
                            root.is_none(),
                            ErrorCode::Corrupt,
                            "binary XML has more than one root element"
                        );
                        root = Some(element);
                    }
                }
            }
            CHUNK_CDATA => {}
            CHUNK_XML => {}
            _ => {}
        }

        offset += chunk_size;
    }

    bb_ensure!(
        stack.is_empty(),
        ErrorCode::Corrupt,
        "binary XML ends with {} unclosed element(s)",
        stack.len()
    );
    let root = root.ok_or_else(|| BbError::corrupt("binary XML has no root element"))?;
    Ok(XmlDocument { strings, root })
}

fn parse_string_pool(chunk: &[u8], header_size: usize) -> Result<Vec<String>> {
    let count = read_u32(chunk, 8)? as usize;
    let flags = read_u32(chunk, 16)?;
    let strings_start = read_u32(chunk, 20)? as usize;
    let utf8 = flags & 0x100 != 0;
    bb_ensure!(
        count <= (chunk.len().saturating_sub(header_size)) / 4,
        ErrorCode::Corrupt,
        "string pool claims {count} strings but is too small"
    );

    let mut strings = Vec::with_capacity(count);
    for index in 0..count {
        let relative = read_u32(chunk, header_size + index * 4)? as usize;
        let position = strings_start
            .checked_add(relative)
            .ok_or_else(|| BbError::corrupt("string offset overflow"))?;
        bb_ensure!(
            position < chunk.len(),
            ErrorCode::Corrupt,
            "string {index} starts outside the pool"
        );
        let value = if utf8 {
            read_utf8_string(chunk, position)?
        } else {
            read_utf16_string(chunk, position)?
        };
        strings.push(value);
    }
    Ok(strings)
}

fn read_utf8_string(chunk: &[u8], position: usize) -> Result<String> {
    let (_, mut cursor) = read_len8(chunk, position)?;
    let (byte_len, next) = read_len8(chunk, cursor)?;
    cursor = next;
    bb_ensure!(
        cursor + byte_len <= chunk.len(),
        ErrorCode::Corrupt,
        "UTF-8 string overruns the pool"
    );
    let bytes = &chunk[cursor..cursor + byte_len];
    String::from_utf8(bytes.to_vec()).map_err(BbError::from)
}

/// AXML UTF-8 length prefix: u8, or `0x80 | hi` followed by `lo`.
fn read_len8(chunk: &[u8], position: usize) -> Result<(usize, usize)> {
    let first = *chunk
        .get(position)
        .ok_or_else(|| BbError::corrupt("truncated string length"))?;
    if first & 0x80 == 0 {
        Ok((first as usize, position + 1))
    } else {
        let second = *chunk
            .get(position + 1)
            .ok_or_else(|| BbError::corrupt("truncated string length"))?;
        Ok((
            (((first & 0x7F) as usize) << 8) | second as usize,
            position + 2,
        ))
    }
}

fn read_utf16_string(chunk: &[u8], position: usize) -> Result<String> {
    // A UTF-16 pool stores exactly one length prefix, counted in UTF-16 code
    // units. The two-prefix form (characters, then bytes) exists only in UTF-8
    // pools; reading a second prefix here shifts every string by one character
    // and rejects the whole real-world manifest.
    let (units_len, data_start) = read_len16(chunk, position)?;
    let byte_len = units_len
        .checked_mul(2)
        .ok_or_else(|| BbError::corrupt("UTF-16 string length overflow"))?;
    bb_ensure!(
        data_start
            .checked_add(byte_len)
            .is_some_and(|end| end <= chunk.len()),
        ErrorCode::Corrupt,
        "UTF-16 string overruns the pool"
    );
    let mut units = Vec::with_capacity(units_len);
    for index in 0..units_len {
        units.push(read_u16(chunk, data_start + index * 2)?);
    }
    Ok(String::from_utf16_lossy(&units))
}

fn read_len16(chunk: &[u8], position: usize) -> Result<(usize, usize)> {
    let first = read_u16(chunk, position)?;
    if first & 0x8000 == 0 {
        Ok((first as usize, position + 2))
    } else {
        let second = read_u16(chunk, position + 2)?;
        Ok((
            (((first & 0x7FFF) as usize) << 16) | second as usize,
            position + 4,
        ))
    }
}

fn parse_resource_map(chunk: &[u8], header_size: usize) -> Result<Vec<u32>> {
    let count = chunk.len().saturating_sub(header_size) / 4;
    let mut ids = Vec::with_capacity(count);
    for index in 0..count {
        ids.push(read_u32(chunk, header_size + index * 4)?);
    }
    Ok(ids)
}

fn parse_start_element(
    chunk: &[u8],
    strings: &[String],
    resource_ids: &[u32],
) -> Result<XmlElement> {
    bb_ensure!(
        chunk.len() >= 36,
        ErrorCode::Corrupt,
        "start element chunk is too small"
    );
    let namespace_index = read_u32(chunk, 16)?;
    let name_index = read_u32(chunk, 20)?;
    let attribute_start = read_u16(chunk, 24)? as usize;
    let attribute_size = read_u16(chunk, 26)? as usize;
    let attribute_count = read_u16(chunk, 28)? as usize;

    let namespace = resolve_namespace(namespace_index, strings)?;
    let name = string_at(strings, name_index)?;
    let mut attributes = Vec::with_capacity(attribute_count);
    let attributes_base = 16usize
        .checked_add(attribute_start)
        .ok_or_else(|| BbError::corrupt("attribute offset overflow"))?;
    bb_ensure!(
        attribute_size >= 20 || attribute_count == 0,
        ErrorCode::Corrupt,
        "attribute size {attribute_size} is too small"
    );
    let end = attributes_base
        .checked_add(attribute_size.saturating_mul(attribute_count))
        .ok_or_else(|| BbError::corrupt("attribute array overflow"))?;
    bb_ensure!(
        end <= chunk.len(),
        ErrorCode::Corrupt,
        "attribute array overruns the element chunk"
    );

    for index in 0..attribute_count {
        let base = attributes_base + index * attribute_size;
        let attr_namespace_index = read_u32(chunk, base)?;
        let attr_name_index = read_u32(chunk, base + 4)?;
        let raw_value_index = read_u32(chunk, base + 8)?;
        let type_code = *chunk
            .get(base + 15)
            .ok_or_else(|| BbError::corrupt("truncated attribute value"))?;
        let data = read_u32(chunk, base + 16)?;

        if attr_name_index == NO_ENTRY {
            continue;
        }
        let attr_name = string_at(strings, attr_name_index)?;
        if attr_name.is_empty() {
            continue;
        }
        let attr_namespace = resolve_namespace(attr_namespace_index, strings)?;
        let resource_id = resource_ids
            .get(attr_name_index as usize)
            .copied()
            .filter(|id| *id != 0);

        let value = match type_code {
            TYPE_NULL => XmlValue::Null,
            TYPE_STRING => {
                let index = if raw_value_index != NO_ENTRY {
                    raw_value_index
                } else {
                    data
                };
                XmlValue::String(string_at(strings, index)?)
            }
            TYPE_REFERENCE => XmlValue::Reference(data),
            TYPE_FLOAT => XmlValue::Float(f32::from_bits(data)),
            TYPE_INT_DEC | TYPE_INT_HEX => XmlValue::Int(data as i32),
            TYPE_INT_BOOLEAN => XmlValue::Boolean(data != 0),
            other => XmlValue::Raw {
                type_code: other,
                data,
            },
        };

        attributes.push(XmlAttribute {
            namespace: attr_namespace,
            name: attr_name,
            resource_id,
            value,
        });
    }

    Ok(XmlElement {
        namespace,
        name,
        attributes,
        children: Vec::new(),
    })
}

fn resolve_namespace(index: u32, strings: &[String]) -> Result<Option<String>> {
    if index == NO_ENTRY {
        return Ok(None);
    }
    let uri = string_at(strings, index)?;
    if uri.is_empty() {
        return Ok(None);
    }
    // Store the URI, not the prefix: prefixes are arbitrary in XML and the
    // engine matches attributes by namespace URI / resource id.
    Ok(Some(uri))
}

fn string_at(strings: &[String], index: u32) -> Result<String> {
    strings
        .get(index as usize)
        .cloned()
        .ok_or_else(|| BbError::corrupt(format!("string index {index} is out of range")))
}

fn read_u16(buffer: &[u8], offset: usize) -> Result<u16> {
    let slice = buffer
        .get(offset..offset + 2)
        .ok_or_else(|| BbError::corrupt("truncated 16-bit field"))?;
    Ok(u16::from_le_bytes([slice[0], slice[1]]))
}

fn read_u32(buffer: &[u8], offset: usize) -> Result<u32> {
    let slice = buffer
        .get(offset..offset + 4)
        .ok_or_else(|| BbError::corrupt("truncated 32-bit field"))?;
    Ok(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixtures::{AxmlAttr, AxmlBuilder, AxmlValue};

    fn fixture_document() -> Vec<u8> {
        let mut builder = AxmlBuilder::new();
        builder.start(
            "manifest",
            &[
                AxmlAttr::plain("package", AxmlValue::Str("com.example.app")),
                AxmlAttr::android("versionCode", AxmlValue::Int(42)),
                AxmlAttr::android("versionName", AxmlValue::Str("4.2")),
            ],
        );
        builder.start(
            "uses-sdk",
            &[
                AxmlAttr::android("minSdkVersion", AxmlValue::Int(26)),
                AxmlAttr::android("targetSdkVersion", AxmlValue::Int(34)),
            ],
        );
        builder.end("uses-sdk");
        builder.start(
            "application",
            &[AxmlAttr::android("debuggable", AxmlValue::Bool(true))],
        );
        builder.start(
            "activity",
            &[AxmlAttr::android("name", AxmlValue::Str(".Main"))],
        );
        builder.end("activity");
        builder.end("application");
        builder.end("manifest");
        builder.finish()
    }

    #[test]
    fn parses_fixture_manifest() {
        let document = parse(&fixture_document()).unwrap();
        let root = &document.root;
        assert_eq!(root.name, "manifest");
        assert_eq!(
            root.attr_string(NO_ENTRY, "package").as_deref(),
            Some("com.example.app")
        );
        assert_eq!(root.attr_u32(ATTR_VERSION_CODE, "versionCode"), Some(42));
        assert_eq!(
            root.attr_string(ATTR_VERSION_NAME, "versionName")
                .as_deref(),
            Some("4.2")
        );

        let uses_sdk = root.child("uses-sdk").unwrap();
        assert_eq!(uses_sdk.attr_u32(ATTR_MIN_SDK, "minSdkVersion"), Some(26));
        assert_eq!(
            uses_sdk.attr_u32(ATTR_TARGET_SDK, "targetSdkVersion"),
            Some(34)
        );

        let application = root.child("application").unwrap();
        assert_eq!(
            application.attr_bool(ATTR_DEBUGGABLE, "debuggable"),
            Some(true)
        );
        let activity = application.child("activity").unwrap();
        assert_eq!(
            activity.attr_string(ATTR_NAME, "name").as_deref(),
            Some(".Main")
        );
        // Namespace prefix is resolved from the namespace chunk.
        assert!(activity.attributes.iter().all(XmlAttribute::is_android));
    }

    #[test]
    fn rejects_truncated_documents() {
        let document = fixture_document();
        for cut in [0usize, 1, 7, 8, 30, document.len() / 2, document.len() - 4] {
            assert!(
                parse(&document[..cut]).is_err(),
                "prefix of {cut} bytes should be rejected"
            );
        }
    }

    #[test]
    fn rejects_random_documents_without_panicking() {
        use bb_common::testing::XorShift64;
        let mut rng = XorShift64::new(99);
        for _ in 0..500 {
            let len = rng.next_in_range(256) as usize;
            let bytes = rng.bytes(len);
            let _ = parse(&bytes);
        }
    }

    #[test]
    fn rejects_wrong_file_type() {
        let mut document = fixture_document();
        document[0] = 0x04;
        let err = parse(&document).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Corrupt);
    }

    #[test]
    fn rejects_out_of_range_string_index() {
        let mut document = fixture_document();
        // The string pool follows the 8-byte document header: type(2) size(2)
        // size(4) count(4) ... -> the string count lives at offset 16.
        let count_offset = 8 + 8;
        document[count_offset..count_offset + 4].copy_from_slice(&0x0000_FFFFu32.to_le_bytes());
        assert!(parse(&document).is_err());
    }

    #[test]
    fn typed_value_conversions_are_tolerant() {
        assert_eq!(XmlValue::Int(7).as_u32(), Some(7));
        assert_eq!(XmlValue::String("26".into()).as_u32(), Some(26));
        assert_eq!(XmlValue::String("yes".into()).as_u32(), None);
        assert_eq!(XmlValue::Boolean(true).as_bool(), Some(true));
        assert_eq!(XmlValue::Int(0).as_bool(), Some(false));
        assert_eq!(XmlValue::String("false".into()).as_bool(), Some(false));
        assert_eq!(XmlValue::Null.as_str(), None);
    }
}
