//! Instance lifecycle state.
//!
//! Rust owns the bookkeeping and the transition table; the Android layer owns
//! the *real* `Activity`/`Service` lifecycle of the guest application. Keeping
//! the table here (and not in Kotlin) means both sides can never disagree
//! about what "Running" means, and the rules are unit-testable off-device.

use serde::{Deserialize, Serialize};

/// Numeric values are part of the ABI (see `bb-bridge`); append only.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[repr(i32)]
pub enum InstanceState {
    /// Metadata row exists, no on-disk layout yet.
    Created = 1,
    /// Layout being created / APK being inspected.
    Preparing = 2,
    /// APK inspected, layout ready, launchable.
    Installed = 3,
    /// Guest process is being spun up by the Android layer.
    Starting = 4,
    /// At least one guest component is alive.
    Running = 5,
    /// Components are being torn down.
    Stopping = 6,
    /// Stopped but data kept; can be started again.
    Stopped = 7,
    /// Something failed; `last_error` carries the diagnosis.
    Failed = 8,
}

impl InstanceState {
    pub const ALL: [InstanceState; 8] = [
        Self::Created,
        Self::Preparing,
        Self::Installed,
        Self::Starting,
        Self::Running,
        Self::Stopping,
        Self::Stopped,
        Self::Failed,
    ];

    pub const fn as_i32(self) -> i32 {
        self as i32
    }

    pub const fn from_i32(value: i32) -> Option<Self> {
        Some(match value {
            1 => Self::Created,
            2 => Self::Preparing,
            3 => Self::Installed,
            4 => Self::Starting,
            5 => Self::Running,
            6 => Self::Stopping,
            7 => Self::Stopped,
            8 => Self::Failed,
            _ => return None,
        })
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Created => "created",
            Self::Preparing => "preparing",
            Self::Installed => "installed",
            Self::Starting => "starting",
            Self::Running => "running",
            Self::Stopping => "stopping",
            Self::Stopped => "stopped",
            Self::Failed => "failed",
        }
    }

    /// States in which a guest process may still own resources.
    pub const fn is_active(self) -> bool {
        matches!(self, Self::Starting | Self::Running | Self::Stopping)
    }

    /// States from which a launch is possible.
    pub const fn is_launchable(self) -> bool {
        matches!(self, Self::Installed | Self::Stopped)
    }

    /// The transition table. Everything not listed here is rejected with
    /// `INVALID_STATE` by `bb-runtime`, which keeps the persisted metadata from
    /// ever describing an impossible situation.
    pub const fn allowed_next(self) -> &'static [InstanceState] {
        match self {
            Self::Created => &[Self::Preparing, Self::Failed],
            Self::Preparing => &[Self::Installed, Self::Failed],
            Self::Installed => &[Self::Starting, Self::Preparing, Self::Failed],
            Self::Starting => &[Self::Running, Self::Failed],
            Self::Running => &[Self::Stopping, Self::Failed],
            Self::Stopping => &[Self::Stopped, Self::Failed],
            Self::Stopped => &[Self::Starting, Self::Preparing, Self::Failed],
            Self::Failed => &[Self::Preparing, Self::Stopped],
        }
    }

    pub const fn can_transition(self, next: InstanceState) -> bool {
        // Written as a `match` (not a slice lookup) so it can be a `const fn`
        // and so the compiler can prove the table is exhaustive.
        match self {
            Self::Created => matches!(next, Self::Preparing | Self::Failed),
            Self::Preparing => matches!(next, Self::Installed | Self::Failed),
            Self::Installed => matches!(next, Self::Starting | Self::Preparing | Self::Failed),
            Self::Starting => matches!(next, Self::Running | Self::Failed),
            Self::Running => matches!(next, Self::Stopping | Self::Failed),
            Self::Stopping => matches!(next, Self::Stopped | Self::Failed),
            Self::Stopped => matches!(next, Self::Starting | Self::Preparing | Self::Failed),
            Self::Failed => matches!(next, Self::Preparing | Self::Stopped),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_round_trip_and_are_unique() {
        let mut seen = Vec::new();
        for state in InstanceState::ALL {
            assert_eq!(InstanceState::from_i32(state.as_i32()), Some(state));
            assert!(!state.as_str().is_empty());
            assert!(!seen.contains(&state.as_i32()));
            seen.push(state.as_i32());
        }
        assert_eq!(InstanceState::from_i32(0), None);
        assert_eq!(InstanceState::from_i32(99), None);
    }

    #[test]
    fn happy_path_is_allowed() {
        let path = [
            InstanceState::Created,
            InstanceState::Preparing,
            InstanceState::Installed,
            InstanceState::Starting,
            InstanceState::Running,
            InstanceState::Stopping,
            InstanceState::Stopped,
        ];
        for pair in path.windows(2) {
            assert!(
                pair[0].can_transition(pair[1]),
                "{} -> {} should be allowed",
                pair[0].as_str(),
                pair[1].as_str()
            );
        }
    }

    #[test]
    fn illegal_jumps_are_rejected() {
        assert!(!InstanceState::Created.can_transition(InstanceState::Running));
        assert!(!InstanceState::Running.can_transition(InstanceState::Installed));
        assert!(!InstanceState::Stopped.can_transition(InstanceState::Running));
        assert!(!InstanceState::Preparing.can_transition(InstanceState::Starting));
        for state in InstanceState::ALL {
            assert!(
                !state.can_transition(state),
                "self transition must be rejected"
            );
        }
    }

    #[test]
    fn failure_is_reachable_from_every_state_but_failed() {
        for state in InstanceState::ALL {
            if state == InstanceState::Failed {
                continue;
            }
            assert!(
                state.can_transition(InstanceState::Failed),
                "{} must fail",
                state.as_str()
            );
        }
    }

    #[test]
    fn serde_uses_names_not_numbers() {
        let json = serde_json::to_string(&InstanceState::Running).unwrap();
        assert_eq!(json, "\"running\"");
        let state: InstanceState = serde_json::from_str("\"stopped\"").unwrap();
        assert_eq!(state, InstanceState::Stopped);
    }
}
