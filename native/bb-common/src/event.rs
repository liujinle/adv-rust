//! The one lifecycle event schema.
//!
//! Why this lives in `bb-common`: both `bb-runtime` (producer) and `bb-ipc`
//! (transport) must describe the same event. When there were two structs, the
//! Kotlin side could observe a state change that the runtime never emitted — the
//! exact "Rust 与 Kotlin 状态不一致" failure mode. There is now a single definition
//! and a single serialisation (`snake_case` state names, never numeric codes).

use serde::{Deserialize, Serialize};

use crate::state::InstanceState;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeEvent {
    /// Monotonic sequence number; clients poll with `since_seq` to replay.
    pub seq: u64,
    pub at_ms: u64,
    pub instance_id: String,
    pub from: Option<InstanceState>,
    pub to: InstanceState,
    pub reason: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serialises_state_names_not_numbers() {
        let event = RuntimeEvent {
            seq: 7,
            at_ms: 1_700_000_000_000,
            instance_id: "instance_001".to_string(),
            from: Some(InstanceState::Starting),
            to: InstanceState::Running,
            reason: "guest process up".to_string(),
        };
        let json = serde_json::to_value(&event).unwrap();
        assert_eq!(json["from"], "starting");
        assert_eq!(json["to"], "running");
        let decoded: RuntimeEvent = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, event);
    }

    #[test]
    fn absent_from_state_round_trips_as_null() {
        let event = RuntimeEvent {
            seq: 1,
            at_ms: 1,
            instance_id: "instance_001".to_string(),
            from: None,
            to: InstanceState::Created,
            reason: "created".to_string(),
        };
        let json = serde_json::to_string(&event).unwrap();
        assert!(json.contains("\"from\":null"));
        assert_eq!(serde_json::from_str::<RuntimeEvent>(&json).unwrap(), event);
    }
}
