package com.bengal.power;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Bounded reusable connection owner. No retries; reopen is explicit, after the old worker exits. */
final class BacpChannel implements AutoCloseable {
    interface Endpoint extends AutoCloseable {
        void offer(Runnable died) throws Exception;
        void signal() throws Exception;
        boolean awaitPacket() throws Exception;
        BacpPacket read() throws Exception;
        void send(BacpPacket packet) throws Exception;
        /** I/O-worker-only final descriptor disposal after shutdown has unblocked poll. */
        void dispose();
        @Override void close();
    }
    interface Factory { Endpoint create() throws Exception; }
    interface Sessions {
        AccPowerSession create(Executor events, Consumer<BacpPacket> send, Runnable failed);
    }
    private final Factory factory;
    private final Sessions sessions;
    private final AccPowerCoordinator.Timer timer;
    private final Executor io, bootstrap;
    private final Consumer<BacpPacket> accepted;
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private final AtomicBoolean bootstrapBusy = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Runnable disconnected = () -> {};
    void onDisconnect(Runnable callback) { disconnected = java.util.Objects.requireNonNull(callback); }
    private AccPowerSession policy; // I/O lane only; the same core serializes power across reconnects.

    BacpChannel(Factory factory, Sessions sessions, AccPowerCoordinator.Timer timer,
            Executor io, Executor bootstrap) {
        this(factory, sessions, timer, io, bootstrap, packet -> {});
    }
    BacpChannel(Factory factory, Sessions sessions, AccPowerCoordinator.Timer timer,
            Executor io, Executor bootstrap, Consumer<BacpPacket> accepted) {
        this.factory = factory; this.sessions = sessions; this.timer = timer;
        this.io = io; this.bootstrap = bootstrap;
        this.accepted = java.util.Objects.requireNonNull(accepted);
    }
    void open() {
        Attempt attempt = new Attempt();
        if (closed || bootstrapBusy.get() || !active.compareAndSet(null, attempt))
            throw new IllegalStateException("BACP owner unavailable/busy");
        if (closed) { active.compareAndSet(attempt, null); throw new IllegalStateException("closed owner"); }
        try { io.execute(attempt::run); }
        catch (RuntimeException failure) { attempt.stop(); active.compareAndSet(attempt, null); throw failure; }
    }
    void disconnect() { Attempt attempt = active.get(); if (attempt != null) attempt.stop(); }
    @Override public void close() { closed = true; disconnect(); }

    private final class Attempt {
        final AtomicBoolean stopped = new AtomicBoolean();
        final Object notifications = new Object();
        final CountDownLatch cleanupDone = new CountDownLatch(1);
        final ArrayBlockingQueue<Runnable> events = new ArrayBlockingQueue<>(8);
        volatile Endpoint endpoint;
        volatile AccPowerSession session;
        volatile AccPowerCoordinator.Lease timeout;
        volatile boolean started;
        boolean helloSent;
        long deadline;

        void event(Runnable action) {
            if (stopped.get()) throw new RejectedExecutionException("closed BACP registration");
            if (!events.offer(action)) { stop(); throw new RejectedExecutionException("BACP event ring full"); }
            Endpoint current = endpoint;
            try { if (current != null) current.signal(); }
            catch (Exception failure) { stop(); }
        }
        void send(BacpPacket packet) {
            if (stopped.get()) throw new IllegalStateException("closed BACP registration");
            try { endpoint.send(packet); }
            catch (Exception failure) { stop(); throw new IllegalStateException("BACP send failed", failure); }
        }
        void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            synchronized (notifications) {
                try { disconnected.run(); } catch (RuntimeException ignored) { }
            }
            // Ancillary notification must not prevent BACP's own revocation.
            // Invalidate descriptor ownership before potentially synchronous participant/WakeLock cleanup.
            try {
                AccPowerSession currentSession = session;
                if (currentSession != null) currentSession.invalidate();
                try { Endpoint current = endpoint; if (current != null) current.close(); }
                finally { if (currentSession != null) currentSession.close(); }
            } finally {
                try {
                    AccPowerCoordinator.Lease currentTimeout = timeout;
                    if (currentTimeout != null) currentTimeout.release();
                } finally { cleanupDone.countDown(); }
            }
        }
        boolean expired() {
            long now = timer.monotonicMillis();
            return now < 0 || now >= deadline;
        }
        void run() {
            try {
                if (stopped.get()) return;
                long now = timer.monotonicMillis();
                if (now < 0 || now > Long.MAX_VALUE - 2000) throw new IllegalStateException("BACP clock overflow");
                deadline = now + 2000;
                Endpoint created = factory.create(); endpoint = created;
                if (stopped.get()) { created.close(); return; }
                if (policy == null) policy = sessions.create(this::event, this::send, this::stop);
                else policy.reopen(this::event, this::send, this::stop);
                session = policy;
                if (stopped.get()) { session.close(); return; }
                long remaining = deadline - timer.monotonicMillis();
                if (remaining <= 0 || remaining > 2000) throw new IllegalStateException("BACP bootstrap deadline");
                timeout = timer.schedule(remaining, () -> { if (!started) stop(); });
                if (stopped.get()) { timeout.release(); return; }
                if (!bootstrapBusy.compareAndSet(false, true)) throw new IllegalStateException("bootstrap occupied");
                try {
                    bootstrap.execute(() -> {
                        try {
                            if (!stopped.get() && !expired()) {
                                created.offer(this::stop);
                                if (!stopped.get()) event(() -> {
                                    if (expired()) stop();
                                    else { send(BacpPacket.hello()); helloSent = true; }
                                });
                            } else stop();
                        } catch (Exception failure) { stop(); }
                        finally { bootstrapBusy.set(false); }
                    });
                } catch (RuntimeException failure) { bootstrapBusy.set(false); throw failure; }
                while (!stopped.get()) {
                    Runnable action;
                    while (!stopped.get() && (action = events.poll()) != null) action.run();
                    if (stopped.get()) break;
                    if (!started && expired()) { stop(); break; }
                    if (!created.awaitPacket()) continue; // Local wake pipe, not a protocol heartbeat.
                    if (stopped.get()) break;
                    if (!started && expired()) { stop(); break; }
                    if (!helloSent) throw new IllegalStateException("BACP packet before HELLO");
                    BacpPacket packet = created.read();
                    session.receive(packet);
                    if (!started) { started = true; timeout.release(); }
                    // Validated event for logging and ancillary camera hold/UI.
                    // This callback does not grant Android sleep authority.
                    synchronized (notifications) {
                        if (!stopped.get()) accepted.accept(packet);
                    }
                }
            } catch (Exception failure) { stop(); }
            finally {
                stop();
                events.clear();
                boolean interrupted = false;
                for (;;) {
                    try { cleanupDone.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                Endpoint current = endpoint; if (current != null) current.dispose();
                if (interrupted) closed = true;
                active.compareAndSet(this, null);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
