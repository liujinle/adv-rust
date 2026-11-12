//! Internal message protocol.
//!
//! Two encodings, on purpose:
//!
//! * **Binary** ([`encode`] / [`decode`]) for the hot path: a tag byte plus
//!   LEB128 varints, no reflection, no allocation beyond the payload strings.
//!   This is what the JNI surface uses for task submission and status polls.
//! * **JSON** ([`to_json`] / [`from_json`]) for debugging, `adb shell` tooling
//!   and the Kotlin facade, where readability beats a few microseconds.
//!
//! The wire format is versioned ([`bb_common::PROTOCOL_VERSION`]) and every
//! decoder is bounds checked: a truncated or hostile buffer must return a
//! `BbError`, never panic. `bb-ipc` is therefore fuzz-tested in unit tests.

use bb_common::error::{BbError, Result};
use bb_common::{ErrorCode, InstanceState, PROTOCOL_VERSION, RuntimeEvent, bb_ensure};
use bb_config::InstancePolicy;
use serde::{Deserialize, Serialize};

/// Hard cap for one message. Keeps a corrupt length prefix from triggering a
/// multi-gigabyte allocation inside the host app.
pub const MAX_MESSAGE_BYTES: usize = 4 * 1024 * 1024;

const MSG_REQUEST: u8 = 1;
const MSG_RESPONSE: u8 = 2;

const REQ_HELLO: u8 = 1;
const REQ_CREATE_INSTANCE: u8 = 2;
const REQ_REMOVE_INSTANCE: u8 = 3;
const REQ_START_INSTANCE: u8 = 4;
const REQ_STOP_INSTANCE: u8 = 5;
const REQ_GET_INSTANCE: u8 = 6;
const REQ_LIST_INSTANCES: u8 = 7;
const REQ_INSPECT_APK: u8 = 8;
const REQ_METRICS: u8 = 9;
const REQ_SHUTDOWN: u8 = 10;
const REQ_EVENTS: u8 = 11;

const RESP_OK: u8 = 1;
const RESP_ERROR: u8 = 2;
const RESP_INSTANCE: u8 = 3;
const RESP_INSTANCES: u8 = 4;
const RESP_APK: u8 = 5;
const RESP_METRICS: u8 = 6;
const RESP_HELLO: u8 = 7;
const RESP_EVENTS: u8 = 8;

/// Arguments for creating one virtual instance.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateInstanceSpec {
    pub instance_id: String,
    pub package_name: String,
    pub apk_path: String,
    /// `None` means "use `EngineConfig::default_policy`".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub policy: Option<InstancePolicy>,
}

/// Snapshot of one instance, safe to send across the boundary.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InstanceStatus {
    pub instance_id: String,
    pub package_name: String,
    pub state: i32,
    pub state_name: String,
    pub refs: u32,
    pub updated_at_ms: u64,
    pub last_error: Option<String>,
}

