//! Bounded task queue.
//!
//! Why a custom pool instead of Tokio: the work here is short, synchronous,
//! blocking filesystem/parsing work driven by Kotlin. A fixed thread pool with
//! an explicit task id, cancellable jobs and a retained outcome map is smaller,
//! easier to reason about, and does not pull an async runtime into the `.so`.
//!
//! Contract:
//! * one job runs on exactly one worker thread; `JNIEnv` is never captured, so
//!   no thread-affine state can leak into a job;
//! * a panicking job never kills a worker and never escapes as a panic - it is
//!   reported as [`bb_common::ErrorCode::Panic`];
//! * outcomes are retained up to `max_retained` tasks, so a caller that polls a
//!   dead task id gets `None` rather than unbounded memory growth.

use std::collections::BTreeMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use bb_common::error::{BbError, Result};
use bb_common::time::now_millis;
use bb_common::{ErrorCode, bb_ensure};

pub type TaskPayload = Vec<u8>;
pub type TaskResult = Result<TaskPayload>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum TaskState {
    Pending = 0,
    Running = 1,
    Succeeded = 2,
    Failed = 3,
    Cancelled = 4,
}

impl TaskState {
    pub const fn as_u8(self) -> u8 {
        self as u8
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Running => "running",
            Self::Succeeded => "succeeded",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }

    pub const fn is_terminal(self) -> bool {
        matches!(self, Self::Succeeded | Self::Failed | Self::Cancelled)
    }

    pub const fn from_u8(value: u8) -> Option<Self> {
        Some(match value {
            0 => Self::Pending,
            1 => Self::Running,
            2 => Self::Succeeded,
            3 => Self::Failed,
            4 => Self::Cancelled,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone)]
pub struct TaskOutcome {
    pub task_id: u64,
    pub name: String,
    pub state: TaskState,
    pub progress: u8,
    pub payload: Option<Vec<u8>>,
    pub error_code: Option<i32>,
    pub error_name: Option<String>,
    pub error_message: Option<String>,
    pub submitted_at_ms: u64,
    pub started_at_ms: Option<u64>,
    pub finished_at_ms: Option<u64>,
}

impl TaskOutcome {
    pub fn is_success(&self) -> bool {
        self.state == TaskState::Succeeded
    }
}

/// Handle handed to a running job.
#[derive(Debug, Clone)]
pub struct TaskContext {
    task_id: u64,
    cancel: Arc<AtomicBool>,
    progress: Arc<AtomicU8>,
}

impl TaskContext {
    pub fn task_id(&self) -> u64 {
        self.task_id
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::Relaxed)
    }

    /// Cooperative progress reporting, clamped to `0..=100`.
    pub fn report_progress(&self, percent: u8) {
        self.progress.store(percent.min(100), Ordering::Relaxed);
    }
}

#[derive(Debug)]
struct SlotState {
    state: TaskState,
    payload: Option<Vec<u8>>,
    error: Option<BbError>,
    submitted_at_ms: u64,
    started_at_ms: Option<u64>,
    finished_at_ms: Option<u64>,
}

#[derive(Debug)]
struct Slot {
    task_id: u64,
    name: String,
    cancel: Arc<AtomicBool>,
    progress: Arc<AtomicU8>,
    state: Mutex<SlotState>,
}

impl Slot {
    fn outcome(&self) -> TaskOutcome {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        TaskOutcome {
            task_id: self.task_id,
            name: self.name.clone(),
            state: state.state,
            progress: self.progress.load(Ordering::Relaxed),
            payload: state.payload.clone(),
            error_code: state.error.as_ref().map(|err| err.code().as_i32()),
            error_name: state
                .error
                .as_ref()
                .map(|err| err.code().as_str().to_string()),
            error_message: state.error.as_ref().map(|err| err.message().to_string()),
            submitted_at_ms: state.submitted_at_ms,
            started_at_ms: state.started_at_ms,
            finished_at_ms: state.finished_at_ms,
        }
    }

    fn is_terminal(&self) -> bool {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .state
            .is_terminal()
    }

    fn mark_running(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if state.state == TaskState::Pending {
            state.state = TaskState::Running;
            state.started_at_ms = Some(now_millis());
        }
    }

    fn mark_cancelled(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.state = TaskState::Cancelled;
        state.payload = None;
        state.finished_at_ms = Some(now_millis());
    }

