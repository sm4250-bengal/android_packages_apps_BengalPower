package com.bengal.power;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class AndroidOwnedPreparationTest {
    private static int count;
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    private static void test(String name, Runnable body) { body.run(); count++; System.out.println("PASS participants " + name); }
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable job) { jobs.add(job); }
        void run() {
            for (;;) { Runnable job; synchronized (this) { job = jobs.poll(); }
                if (job == null) return; job.run(); }
        }
    }
    private static final class Timer implements AccPowerCoordinator.Timer {
        final Map<Long, Runnable> deadlines = new LinkedHashMap<>();
        @Override public long monotonicMillis() { return 100; }
        @Override public AccPowerCoordinator.Lease schedule(long timeout, Runnable callback) {
            deadlines.put(timeout, callback);
            return () -> deadlines.remove(timeout, callback);
        }
        void fire(long timeout) { Runnable action = deadlines.remove(timeout); check(action != null, "missing timer"); action.run(); }
    }
    private static final class Platform implements AndroidOwnedPreparation.Platform {
        final Map<String, AndroidOwnedPreparation.Volume> disks = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        Runnable changed = () -> {}, lost = () -> {}, unmountHook = () -> {};
        boolean focus = true, completeUnmount = true, completeMount = true, failSnapshot;
        int btState = 10;
        boolean btWanted, disableAccepted = true, enableAccepted = true;
        Runnable disableHook = () -> {};
        boolean peripheralsReady = true;
        public void preparePeripherals() { calls.add("peripheral-off"); }
        public boolean peripheralsQuiet() { return peripheralsReady; }
        public void restorePeripherals() { calls.add("peripheral-restore"); }
        public int bluetoothState() { return btState; }
        public boolean bluetoothWanted() { return btWanted; }
        public boolean disableBluetooth() {
            calls.add("bt-disable"); disableHook.run(); return disableAccepted;
        }
        public boolean restoreBluetooth() { calls.add("bt-restore"); return enableAccepted; }
        void volume(String id, boolean usb, int state) { volume(id, id + "-epoch1", usb, state); }
        synchronized void volume(String id, String identity, boolean usb, int state) {
            disks.put(id, new AndroidOwnedPreparation.Volume(id, identity, usb, state));
        }
        synchronized void state(String id, int state) {
            AndroidOwnedPreparation.Volume old = disks.get(id);
            volume(id, old.identity, old.usbPublic, state);
        }
        @Override public void watch(Runnable callback) { changed = callback; }
        @Override public boolean requestTransientFocus(Runnable callback) { calls.add("focus"); lost = callback; return focus; }
        @Override public void abandonFocus() { calls.add("abandon"); }
        @Override public synchronized List<AndroidOwnedPreparation.Volume> volumes() {
            if (failSnapshot) throw new IllegalStateException("storage dead");
            return new ArrayList<>(disks.values());
        }
        @Override public void unmount(String id) {
            calls.add("unmount:" + id); unmountHook.run();
            state(id, completeUnmount ? AndroidOwnedPreparation.UNMOUNTED : AndroidOwnedPreparation.TRANSITION);
            changed.run();
        }
        @Override public void mount(String id) {
            calls.add("mount:" + id);
            state(id, completeMount ? AndroidOwnedPreparation.MOUNTED : AndroidOwnedPreparation.TRANSITION);
            changed.run();
        }
    }
    private static final class Fixture {
        final Manual worker = new Manual(), power = new Manual();
        final Timer timer = new Timer();
        final Platform platform = new Platform();
        final AndroidOwnedPreparation owner;
        final AccPowerCoordinator core;
        final AccPowerCoordinator.Session session;
        int sleep, wake, held, revoked;
        Fixture() {
            owner = new AndroidOwnedPreparation(platform, worker, timer, () -> revoked++);
            core = new AccPowerCoordinator(true, 2000, new AccPowerCoordinator.Host() {
                @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) { held++; return () -> held--; }
                @Override public long uptimeMillis() { return 1; }
                @Override public void sleep(long time) { sleep++; }
                @Override public void wake(long time) { wake++; }
            }, timer, owner, owner::retained, Runnable::run, power);
            session = core.connect("daemon", 0);
        }
        void grace() { owner.grace(session, 1); worker.run(); }
        void prepare() { core.beginOff(session, 1); worker.run(); }
        void cancel() { owner.cancelGrace(session, 1); core.cancel(session, 1); worker.run(); power.run(); }
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    public static void main(String[] args) {
        test("peripheral completion, not request return, gates READY", () -> {
            Fixture f = new Fixture(); f.platform.peripheralsReady = false;
            f.grace(); f.prepare(); f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.sleep == 0, "peripherals still active");
            f.platform.peripheralsReady = true; f.platform.changed.run(); f.worker.run(); f.power.run();
            check(f.sleep == 1, "quiet peripheral observation rejected"); f.cancel();
            check(java.util.Collections.frequency(f.platform.calls, "peripheral-restore") == 1,
                    "peripheral restore ownership");
        });
        test("peripheral reactivation revokes READY", () -> {
            Fixture f = new Fixture(); f.grace(); f.prepare();
            f.platform.peripheralsReady = false;
            f.core.finalHostWrite(f.session, 1, true); f.power.run(); f.worker.run();
            check(f.sleep == 0 && f.revoked > 0, "reactivated peripheral ignored");
        });
        test("Bluetooth starting must stop before READY and restore on wake", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true; f.platform.btState = 14;
            f.grace();
            check(f.platform.calls.contains("bt-disable"), "ACC grace omitted Bluetooth disable");
            f.prepare(); f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.sleep == 0, "BLE_TURNING_ON passed as classic OFF");
            f.platform.btState = 10; f.platform.changed.run(); f.worker.run(); f.power.run();
            check(f.sleep == 1, "observed Bluetooth OFF not adopted");
            f.cancel();
            check(java.util.Collections.frequency(f.platform.calls, "bt-disable") == 1
                    && java.util.Collections.frequency(f.platform.calls, "bt-restore") == 1,
                    "Bluetooth shutdown/restore ownership");
        });
        test("ACC requires physical OFF, not retained BLE", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true; f.platform.btState = 12;
            f.grace(); f.prepare(); f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.sleep == 0, "disable return accepted while profiles still ON");
            f.platform.btState = 15; f.platform.changed.run(); f.worker.run(); f.power.run();
            check(f.sleep == 0, "BLE controller still powered");
            f.platform.btState = 10; f.platform.changed.run(); f.worker.run(); f.power.run();
            check(f.sleep == 1, "physical OFF rejected"); f.cancel();
        });
        test("previously disabled Bluetooth stays disabled", () -> {
            for (int state : new int[] {10, 15}) {
                Fixture f = new Fixture(); f.platform.btState = state;
                f.grace(); f.cancel();
                check(f.platform.calls.contains("bt-disable") == (state == 15)
                        && !f.platform.calls.contains("bt-restore"),
                        "changed user's OFF preference");
            }
        });
        test("disable cancels retry even while adapter temporarily OFF", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true;
            f.grace(); f.cancel();
            check(f.platform.calls.contains("bt-disable") && f.platform.calls.contains("bt-restore"),
                    "OFF gap in crash/restart loop escaped ownership");
        });
        test("Bluetooth shutdown rejection revokes without sleep", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true; f.platform.btState = 12;
            f.platform.disableAccepted = false; f.grace(); f.prepare();
            check(f.revoked > 0 && f.sleep == 0 && f.platform.calls.contains("abandon"), "rejection ignored");
        });
        test("Bluetooth reactivation after READY revokes final host write", () -> {
            Fixture f = new Fixture(); f.grace(); f.prepare(); f.platform.btState = 14;
            f.core.finalHostWrite(f.session, 1, true); f.power.run(); f.worker.run();
            check(f.sleep == 0 && f.revoked > 0, "reactivated Bluetooth ignored");
        });
        test("user switches Bluetooth off during grace; no unwanted restore", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true; f.grace();
            f.platform.btWanted = false; f.cancel();
            check(!f.platform.calls.contains("bt-restore"), "overrode newer user OFF");
        });
        test("cancel during Bluetooth Binder request restores after return", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true; f.platform.btState = 14;
            f.platform.disableHook = () -> f.owner.cancelGrace(f.session, 1);
            f.grace();
            check(f.platform.calls.equals(List.of("focus", "peripheral-off", "bt-disable", "peripheral-restore", "bt-restore", "abandon")),
                    "Bluetooth cancellation order " + f.platform.calls);
            check(f.timer.deadlines.isEmpty(), "Bluetooth ownership leaked");
        });
        test("Bluetooth restore request does not wait for slow controller startup", () -> {
            Fixture f = new Fixture(); f.platform.btWanted = true; f.platform.btState = 14;
            f.grace(); f.cancel();
            check(f.timer.deadlines.isEmpty(), "restart bound to cold controller startup");
            f.platform.btWanted = false; f.platform.btState = 10;
            f.owner.grace(f.session, 2); f.worker.run(); f.owner.cancelGrace(f.session, 2); f.worker.run();
            check(java.util.Collections.frequency(f.platform.calls, "bt-restore") == 1, "old restore repeated");
        });
        test("early grace adopted; only USB public mounted owned", () -> {
            Fixture f = new Fixture();
            f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED);
            f.platform.volume("already-off", true, AndroidOwnedPreparation.UNMOUNTED);
            f.platform.volume("private-userdata", false, AndroidOwnedPreparation.MOUNTED);
            f.grace(); check(f.held == 0 && f.sleep == 0, "grace power action");
            f.prepare(); f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.sleep == 1 && f.held == 0, "prepared did not sleep");
            f.cancel();
            check(f.platform.calls.equals(List.of("focus", "peripheral-off", "unmount:usb", "peripheral-restore", "mount:usb", "abandon")), "wrong ownership " + f.platform.calls);
            check(f.wake == 1 && f.timer.deadlines.isEmpty(), "wake/timer cleanup");
        });
        test("unmount return is not completion", () -> {
            Fixture f = new Fixture(); f.platform.completeUnmount = false;
            f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED);
            f.grace(); f.prepare(); f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.sleep == 0 && f.core.state() == AccPowerCoordinator.State.PREPARING, "return fabricated completion");
            f.platform.state("usb", AndroidOwnedPreparation.UNMOUNTED); f.platform.changed.run(); f.worker.run(); f.power.run();
            check(f.sleep == 1, "actual unmounted not adopted"); f.cancel();
        });
        test("cancel before prepare rolls back grace", () -> {
            Fixture f = new Fixture(); f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED);
            f.grace(); f.cancel();
            check(f.platform.calls.contains("mount:usb") && f.platform.calls.contains("abandon"), "early rollback absent");
            check(f.sleep == 0 && f.held == 0 && f.timer.deadlines.isEmpty(), "early power/timer leak");
        });
        test("cancel before worker means no side effect", () -> {
            Fixture f = new Fixture(); f.owner.grace(f.session, 1); f.owner.cancelGrace(f.session, 1); f.worker.run();
            check(f.platform.calls.isEmpty(), "cancelled work ran");
        });
        test("focus denial never prepares", () -> {
            Fixture f = new Fixture(); f.platform.focus = false; f.grace(); f.prepare();
            check(f.sleep == 0 && f.revoked > 0 && f.platform.calls.equals(List.of("focus", "abandon")), "focus denial ignored");
        });
        test("late old focus callback cannot cancel next owner", () -> {
            Fixture f = new Fixture(); f.grace(); Runnable old = f.platform.lost; f.cancel();
            f.owner.grace(f.session, 2); f.worker.run(); int revoked = f.revoked; old.run();
            check(f.revoked == revoked, "old focus revoked next generation");
            f.owner.cancelGrace(f.session, 2); f.worker.run();
        });
        test("focus loss after ready revokes", () -> {
            Fixture f = new Fixture(); f.grace(); f.prepare(); f.platform.lost.run(); f.worker.run();
            f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.revoked > 0 && f.sleep == 0 && f.platform.calls.contains("abandon"), "focus revocation lost");
        });
        test("replacement identity never remounted", () -> {
            Fixture f = new Fixture(); f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED); f.grace();
            f.platform.volume("usb", "new-incarnation", true, AndroidOwnedPreparation.UNMOUNTED); f.cancel();
            check(!f.platform.calls.contains("mount:usb"), "remounted replacement disk");
        });
        test("new mounted USB after ready denies final check", () -> {
            Fixture f = new Fixture(); f.grace(); f.prepare();
            f.platform.volume("hotplug", true, AndroidOwnedPreparation.MOUNTED);
            f.core.finalHostWrite(f.session, 1, true); f.power.run(); f.worker.run();
            check(f.sleep == 0 && f.revoked > 0, "hotplug ignored");
        });
        test("grace deadline restores without PREPARE", () -> {
            Fixture f = new Fixture(); f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED); f.grace();
            f.timer.fire(AndroidOwnedPreparation.GRACE_MILLIS); f.worker.run();
            check(f.platform.calls.contains("mount:usb") && f.sleep == 0 && f.timer.deadlines.isEmpty(), "grace timeout leak");
        });
        test("rollback deadline poisons new work; late completion cleans", () -> {
            Fixture f = new Fixture(); f.platform.completeMount = false;
            f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED); f.grace(); f.cancel();
            f.timer.fire(AndroidOwnedPreparation.ROLLBACK_MILLIS); f.worker.run();
            check(f.platform.calls.contains("abandon"), "focus held beyond rollback deadline");
            try { f.owner.grace(f.session, 2); throw new AssertionError("poisoned owner accepted"); }
            catch (IllegalStateException expected) { }
            f.platform.state("usb", AndroidOwnedPreparation.MOUNTED); f.platform.changed.run(); f.worker.run();
            check(java.util.Collections.frequency(f.platform.calls, "mount:usb") == 1, "repeated remount");
        });
        test("storage failure releases focus", () -> {
            Fixture f = new Fixture(); f.platform.failSnapshot = true; f.grace();
            check(f.platform.calls.contains("abandon") && f.revoked > 0, "storage failure retained focus");
        });
        test("delayed old grace timeout cannot revoke adopted cycle", () -> {
            Fixture f = new Fixture(); f.grace(); Runnable old = f.timer.deadlines.get(AndroidOwnedPreparation.GRACE_MILLIS);
            f.prepare(); old.run(); f.worker.run();
            f.core.finalHostWrite(f.session, 1, true); f.power.run();
            check(f.sleep == 1 && f.revoked == 0, "adopted cycle hit old grace timeout"); f.cancel();
        });
        test("delayed completed rollback timeout cannot poison next cycle", () -> {
            Fixture f = new Fixture(); f.platform.completeMount = false;
            f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED); f.grace(); f.cancel();
            Runnable old = f.timer.deadlines.get(AndroidOwnedPreparation.ROLLBACK_MILLIS);
            f.platform.state("usb", AndroidOwnedPreparation.MOUNTED); f.platform.changed.run(); f.worker.run();
            old.run(); f.owner.grace(f.session, 2); f.worker.run();
            f.owner.cancelGrace(f.session, 2); f.worker.run();
        });
        test("real in-flight unmount cancellation remounts after return", () -> {
            Fixture f = new Fixture(); f.platform.volume("usb", true, AndroidOwnedPreparation.MOUNTED);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            f.platform.unmountHook = () -> { entered.countDown(); await(release); };
            f.owner.grace(f.session, 1);
            Thread thread = new Thread(() -> { try { f.worker.run(); } finally { done.countDown(); } }); thread.start();
            await(entered); f.owner.cancelGrace(f.session, 1);
            check(!f.platform.calls.contains("mount:usb"), "mount raced unmount borrower");
            release.countDown(); await(done);
            check(f.platform.calls.equals(List.of("focus", "peripheral-off", "unmount:usb", "peripheral-restore", "mount:usb", "abandon")), "inflight rollback order " + f.platform.calls);
        });
        System.out.println("PASS " + count + " focused Android participant cases");
    }
}