/// Subset of `bb-apk::ApkInfo` that the UI actually needs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ApkSummary {
    pub package: String,
    pub version_code: u64,
    pub version_name: Option<String>,
    pub min_sdk: u32,
    pub target_sdk: u32,
    pub permissions: Vec<String>,
    pub launcher_activity: Option<String>,
    pub native_abis: Vec<String>,
    pub activity_count: u32,
    pub file_size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Request {
    Hello { protocol: u32 },
    CreateInstance { spec: CreateInstanceSpec },
    RemoveInstance { instance_id: String },
    StartInstance { instance_id: String },
    StopInstance { instance_id: String },
    GetInstance { instance_id: String },
    ListInstances,
    InspectApk { apk_path: String },
    Metrics,
    Shutdown,
    /// Replays lifecycle events newer than `since_seq`. The response uses the
    /// shared [`RuntimeEvent`] schema, so Rust and Kotlin cannot drift apart.
    Events { since_seq: u64 },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Response {
    Ok {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        message: Option<String>,
    },
    Error {
        code: i32,
        code_name: String,
        message: String,
    },
    Instance {
        instance: InstanceStatus,
    },
    Instances {
        instances: Vec<InstanceStatus>,
    },
    Apk {
        apk: ApkSummary,
    },
    Metrics {
        snapshot_json: String,
    },
    Hello {
        protocol: u32,
        abi_version: u32,
    },
    Events {
        events: Vec<RuntimeEvent>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Message {
    Request(Request),
    Response(Response),
}

impl Response {
    pub fn from_error(err: &BbError) -> Self {
        Self::Error {
            code: err.code().as_i32(),
            code_name: err.code().as_str().to_string(),
            message: err.message().to_string(),
        }
    }

    pub fn ok() -> Self {
        Self::Ok { message: None }
    }
}

impl Message {
    pub fn request(request: Request) -> Self {
        Self::Request(request)
    }

    pub fn response(response: Response) -> Self {
        Self::Response(response)
    }

    pub fn as_request(&self) -> Option<&Request> {
        match self {
            Self::Request(request) => Some(request),
            _ => None,
        }
    }

    pub fn as_response(&self) -> Option<&Response> {
        match self {
            Self::Response(response) => Some(response),
            _ => None,
        }
    }
}

// ---------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------

fn put_u64(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            break;
        }
        out.push(byte | 0x80);
    }
}

fn put_i64(out: &mut Vec<u8>, value: i64) {
    // ZigZag so small negative error codes stay one byte.
    let zigzag = ((value << 1) ^ (value >> 63)) as u64;
    put_u64(out, zigzag);
}

fn put_str(out: &mut Vec<u8>, value: &str) {
    put_u64(out, value.len() as u64);
    out.extend_from_slice(value.as_bytes());
}

fn put_opt_str(out: &mut Vec<u8>, value: Option<&str>) {
    match value {
        Some(value) => {
            out.push(1);
            put_str(out, value);
        }
        None => out.push(0),
    }
}

fn put_string_list(out: &mut Vec<u8>, values: &[String]) {
    put_u64(out, values.len() as u64);
    for value in values {
        put_str(out, value);
    }
}

fn put_policy(out: &mut Vec<u8>, policy: &InstancePolicy) {
    out.push(policy.allow_network as u8);
    out.push(policy.allow_external_storage as u8);
    out.push(policy.allow_file_uri_exposure as u8);
    put_u64(out, policy.max_storage_bytes);
    out.push(policy.allow_background_start as u8);
}

fn put_event(out: &mut Vec<u8>, event: &RuntimeEvent) {
    put_u64(out, event.seq);
    put_u64(out, event.at_ms);
    put_str(out, &event.instance_id);
    match event.from {
        Some(from) => {
            out.push(1);
            put_i64(out, from.as_i32() as i64);
        }
        None => out.push(0),
    }
    put_i64(out, event.to.as_i32() as i64);
    put_str(out, &event.reason);
}

fn put_instance(out: &mut Vec<u8>, status: &InstanceStatus) {
    put_str(out, &status.instance_id);
    put_str(out, &status.package_name);
    put_i64(out, status.state as i64);
    put_str(out, &status.state_name);
    put_u64(out, status.refs as u64);
    put_u64(out, status.updated_at_ms);
    put_opt_str(out, status.last_error.as_deref());
}

fn put_apk(out: &mut Vec<u8>, apk: &ApkSummary) {
    put_str(out, &apk.package);
    put_u64(out, apk.version_code);
    put_opt_str(out, apk.version_name.as_deref());
    put_u64(out, apk.min_sdk as u64);
    put_u64(out, apk.target_sdk as u64);
    put_string_list(out, &apk.permissions);
    put_opt_str(out, apk.launcher_activity.as_deref());
    put_string_list(out, &apk.native_abis);
    put_u64(out, apk.activity_count as u64);
    put_u64(out, apk.file_size);
}

/// Encodes one message. Output is capped at [`MAX_MESSAGE_BYTES`].
pub fn encode(message: &Message) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(64);
    match message {
        Message::Request(request) => {
            out.push(MSG_REQUEST);
            match request {
                Request::Hello { protocol } => {
                    out.push(REQ_HELLO);
                    put_u64(&mut out, *protocol as u64);
                }
                Request::CreateInstance { spec } => {
                    out.push(REQ_CREATE_INSTANCE);
                    put_str(&mut out, &spec.instance_id);
                    put_str(&mut out, &spec.package_name);
                    put_str(&mut out, &spec.apk_path);
                    match &spec.policy {
                        Some(policy) => {
                            out.push(1);
                            put_policy(&mut out, policy);
                        }
                        None => out.push(0),
                    }
                }
                Request::RemoveInstance { instance_id } => {
                    out.push(REQ_REMOVE_INSTANCE);
                    put_str(&mut out, instance_id);
                }
                Request::StartInstance { instance_id } => {
                    out.push(REQ_START_INSTANCE);
                    put_str(&mut out, instance_id);
                }
                Request::StopInstance { instance_id } => {
                    out.push(REQ_STOP_INSTANCE);
                    put_str(&mut out, instance_id);
                }
                Request::GetInstance { instance_id } => {
                    out.push(REQ_GET_INSTANCE);
                    put_str(&mut out, instance_id);
                }
                Request::ListInstances => out.push(REQ_LIST_INSTANCES),
                Request::InspectApk { apk_path } => {
                    out.push(REQ_INSPECT_APK);
                    put_str(&mut out, apk_path);
                }
                Request::Metrics => out.push(REQ_METRICS),
                Request::Shutdown => out.push(REQ_SHUTDOWN),
                Request::Events { since_seq } => {
                    out.push(REQ_EVENTS);
                    put_u64(&mut out, *since_seq);
                }
            }
        }
        Message::Response(response) => {
            out.push(MSG_RESPONSE);
            match response {
                Response::Ok { message } => {
                    out.push(RESP_OK);
                    put_opt_str(&mut out, message.as_deref());
                }
                Response::Error {
                    code,
                    code_name,
                    message,
                } => {
                    out.push(RESP_ERROR);
                    put_i64(&mut out, *code as i64);
                    put_str(&mut out, code_name);
                    put_str(&mut out, message);
                }
                Response::Instance { instance } => {
                    out.push(RESP_INSTANCE);
                    put_instance(&mut out, instance);
                }
                Response::Instances { instances } => {
                    out.push(RESP_INSTANCES);
                    put_u64(&mut out, instances.len() as u64);
                    for instance in instances {
                        put_instance(&mut out, instance);
                    }
                }
                Response::Apk { apk } => {
                    out.push(RESP_APK);
                    put_apk(&mut out, apk);
                }
                Response::Metrics { snapshot_json } => {
                    out.push(RESP_METRICS);
                    put_str(&mut out, snapshot_json);
                }
                Response::Hello {
                    protocol,
                    abi_version,
                } => {
                    out.push(RESP_HELLO);
                    put_u64(&mut out, *protocol as u64);
                    put_u64(&mut out, *abi_version as u64);
                }
                Response::Events { events } => {
                    out.push(RESP_EVENTS);
                    put_u64(&mut out, events.len() as u64);
                    for event in events {
                        put_event(&mut out, event);
                    }
                }
            }
        }
    }
    bb_ensure!(
        out.len() <= MAX_MESSAGE_BYTES,
        ErrorCode::TooLarge,
        "encoded message is {} bytes, limit is {}",
        out.len(),
        MAX_MESSAGE_BYTES
    );
    Ok(out)
}

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

