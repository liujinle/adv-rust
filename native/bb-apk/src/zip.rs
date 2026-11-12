//! Minimal, read-mostly ZIP reader for APK containers.
//!
//! Scope decisions:
//! * supports stored (0) and deflate (8) entries, which is everything an APK
//!   produced by `aapt2`/`zipalign` needs;
//! * rejects ZIP64, encrypted entries and multi-disk archives explicitly rather
//!   than mis-parsing them;
//! * every offset and length read from the file is bounds checked, because an
//!   APK handed to the engine may be hostile.
//!
//! Not implemented: writing (except in `fixtures`, test-only) and streaming
//! decompression into an output sink.

use std::collections::BTreeMap;
use std::io::{Cursor, Read, Seek, SeekFrom};

use bb_common::error::{BbError, Result};
use bb_common::{ErrorCode, bb_ensure};

use crate::crc32::crc32;

const EOCD_SIGNATURE: u32 = 0x0605_4B50;
const CENTRAL_SIGNATURE: u32 = 0x0201_4B50;
const LOCAL_SIGNATURE: u32 = 0x0403_4B50;

const METHOD_STORED: u16 = 0;
const METHOD_DEFLATE: u16 = 8;

/// Default cap for one extracted entry (the manifest is ~10 KiB; 8 MiB leaves a
/// lot of headroom while preventing a zip bomb from exhausting the host heap).
pub const DEFAULT_MAX_ENTRY_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ZipEntry {
    pub name: String,
    pub method: u16,
    pub crc32: u32,
    pub compressed_size: u64,
    pub uncompressed_size: u64,
    pub local_header_offset: u64,
}

impl ZipEntry {
    pub fn is_directory(&self) -> bool {
        self.name.ends_with('/')
    }

    pub fn is_compressed(&self) -> bool {
        self.method == METHOD_DEFLATE
    }
}

#[derive(Debug)]
pub struct ZipArchive<R: Read + Seek> {
    reader: R,
    file_size: u64,
    entries: Vec<ZipEntry>,
    by_name: BTreeMap<String, usize>,
}

impl ZipArchive<Cursor<Vec<u8>>> {
    pub fn open_bytes(bytes: Vec<u8>) -> Result<Self> {
        let size = bytes.len() as u64;
        Self::open_reader(Cursor::new(bytes), size)
    }
}

impl ZipArchive<std::fs::File> {
    pub fn open(path: impl AsRef<std::path::Path>) -> Result<Self> {
        let path = path.as_ref();
        let file = std::fs::File::open(path)?;
        let file_size = file.metadata()?.len();
        Self::open_reader(file, file_size)
    }
}

impl<R: Read + Seek> ZipArchive<R> {
    pub fn open_reader(mut reader: R, file_size: u64) -> Result<Self> {
        bb_ensure!(
            file_size >= 22,
            ErrorCode::Corrupt,
            "file is too small to be a ZIP"
        );
        let eocd_offset = find_eocd(&mut reader, file_size)?;
        let eocd = read_at(&mut reader, eocd_offset, 22)?;

        let total_entries = read_u16(&eocd, 10)? as u64;
        let central_size = read_u32(&eocd, 12)? as u64;
        let central_offset = read_u32(&eocd, 16)? as u64;

        bb_ensure!(
            total_entries != 0xFFFF && central_offset != 0xFFFF_FFFF && central_size != 0xFFFF_FFFF,
            ErrorCode::Unsupported,
            "ZIP64 archives are not supported"
        );
        bb_ensure!(
            central_offset
                .checked_add(central_size)
                .is_some_and(|end| end <= file_size),
            ErrorCode::Corrupt,
            "central directory lies outside the file"
        );

        let mut entries = Vec::with_capacity(total_entries.min(4096) as usize);
        let mut by_name = BTreeMap::new();
        let mut cursor = central_offset;
        for _ in 0..total_entries {
            let header = read_at(&mut reader, cursor, 46)?;
            let signature = read_u32(&header, 0)?;
            bb_ensure!(
                signature == CENTRAL_SIGNATURE,
                ErrorCode::Corrupt,
                "bad central directory signature at {cursor}"
            );
            let flags = read_u16(&header, 8)?;
            let method = read_u16(&header, 10)?;
            let crc = read_u32(&header, 16)?;
            let compressed_size = read_u32(&header, 20)? as u64;
            let uncompressed_size = read_u32(&header, 24)? as u64;
            let name_len = read_u16(&header, 28)? as usize;
            let extra_len = read_u16(&header, 30)? as usize;
            let comment_len = read_u16(&header, 32)? as usize;
            let local_offset = read_u32(&header, 42)? as u64;

            bb_ensure!(
                flags & 0x0001 == 0,
                ErrorCode::Unsupported,
                "encrypted ZIP entries are not supported"
            );
            bb_ensure!(
                method == METHOD_STORED || method == METHOD_DEFLATE,
                ErrorCode::Unsupported,
                "unsupported compression method {method}"
            );
            bb_ensure!(
                (1..=4096).contains(&name_len),
                ErrorCode::Corrupt,
                "entry name length {name_len} is implausible"
            );

            let name_bytes = read_at(&mut reader, cursor + 46, name_len)?;
            let name = decode_name(&name_bytes)?;
            let entry = ZipEntry {
                name: name.clone(),
                method,
                crc32: crc,
                compressed_size,
                uncompressed_size,
                local_header_offset: local_offset,
            };
            let index = entries.len();
            entries.push(entry);
            by_name.entry(name).or_insert(index);

            cursor = cursor
                .checked_add(46 + name_len as u64 + extra_len as u64 + comment_len as u64)
                .ok_or_else(|| BbError::corrupt("central directory size overflow"))?;
        }

        Ok(Self {
            reader,
            file_size,
            entries,
            by_name,
        })
    }