    fn complete(&self, result: TaskResult) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if self.cancel.load(Ordering::Relaxed) || state.state == TaskState::Cancelled {
            state.state = TaskState::Cancelled;
            state.payload = None;
        } else {
            match result {
                Ok(payload) => {
                    state.state = TaskState::Succeeded;
                    state.payload = Some(payload);
                    self.progress.store(100, Ordering::Relaxed);
                }
                Err(err) => {
                    state.state = TaskState::Failed;
                    state.error = Some(err);
                }
            }
        }
        state.finished_at_ms = Some(now_millis());
    }
}

struct Job {
    slot: Arc<Slot>,
    work: Box<dyn FnOnce(&TaskContext) -> TaskResult + Send + 'static>,
}

#[derive(Debug, Clone, Copy)]
pub struct SchedulerLimits {
    pub threads: usize,
    pub max_queued: usize,
    pub max_retained: usize,
}

impl Default for SchedulerLimits {
    fn default() -> Self {
        Self {
            threads: 2,
            max_queued: 1024,
            max_retained: 256,
        }
    }
}

struct Inner {
    outcomes: Mutex<BTreeMap<u64, Arc<Slot>>>,
    completed: Condvar,
    next_id: AtomicU64,
    queued: AtomicUsize,
    active: AtomicUsize,
    max_queued: usize,
    max_retained: usize,
}

impl Inner {
    fn lock_outcomes(&self) -> std::sync::MutexGuard<'_, BTreeMap<u64, Arc<Slot>>> {
        self.outcomes
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn prune(&self, outcomes: &mut BTreeMap<u64, Arc<Slot>>) {
        if outcomes.len() <= self.max_retained {
            return;
        }
        let overflow = outcomes.len() - self.max_retained;
        let victims: Vec<u64> = outcomes
            .iter()
            .filter(|(_, slot)| slot.is_terminal())
            .map(|(id, _)| *id)
            .take(overflow)
            .collect();
        for victim in victims {
            outcomes.remove(&victim);
        }
    }
}

/// Fixed-size worker pool.
pub struct Scheduler {
    inner: Arc<Inner>,
    sender: Mutex<Option<Sender<Job>>>,
    workers: Mutex<Vec<JoinHandle<()>>>,
    shutdown: AtomicBool,
}

impl std::fmt::Debug for Scheduler {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Scheduler")
            .field("workers", &self.worker_count())
            .field("queued", &self.queued())
            .field("active", &self.active())
            .finish()
    }
}

impl Scheduler {
    pub fn new(threads: usize) -> Result<Self> {
        Self::with_limits(SchedulerLimits {
            threads,
            ..SchedulerLimits::default()
        })
    }

    pub fn with_limits(limits: SchedulerLimits) -> Result<Self> {
        bb_ensure!(
            (1..=64).contains(&limits.threads),
            ErrorCode::InvalidArgument,
            "scheduler needs 1..=64 threads, got {}",
            limits.threads
        );
        bb_ensure!(
            limits.max_queued > 0 && limits.max_retained >= 16,
            ErrorCode::InvalidArgument,
            "invalid scheduler limits"
        );
        let (sender, receiver) = mpsc::channel::<Job>();
        let receiver = Arc::new(Mutex::new(receiver));
        let inner = Arc::new(Inner {
            outcomes: Mutex::new(BTreeMap::new()),
            completed: Condvar::new(),
            next_id: AtomicU64::new(1),
            queued: AtomicUsize::new(0),
            active: AtomicUsize::new(0),
            max_queued: limits.max_queued,
            max_retained: limits.max_retained,
        });
        let mut workers = Vec::with_capacity(limits.threads);
        for index in 0..limits.threads {
            let receiver = receiver.clone();
            let inner = inner.clone();
            let handle = thread::Builder::new()
                .name(format!("bb-task-{index}"))
                .spawn(move || worker_loop(receiver, inner))
                .map_err(|err| BbError::io(format!("cannot spawn worker: {err}")))?;
            workers.push(handle);
        }
        Ok(Self {
            inner,
            sender: Mutex::new(Some(sender)),
            workers: Mutex::new(workers),
            shutdown: AtomicBool::new(false),
        })
    }

    pub fn queued(&self) -> usize {
        self.inner.queued.load(Ordering::Relaxed)
    }

    pub fn active(&self) -> usize {
        self.inner.active.load(Ordering::Relaxed)
    }

    pub fn worker_count(&self) -> usize {
        self.workers
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .len()
    }

    pub fn is_shutdown(&self) -> bool {
        self.shutdown.load(Ordering::Relaxed)
    }