struct Decoder<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Decoder<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }

    fn take(&mut self, len: usize) -> Result<&'a [u8]> {
        bb_ensure!(
            len <= self.remaining(),
            ErrorCode::Corrupt,
            "truncated message: need {len} bytes, have {}",
            self.remaining()
        );
        let slice = &self.buf[self.pos..self.pos + len];
        self.pos += len;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn bool(&mut self) -> Result<bool> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            other => Err(BbError::corrupt(format!("invalid boolean byte {other}"))),
        }
    }

    fn u64(&mut self) -> Result<u64> {
        let mut result: u64 = 0;
        let mut shift = 0u32;
        loop {
            let byte = self.u8()?;
            if shift >= 64 {
                return Err(BbError::corrupt("varint is too long"));
            }
            result |= ((byte & 0x7F) as u64) << shift;
            if byte & 0x80 == 0 {
                return Ok(result);
            }
            shift += 7;
        }
    }

    fn usize_bounded(&mut self, min_item_bytes: usize) -> Result<usize> {
        let value = self.u64()?;
        let value =
            usize::try_from(value).map_err(|_| BbError::corrupt("length does not fit in usize"))?;
        // Each item needs at least `min_item_bytes`, so a bogus count can be
        // rejected before any allocation happens.
        let upper = self.remaining() / min_item_bytes.max(1);
        bb_ensure!(
            value <= upper,
            ErrorCode::Corrupt,
            "declared length {value} exceeds what the buffer can hold"
        );
        Ok(value)
    }

    fn i64(&mut self) -> Result<i64> {
        let zigzag = self.u64()?;
        Ok(((zigzag >> 1) as i64) ^ -((zigzag & 1) as i64))
    }

    fn str(&mut self) -> Result<String> {
        let len = self.usize_bounded(1)?;
        let bytes = self.take(len)?;
        String::from_utf8(bytes.to_vec()).map_err(BbError::from)
    }

    fn opt_str(&mut self) -> Result<Option<String>> {
        if self.bool()? {
            Ok(Some(self.str()?))
        } else {
            Ok(None)
        }
    }

    fn string_list(&mut self) -> Result<Vec<String>> {
        let count = self.usize_bounded(1)?;
        let mut values = Vec::with_capacity(count.min(1024));
        for _ in 0..count {
            values.push(self.str()?);
        }
        Ok(values)
    }

    fn state_code(&mut self) -> Result<i32> {
        let value = self.i64()?;
        let value =
            i32::try_from(value).map_err(|_| BbError::corrupt("state code does not fit in i32"))?;
        Ok(value)
    }

    fn state(&mut self) -> Result<InstanceState> {
        let code = self.state_code()?;
        InstanceState::from_i32(code)
            .ok_or_else(|| BbError::corrupt(format!("unknown instance state code {code}")))
    }

    fn event(&mut self) -> Result<RuntimeEvent> {
        Ok(RuntimeEvent {
            seq: self.u64()?,
            at_ms: self.u64()?,
            instance_id: self.str()?,
            from: if self.bool()? {
                Some(self.state()?)
            } else {
                None
            },
            to: self.state()?,
            reason: self.str()?,
        })
    }

    fn policy(&mut self) -> Result<InstancePolicy> {
        let policy = InstancePolicy {
            allow_network: self.bool()?,
            allow_external_storage: self.bool()?,
            allow_file_uri_exposure: self.bool()?,
            max_storage_bytes: self.u64()?,
            allow_background_start: self.bool()?,
        };
        Ok(policy)
    }

    fn instance(&mut self) -> Result<InstanceStatus> {
        Ok(InstanceStatus {
            instance_id: self.str()?,
            package_name: self.str()?,
            state: self.state_code()?,
            state_name: self.str()?,
            refs: u32::try_from(self.u64()?).map_err(|_| BbError::corrupt("refs overflow"))?,
            updated_at_ms: self.u64()?,
            last_error: self.opt_str()?,
        })
    }

    fn apk(&mut self) -> Result<ApkSummary> {
        Ok(ApkSummary {
            package: self.str()?,
            version_code: self.u64()?,
            version_name: self.opt_str()?,
            min_sdk: u32::try_from(self.u64()?)
                .map_err(|_| BbError::corrupt("min_sdk overflow"))?,
            target_sdk: u32::try_from(self.u64()?)
                .map_err(|_| BbError::corrupt("target_sdk overflow"))?,
            permissions: self.string_list()?,
            launcher_activity: self.opt_str()?,
            native_abis: self.string_list()?,
            activity_count: u32::try_from(self.u64()?)
                .map_err(|_| BbError::corrupt("activity_count overflow"))?,
            file_size: self.u64()?,
        })
    }

    fn finish(self) -> Result<()> {
        bb_ensure!(
            self.remaining() == 0,
            ErrorCode::Corrupt,
            "{} trailing bytes after message",
            self.remaining()
        );
        Ok(())
    }
}

