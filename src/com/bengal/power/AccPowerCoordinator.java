package com.bengal.power;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Inert until explicitly enabled and fed authenticated live events by a future system owner. */
public final class AccPowerCoordinator implements AutoCloseable {
    public enum State {
        INERT, OBSERVING, PREPARING, WAITING_FINAL, SLEEP_QUEUED, SLEEP_REQUESTED,
        CANCELLED, TIMED_OUT, TARGET_UNAVAILABLE, FAILED, CLOSED
    }

    /**
     * Called outside coordinator locks. Android WakeLock release uses synchronous Binder;
     * its acquire timeout bounds the hold under a functioning framework, not RPC liveness.
     */
    public interface Lease { void release(); }
    public interface Host {
        Lease acquirePreparationLease(long timeoutMillis) throws Exception;
        long uptimeMillis(); // SystemClock.uptimeMillis(), sampled immediately at each power call.
        void sleep(long uptimeMillis) throws Exception;
        void wake(long uptimeMillis) throws Exception;
    }
    public interface Timer {
        long monotonicMillis();
        Lease schedule(long timeoutMillis, Runnable timeout);
    }
    public interface Participants {
        /** Nonblocking staging for authenticated live GRACE only; snapshots never call this. */
        default void grace(Session session, long generation) { }
        /** Cancel staging even if no PREPARE/Cycle was ever created. */
        default void cancelGrace(Session session, long generation) { }
        CompletionStage<Boolean> prepare(Cycle cycle) throws Exception;
        /** Nonblocking local cancellation notification; participants own bounded rollback. */
        void cancel(Cycle cycle);
    }
    public interface DeepCapability {
        /** Current installed/selected deep capability, not a configuration wish or screen state. */
        boolean ready(Cycle cycle) throws Exception;
    }

    /** Opaque registration epoch. A reconnect always obtains a new object, even for the same daemon. */
    public static final class Session {
        public final String daemonIdentity;
        private Session(String identity) { daemonIdentity = Objects.requireNonNull(identity); }
    }
    public static final class Cycle {
        public final Session session;
        public final long generation;
        private volatile boolean cancelled;
        private boolean finalSeen, hostWriteSucceeded, participantsReady, queued, wakeQueued;
        private long preparationStartMillis;
        private Lease lease, timer;
        private Cycle(Session session, long generation) {
            this.session = session;
            this.generation = generation;
        }
        public boolean isCancelled() { return cancelled; }
    }

    private final Object lock = new Object();
    private final boolean enabled;
    private final long preparationTimeoutMillis;
    private final Host host;
    private final Timer timer;
    private final Participants participants;
    private final DeepCapability deep;
    private final Executor preparationExecutor;
    private final SerialExecutor powerExecutor;
    private Session session;
    private long highestGeneration;
    private Cycle current, inFlight, mayBeAsleep;
    private State state;
    private boolean closed, faulted;

    public AccPowerCoordinator(boolean enabled, long preparationTimeoutMillis, Host host,
            Timer timer, Participants participants, DeepCapability deep,
            Executor preparationExecutor, Executor powerExecutor) {
        if (preparationTimeoutMillis <= 0 || preparationTimeoutMillis > 30_000) {
            throw new IllegalArgumentException("preparation timeout must be 1..30000 ms");
        }
        this.enabled = enabled;
        this.preparationTimeoutMillis = preparationTimeoutMillis;
        this.host = Objects.requireNonNull(host);
        this.timer = Objects.requireNonNull(timer);
        this.participants = Objects.requireNonNull(participants);
        this.deep = Objects.requireNonNull(deep);
        this.preparationExecutor = Objects.requireNonNull(preparationExecutor);
        this.powerExecutor = new SerialExecutor(Objects.requireNonNull(powerExecutor),
                this::executorFailed);
        state = enabled ? State.OBSERVING : State.INERT;
    }

    /** The snapshot is a generation floor only. It never causes preparation, sleep or cold-start wake. */
    public Session connect(String daemonIdentity, long snapshotGeneration) {
        if (snapshotGeneration < 0) throw new IllegalArgumentException("negative generation");
        Session next = new Session(daemonIdentity);
        Cycle old;
        synchronized (lock) {
            if (closed || faulted) throw new IllegalStateException("closed or terminal executor/wake fault");
            old = invalidateLocked(State.OBSERVING);
            session = next;
            highestGeneration = snapshotGeneration;
        }
        cleanup(old);
        wakeOutstanding();
        return next;
    }