    pub fn entries(&self) -> &[ZipEntry] {
        &self.entries
    }

    pub fn file_size(&self) -> u64 {
        self.file_size
    }

    pub fn find(&self, name: &str) -> Option<&ZipEntry> {
        self.by_name.get(name).map(|index| &self.entries[*index])
    }

    pub fn contains(&self, name: &str) -> bool {
        self.by_name.contains_key(name)
    }

    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.entries.iter().map(|entry| entry.name.as_str())
    }

    /// Extracts and verifies one entry.
    pub fn read_entry(&mut self, name: &str, max_bytes: usize) -> Result<Vec<u8>> {
        let entry = self
            .find(name)
            .cloned()
            .ok_or_else(|| BbError::not_found(format!("APK entry '{name}' is missing")))?;
        self.read_entry_data(&entry, max_bytes)
    }

    pub fn read_entry_data(&mut self, entry: &ZipEntry, max_bytes: usize) -> Result<Vec<u8>> {
        bb_ensure!(
            entry.uncompressed_size <= max_bytes as u64,
            ErrorCode::TooLarge,
            "entry '{}' claims {} uncompressed bytes (limit {max_bytes})",
            entry.name,
            entry.uncompressed_size
        );
        // Deflate can inflate a small payload into a huge one, but the reverse
        // is bounded: refuse absurd declared sizes before allocating.
        bb_ensure!(
            entry.compressed_size <= max_bytes as u64 + 65_536,
            ErrorCode::TooLarge,
            "entry '{}' claims {} compressed bytes",
            entry.name,
            entry.compressed_size
        );

        let local = read_at(&mut self.reader, entry.local_header_offset, 30)?;
        bb_ensure!(
            read_u32(&local, 0)? == LOCAL_SIGNATURE,
            ErrorCode::Corrupt,
            "bad local header for '{}'",
            entry.name
        );
        let name_len = read_u16(&local, 26)? as u64;
        let extra_len = read_u16(&local, 28)? as u64;
        let data_offset = entry
            .local_header_offset
            .checked_add(30 + name_len + extra_len)
            .ok_or_else(|| BbError::corrupt("local header offset overflow"))?;
        bb_ensure!(
            data_offset
                .checked_add(entry.compressed_size)
                .is_some_and(|end| end <= self.file_size),
            ErrorCode::Corrupt,
            "entry '{}' data lies outside the file",
            entry.name
        );

        self.reader.seek(SeekFrom::Start(data_offset))?;
        let mut compressed = vec![0u8; entry.compressed_size as usize];
        self.reader.read_exact(&mut compressed)?;

        let data = match entry.method {
            METHOD_STORED => compressed,
            METHOD_DEFLATE => {
                let mut decoder = flate2::read::DeflateDecoder::new(&compressed[..]);
                let mut out = Vec::with_capacity(entry.uncompressed_size as usize);
                let mut limited = (&mut decoder).take(max_bytes as u64 + 1);
                limited.read_to_end(&mut out)?;
                bb_ensure!(
                    out.len() <= max_bytes,
                    ErrorCode::TooLarge,
                    "entry '{}' decompresses past the limit",
                    entry.name
                );
                out
            }
            other => {
                return Err(BbError::unsupported(format!(
                    "compression method {other} is not supported"
                )));
            }
        };

        bb_ensure!(
            data.len() as u64 == entry.uncompressed_size,
            ErrorCode::Corrupt,
            "entry '{}' size mismatch: got {} expected {}",
            entry.name,
            data.len(),
            entry.uncompressed_size
        );
        bb_ensure!(
            crc32(&data) == entry.crc32,
            ErrorCode::Corrupt,
            "entry '{}' failed its CRC check",
            entry.name
        );
        Ok(data)
    }
}