/// Decodes one message. Rejects truncation, trailing garbage and absurd lengths.
pub fn decode(bytes: &[u8]) -> Result<Message> {
    bb_ensure!(
        bytes.len() <= MAX_MESSAGE_BYTES,
        ErrorCode::TooLarge,
        "message of {} bytes exceeds the {} byte limit",
        bytes.len(),
        MAX_MESSAGE_BYTES
    );
    let mut decoder = Decoder::new(bytes);
    let kind = decoder.u8()?;
    let message = match kind {
        MSG_REQUEST => Message::Request(decode_request(&mut decoder)?),
        MSG_RESPONSE => Message::Response(decode_response(&mut decoder)?),
        other => return Err(BbError::corrupt(format!("unknown message tag {other}"))),
    };
    decoder.finish()?;
    Ok(message)
}

fn decode_request(decoder: &mut Decoder<'_>) -> Result<Request> {
    Ok(match decoder.u8()? {
        REQ_HELLO => Request::Hello {
            protocol: u32::try_from(decoder.u64()?)
                .map_err(|_| BbError::corrupt("protocol version overflow"))?,
        },
        REQ_CREATE_INSTANCE => {
            let instance_id = decoder.str()?;
            let package_name = decoder.str()?;
            let apk_path = decoder.str()?;
            let policy = if decoder.bool()? {
                Some(decoder.policy()?)
            } else {
                None
            };
            Request::CreateInstance {
                spec: CreateInstanceSpec {
                    instance_id,
                    package_name,
                    apk_path,
                    policy,
                },
            }
        }
        REQ_REMOVE_INSTANCE => Request::RemoveInstance {
            instance_id: decoder.str()?,
        },
        REQ_START_INSTANCE => Request::StartInstance {
            instance_id: decoder.str()?,
        },
        REQ_STOP_INSTANCE => Request::StopInstance {
            instance_id: decoder.str()?,
        },
        REQ_GET_INSTANCE => Request::GetInstance {
            instance_id: decoder.str()?,
        },
        REQ_LIST_INSTANCES => Request::ListInstances,
        REQ_INSPECT_APK => Request::InspectApk {
            apk_path: decoder.str()?,
        },
        REQ_METRICS => Request::Metrics,
        REQ_SHUTDOWN => Request::Shutdown,
        REQ_EVENTS => Request::Events {
            since_seq: decoder.u64()?,
        },
        other => return Err(BbError::corrupt(format!("unknown request tag {other}"))),
    })
}

