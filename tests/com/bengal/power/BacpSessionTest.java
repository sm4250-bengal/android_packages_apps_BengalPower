package com.bengal.power;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BacpSessionTest {
    private static final byte[] SESSION = new byte[16], REGISTRATION = new byte[16];
    static { Arrays.fill(SESSION, (byte) 1); Arrays.fill(REGISTRATION, (byte) 2); }
    private static int count;
    private static void check(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | SecurityException expected) { return; }
        throw new AssertionError("accepted invalid input");
    }
    private static void test(String name, Runnable body) { body.run(); count++; System.out.println("PASS BACP " + name); }
    private static BacpPacket packet(int kind, int phase, long seq, long gen, long deadline) {
        return new BacpPacket(kind, phase, SESSION, REGISTRATION, seq, gen, deadline);
    }
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable action) { queue.add(action); }
        void run() {
            for (;;) {
                Runnable action;
                synchronized (this) { action = queue.poll(); }
                if (action == null) return;
                action.run();
            }
        }
    }
    private static final class Fixture {
        final Manual prepare = new Manual(), power = new Manual(), io = new Manual();
        final List<BacpPacket> sent = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final CompletableFuture<Boolean> participant = new CompletableFuture<>();
        final AccPowerSession session;
        long now = 1000;
        int held, failed, prepared, graces, graceCancels;
        boolean deep = true, throwGraceCancel;
        Runnable sleeping = () -> {};
        Runnable sampling = () -> {};
        Fixture(boolean enabled) {
            AccPowerCoordinator.Host host = new AccPowerCoordinator.Host() {
                @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) { held++; return () -> held--; }
                @Override public long uptimeMillis() { sampling.run(); return 50; }
                @Override public void sleep(long time) { check(held == 0, "held preparation lease"); calls.add("sleep"); sleeping.run(); }
                @Override public void wake(long time) { calls.add("wake"); }
            };
            AccPowerCoordinator.Timer timer = new AccPowerCoordinator.Timer() {
                @Override public long monotonicMillis() { return now; }
                @Override public AccPowerCoordinator.Lease schedule(long timeout, Runnable action) { return () -> {}; }
            };
            session = new AccPowerSession(enabled, host, timer, new AccPowerCoordinator.Participants() {
                @Override public void grace(AccPowerCoordinator.Session token, long gen) { graces++; }
                @Override public void cancelGrace(AccPowerCoordinator.Session token, long gen) {
                    if (token != null) graceCancels++;
                    if (throwGraceCancel) throw new IllegalStateException("participant cancellation failure");
                }
                @Override public CompletableFuture<Boolean> prepare(AccPowerCoordinator.Cycle cycle) { prepared++; return participant; }
                @Override public void cancel(AccPowerCoordinator.Cycle cycle) {}
            }, cycle -> deep, prepare, power, io, sent::add, () -> failed++);
        }
        void snapshot(int phase, long gen) { session.receive(packet(BacpPacket.SNAPSHOT, phase, 10, gen, 0)); }
        void begin() {
            snapshot(1, 0);
            session.receive(packet(BacpPacket.GRACE, 0, 11, 1, 0));
            session.receive(packet(BacpPacket.PREPARE, 0, 12, 1, 2500)); prepare.run();
        }
        void ready() { participant.complete(true); io.run(); }
        void complete() { session.receive(packet(BacpPacket.FINAL_RESULT, 1, 13, 1, 2500)); }
        void authorize() { session.receive(packet(BacpPacket.AUTHORIZE, 0, 13, 1, 2500)); }
        long messages(int kind) { return sent.stream().filter(p -> p.kind == kind).count(); }
        void end() { session.close(); io.run(); power.run(); check(held == 0, "lease leak"); }
    }
    public static void main(String[] args) throws Exception {
        Map<String, BacpPacket> golden = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split(" "); byte[] bytes = new byte[64];
            check(fields[1].length() == 128, "golden length");
            for (int i = 0; i < 64; i++) bytes[i] = (byte) Integer.parseInt(fields[1].substring(i * 2, i * 2 + 2), 16);
            BacpPacket decoded = BacpPacket.decode(bytes); check(Arrays.equals(bytes, decoded.encode()), "golden round trip");
            golden.put(fields[0], decoded);
        }
        check(golden.size() == 12 && golden.get("final_complete").phase == 1 && golden.get("final_failed").phase == 2,
                "independent twelve-vector census");
        test("12 immutable native golden vectors", () -> check(golden.get("prepare").deadline == 123456, "big endian"));
        test("length/kind/phase/reserved/nonce/signed overflow", () -> {
            reject(() -> BacpPacket.decode(new byte[65])); reject(() -> BacpPacket.decode(new byte[63]));
            for (int index : new int[] {0, 4, 5, 6, 7, 40, 48, 56}) {
                byte[] bytes = golden.get("prepare").encode(); bytes[index] = (byte) 255;
                reject(() -> BacpPacket.decode(bytes));
            }
            byte[] zero = golden.get("prepare").encode(); Arrays.fill(zero, 8, 24, (byte) 0);
            reject(() -> BacpPacket.decode(zero));
            reject(() -> packet(BacpPacket.SNAPSHOT, 5, 0, 0, 0));
            reject(() -> packet(BacpPacket.CLOSE, 0, 1, 0, 0));
            reject(() -> packet(BacpPacket.FINAL_RESULT, 0, 1, 1, 0));
        });
        test("exact UID/domain/levelFrom all and canonical category ranges", () -> {
            BacpPeerAuth.authenticateOwnIdentity(10255, "u:r:bengal_power_app:s0:c255.c256,c512,c768");
            for (String context : new String[] {null, "u:r:bengal_power_app:s0", "u:r:bengal_power_app:s0:c1,c256,c512,c768",
                    "u:r:bengal_power_app_extra:s0:c255,c256,c512,c768", "u:r:bengal_power_app:s0:c255.c512,c768",
                    "u:r:bengal_power_app:s0:c0255,c256,c512,c768", "u:r:bengal_power_app:s1:c255,c256,c512,c768",
                    "u:r:bengal_power_app:s0:c255,c255,c256,c512,c768"}) {
                reject(() -> BacpPeerAuth.authenticateOwnIdentity(10255, context));
            }
            for (int uid : new int[] {0, 1000, 2000, 9999, 20000, 110255})
                reject(() -> BacpPeerAuth.authenticateOwnIdentity(uid, "u:r:bengal_power_app:s0:c255,c256,c512,c768"));
            reject(() -> BacpPeerAuth.authenticateOwnIdentity(10255, "u:r:platform_app:s0:c255,c256,c512,c768"));
        });
        test("START fence and ready+Complete alone cannot sleep", () -> {
            Fixture f = new Fixture(true); f.begin(); check(f.sent.get(0).kind == BacpPacket.START, "START first");
            f.ready(); check(f.messages(BacpPacket.READY) == 1, "actual readiness");
            f.complete(); f.power.run(); check(f.calls.isEmpty(), "Complete authorized sleep");
            f.authorize(); f.power.run(); check(f.calls.equals(List.of("sleep")), "authorization did not sleep");
            f.session.receive(packet(BacpPacket.RESUME, 0, 14, 1, 0)); f.power.run();
            check(f.calls.equals(List.of("sleep", "wake")), "resume"); f.end();
        });
        test("snapshot historical prepare/final never replay", () -> {
            Fixture f = new Fixture(true); f.snapshot(2, 1);
            f.session.receive(packet(BacpPacket.PREPARE, 0, 11, 1, 2500));
            f.session.receive(packet(BacpPacket.FINAL_RESULT, 1, 12, 1, 0));
            f.prepare.run(); f.ready(); f.power.run();
            check(f.prepared == 0 && f.messages(BacpPacket.READY) == 0 && f.calls.isEmpty(), "snapshot replay"); f.end();
        });
        test("observation and missing deep produce no READY/sleep", () -> {
            Fixture f = new Fixture(false); f.begin(); f.ready(); f.complete(); f.power.run();
            check(f.prepared == 0 && f.messages(BacpPacket.READY) == 0 && f.calls.isEmpty(), "observation activated"); f.end();
            Fixture g = new Fixture(true); g.deep = false; g.begin(); g.ready(); g.complete(); g.power.run();
            check(g.messages(BacpPacket.READY) == 0 && g.calls.isEmpty(), "missing deep ready"); g.end();
        });
        test("late readiness and failed final cannot authorize", () -> {
            Fixture f = new Fixture(true); f.begin(); f.complete(); f.ready();
            check(f.messages(BacpPacket.READY) == 0, "late READY"); reject(f::authorize); f.end();
            Fixture g = new Fixture(true); g.begin(); g.ready();
            g.session.receive(packet(BacpPacket.FINAL_RESULT, 2, 13, 1, 2500)); reject(g::authorize); g.end();
        });
        test("old nonce, sequence gap, duplicate authorization", () -> {
            Fixture f = new Fixture(true); f.begin();
            reject(() -> f.session.receive(packet(BacpPacket.FINAL_RESULT, 1, 14, 1, 2500))); f.end();
            Fixture g = new Fixture(true); g.begin(); byte[] bytes = packet(BacpPacket.FINAL_RESULT, 1, 13, 1, 2500).encode();
            bytes[24] ^= 1; reject(() -> g.session.receive(BacpPacket.decode(bytes))); g.end();
            Fixture h = new Fixture(true); h.begin(); h.ready(); h.complete(); h.authorize(); reject(h::authorize); h.end();
        });
        test("authorization deadline at receipt and queued power call", () -> {
            Fixture f = new Fixture(true); f.begin(); f.ready(); f.complete(); f.now = 2500; reject(f::authorize); f.end();
            Fixture g = new Fixture(true); g.begin(); g.ready(); g.complete(); g.authorize(); g.now = 2500;
            g.power.run(); check(g.calls.isEmpty(), "expired queued authority"); g.end();
        });
        test("connection death cancels queued authority and stale callback", () -> {
            Fixture f = new Fixture(true); f.begin(); f.session.close(); f.ready(); f.power.run();
            check(f.messages(BacpPacket.READY) == 0 && f.calls.isEmpty(), "dead callback"); f.end();
            Fixture g = new Fixture(true); g.begin(); g.ready(); g.complete(); g.authorize(); g.session.close();
            g.power.run(); check(g.calls.isEmpty(), "sleep after death"); g.end();
        });
        test("death during actual in-flight sleep compensates after return", () -> {
            Fixture f = new Fixture(true); f.begin(); f.ready(); f.complete(); f.authorize();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            f.sleeping = () -> { entered.countDown(); await(release); };
            Thread worker = new Thread(() -> { try { f.power.run(); } finally { done.countDown(); } });
            worker.start(); await(entered); f.session.close();
            check(f.calls.equals(List.of("sleep")), "wake preceded sleep return");
            release.countDown(); await(done);
            check(f.calls.equals(List.of("sleep", "wake")), "missing in-flight compensation"); f.end();
        });
        test("ON cancels preparation; reconnect snapshot remains a floor", () -> {
            Fixture f = new Fixture(true); f.begin();
            f.session.receive(packet(BacpPacket.RESUME, 0, 13, 1, 0)); f.ready(); f.power.run();
            check(f.calls.isEmpty() && f.messages(BacpPacket.READY) == 0, "499ms cancellation replay"); f.end();
            Fixture g = new Fixture(true); g.snapshot(3, 1);
            g.session.receive(packet(BacpPacket.FINAL_RESULT, 1, 11, 1, 0));
            g.prepare.run(); g.power.run(); check(g.calls.isEmpty() && g.prepared == 0, "reopen replay"); g.end();
        });
        test("session nonce and event sequence exhaustion deny", () -> {
            Fixture f = new Fixture(true); f.begin(); byte[] bytes = packet(BacpPacket.FINAL_RESULT, 1, 13, 1, 2500).encode();
            bytes[8] ^= 1; reject(() -> f.session.receive(BacpPacket.decode(bytes))); f.end();
            Fixture g = new Fixture(true); g.session.receive(packet(BacpPacket.SNAPSHOT, 1, Long.MAX_VALUE, 0, 0));
            reject(() -> g.session.receive(packet(BacpPacket.GRACE, 0, 0, 1, 0))); g.end();
        });
        test("reconnect retains old wake before new generation-one sleep", () -> {
            Fixture f = new Fixture(true); f.begin(); f.ready(); f.complete(); f.authorize(); f.power.run();
            f.session.close(); // Old wake reserved but deliberately not dispatched by the manual backend yet.
            f.session.reopen(f.io, f.sent::add, () -> f.failed++);
            f.begin(); f.io.run(); f.complete(); f.authorize(); f.power.run();
            check(f.calls.equals(List.of("sleep", "wake", "sleep")), "old wake reordered across registrations"); f.end();
        });
        test("old power job cannot borrow new registration deadline/authority", () -> {
            Fixture f = new Fixture(true); f.begin(); f.ready(); f.complete(); f.authorize();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger samples = new java.util.concurrent.atomic.AtomicInteger();
            f.sampling = () -> { if (samples.incrementAndGet() == 1) { entered.countDown(); await(release); } };
            Thread worker = new Thread(() -> { try { f.power.run(); } finally { done.countDown(); } });
            worker.start(); await(entered); f.session.close(); f.session.reopen(f.io, f.sent::add, () -> f.failed++);
            f.now = 2600; f.snapshot(1, 0);
            f.session.receive(packet(BacpPacket.GRACE, 0, 11, 1, 0));
            f.session.receive(packet(BacpPacket.PREPARE, 0, 12, 1, 3500)); f.prepare.run(); f.io.run();
            f.session.receive(packet(BacpPacket.FINAL_RESULT, 1, 13, 1, 3500));
            f.session.receive(packet(BacpPacket.AUTHORIZE, 0, 13, 1, 3500));
            release.countDown(); await(done);
            check(f.calls.equals(List.of("wake", "sleep")), "old job borrowed new live authority"); f.end();
        });
        test("old failure callback return cannot disconnect a reopened session", () -> {
            Fixture f = new Fixture(true); f.session.close();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            f.session.reopen(f.io, f.sent::add, () -> { entered.countDown(); await(release); }); f.begin();
            Thread worker = new Thread(() -> {
                try { reject(() -> f.session.receive(packet(BacpPacket.RESUME, 0, 99, 1, 0))); }
                finally { done.countDown(); }
            });
            worker.start(); await(entered); f.session.close();
            f.session.reopen(f.io, f.sent::add, () -> f.failed++); f.begin(); f.ready(); f.complete(); f.authorize();
            release.countDown(); await(done); f.power.run();
            check(f.calls.equals(List.of("sleep")), "old failure return killed current authority"); f.end();
        });
        test("live grace stages; resume before PREPARE cancels", () -> {
            Fixture f = new Fixture(true); f.snapshot(1, 0);
            check(f.graces == 0, "snapshot staged preparation");
            f.session.receive(packet(BacpPacket.GRACE, 0, 11, 1, 0));
            check(f.graces == 1 && f.prepared == 0 && f.held == 0, "grace not separate from PREPARE");
            f.session.receive(packet(BacpPacket.RESUME, 0, 12, 1, 0));
            check(f.graceCancels == 1 && f.calls.isEmpty(), "early RESUME missed rollback"); f.end();
        });
        test("grace death invalidation and observer isolation", () -> {
            Fixture f = new Fixture(true); f.snapshot(1, 0);
            f.session.receive(packet(BacpPacket.GRACE, 0, 11, 1, 0)); f.session.invalidate();
            check(f.graceCancels == 1 && f.prepared == 0, "death missed grace cancellation"); f.end();
            Fixture observer = new Fixture(false); observer.begin(); observer.end();
            check(observer.graces == 0 && observer.graceCancels == 0, "observer invoked participants");
        });
        test("snapshot grace and malformed live event never stage", () -> {
            Fixture f = new Fixture(true); f.snapshot(2, 1);
            check(f.graces == 0, "historical GRACE replayed");
            reject(() -> f.session.receive(packet(BacpPacket.GRACE, 0, 99, 2, 0)));
            check(f.graces == 0 && f.calls.isEmpty(), "invalid GRACE staged"); f.end();
        });
        test("throwing grace cleanup cannot suppress core cancellation", () -> {
            Fixture f = new Fixture(true); f.begin(); f.ready(); f.complete(); f.authorize();
            f.throwGraceCancel = true; f.session.invalidate(); f.session.close(); f.power.run();
            check(f.calls.isEmpty() && f.held == 0, "throwing staging cleanup bypassed core close");
        });
        System.out.println("PASS " + count + " BACP tests");
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "latch did not complete"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
