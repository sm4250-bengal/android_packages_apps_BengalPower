package com.bengal.power;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One authenticated transport registration. Packet/control callbacks run on its single I/O worker. */
public final class AccPowerSession implements AutoCloseable {
    private static final class Live {
        final BacpPacket prepare;
        volatile boolean cancelled, authorized;
        boolean readySent, finalComplete, cancelSent;
        long finalSequence;
        Live(BacpPacket prepare) { this.prepare = prepare; }
    }
    private final boolean physicalPolicy;
    private final AccPowerCoordinator core;
    private final AccPowerCoordinator.Timer clock;
    private final AccPowerCoordinator.Participants participants;
    // Carries the deep-check's exact cycle to the same synchronous power job, never a newer live cycle.
    private final ThreadLocal<Live> powerCandidate = new ThreadLocal<>();
    private volatile Executor ioEvents;
    private volatile Consumer<BacpPacket> send;
    private volatile Runnable failedTransport;
    private final Object registrationLock = new Object();
    private volatile long registration;
    private volatile boolean closed;
    private volatile Live live;
    private volatile AccPowerCoordinator.Session session;
    private BacpPacket snapshot;
    private long lastSequence, generation, floor, observedDeadline;
    private int phase, lastEvent;

    public AccPowerSession(boolean physicalPolicy, AccPowerCoordinator.Host host,
            AccPowerCoordinator.Timer timer, AccPowerCoordinator.Participants participants,
            AccPowerCoordinator.DeepCapability deep, Executor preparation, Executor power,
            Executor ioEvents, Consumer<BacpPacket> send, Runnable failedTransport) {
        this.physicalPolicy = physicalPolicy;
        this.clock = Objects.requireNonNull(timer);
        this.participants = Objects.requireNonNull(participants);
        this.ioEvents = Objects.requireNonNull(ioEvents);
        this.send = Objects.requireNonNull(send);
        this.failedTransport = Objects.requireNonNull(failedTransport);
        Objects.requireNonNull(participants); Objects.requireNonNull(deep); Objects.requireNonNull(host);
        AccPowerCoordinator.Host guardedHost = new AccPowerCoordinator.Host() {
            @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) throws Exception {
                return host.acquirePreparationLease(remaining(timeout));
            }
            @Override public long uptimeMillis() { return host.uptimeMillis(); }
            @Override public void sleep(long time) throws Exception {
                Live active = powerCandidate.get(); powerCandidate.remove();
                if (!physicalPolicy || !valid(active) || !active.authorized)
                    throw new IllegalStateException("BACP authority expired before power call");
                host.sleep(time);
            }
            @Override public void wake(long time) throws Exception { powerCandidate.remove(); host.wake(time); }
        };
        AccPowerCoordinator.Timer boundedTimer = new AccPowerCoordinator.Timer() {
            @Override public long monotonicMillis() { return timer.monotonicMillis(); }
            @Override public AccPowerCoordinator.Lease schedule(long timeout, Runnable action) {
                return timer.schedule(remaining(timeout), action);
            }
        };
        core = new AccPowerCoordinator(physicalPolicy, 2000, guardedHost, boundedTimer,
                new AccPowerCoordinator.Participants() {
                    @Override public CompletionStage<Boolean> prepare(AccPowerCoordinator.Cycle cycle) throws Exception {
                        powerCandidate.remove(); // The initial deep check was preparation, not a power job.
                        Live active = live;
                        if (!current(active, cycle)) return CompletableFuture.completedFuture(false);
                        return participants.prepare(cycle).handle((ready, error) -> {
                            boolean accepted = false;
                            try { accepted = error == null && Boolean.TRUE.equals(ready)
                                    && current(active, cycle) && deep.ready(cycle); }
                            catch (Exception unavailable) { /* No readiness. */ }
                            final boolean result = accepted;
                            enqueue(() -> prepared(active, cycle, result));
                            return accepted;
                        });
                    }
                    @Override public void cancel(AccPowerCoordinator.Cycle cycle) {
                        Live active = live;
                        if (active != null && active.prepare.generation == cycle.generation
                                && cycle.session == session) {
                            active.cancelled = true;
                            enqueue(() -> sendCancel(active));
                        }
                        participants.cancel(cycle);
                    }
                }, cycle -> {
                    Live active = live;
                    boolean ready = current(active, cycle) && deep.ready(cycle);
                    if (ready) powerCandidate.set(active); else powerCandidate.remove();
                    return ready;
                }, preparation, power);
    }
    private boolean valid(Live active) {
        long now = clock.monotonicMillis();
        return !closed && active != null && active == live && !active.cancelled
                && now >= 0 && now < active.prepare.deadline;
    }
    private long remaining(long requested) {
        Live active = live;
        long now = clock.monotonicMillis();
        if (closed || active == null || active.cancelled || now < 0 || now >= active.prepare.deadline)
            throw new IllegalStateException("BACP preparation expired");
        return Math.min(requested, active.prepare.deadline - now);
    }
    private boolean current(Live active, AccPowerCoordinator.Cycle cycle) {
        return valid(active) && !cycle.isCancelled() && cycle.session == session
                && cycle.generation == active.prepare.generation;
    }
    private void enqueue(Runnable event) {
        long epoch = registration;
        if (closed) return;
        try { ioEvents.execute(() -> {
            try { if (!closed && epoch == registration) event.run(); }
            catch (RuntimeException failure) { fail(epoch); }
        }); }
        catch (RuntimeException failure) { fail(epoch); }
    }
    /** I/O owner only, after old disconnect cleanup. Reuses the core's power FIFO across sessions. */
    void reopen(Executor events, Consumer<BacpPacket> output, Runnable failed) {
        Objects.requireNonNull(events); Objects.requireNonNull(output); Objects.requireNonNull(failed);
        synchronized (registrationLock) {
            if (!closed || registration == Long.MAX_VALUE) throw new IllegalStateException("registration still active/exhausted");
            registration++;
            ioEvents = events; send = output; failedTransport = failed;
            live = null; session = null; snapshot = null;
            lastSequence = generation = floor = observedDeadline = 0; phase = lastEvent = 0;
            closed = false;
        }
    }
    private void prepared(Live active, AccPowerCoordinator.Cycle cycle, boolean ready) {
        if (active != live || closed) return;
        if (!ready || !current(active, cycle) || lastEvent == BacpPacket.FINAL_RESULT) {
            active.cancelled = true;
            core.cancel(session, active.prepare.generation);
            sendCancel(active);
            return;
        }
        if (active.readySent) return;
        active.readySent = true;
        send.accept(active.prepare.control(BacpPacket.READY, active.prepare.sequence,
                active.prepare.generation, active.prepare.deadline));
    }
    private void sendCancel(Live active) {
        if (closed || active != live || active.cancelSent) return;
        active.cancelSent = true;
        send.accept(active.prepare.control(BacpPacket.CANCEL, active.prepare.sequence, active.prepare.generation, 0));
    }

    /** Only after transport peer authentication. Initializes core floor before publishing START. */
    public void receive(BacpPacket packet) {
        try { accept(packet); }
        catch (RuntimeException violation) { fail(); throw violation; }
    }
    private void accept(BacpPacket packet) {
        if (closed) throw new IllegalStateException("closed registration");
        if (snapshot == null) {
            require(packet.kind == BacpPacket.SNAPSHOT, "SNAPSHOT first");
            snapshot = packet; lastSequence = packet.sequence; generation = floor = packet.generation;
            phase = packet.phase; observedDeadline = phase == 3 ? -1 : 0;
            session = core.connect("BACP registration", floor);
            if (closed) { core.disconnected(session); return; }
            send.accept(packet.control(BacpPacket.START, lastSequence, generation, 0));
            return;
        }
        require(snapshot.sameConnection(packet), "nonce mismatch");
        if (packet.kind == BacpPacket.AUTHORIZE) {
            Live active = live;
            require(valid(active) && active.readySent && active.finalComplete && !active.authorized
                    && lastEvent == BacpPacket.FINAL_RESULT && packet.sequence == lastSequence
                    && packet.sequence == active.finalSequence && packet.generation == active.prepare.generation
                    && packet.deadline == active.prepare.deadline, "unmatched AUTHORIZE");
            active.authorized = true;
            if (physicalPolicy) core.finalHostWrite(session, packet.generation, true);
            return;
        }
        require(packet.kind == BacpPacket.GRACE || packet.kind == BacpPacket.PREPARE
                || packet.kind == BacpPacket.FINAL_RESULT || packet.kind == BacpPacket.RESUME, "server kind");
        require(lastSequence != Long.MAX_VALUE && packet.sequence == lastSequence + 1, "event sequence");
        lastSequence = packet.sequence; lastEvent = packet.kind;
        switch (packet.kind) {
            case BacpPacket.GRACE:
                require(packet.generation > generation && (phase == 0 || phase == 1), "GRACE cycle");
                generation = packet.generation; phase = 2; observedDeadline = 0;
                if (physicalPolicy) participants.grace(session, generation);
                break;
            case BacpPacket.PREPARE:
                require(packet.generation == generation && phase == 2, "PREPARE cycle");
                phase = 3; observedDeadline = packet.deadline;
                if (generation <= floor) break; // Snapshot OFF remains observation only.
                long now = clock.monotonicMillis();
                require(now >= 0 && packet.deadline > now && packet.deadline - now <= 2000, "PREPARE deadline");
                live = new Live(packet);
                if (physicalPolicy) core.beginOff(session, generation);
                break;
            case BacpPacket.FINAL_RESULT:
                require(packet.generation == generation && phase == 3, "FINAL cycle");
                phase = 4;
                if (live == null) {
                    require(observedDeadline == -1 || packet.deadline == 0 || packet.deadline == observedDeadline,
                            "historical deadline");
                    break;
                }
                require(packet.deadline == live.prepare.deadline, "FINAL deadline");
                live.finalComplete = packet.phase == 1; live.finalSequence = packet.sequence;
                if (packet.phase == 2) {
                    live.cancelled = true;
                    core.finalHostWrite(session, generation, false);
                }
                break; // Complete alone deliberately grants no core permission.
            case BacpPacket.RESUME:
                require(packet.generation == generation && phase >= 2, "RESUME cycle");
                require(cancelStaging(session, generation), "grace cancellation failed");
                if (live != null) { live.cancelled = true; core.cancel(session, generation); }
                live = null; phase = 1; break;
            default: throw new AssertionError();
        }
    }
    public void cancel() {
        if (!cancelStaging(session, generation)) { fail(); return; }
        Live active = live;
        if (active == null) {
            try { if (snapshot != null) send.accept(snapshot.control(BacpPacket.CLOSE, 0, 0, 0)); }
            finally { fail(); }
            return;
        }
        active.cancelled = true;
        core.cancel(session, active.prepare.generation);
        sendCancel(active);
    }
    public AccPowerCoordinator.State state() { return core.state(); }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    private void fail() { fail(registration); }
    private void fail(long epoch) {
        AccPowerCoordinator.Session token;
        long cancelledGeneration;
        Runnable failed;
        synchronized (registrationLock) {
            if (epoch != registration) return;
            token = session; failed = failedTransport;
            cancelledGeneration = generation;
            closed = true;
            Live active = live; if (active != null) active.cancelled = true;
        }
        cancelStaging(token, cancelledGeneration);
        try { failed.run(); }
        finally { if (token != null) core.disconnected(token); }
    }
    /** Pure local authority revocation; transport calls this before descriptor/framework cleanup. */
    void invalidate() {
        AccPowerCoordinator.Session token;
        long cancelledGeneration;
        synchronized (registrationLock) {
            closed = true;
            token = session; cancelledGeneration = generation;
            Live active = live; if (active != null) active.cancelled = true;
        }
        cancelStaging(token, cancelledGeneration);
    }
    private boolean cancelStaging(AccPowerCoordinator.Session token, long gen) {
        if (!physicalPolicy || token == null) return true;
        try { participants.cancelGrace(token, gen); return true; }
        catch (RuntimeException failure) { return false; } // Never suppress descriptor/core cleanup.
    }
    @Override public void close() {
        invalidate();
        AccPowerCoordinator.Session token = session;
        if (token != null) core.disconnected(token);
    }
}