fn decode_response(decoder: &mut Decoder<'_>) -> Result<Response> {
    Ok(match decoder.u8()? {
        RESP_OK => Response::Ok {
            message: decoder.opt_str()?,
        },
        RESP_ERROR => Response::Error {
            code: i32::try_from(decoder.i64()?)
                .map_err(|_| BbError::corrupt("error code overflow"))?,
            code_name: decoder.str()?,
            message: decoder.str()?,
        },
        RESP_INSTANCE => Response::Instance {
            instance: decoder.instance()?,
        },
        RESP_INSTANCES => {
            let count = decoder.usize_bounded(8)?;
            let mut instances = Vec::with_capacity(count.min(1024));
            for _ in 0..count {
                instances.push(decoder.instance()?);
            }
            Response::Instances { instances }
        }
        RESP_APK => Response::Apk {
            apk: decoder.apk()?,
        },
        RESP_METRICS => Response::Metrics {
            snapshot_json: decoder.str()?,
        },
        RESP_HELLO => Response::Hello {
            protocol: u32::try_from(decoder.u64()?)
                .map_err(|_| BbError::corrupt("protocol version overflow"))?,
            abi_version: u32::try_from(decoder.u64()?)
                .map_err(|_| BbError::corrupt("abi version overflow"))?,
        },
        RESP_EVENTS => {
            let count = decoder.usize_bounded(8)?;
            let mut events = Vec::with_capacity(count.min(1024));
            for _ in 0..count {
                events.push(decoder.event()?);
            }
            Response::Events { events }
        }
        other => return Err(BbError::corrupt(format!("unknown response tag {other}"))),
    })
}

// ---------------------------------------------------------------------------
// Framing
// ---------------------------------------------------------------------------

/// Adds a 4-byte little-endian length prefix (useful for sockets/pipes between
/// the host app and a native helper process).
pub fn encode_frame(message: &Message) -> Result<Vec<u8>> {
    let payload = encode(message)?;
    let mut frame = Vec::with_capacity(payload.len() + 4);
    frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    frame.extend_from_slice(&payload);
    Ok(frame)
}

