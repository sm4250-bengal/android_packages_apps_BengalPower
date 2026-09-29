package com.bengal.power;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual channel/record/session classes with controlled syscall/Binder boundaries; no timing sleeps. */
public final class BacpChannelTest {
    private static int count;
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void test(String name, Runnable body) { body.run(); count++; System.out.println("PASS channel " + name); }
    private static BacpPacket snapshot() {
        byte[] session = new byte[16], registration = new byte[16]; session[0] = 1; registration[0] = 2;
        return new BacpPacket(BacpPacket.SNAPSHOT, 1, session, registration, 0, 0, 0);
    }
    private static final class Endpoint implements BacpChannel.Endpoint {
        final ArrayBlockingQueue<Boolean> wake = new ArrayBlockingQueue<>(32);
        final ArrayBlockingQueue<BacpPacket> packets = new ArrayBlockingQueue<>(8);
        final CountDownLatch disposed = new CountDownLatch(1), hello = new CountDownLatch(1), start = new CountDownLatch(1);
        final CountDownLatch offering = new CountDownLatch(1), offerReturn = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger(), disposeCount = new AtomicInteger(), offers = new AtomicInteger();
        volatile boolean closed, blockOffer, failOffer, failSend;
        volatile Runnable death;
        volatile Runnable waiting = () -> {};
        volatile Runnable closing = () -> {};
        @Override public void offer(Runnable died) throws Exception {
            offers.incrementAndGet(); death = died; offering.countDown();
            if (blockOffer) await(offerReturn);
            if (failOffer) throw new IOException("rejected offer");
        }
        @Override public void signal() { wake.offer(false); }
        void packet(BacpPacket packet) { packets.add(packet); wake.add(true); }
        @Override public boolean awaitPacket() throws InterruptedException { waiting.run(); return wake.take(); }
        @Override public BacpPacket read() throws IOException {
            BacpPacket packet = packets.poll(); if (packet == null) throw new IOException("EOF"); return packet;
        }
        @Override public void send(BacpPacket packet) throws IOException {
            if (failSend) throw new IOException("send failure");
            check(!closed, "send after endpoint shutdown");
            if (packet.kind == BacpPacket.HELLO) hello.countDown();
            if (packet.kind == BacpPacket.START) start.countDown();
        }
        @Override public void close() { closing.run(); if (!closed) { closed = true; closeCount.incrementAndGet(); } signal(); }
        @Override public void dispose() { disposeCount.incrementAndGet(); disposed.countDown(); }
    }
    private static final class Timer implements AccPowerCoordinator.Timer {
        volatile long now = 100;
        volatile Runnable timeout;
        final CountDownLatch scheduled = new CountDownLatch(1);
        final AtomicInteger leases = new AtomicInteger();
        @Override public long monotonicMillis() { return now; }
        @Override public AccPowerCoordinator.Lease schedule(long delay, Runnable callback) {
            check(delay > 0 && delay <= 2000, "unbounded handshake");
            leases.incrementAndGet(); timeout = callback; scheduled.countDown();
            java.util.concurrent.atomic.AtomicBoolean held = new java.util.concurrent.atomic.AtomicBoolean(true);
            return () -> { if (held.compareAndSet(true, false)) leases.decrementAndGet(); };
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Endpoint endpoint = new Endpoint(), second = new Endpoint();
        final AtomicInteger created = new AtomicInteger();
        final Timer timer = new Timer();
        volatile Thread ioThread;
        final ExecutorService io = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action); ioThread = thread; return thread;
        }), bootstrap = Executors.newSingleThreadExecutor();
        final BacpChannel channel;
        volatile Executor events;
        final AtomicInteger observed = new AtomicInteger();
        volatile java.util.function.Consumer<BacpPacket> observer = packet -> {};
        Fixture() { this(false); }
        Fixture(boolean observe) {
            BacpChannel.Factory factory = () -> created.getAndIncrement() == 0 ? endpoint : second;
            BacpChannel.Sessions sessions = (queue, send, failed) -> {
                events = queue;
                return new AccPowerSession(false, new AccPowerCoordinator.Host() {
                    @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) { throw new AssertionError(); }
                    @Override public long uptimeMillis() { return 1; }
                    @Override public void sleep(long time) { throw new AssertionError("observation sleep"); }
                    @Override public void wake(long time) { throw new AssertionError("cold start wake"); }
                }, timer, new AccPowerCoordinator.Participants() {
                    @Override public CompletableFuture<Boolean> prepare(AccPowerCoordinator.Cycle cycle) { return CompletableFuture.completedFuture(false); }
                    @Override public void cancel(AccPowerCoordinator.Cycle cycle) {}
                }, cycle -> false, Runnable::run, Runnable::run, queue, send, failed);
            };
            channel = observe ? new BacpChannel(factory, sessions, timer, io, bootstrap,
                    packet -> { observed.incrementAndGet(); observer.accept(packet); })
                    : new BacpChannel(factory, sessions, timer, io, bootstrap);
        }
        void open() { channel.open(); await(timer.scheduled); }
        void handshake() { open(); await(endpoint.hello); endpoint.packet(snapshot()); await(endpoint.start); }
        @Override public void close() {
            channel.close(); endpoint.offerReturn.countDown(); io.shutdown(); bootstrap.shutdown();
            try {
                check(io.awaitTermination(5, TimeUnit.SECONDS), "I/O worker leak");
                check(bootstrap.awaitTermination(5, TimeUnit.SECONDS), "bootstrap worker leak");
            } catch (InterruptedException error) { throw new AssertionError(error); }
            check(timer.leases.get() == 0, "handshake timer lease leak");
            check(endpoint.disposeCount.get() == 1, "endpoint disposal count");
            if (created.get() == 2) check(second.disposeCount.get() == 1, "reopened endpoint disposal count");
        }
    }
    public static void main(String[] args) {
        test("64 byte single receive positive control", () -> record(64, false, 0, false));
        test("65/huge/undersize/EOF rejected in one receive", () -> {
            for (int length : new int[] {65, 1_000_000, 63, 0, -1}) record(length, false, 0, true);
        });
        test("ancillary cleanup on valid/oversize/error receive", () -> {
            record(64, false, 64, true); record(65, false, 64, true); record(0, true, 64, true);
        });
        test("all ancillary closes attempted even when first close fails", () -> {
            AtomicInteger attempts = new AtomicInteger();
            try {
                BacpRecordReader.closeAll(new Integer[64], descriptor -> {
                    if (attempts.incrementAndGet() == 1) throw new IOException("first close failed");
                });
                throw new AssertionError("close error hidden");
            } catch (IOException expected) { check(attempts.get() == 64, "remaining ancillary leak"); }
        });
        test("snapshot START then EOF invalidates bounded owner", () -> {
            try (Fixture f = new Fixture()) { f.handshake(); f.endpoint.wake.add(true); await(f.endpoint.disposed); }
        });
        test("deadline shuts down late blocked Binder without retry thread", () -> {
            try (Fixture f = new Fixture()) {
                f.endpoint.blockOffer = true; f.open(); await(f.endpoint.offering);
                f.timer.now = 2100; f.timer.timeout.run(); await(f.endpoint.disposed);
                check(f.endpoint.hello.getCount() == 1, "HELLO before offer return");
                try { f.channel.open(); throw new AssertionError("retried blocked bootstrap"); }
                catch (IllegalStateException expected) { /* Busy bounded lane. */ }
                f.endpoint.offerReturn.countDown();
            }
        });
        test("death links current registration only", () -> {
            try (Fixture f = new Fixture()) { f.handshake(); f.endpoint.death.run(); await(f.endpoint.disposed); }
        });
        test("failed offer and send fail closed", () -> {
            try (Fixture f = new Fixture()) { f.endpoint.failOffer = true; f.open(); await(f.endpoint.disposed); }
            try (Fixture f = new Fixture()) { f.endpoint.failSend = true; f.open(); await(f.endpoint.disposed); }
        });
        test("bounded callback ring rejects overflow", () -> {
            try (Fixture f = new Fixture()) {
                f.handshake(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                f.events.execute(() -> { entered.countDown(); await(release); }); await(entered);
                for (int i = 0; i < 8; i++) f.events.execute(() -> {});
                try { f.events.execute(() -> {}); throw new AssertionError("ring grew"); }
                catch (java.util.concurrent.RejectedExecutionException expected) { /* Bounded. */ }
                release.countDown(); await(f.endpoint.disposed);
            }
        });
        test("local cancellation does not wait for remote handshake", () -> {
            try (Fixture f = new Fixture()) { f.open(); f.channel.disconnect(); await(f.endpoint.disposed); }
        });
        test("explicit reopen fences old death and resets snapshot", () -> {
            try (Fixture f = new Fixture()) {
                f.handshake(); f.channel.disconnect(); await(f.endpoint.disposed);
                CountDownLatch idle = new CountDownLatch(1); f.io.execute(idle::countDown); await(idle);
                f.channel.open(); await(f.second.hello); f.second.packet(snapshot()); await(f.second.start);
                f.endpoint.death.run(); check(!f.second.closed, "old death killed reopened registration");
                f.second.wake.add(true); await(f.second.disposed);
            }
        });
        test("throwing I/O callback closes the current registration", () -> {
            try (Fixture f = new Fixture()) {
                f.handshake(); f.events.execute(() -> { throw new IllegalStateException("callback failure"); });
                await(f.endpoint.disposed);
            }
        });
        test("final disposal waits for an already-running close", () -> {
            try (Fixture f = new Fixture()) {
                f.handshake(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
                f.endpoint.closing = () -> { entered.countDown(); await(release); };
                Thread closer = new Thread(() -> { try { f.channel.disconnect(); } finally { done.countDown(); } });
                closer.start(); await(entered); f.endpoint.signal();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean waitingForCleanup = false;
                    while (f.endpoint.disposeCount.get() == 0 && System.nanoTime() < deadline) {
                        for (StackTraceElement frame : f.ioThread.getStackTrace())
                            if (frame.getClassName().equals("java.util.concurrent.CountDownLatch") && frame.getMethodName().equals("await"))
                                waitingForCleanup = true;
                        if (waitingForCleanup) break;
                        Thread.yield();
                    }
                    check(waitingForCleanup && f.endpoint.disposeCount.get() == 0, "dispose preceded close return");
                } finally { release.countDown(); await(done); }
                await(f.endpoint.disposed);
            }
        });
        test("accepted observation follows START on I/O and excludes rejected packets", () -> {
            try (Fixture f = new Fixture(true)) {
                CountDownLatch observed = new CountDownLatch(1);
                f.observer = packet -> {
                    check(Thread.currentThread() == f.ioThread && f.endpoint.start.getCount() == 0, "observation before START/off I/O");
                    observed.countDown();
                };
                f.handshake(); await(observed);
                f.endpoint.packet(BacpPacket.hello()); await(f.endpoint.disposed);
                check(f.observed.get() == 1, "rejected packet observed as accepted");
            }
        });
        test("throwing observer fails closed without power authority", () -> {
            try (Fixture f = new Fixture(true)) {
                f.observer = packet -> { throw new IllegalStateException("observer failed"); };
                f.handshake(); await(f.endpoint.disposed); check(f.observed.get() == 1, "observer count");
            }
        });
        test("camera revocation follows an in-flight accepted notification", () -> {
            try (Fixture f = new Fixture(true)) {
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
                AtomicInteger permission = new AtomicInteger();
                f.observer = packet -> { entered.countDown(); await(release); permission.set(1); };
                f.channel.onDisconnect(() -> permission.set(0));
                f.handshake(); await(entered);
                Thread closer = new Thread(() -> { try { f.channel.disconnect(); } finally { done.countDown(); } });
                closer.start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (closer.getState() != Thread.State.BLOCKED && done.getCount() != 0
                            && System.nanoTime() < deadline) Thread.yield();
                    check(closer.getState() == Thread.State.BLOCKED && done.getCount() == 1,
                            "revocation raced before accepted callback finished");
                } finally { release.countDown(); await(done); }
                await(f.endpoint.disposed);
                check(permission.get() == 0, "late accepted packet re-enabled camera");
            }
        });
        test("throwing disconnect notification cannot prevent BACP cleanup", () -> {
            try (Fixture f = new Fixture()) {
                f.channel.onDisconnect(() -> { throw new IllegalStateException("camera unavailable"); });
                f.handshake(); f.channel.disconnect(); await(f.endpoint.disposed);
            }
        });
        System.out.println("PASS " + count + " channel tests");
    }
    private static void record(int size, boolean throwsRead, int descriptors, boolean denied) {
        AtomicInteger reads = new AtomicInteger(), closed = new AtomicInteger();
        try {
            BacpPacket result = BacpRecordReader.read(new BacpRecordReader.Input() {
                @Override public int read(byte[] buffer) throws IOException {
                    reads.incrementAndGet(); check(buffer.length == 65, "not MAX_PACKET+1");
                    if (throwsRead) throw new IOException("native error/truncation");
                    byte[] bytes = snapshot().encode(); System.arraycopy(bytes, 0, buffer, 0, bytes.length); return size;
                }
                @Override public boolean closeAncillary() throws IOException {
                    return BacpRecordReader.closeAll(new Integer[descriptors], descriptor -> closed.incrementAndGet());
                }
            });
            check(!denied && result.kind == BacpPacket.SNAPSHOT, "invalid record accepted");
        } catch (IOException expected) { check(denied, "positive record rejected"); }
        check(reads.get() == 1 && closed.get() == descriptors, "readFully or ancillary leak");
    }
}