    /// Queues `work` and returns its id.
    pub fn submit<F>(&self, name: impl Into<String>, work: F) -> Result<u64>
    where
        F: FnOnce(&TaskContext) -> TaskResult + Send + 'static,
    {
        bb_ensure!(
            !self.is_shutdown(),
            ErrorCode::NotInitialized,
            "scheduler is shut down"
        );
        bb_ensure!(
            self.inner.queued.load(Ordering::Relaxed) < self.inner.max_queued,
            ErrorCode::Busy,
            "task queue is full ({} queued)",
            self.inner.max_queued
        );
        let task_id = self.inner.next_id.fetch_add(1, Ordering::Relaxed);
        let slot = Arc::new(Slot {
            task_id,
            name: name.into(),
            cancel: Arc::new(AtomicBool::new(false)),
            progress: Arc::new(AtomicU8::new(0)),
            state: Mutex::new(SlotState {
                state: TaskState::Pending,
                payload: None,
                error: None,
                submitted_at_ms: now_millis(),
                started_at_ms: None,
                finished_at_ms: None,
            }),
        });
        {
            let mut outcomes = self.inner.lock_outcomes();
            outcomes.insert(task_id, slot.clone());
            self.inner.prune(&mut outcomes);
        }
        let job = Job {
            slot: slot.clone(),
            work: Box::new(work),
        };
        self.inner.queued.fetch_add(1, Ordering::Relaxed);
        let sender = self
            .sender
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        match sender {
            Some(sender) => match sender.send(job) {
                Ok(()) => Ok(task_id),
                Err(_) => {
                    self.inner.queued.fetch_sub(1, Ordering::Relaxed);
                    slot.complete(Err(BbError::not_initialized("scheduler is shut down")));
                    self.inner.completed.notify_all();
                    Ok(task_id)
                }
            },
            None => {
                self.inner.queued.fetch_sub(1, Ordering::Relaxed);
                slot.complete(Err(BbError::not_initialized("scheduler is shut down")));
                self.inner.completed.notify_all();
                Ok(task_id)
            }
        }
    }

    pub fn status(&self, task_id: u64) -> Option<TaskOutcome> {
        self.inner
            .lock_outcomes()
            .get(&task_id)
            .map(|slot| slot.outcome())
    }

    pub fn progress(&self, task_id: u64) -> Option<u8> {
        self.inner
            .lock_outcomes()
            .get(&task_id)
            .map(|slot| slot.progress.load(Ordering::Relaxed))
    }

    /// Blocks the *caller* until the task finishes or `timeout` elapses.
    /// Returns `None` on timeout or unknown id. Never call this on the UI thread;
    /// Kotlin uses `nativeTaskResult` polling instead.
    pub fn wait(&self, task_id: u64, timeout: Duration) -> Option<TaskOutcome> {
        let deadline = Instant::now() + timeout;
        let mut outcomes = self.inner.lock_outcomes();
        loop {
            let slot = outcomes.get(&task_id)?;
            if slot.is_terminal() {
                return Some(slot.outcome());
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let (guard, _) = self
                .inner
                .completed
                .wait_timeout(outcomes, deadline - now)
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            outcomes = guard;
        }
    }

    /// Requests cancellation. Running jobs must observe
    /// [`TaskContext::is_cancelled`]; queued jobs are dropped without running.
    pub fn cancel(&self, task_id: u64) -> bool {
        let slot = {
            let outcomes = self.inner.lock_outcomes();
            outcomes.get(&task_id).cloned()
        };
        let Some(slot) = slot else { return false };
        slot.cancel.store(true, Ordering::Relaxed);
        if !slot.is_terminal() {
            slot.mark_cancelled();
            self.inner.completed.notify_all();
        }
        true
    }

    pub fn retain_limit(&self) -> usize {
        self.inner.max_retained
    }

    pub fn tracked_tasks(&self) -> usize {
        self.inner.lock_outcomes().len()
    }

    /// Stops accepting work, drains the queue and joins every worker.
    pub fn shutdown(&self) {
        if self.shutdown.swap(true, Ordering::SeqCst) {
            return;
        }
        let sender = self
            .sender
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take();
        drop(sender);
        let workers = std::mem::take(
            &mut *self
                .workers
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()),
        );
        for worker in workers {
            let _ = worker.join();
        }
        self.inner.completed.notify_all();
    }
}