pub fn decode_frame(frame: &[u8]) -> Result<Message> {
    bb_ensure!(
        frame.len() >= 4,
        ErrorCode::Corrupt,
        "frame is shorter than its length prefix"
    );
    let declared = u32::from_le_bytes([frame[0], frame[1], frame[2], frame[3]]) as usize;
    bb_ensure!(
        declared <= MAX_MESSAGE_BYTES,
        ErrorCode::TooLarge,
        "frame declares {declared} bytes, limit is {MAX_MESSAGE_BYTES}"
    );
    bb_ensure!(
        frame.len() - 4 == declared,
        ErrorCode::Corrupt,
        "frame declares {declared} payload bytes but carries {}",
        frame.len() - 4
    );
    decode(&frame[4..])
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

pub fn to_json(message: &Message) -> Result<String> {
    #[derive(Serialize)]
    #[serde(tag = "kind", rename_all = "snake_case")]
    enum Tagged<'a> {
        Request(&'a Request),
        Response(&'a Response),
    }
    let tagged = match message {
        Message::Request(request) => Tagged::Request(request),
        Message::Response(response) => Tagged::Response(response),
    };
    Ok(serde_json::to_string(&tagged)?)
}

pub fn from_json(json: &str) -> Result<Message> {
    #[derive(Deserialize)]
    #[serde(tag = "kind", rename_all = "snake_case")]
    enum Tagged {
        Request(Request),
        Response(Response),
    }
    let tagged: Tagged = serde_json::from_str(json)?;
    Ok(match tagged {
        Tagged::Request(request) => Message::Request(request),
        Tagged::Response(response) => Message::Response(response),
    })
}

/// Handshake check performed by `bb-bridge` on `Hello`.
pub fn check_protocol(protocol: u32) -> Result<()> {
    bb_ensure!(
        protocol == PROTOCOL_VERSION,
        ErrorCode::Unsupported,
        "protocol {protocol} is not supported (core speaks {PROTOCOL_VERSION})"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use bb_common::testing::XorShift64;

    fn status() -> InstanceStatus {
        InstanceStatus {
            instance_id: "instance_001".to_string(),
            package_name: "com.example.app".to_string(),
            state: 5,
            state_name: "running".to_string(),
            refs: 3,
            updated_at_ms: 1_700_000_000_000,
            last_error: Some("none today".to_string()),
        }
    }

    fn samples() -> Vec<Message> {
        vec![
            Message::request(Request::Hello {
                protocol: PROTOCOL_VERSION,
            }),
            Message::request(Request::CreateInstance {
                spec: CreateInstanceSpec {
                    instance_id: "instance_001".to_string(),
                    package_name: "com.example.app".to_string(),
                    apk_path: "/sdcard/app.apk".to_string(),
                    policy: Some(InstancePolicy::restricted()),
                },
            }),
            Message::request(Request::CreateInstance {
                spec: CreateInstanceSpec {
                    instance_id: "instance_002".to_string(),
                    package_name: "com.example.other".to_string(),
                    apk_path: "/sdcard/other.apk".to_string(),
                    policy: None,
                },
            }),
            Message::request(Request::RemoveInstance {
                instance_id: "instance_001".into(),
            }),
            Message::request(Request::StartInstance {
                instance_id: "instance_001".into(),
            }),
            Message::request(Request::StopInstance {
                instance_id: "instance_001".into(),
            }),
            Message::request(Request::GetInstance {
                instance_id: "instance_001".into(),
            }),
            Message::request(Request::ListInstances),
            Message::request(Request::InspectApk {
                apk_path: "/sdcard/app.apk".into(),
            }),
            Message::request(Request::Metrics),
            Message::request(Request::Shutdown),
            Message::response(Response::ok()),
            Message::response(Response::Ok {
                message: Some("done".into()),
            }),
            Message::response(Response::from_error(&BbError::busy("busy"))),
            Message::response(Response::Instance { instance: status() }),
            Message::response(Response::Instances {
                instances: vec![
                    status(),
                    InstanceStatus {
                        last_error: None,
                        ..status()
                    },
                ],
            }),
            Message::response(Response::Apk {
                apk: ApkSummary {
                    package: "com.example.app".into(),
                    version_code: 42,
                    version_name: Some("4.2".into()),
                    min_sdk: 26,
                    target_sdk: 34,
                    permissions: vec!["android.permission.INTERNET".into()],
                    launcher_activity: Some(".Main".into()),
                    native_abis: vec!["arm64-v8a".into(), "armeabi-v7a".into()],
                    activity_count: 7,
                    file_size: 1_234_567,
                },
            }),
            Message::response(Response::Metrics {
                snapshot_json: "{}".into(),
            }),
            Message::response(Response::Hello {
                protocol: PROTOCOL_VERSION,
                abi_version: 1,
            }),
            Message::request(Request::Events { since_seq: 0 }),
            Message::request(Request::Events { since_seq: 4_294_967_296 }),
            Message::response(Response::Events {
                events: vec![
                    RuntimeEvent {
                        seq: 9,
                        at_ms: 1,
                        instance_id: "instance_001".into(),
                        from: Some(InstanceState::Starting),
                        to: InstanceState::Running,
                        reason: "guest process up".into(),
                    },
                    RuntimeEvent {
                        seq: 10,
                        at_ms: 2,
                        instance_id: "instance_001".into(),
                        from: None,
                        to: InstanceState::Created,
                        reason: "created".into(),
                    },
                ],
            }),
        ]
    }

    #[test]
    fn every_variant_round_trips() {
        for message in samples() {
            let encoded = encode(&message).unwrap();
            let decoded = decode(&encoded).unwrap();
            assert_eq!(decoded, message, "wire mismatch for {message:?}");
        }
    }

    #[test]
    fn json_round_trip_matches_binary() {
        for message in samples() {
            let json = to_json(&message).unwrap();
            let decoded = from_json(&json).unwrap();
            assert_eq!(decoded, message, "json mismatch for {json}");
        }
    }

    #[test]
    fn negative_error_codes_survive() {
        let message = Message::response(Response::from_error(&BbError::new(
            ErrorCode::PermissionDenied,
            "nope",
        )));
        let decoded = decode(&encode(&message).unwrap()).unwrap();
        match decoded {
            Message::Response(Response::Error {
                code, code_name, ..
            }) => {
                assert_eq!(code, -10);
                assert_eq!(code_name, "PERMISSION_DENIED");
            }
            other => panic!("unexpected message {other:?}"),
        }
    }

    #[test]
    fn truncated_buffers_never_panic() {
        for message in samples() {
            let encoded = encode(&message).unwrap();
            for cut in 0..encoded.len() {
                let result = decode(&encoded[..cut]);
                assert!(result.is_err(), "prefix of {cut} bytes should fail");
            }
        }
    }

    #[test]
    fn random_garbage_never_panics() {
        let mut rng = XorShift64::new(0xDEAD_BEEF);
        for _ in 0..2_000 {
            let len = rng.next_in_range(64) as usize;
            let bytes = rng.bytes(len);
            let _ = decode(&bytes);
            let _ = decode_frame(&bytes);
            let _ = from_json(std::str::from_utf8(&bytes).unwrap_or("null"));
        }
    }

    #[test]
    fn absurd_length_prefix_is_rejected() {
        // MSG_REQUEST, REQ_INSPECT_APK, then a 2^60 byte string length.
        let mut bytes = vec![MSG_REQUEST, REQ_INSPECT_APK];
        put_u64(&mut bytes, 1u64 << 60);
        let err = decode(&bytes).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Corrupt);

        let huge = vec![0u8; MAX_MESSAGE_BYTES + 1];
        assert_eq!(decode(&huge).unwrap_err().code(), ErrorCode::TooLarge);
    }

    #[test]
    fn unknown_tags_are_rejected() {
        assert_eq!(decode(&[0xFF]).unwrap_err().code(), ErrorCode::Corrupt);
        assert_eq!(
            decode(&[MSG_REQUEST, 0x7F]).unwrap_err().code(),
            ErrorCode::Corrupt
        );
        assert_eq!(
            decode(&[MSG_RESPONSE, 0x7F]).unwrap_err().code(),
            ErrorCode::Corrupt
        );
        assert_eq!(
            decode(&[MSG_REQUEST, REQ_EVENTS]).unwrap_err().code(),
            ErrorCode::Corrupt,
            "events request without a since_seq must be a truncation error"
        );
    }

    #[test]
    fn trailing_bytes_are_rejected() {
        let mut encoded = encode(&Message::request(Request::ListInstances)).unwrap();
        encoded.push(0);
        assert_eq!(decode(&encoded).unwrap_err().code(), ErrorCode::Corrupt);
    }

    #[test]
    fn frames_round_trip_and_validate() {
        let message = Message::request(Request::ListInstances);
        let frame = encode_frame(&message).unwrap();
        assert_eq!(decode_frame(&frame).unwrap(), message);

        assert!(decode_frame(&frame[..2]).is_err());
        let mut lying = frame.clone();
        lying[0] = lying[0].wrapping_add(1);
        assert!(decode_frame(&lying).is_err());
    }

    #[test]
    fn protocol_handshake_is_strict() {
        assert!(check_protocol(PROTOCOL_VERSION).is_ok());
        let err = check_protocol(PROTOCOL_VERSION + 1).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Unsupported);
    }

    #[test]
    fn encode_rejects_oversized_messages() {
        let message = Message::request(Request::InspectApk {
            apk_path: "a".repeat(MAX_MESSAGE_BYTES),
        });
        assert_eq!(encode(&message).unwrap_err().code(), ErrorCode::TooLarge);
    }
}