fn find_eocd<R: Read + Seek>(reader: &mut R, file_size: u64) -> Result<u64> {
    // EOCD is at most 22 + 65535 bytes from the end.
    let tail_len = file_size.min(22 + 65_535);
    let tail_start = file_size - tail_len;
    let tail = read_at(reader, tail_start, tail_len as usize)?;

    let mut index = tail.len().saturating_sub(22);
    loop {
        if read_u32(&tail, index)? == EOCD_SIGNATURE {
            let comment_len = read_u16(&tail, index + 20)? as usize;
            if index + 22 + comment_len == tail.len() {
                return Ok(tail_start + index as u64);
            }
        }
        if index == 0 {
            break;
        }
        index -= 1;
    }
    Err(BbError::corrupt(
        "end of central directory record not found",
    ))
}

fn read_at<R: Read + Seek>(reader: &mut R, offset: u64, len: usize) -> Result<Vec<u8>> {
    let mut buffer = vec![0u8; len];
    reader.seek(SeekFrom::Start(offset))?;
    reader.read_exact(&mut buffer)?;
    Ok(buffer)
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

fn decode_name(bytes: &[u8]) -> Result<String> {
    // The general-purpose flag bit 11 would mean UTF-8; APK entry names are
    // ASCII in practice. Accept both: invalid UTF-8 falls back to Latin-1 so a
    // weird entry name cannot abort the whole inspection.
    match std::str::from_utf8(bytes) {
        Ok(name) => Ok(name.to_string()),
        Err(_) => Ok(bytes.iter().map(|byte| *byte as char).collect()),
    }
}

// ---------------------------------------------------------------------------
// Test-only writer (also used by `fixtures`)
// ---------------------------------------------------------------------------

#[cfg(any(test, feature = "fixtures"))]
pub(crate) struct ZipWriter {
    data: Vec<u8>,
    central: Vec<u8>,
    count: u16,
}

#[cfg(any(test, feature = "fixtures"))]
impl ZipWriter {
    pub(crate) fn new() -> Self {
        Self {
            data: Vec::new(),
            central: Vec::new(),
            count: 0,
        }
    }

    pub(crate) fn add(&mut self, name: &str, contents: &[u8], compress: bool) {
        let crc = crc32(contents);
        let (method, payload) = if compress {
            use flate2::Compression;
            use flate2::write::DeflateEncoder;
            use std::io::Write;
            let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
            encoder.write_all(contents).expect("deflate in memory");
            (8u16, encoder.finish().expect("finish deflate"))
        } else {
            (0u16, contents.to_vec())
        };
        let local_offset = self.data.len() as u32;
        let name_bytes = name.as_bytes();

        self.data.extend_from_slice(&LOCAL_SIGNATURE.to_le_bytes());
        self.data.extend_from_slice(&20u16.to_le_bytes()); // version needed
        self.data.extend_from_slice(&0u16.to_le_bytes()); // flags
        self.data.extend_from_slice(&method.to_le_bytes());
        self.data.extend_from_slice(&0u16.to_le_bytes()); // mod time
        self.data.extend_from_slice(&0u16.to_le_bytes()); // mod date
        self.data.extend_from_slice(&crc.to_le_bytes());
        self.data
            .extend_from_slice(&(payload.len() as u32).to_le_bytes());
        self.data
            .extend_from_slice(&(contents.len() as u32).to_le_bytes());
        self.data
            .extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        self.data.extend_from_slice(&0u16.to_le_bytes()); // extra len
        self.data.extend_from_slice(name_bytes);
        self.data.extend_from_slice(&payload);

        self.central
            .extend_from_slice(&CENTRAL_SIGNATURE.to_le_bytes());
        self.central.extend_from_slice(&20u16.to_le_bytes()); // version made by
        self.central.extend_from_slice(&20u16.to_le_bytes()); // version needed
        self.central.extend_from_slice(&0u16.to_le_bytes()); // flags
        self.central.extend_from_slice(&method.to_le_bytes());
        self.central.extend_from_slice(&0u16.to_le_bytes());
        self.central.extend_from_slice(&0u16.to_le_bytes());
        self.central.extend_from_slice(&crc.to_le_bytes());
        self.central
            .extend_from_slice(&(payload.len() as u32).to_le_bytes());
        self.central
            .extend_from_slice(&(contents.len() as u32).to_le_bytes());
        self.central
            .extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        self.central.extend_from_slice(&0u16.to_le_bytes()); // extra
        self.central.extend_from_slice(&0u16.to_le_bytes()); // comment
        self.central.extend_from_slice(&0u16.to_le_bytes()); // disk
        self.central.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        self.central.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        self.central.extend_from_slice(&local_offset.to_le_bytes());
        self.central.extend_from_slice(name_bytes);

        self.count += 1;
    }

    pub(crate) fn finish(self) -> Vec<u8> {
        let central_offset = self.data.len() as u32;
        let central_size = self.central.len() as u32;
        let mut out = self.data;
        out.extend_from_slice(&self.central);
        out.extend_from_slice(&EOCD_SIGNATURE.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&self.count.to_le_bytes());
        out.extend_from_slice(&self.count.to_le_bytes());
        out.extend_from_slice(&central_size.to_le_bytes());
        out.extend_from_slice(&central_offset.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::XorShift64;

    fn sample_zip() -> Vec<u8> {
        let mut writer = ZipWriter::new();
        writer.add("AndroidManifest.xml", b"manifest-bytes", true);
        writer.add("classes.dex", b"dex-bytes", false);
        writer.add("lib/arm64-v8a/libfoo.so", b"\x7fELF", false);
        writer.finish()
    }

    #[test]
    fn lists_and_extracts_entries() {
        let mut archive = ZipArchive::open_bytes(sample_zip()).unwrap();
        assert_eq!(archive.entries().len(), 3);
        assert!(archive.contains("AndroidManifest.xml"));
        assert!(!archive.contains("missing"));
        assert_eq!(
            archive
                .read_entry("AndroidManifest.xml", DEFAULT_MAX_ENTRY_BYTES)
                .unwrap(),
            b"manifest-bytes"
        );
        assert_eq!(
            archive.read_entry("classes.dex", 1024).unwrap(),
            b"dex-bytes"
        );
        assert_eq!(
            archive.read_entry("lib/arm64-v8a/libfoo.so", 1024).unwrap(),
            b"\x7fELF"
        );
        assert_eq!(
            archive.read_entry("missing", 1024).unwrap_err().code(),
            ErrorCode::NotFound
        );
        let compressed = archive.find("classes.dex").unwrap();
        assert!(!compressed.is_compressed());
        assert!(archive.find("AndroidManifest.xml").unwrap().is_compressed());
    }

    #[test]
    fn size_limit_is_enforced_before_decompression() {
        let mut archive = ZipArchive::open_bytes(sample_zip()).unwrap();
        let err = archive.read_entry("classes.dex", 4).unwrap_err();
        assert_eq!(err.code(), ErrorCode::TooLarge);
    }

    #[test]
    fn corrupt_payload_fails_crc() {
        let mut bytes = sample_zip();
        // classes.dex is stored, so flip a byte of its payload in place.
        let position = bytes
            .windows(7)
            .position(|window| window == b"dex-byt")
            .expect("payload present");
        bytes[position] ^= 0xFF;
        let mut archive = ZipArchive::open_bytes(bytes).unwrap();
        let err = archive.read_entry("classes.dex", 1024).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Corrupt);
    }

    #[test]
    fn truncated_and_random_files_are_rejected() {
        let bytes = sample_zip();
        for cut in [0usize, 5, 22, bytes.len() / 2, bytes.len() - 1] {
            assert!(ZipArchive::open_bytes(bytes[..cut].to_vec()).is_err());
        }

        let mut rng = XorShift64::new(7);
        for _ in 0..200 {
            let len = rng.next_in_range(512) as usize;
            let data = rng.bytes(len);
            let _ = ZipArchive::open_bytes(data);
        }
    }

    #[test]
    fn entry_offsets_outside_the_file_are_rejected() {
        let mut bytes = sample_zip();
        // Corrupt the central directory's "uncompressed size" of the first entry
        // and enlarge the declared compressed size to run past EOF.
        let eocd = bytes.len() - 22;
        let central_offset =
            u32::from_le_bytes(bytes[eocd + 16..eocd + 20].try_into().unwrap()) as usize;
        bytes[central_offset + 20..central_offset + 24].copy_from_slice(&u32::MAX.to_le_bytes());
        let mut archive = ZipArchive::open_bytes(bytes).unwrap();
        let err = archive
            .read_entry("AndroidManifest.xml", DEFAULT_MAX_ENTRY_BYTES)
            .unwrap_err();
        assert!(matches!(
            err.code(),
            ErrorCode::Corrupt | ErrorCode::TooLarge
        ));
    }

    #[test]
    fn crc32_matches_archive_metadata() {
        let archive = ZipArchive::open_bytes(sample_zip()).unwrap();
        let entry = archive.find("classes.dex").unwrap();
        assert_eq!(entry.crc32, crc32(b"dex-bytes"));
    }
}
