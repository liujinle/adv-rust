//! Clock helpers.
//!
//! Persisted timestamps are wall-clock milliseconds since the Unix epoch so the
//! Kotlin UI can render them directly; in-process measurements use `Instant` so
//! they are immune to clock jumps.

use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn now_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_micros() as u64)
        .unwrap_or(0)
}

/// Monotonic stopwatch for benchmarks and diagnostics.
#[derive(Debug, Clone)]
pub struct Stopwatch {
    start: Instant,
}

impl Stopwatch {
    pub fn start() -> Self {
        Self {
            start: Instant::now(),
        }
    }

    pub fn elapsed(&self) -> Duration {
        self.start.elapsed()
    }

    pub fn elapsed_millis(&self) -> u64 {
        self.elapsed().as_millis() as u64
    }

    pub fn elapsed_micros(&self) -> u64 {
        self.elapsed().as_micros() as u64
    }
}

impl Default for Stopwatch {
    fn default() -> Self {
        Self::start()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wall_clock_is_plausible() {
        let now = now_millis();
        // 2020-01-01 .. 2200-01-01 — catches an inverted or zeroed clock.
        assert!(now > 1_577_836_800_000, "clock looks wrong: {now}");
        assert!(now < 7_258_118_400_000, "clock looks wrong: {now}");
        assert!(now_micros() / 1000 >= now.saturating_sub(5));
    }

    #[test]
    fn stopwatch_measures_real_work() {
        let watch = Stopwatch::start();
        std::thread::sleep(Duration::from_millis(15));
        assert!(
            watch.elapsed_millis() >= 10,
            "elapsed {:?}",
            watch.elapsed()
        );
        assert!(watch.elapsed_micros() >= 10_000);
    }
}