    /** Live final-stage preparation request following a confirmed OFF; the daemon owns 500 ms. */
    public void beginOff(Session source, long generation) {
        Cycle old, cycle;
        synchronized (lock) {
            if (!enabled || closed || source == null || source != session
                    || generation <= highestGeneration) return;
            highestGeneration = generation;
            old = invalidateLocked(State.PREPARING);
            cycle = new Cycle(source, generation);
            current = cycle;
        }
        cleanup(old);
        wakeOutstanding();
        try {
            cycle.preparationStartMillis = timer.monotonicMillis();
            Lease deadline = Objects.requireNonNull(timer.schedule(preparationTimeoutMillis,
                    () -> invalidate(cycle, State.TIMED_OUT)));
            boolean keep;
            synchronized (lock) {
                keep = currentLocked(cycle);
                if (keep) cycle.timer = deadline;
            }
            if (!keep) deadline.release();
            preparationExecutor.execute(() -> prepare(cycle));
        } catch (Exception failure) {
            invalidate(cycle, State.FAILED);
        }
    }

    /** The one final result must describe completed host write_all, never an attempted TX/state flag. */
    public void finalHostWrite(Session source, long generation, boolean succeeded) {
        Cycle cycle;
        synchronized (lock) {
            cycle = matchingLocked(source, generation);
            if (cycle == null || cycle.finalSeen) return;
            cycle.finalSeen = true;
            cycle.hostWriteSucceeded = succeeded;
        }
        if (!succeeded) invalidate(cycle, State.FAILED);
        else maybeQueueSleep(cycle);
    }

    /** Confirmed ON or explicit daemon cancellation for exactly this cycle. */
    public void cancel(Session source, long generation) {
        Cycle cycle;
        synchronized (lock) { cycle = matchingLocked(source, generation); }
        if (cycle != null) invalidate(cycle, State.CANCELLED);
    }

    /** Binder death, loss of connection, or participant-owner death: invalidate before returning. */
    public void disconnected(Session source) {
        Cycle old;
        synchronized (lock) {
            if (source == null || source != session) return;
            session = null;
            old = invalidateLocked(State.CANCELLED);
        }
        cleanup(old);
        wakeOutstanding();
    }

    public State state() { synchronized (lock) { return state; } }

