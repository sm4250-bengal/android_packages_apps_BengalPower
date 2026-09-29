package com.bengal.power;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Offline tests execute the production class; no Android stubs, wall-clock sleeps or mock policy. */
public final class AccPowerCoordinatorTest {
    private static int tests;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "latch watchdog expired"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void test(String name, Runnable body) {
        body.run();
        tests++;
        System.out.println("PASS " + name);
    }
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable task) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException();
            queue.add(task);
        }
        void runAll() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    private static final class Owned implements AccPowerCoordinator.Lease {
        int releases;
        @Override public void release() { check(++releases == 1, "double release"); }
    }
    private static final class Fixture implements AccPowerCoordinator.Host,
            AccPowerCoordinator.Timer, AccPowerCoordinator.Participants {
        final Manual prep = new Manual(), power = new Manual();
        final List<Owned> leases = new ArrayList<>(), deadlines = new ArrayList<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final List<CompletableFuture<Boolean>> ready = new ArrayList<>();
        final List<AccPowerCoordinator.Cycle> cycles = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        AccPowerCoordinator coordinator;
        AccPowerCoordinator.Session session;
        boolean deep = true, throwPrepare, throwLease, throwSleep, throwWake;
        long uptime = 123, monotonic;
        Runnable sleepHook = () -> {}, leaseHook = () -> {}, prepareHook = () -> {};
        Fixture() { this(true, null); }
        Fixture(boolean enabled, Executor powerBackend) {
            coordinator = new AccPowerCoordinator(enabled, 1000, this, this, this,
                    cycle -> deep, prep, powerBackend == null ? power : powerBackend);
            session = coordinator.connect("daemon-boot-A", 0);
        }
        @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) {
            check(timeout == 1000, "bounded lease");
            if (throwLease) throw new IllegalStateException("lease acquisition failed");
            Owned owned = new Owned(); leases.add(owned); leaseHook.run(); return owned;
        }
        @Override public long uptimeMillis() { return uptime++; }
        @Override public void sleep(long time) {
            check(leases.stream().allMatch(l -> l.releases == 1), "lease held over sleep");
            calls.add("sleep:" + time);
            sleepHook.run();
            if (throwSleep) throw new IllegalStateException("uncertain sleep RPC result");
        }
        @Override public void wake(long time) {
            calls.add("wake:" + time);
            if (throwWake) throw new IllegalStateException("uncertain wake RPC result");
        }
        @Override public AccPowerCoordinator.Lease schedule(long timeout, Runnable callback) {
            Owned owned = new Owned(); deadlines.add(owned); timeouts.add(callback); return owned;
        }
        @Override public long monotonicMillis() { return monotonic; }
        @Override public CompletableFuture<Boolean> prepare(AccPowerCoordinator.Cycle cycle) {
            prepareHook.run();
            if (throwPrepare) throw new IllegalStateException("participant start failed");
            cycles.add(cycle);
            CompletableFuture<Boolean> future = new CompletableFuture<>(); ready.add(future); return future;
        }
        @Override public void cancel(AccPowerCoordinator.Cycle cycle) {
            check(cycle.isCancelled(), "cancel notification before invalidation");
        }
        void begin(long generation) { coordinator.beginOff(session, generation); prep.runAll(); }
        void finalOk(long generation) { coordinator.finalHostWrite(session, generation, true); }
        void commit(long generation) { begin(generation); ready.get(ready.size() - 1).complete(true); finalOk(generation); }
        void none() { power.runAll(); check(calls.isEmpty(), "unexpected power action " + calls); }
        void released() {
            check(leases.stream().allMatch(l -> l.releases == 1), "leaked preparation lease");
            check(deadlines.stream().allMatch(l -> l.releases == 1), "leaked deadline");
        }
        void end() { coordinator.close(); power.runAll(); released(); }
    }

    public static void main(String[] args) {
        if (args.length == 1) {
            if (args[0].equals("rejection")) rejectionBoundary();
            else if (args[0].equals("reservation")) reservationBoundary();
            else throw new IllegalArgumentException("unknown focused test");
            return;
        }
        test("default inert and startup observations", () -> {
            Fixture f = new Fixture(false, null);
            f.coordinator.beginOff(f.session, 1); f.finalOk(1); f.none();
            check(f.deadlines.isEmpty(), "disabled work"); f.end();
            Fixture live = new Fixture(); live.none();
            live.session = live.coordinator.connect("snapshot", 99);
            live.coordinator.beginOff(live.session, 99); live.finalOk(99); live.none();
            check(live.deadlines.isEmpty(), "snapshot replay"); live.end();
        });
        test("all three gates and host uptime positive control", () -> {
            Fixture f = new Fixture(); f.begin(1); f.finalOk(1); f.none();
            f.ready.get(0).complete(true); f.uptime = 9000; f.power.runAll();
            check(f.calls.equals(List.of("sleep:9000")), "host uptime at action");
            check(f.coordinator.state() == AccPowerCoordinator.State.SLEEP_REQUESTED, "request only");
            f.released(); f.coordinator.cancel(f.session, 1); f.power.runAll();
            check(f.calls.equals(List.of("sleep:9000", "wake:9001")), "typed wake"); f.end();
        });
        test("ready alone is not final TX permission", () -> {
            Fixture f = new Fixture(); f.begin(1); f.ready.get(0).complete(true); f.none(); f.end();
        });
        test("ON at daemon 499ms boundary cancels before final", () -> {
            Fixture f = new Fixture(); f.begin(1); f.ready.get(0).complete(true);
            f.coordinator.cancel(f.session, 1); f.finalOk(1); f.none(); f.released(); f.end();
        });
        test("duplicates and stale generation cannot repeat sleep", () -> {
            Fixture f = new Fixture(); f.commit(2);
            f.coordinator.beginOff(f.session, 2); f.finalOk(1); f.finalOk(2); f.finalOk(2);
            f.power.runAll(); check(f.calls.size() == 1, "duplicate sleep");
            f.coordinator.cancel(f.session, 1); f.power.runAll(); check(f.calls.size() == 1, "old wake"); f.end();
        });
        test("failed final is terminal even after successful duplicate", () -> {
            Fixture f = new Fixture(); f.begin(1); f.ready.get(0).complete(true);
            f.coordinator.finalHostWrite(f.session, 1, false); f.finalOk(1); f.none(); f.released(); f.end();
        });
        test("old session and reconnect snapshot never replay", () -> {
            Fixture f = new Fixture(); f.begin(1); AccPowerCoordinator.Session old = f.session;
            f.session = f.coordinator.connect("daemon-boot-A", 1);
            f.ready.get(0).complete(true); f.coordinator.finalHostWrite(old, 1, true);
            f.coordinator.beginOff(old, 2); f.coordinator.beginOff(f.session, 1); f.finalOk(1); f.none();
            f.commit(2); f.power.runAll(); check(f.calls.size() == 1, "fresh session live event"); f.end();
        });
        test("stale preparation completion", () -> {
            Fixture f = new Fixture(); f.begin(1); f.begin(2); f.finalOk(2);
            f.ready.get(0).complete(true); f.none(); f.ready.get(1).complete(true);
            f.power.runAll(); check(f.calls.size() == 1, "current preparation only"); f.end();
        });
        test("preparation negative and exceptional completion", () -> {
            for (boolean exception : new boolean[] {false, true}) {
                Fixture f = new Fixture(); f.begin(1); f.finalOk(1);
                if (exception) f.ready.get(0).completeExceptionally(new IllegalStateException());
                else f.ready.get(0).complete(false);
                f.none(); f.released(); f.end();
            }
        });
        test("deep missing and revoked before queued action", () -> {
            Fixture f = new Fixture(); f.deep = false; f.begin(1); f.finalOk(1); f.none();
            check(f.coordinator.state() == AccPowerCoordinator.State.TARGET_UNAVAILABLE, "deep missing"); f.end();
            Fixture g = new Fixture(); g.commit(1); g.deep = false; g.none(); g.released(); g.end();
        });
        test("ON during preparation and late lease acquisition", () -> {
            Fixture f = new Fixture(); f.prepareHook = () -> f.coordinator.cancel(f.session, 1);
            f.begin(1); f.ready.get(0).complete(true); f.finalOk(1); f.none(); f.released(); f.end();
            Fixture g = new Fixture(); g.leaseHook = () -> g.coordinator.cancel(g.session, 1);
            g.begin(1); g.finalOk(1); g.none(); g.released(); g.end();
        });
        test("ON after queue before sleep entry", () -> {
            Fixture f = new Fixture(); f.commit(1); f.coordinator.cancel(f.session, 1);
            f.none(); f.released(); f.end();
        });
        test("timeout while actor delayed and completion delayed", () -> {
            Fixture f = new Fixture(); f.coordinator.beginOff(f.session, 1);
            f.timeouts.get(0).run(); f.prep.runAll(); f.finalOk(1); f.none(); f.released(); f.end();
            Fixture g = new Fixture(); g.begin(1); g.finalOk(1); g.timeouts.get(0).run();
            g.ready.get(0).complete(true); g.none(); g.released(); g.end();
        });
        test("actor startup and lease failures", () -> {
            Fixture f = new Fixture(); f.throwPrepare = true; f.begin(1); f.finalOk(1); f.none(); f.released(); f.end();
            Fixture g = new Fixture(); g.throwLease = true; g.begin(1); g.finalOk(1); g.none(); g.released(); g.end();
        });
        test("expired preparation rejected even when timeout callback is delayed", () -> {
            Fixture f = new Fixture(); f.commit(1); f.monotonic = 1000;
            f.none(); check(f.coordinator.state() == AccPowerCoordinator.State.TIMED_OUT, "deadline recheck");
            f.released(); f.end();
            Fixture g = new Fixture(); g.commit(1); g.monotonic = 999;
            g.power.runAll(); check(g.calls.equals(List.of("sleep:123")), "deadline positive control"); g.end();
        });
        test("executor rejection fails closed and releases", () -> {
            Fixture f = new Fixture(); f.prep.reject = true; f.begin(1); f.finalOk(1); f.none(); f.released(); f.end();
            Fixture g = new Fixture(); g.power.reject = true; g.commit(1); g.none(); g.released(); g.end();
        });
        test("death before queued sleep and stale death after reconnect", () -> {
            Fixture f = new Fixture(); f.commit(1); AccPowerCoordinator.Session old = f.session;
            f.coordinator.disconnected(old); f.none(); f.released();
            f.session = f.coordinator.connect("daemon-boot-B", 1); f.coordinator.disconnected(old);
            f.finalOk(1); f.none(); f.commit(2); f.power.runAll();
            check(f.calls.size() == 1, "stale death killed fresh session"); f.end();
        });
        test("uncertain sleep failure gets compensating wake", () -> {
            Fixture f = new Fixture(); f.throwSleep = true; f.commit(1); f.power.runAll();
            check(f.calls.equals(List.of("sleep:123", "wake:124")), "failed RPC compensation"); f.end();
        });
        test("wake failure invalidates a newer queued OFF", () -> {
            Fixture f = new Fixture(); f.commit(1); f.power.runAll();
            f.throwWake = true; f.coordinator.cancel(f.session, 1); f.commit(2); f.power.runAll();
            check(f.calls.equals(List.of("sleep:123", "wake:124")), "sleep after failed wake");
            f.throwWake = false; f.end();
        });
        test("delayed old wake runs before new OFF sleep", () -> {
            Fixture f = new Fixture(); f.commit(1); f.power.runAll();
            f.coordinator.cancel(f.session, 1); f.commit(2); f.power.runAll();
            check(f.calls.equals(List.of("sleep:123", "wake:124", "sleep:125")), "old wake order");
            f.coordinator.cancel(f.session, 1); f.power.runAll(); check(f.calls.size() == 3, "stale wake"); f.end();
        });
        test("actual in-flight sleep ON and newer OFF serialize on pool backend", () -> inFlight(false));
        test("actual in-flight sleep death compensates after return", () -> inFlight(true));
        test("rejected lane stays terminal while failure notification is blocked", AccPowerCoordinatorTest::rejectionBoundary);
        test("reservation and FIFO append are atomic while dispatch is delayed", AccPowerCoordinatorTest::reservationBoundary);
        System.out.println("PASS " + tests + " focused tests");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void submit(Object lane, Runnable task) {
        try {
            // Keep the original execute API usable for the red control on the frozen baseline.
            Method append;
            try { append = lane.getClass().getDeclaredMethod("append", Runnable.class); }
            catch (NoSuchMethodException oldApi) {
                append = lane.getClass().getDeclaredMethod("execute", Runnable.class);
            }
            append.setAccessible(true);
            Object dispatch = append.invoke(lane, task);
            if (dispatch != null) ((Runnable) dispatch).run();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Thread start(Runnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try { action.run(); } catch (Throwable caught) { failure.set(caught); }
        });
        thread.start();
        return thread;
    }
    private static void join(Thread thread, AtomicReference<Throwable> failure) {
        try { thread.join(5000); }
        catch (InterruptedException caught) { Thread.currentThread().interrupt(); throw new AssertionError(caught); }
        check(!thread.isAlive(), "thread watchdog expired");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void rejectionBoundary() {
        AtomicInteger submissions = new AtomicInteger(), actions = new AtomicInteger();
        CountDownLatch notifying = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Object lane;
        try {
            Class<?> type = Class.forName(AccPowerCoordinator.class.getName() + "$SerialExecutor");
            Constructor<?> constructor = type.getDeclaredConstructor(Executor.class, Runnable.class);
            constructor.setAccessible(true);
            Executor backend = task -> {
                if (submissions.incrementAndGet() == 1) {
                    throw new java.util.concurrent.RejectedExecutionException("first dispatch only");
                }
                task.run();
            };
            lane = constructor.newInstance(backend, (Runnable) () -> {
                notifying.countDown(); await(release);
            });
        } catch (ReflectiveOperationException caught) { throw new AssertionError(caught); }
        Thread first = start(() -> submit(lane, actions::incrementAndGet), failure);
        try {
            await(notifying);
            // The actual rejection handler has reached its external notification. A second
            // caller must not reopen the lane even though this backend would now accept it.
            submit(lane, actions::incrementAndGet);
            check(actions.get() == 0 && submissions.get() == 1,
                    "rejected lane reopened before failure notification completed");
        } finally { release.countDown(); join(first, failure); }
        submit(lane, actions::incrementAndGet);
        check(actions.get() == 0 && submissions.get() == 1, "terminal lane restarted");
    }
    private static final class InspectQueue extends ArrayDeque<Runnable> {
        private static final long serialVersionUID = 1L;
        final transient Object stateLock;
        final AtomicInteger unlockedAppends = new AtomicInteger();
        InspectQueue(Object stateLock) { this.stateLock = stateLock; }
        @Override public boolean add(Runnable task) {
            // White-box invariant check on the real FIFO, with no production callback seam.
            if (!Thread.holdsLock(stateLock)) unlockedAppends.incrementAndGet();
            return super.add(task);
        }
    }
    private static void reservationBoundary() {
        CountDownLatch dispatching = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Fixture> owner = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Fixture f = new Fixture(true, task -> {
            check(!Thread.holdsLock(field(owner.get().coordinator, "lock")), "backend called under state lock");
            check(!Thread.holdsLock(field(field(owner.get().coordinator, "powerExecutor"), "queue")),
                    "backend called under FIFO lock");
            if (submissions.incrementAndGet() == 2) { dispatching.countDown(); await(release); }
            task.run();
        });
        owner.set(f);
        Object lane = field(f.coordinator, "powerExecutor");
        InspectQueue queue = new InspectQueue(field(f.coordinator, "lock"));
        try {
            Field target = lane.getClass().getDeclaredField("queue");
            target.setAccessible(true); target.set(lane, queue);
        } catch (ReflectiveOperationException caught) { throw new AssertionError(caught); }
        f.commit(1);
        Thread cancelling = start(() -> f.coordinator.cancel(f.session, 1), failure);
        try {
            await(dispatching);
            f.commit(2);
            check(f.calls.equals(List.of("sleep:123")), "new sleep overtook reserved wake");
        } finally { release.countDown(); join(cancelling, failure); }
        check(f.calls.equals(List.of("sleep:123", "wake:124", "sleep:125")), "reserved wake order " + f.calls);
        f.end();
        check(queue.unlockedAppends.get() == 0, "reservation was published separately from FIFO append");
    }

    private static void inFlight(boolean death) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        // Wrap only the backend drain: the coordinator itself must provide serialization.
        Fixture f = new Fixture(true, task -> pool.execute(() -> { try { task.run(); } finally { drained.countDown(); } }));
        try {
            f.sleepHook = () -> { entered.countDown(); await(release); };
            f.commit(1); await(entered);
            if (death) f.coordinator.disconnected(f.session);
            else {
                f.coordinator.cancel(f.session, 1);
                f.commit(2);
            }
            // Returning here while RPC is latched also proves no state lock is held over IPC.
            check(f.calls.equals(List.of("sleep:123")), "wake before sleep returned");
            check(f.leases.get(0).releases == 1 && f.deadlines.get(0).releases == 1,
                    "first cycle resources retained over RPC");
            release.countDown(); await(drained);
            check(f.calls.equals(death ? List.of("sleep:123", "wake:124")
                    : List.of("sleep:123", "wake:124", "sleep:125")), "in-flight compensation order " + f.calls);
            f.coordinator.close();
        } finally {
            release.countDown(); pool.shutdown();
            try { check(pool.awaitTermination(5, TimeUnit.SECONDS), "worker not stopped"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }
        f.released();
    }
}