impl Drop for Scheduler {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn worker_loop(receiver: Arc<Mutex<Receiver<Job>>>, inner: Arc<Inner>) {
    loop {
        let job = {
            let guard = receiver
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            guard.recv()
        };
        let Ok(Job { slot, work }) = job else { break };
        inner.queued.fetch_sub(1, Ordering::Relaxed);

        if slot.cancel.load(Ordering::Relaxed) {
            slot.mark_cancelled();
            inner.completed.notify_all();
            continue;
        }

        inner.active.fetch_add(1, Ordering::Relaxed);
        slot.mark_running();
        let context = TaskContext {
            task_id: slot.task_id,
            cancel: slot.cancel.clone(),
            progress: slot.progress.clone(),
        };
        let result = catch_unwind(AssertUnwindSafe(|| work(&context)));
        inner.active.fetch_sub(1, Ordering::Relaxed);

        match result {
            Ok(task_result) => slot.complete(task_result),
            Err(payload) => {
                let err = BbError::from_panic_payload(payload.as_ref());
                slot.complete(Err(err));
            }
        }
        inner.completed.notify_all();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    fn scheduler(threads: usize) -> Scheduler {
        Scheduler::new(threads).unwrap()
    }

    #[test]
    fn successful_task_returns_payload() {
        let scheduler = scheduler(2);
        let id = scheduler
            .submit("echo", |_ctx| Ok(b"payload".to_vec()))
            .unwrap();
        let outcome = scheduler.wait(id, Duration::from_secs(5)).unwrap();
        assert_eq!(outcome.state, TaskState::Succeeded);
        assert_eq!(outcome.payload.as_deref(), Some(&b"payload"[..]));
        assert_eq!(outcome.progress, 100);
        assert!(outcome.is_success());
        assert!(outcome.started_at_ms.is_some());
        assert!(outcome.finished_at_ms.is_some());
    }

    #[test]
    fn errors_are_reported_with_codes() {
        let scheduler = scheduler(2);
        let id = scheduler
            .submit("fail", |_ctx| Err(BbError::not_found("no apk")))
            .unwrap();
        let outcome = scheduler.wait(id, Duration::from_secs(5)).unwrap();
        assert_eq!(outcome.state, TaskState::Failed);
        assert_eq!(outcome.error_code, Some(ErrorCode::NotFound.as_i32()));
        assert_eq!(outcome.error_name.as_deref(), Some("NOT_FOUND"));
        assert_eq!(outcome.error_message.as_deref(), Some("no apk"));
        assert!(outcome.payload.is_none());
    }

    #[test]
    fn panicking_task_is_contained() {
        let scheduler = scheduler(1);
        let id = scheduler
            .submit("panic", |_ctx| panic!("job exploded"))
            .unwrap();
        let outcome = scheduler.wait(id, Duration::from_secs(5)).unwrap();
        assert_eq!(outcome.state, TaskState::Failed);
        assert_eq!(outcome.error_code, Some(ErrorCode::Panic.as_i32()));
        assert!(
            outcome
                .error_message
                .as_deref()
                .unwrap_or_default()
                .contains("job exploded")
        );
        // The worker survived: the next task still runs.
        let id = scheduler.submit("after", |_ctx| Ok(Vec::new())).unwrap();
        assert!(
            scheduler
                .wait(id, Duration::from_secs(5))
                .unwrap()
                .is_success()
        );
    }

    #[test]
    fn progress_is_visible_while_running() {
        let scheduler = scheduler(1);
        let (tx, rx) = mpsc::channel::<()>();
        let id = scheduler
            .submit("progress", move |ctx| {
                ctx.report_progress(42);
                let _ = rx.recv_timeout(Duration::from_secs(5));
                Ok(Vec::new())
            })
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while scheduler.progress(id) != Some(42) && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(scheduler.progress(id), Some(42));
        tx.send(()).unwrap();
        let outcome = scheduler.wait(id, Duration::from_secs(5)).unwrap();
        assert_eq!(outcome.state, TaskState::Succeeded);
    }

    #[test]
    fn queued_task_can_be_cancelled() {
        let scheduler = scheduler(1);
        let (release, blocked) = mpsc::channel::<()>();
        let blocker = scheduler
            .submit("blocker", move |_ctx| {
                let _ = blocked.recv_timeout(Duration::from_secs(5));
                Ok(Vec::new())
            })
            .unwrap();
        // Wait until the blocker occupies the single worker.
        let deadline = Instant::now() + Duration::from_secs(5);
        while scheduler.status(blocker).map(|outcome| outcome.state) != Some(TaskState::Running) {
            assert!(Instant::now() < deadline, "blocker never started");
            thread::sleep(Duration::from_millis(5));
        }
        let queued = scheduler
            .submit("queued", |_ctx| Ok(b"should not run".to_vec()))
            .unwrap();
        assert!(scheduler.cancel(queued));
        release.send(()).unwrap();
        let outcome = scheduler.wait(queued, Duration::from_secs(5)).unwrap();
        assert_eq!(outcome.state, TaskState::Cancelled);
        assert!(outcome.payload.is_none());
        assert!(!scheduler.cancel(9999));
    }

    #[test]
    fn running_task_observes_cancellation() {
        let scheduler = scheduler(1);
        let id = scheduler
            .submit("cooperative", |ctx| {
                let deadline = Instant::now() + Duration::from_secs(5);
                while Instant::now() < deadline {
                    if ctx.is_cancelled() {
                        return Ok(b"cancelled".to_vec());
                    }
                    thread::sleep(Duration::from_millis(2));
                }
                Err(BbError::timed_out("never cancelled"))
            })
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while scheduler.status(id).map(|outcome| outcome.state) != Some(TaskState::Running) {
            assert!(Instant::now() < deadline, "task never started");
            thread::sleep(Duration::from_millis(2));
        }
        assert!(scheduler.cancel(id));
        let outcome = scheduler.wait(id, Duration::from_secs(5)).unwrap();
        assert_eq!(outcome.state, TaskState::Cancelled);
    }

    #[test]
    fn wait_times_out_without_blocking_forever() {
        let scheduler = scheduler(1);
        let (release, blocked) = mpsc::channel::<()>();
        let id = scheduler
            .submit("slow", move |_ctx| {
                let _ = blocked.recv_timeout(Duration::from_secs(5));
                Ok(Vec::new())
            })
            .unwrap();
        assert!(scheduler.wait(id, Duration::from_millis(30)).is_none());
        assert_eq!(scheduler.status(id).unwrap().state, TaskState::Running);
        release.send(()).unwrap();
        assert!(scheduler.wait(id, Duration::from_secs(5)).is_some());
        assert!(scheduler.status(9999).is_none());
    }

    #[test]
    fn many_tasks_run_concurrently() {
        let scheduler = concurrent_scheduler();
        let counter = Arc::new(AtomicUsize::new(0));
        let mut ids = Vec::new();
        for _ in 0..100 {
            let counter = counter.clone();
            ids.push(
                scheduler
                    .submit("count", move |_ctx| {
                        counter.fetch_add(1, Ordering::Relaxed);
                        Ok(Vec::new())
                    })
                    .unwrap(),
            );
        }
        for id in ids {
            assert!(
                scheduler
                    .wait(id, Duration::from_secs(10))
                    .unwrap()
                    .is_success()
            );
        }
        assert_eq!(counter.load(Ordering::Relaxed), 100);
        assert_eq!(scheduler.queued(), 0);
        assert_eq!(scheduler.active(), 0);
    }

    fn concurrent_scheduler() -> Scheduler {
        Scheduler::with_limits(SchedulerLimits {
            threads: 4,
            max_queued: 4096,
            max_retained: 512,
        })
        .unwrap()
    }

    #[test]
    fn retained_outcomes_are_bounded() {
        let scheduler = Scheduler::with_limits(SchedulerLimits {
            threads: 2,
            max_queued: 4096,
            max_retained: 16,
        })
        .unwrap();
        for index in 0..64 {
            let id = scheduler.submit("noop", |_ctx| Ok(Vec::new())).unwrap();
            scheduler.wait(id, Duration::from_secs(5)).unwrap();
            let _ = index;
        }
        assert!(scheduler.tracked_tasks() <= 16);
        assert!(scheduler.tracked_tasks() >= 1);
    }

    #[test]
    fn shutdown_rejects_new_work_and_joins() {
        let scheduler = scheduler(2);
        scheduler.shutdown();
        assert!(scheduler.is_shutdown());
        let err = scheduler.submit("late", |_ctx| Ok(Vec::new())).unwrap_err();
        assert_eq!(err.code(), ErrorCode::NotInitialized);
        // Idempotent.
        scheduler.shutdown();
    }

    #[test]
    fn limits_are_validated() {
        assert!(Scheduler::new(0).is_err());
        assert!(Scheduler::new(65).is_err());
        assert!(
            Scheduler::with_limits(SchedulerLimits {
                threads: 1,
                max_queued: 0,
                max_retained: 16,
            })
            .is_err()
        );
    }
}