    private void prepare(Cycle cycle) {
        try {
            synchronized (lock) { if (!currentLocked(cycle)) return; }
            Lease lease = Objects.requireNonNull(host.acquirePreparationLease(preparationTimeoutMillis));
            boolean keep;
            synchronized (lock) {
                keep = currentLocked(cycle);
                if (keep) cycle.lease = lease;
            }
            if (!keep) { lease.release(); return; }
            if (!deep.ready(cycle)) { invalidate(cycle, State.TARGET_UNAVAILABLE); return; }
            synchronized (lock) { if (!currentLocked(cycle)) return; }
            Objects.requireNonNull(participants.prepare(cycle)).whenComplete((ready, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(ready)) {
                    invalidate(cycle, State.FAILED);
                    return;
                }
                synchronized (lock) {
                    if (!currentLocked(cycle)) return;
                    cycle.participantsReady = true;
                    state = State.WAITING_FINAL;
                }
                maybeQueueSleep(cycle);
            });
        } catch (Exception failure) {
            invalidate(cycle, State.FAILED);
        }
    }

    private boolean currentLocked(Cycle cycle) {
        return enabled && !closed && cycle != null && cycle == current && !cycle.cancelled
                && cycle.session == session;
    }
    private Cycle matchingLocked(Session source, long generation) {
        return currentLocked(current) && source == session && generation == current.generation
                ? current : null;
    }
    private boolean eligibleLocked(Cycle cycle) {
        return currentLocked(cycle) && cycle.hostWriteSucceeded && cycle.participantsReady;
    }
    private void maybeQueueSleep(Cycle cycle) {
        Runnable dispatch;
        synchronized (lock) {
            if (!eligibleLocked(cycle) || cycle.queued) return;
            cycle.queued = true;
            state = State.SLEEP_QUEUED;
            dispatch = powerExecutor.append(() -> sleep(cycle));
        }
        if (dispatch != null) dispatch.run();
    }

    private void sleep(Cycle cycle) {
        try {
            synchronized (lock) { if (!eligibleLocked(cycle)) return; }
            if (!deep.ready(cycle)) { invalidate(cycle, State.TARGET_UNAVAILABLE); return; }
            // Release our blocker before framework sleep. Recheck after these external calls.
            if (!releaseResources(cycle)) { invalidate(cycle, State.FAILED); return; }
            long elapsed = timer.monotonicMillis() - cycle.preparationStartMillis;
            if (elapsed < 0 || elapsed >= preparationTimeoutMillis) {
                invalidate(cycle, State.TIMED_OUT);
                return;
            }
            synchronized (lock) {
                if (!eligibleLocked(cycle)) return;
                inFlight = cycle;
                mayBeAsleep = cycle; // Even a throwing RPC may already have taken effect.
            }
            boolean succeeded = false;
            try {
                host.sleep(host.uptimeMillis());
                succeeded = true;
            } finally {
                boolean compensate;
                synchronized (lock) {
                    inFlight = null;
                    compensate = !succeeded || !currentLocked(cycle);
                    if (compensate) cycle.wakeQueued = true;
                    if (succeeded && !compensate) state = State.SLEEP_REQUESTED;
                }
                if (!succeeded) invalidate(cycle, State.FAILED);
                // This is inside the serial power job: ON during the RPC cannot put wake
                // before sleep, and a later OFF sleep cannot be overtaken by this old wake.
                if (compensate) wake(cycle);
            }
        } catch (Exception failure) {
            invalidate(cycle, State.FAILED);
        }
    }

    private void wakeOutstanding() {
        Runnable dispatch = null;
        synchronized (lock) {
            Cycle asleep = inFlight == null ? mayBeAsleep : null;
            if (asleep != null && !asleep.wakeQueued) {
                asleep.wakeQueued = true;
                dispatch = powerExecutor.append(() -> wake(asleep));
            }
        }
        if (dispatch != null) dispatch.run();
    }
    private void wake(Cycle cycle) {
        synchronized (lock) { if (mayBeAsleep != cycle) return; }
        try {
            host.wake(host.uptimeMillis());
            synchronized (lock) { if (mayBeAsleep == cycle) mayBeAsleep = null; }
        } catch (Exception failure) {
            executorFailed(); // Unknown wake outcome is terminal; the adapter must reconcile it.
        }
    }

    private Cycle invalidateLocked(State reason) {
        Cycle old = current;
        if (old != null) old.cancelled = true;
        current = null;
        state = closed ? State.CLOSED : enabled ? reason : State.INERT;
        return old;
    }
    private void invalidate(Cycle cycle, State reason) {
        synchronized (lock) {
            if (!currentLocked(cycle)) return;
            invalidateLocked(reason);
        }
        cleanup(cycle);
        wakeOutstanding();
    }
    private boolean releaseResources(Cycle cycle) {
        Lease lease, deadline;
        synchronized (lock) {
            lease = cycle.lease;
            deadline = cycle.timer;
            cycle.lease = null;
            cycle.timer = null;
        }
        boolean ok = release(lease);
        return release(deadline) && ok;
    }
    private static boolean release(Lease resource) {
        try { if (resource != null) resource.release(); return true; }
        catch (RuntimeException failure) { return false; }
    }
    private void cleanup(Cycle cycle) {
        if (cycle == null) return;
        releaseResources(cycle);
        try { participants.cancel(cycle); }
        catch (RuntimeException failure) { /* Already invalid; no sleep can follow. */ }
    }
    private void executorFailed() {
        Cycle old;
        synchronized (lock) {
            faulted = true;
            session = null;
            old = invalidateLocked(State.FAILED);
        }
        cleanup(old);
    }
    @Override public void close() {
        Cycle old;
        synchronized (lock) {
            closed = true;
            session = null;
            old = invalidateLocked(State.CLOSED);
        }
        cleanup(old);
        wakeOutstanding();
    }

    /**
     * Append under the coordinator state lock; dispatch only after releasing it. Lock order is
     * state then queue. Neither the backend nor tasks/failure callbacks run under either lock.
     */
    private static final class SerialExecutor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private final Executor backend;
        private final Runnable failed;
        private boolean running, faulted;
        SerialExecutor(Executor backend, Runnable failed) { this.backend = backend; this.failed = failed; }
        private Runnable append(Runnable task) {
            synchronized (queue) {
                if (faulted) return null;
                queue.add(task);
                if (running) return null;
                running = true;
                return this::dispatch;
            }
        }
        private void dispatch() {
            synchronized (queue) { if (faulted) return; }
            try { backend.execute(this::drain); }
            catch (RuntimeException failure) { fail(); }
        }
        private void fail() {
            synchronized (queue) {
                if (faulted) return;
                faulted = true;
                running = false;
                queue.clear();
            }
            failed.run();
        }
        private void drain() {
            while (true) {
                Runnable task;
                synchronized (queue) {
                    if (faulted) return;
                    task = queue.poll();
                    if (task == null) { running = false; return; }
                }
                try { task.run(); }
                catch (RuntimeException failure) { fail(); }
            }
        }
    }
}
