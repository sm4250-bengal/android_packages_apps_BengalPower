package com.bengal.power;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One process-lived Android preparation owner; framework operations run only on its serial lane. */
final class AndroidOwnedPreparation implements AccPowerCoordinator.Participants {
    static final int UNMOUNTED = 0, MOUNTED = 1, TRANSITION = 2, GONE = 3;
    static final int MAX_VOLUMES = 64;
    static final long ROLLBACK_MILLIS = 10_000;
    static final long GRACE_MILLIS = 30_000;
    // Internal adapter states, NOT getState(): that masks BLE_TURNING_ON as OFF.
    static final int BT_OFF = 10, BT_BLE_ON = 15;

    static final class Volume {
        final String id, identity;
        final boolean usbPublic;
        final int state;
        Volume(String id, String identity, boolean usbPublic, int state) {
            this.id = Objects.requireNonNull(id);
            this.identity = Objects.requireNonNull(identity);
            this.usbPublic = usbPublic;
            this.state = state;
        }
    }
    interface Platform {
        void watch(Runnable changed) throws Exception;
        boolean requestTransientFocus(Runnable lost) throws Exception;
        void abandonFocus() throws Exception;
        List<Volume> volumes() throws Exception;
        void unmount(String id) throws Exception;
        void mount(String id) throws Exception;
        int bluetoothState() throws Exception;
        boolean bluetoothWanted() throws Exception;
        boolean disableBluetooth() throws Exception;
        boolean restoreBluetooth() throws Exception;
        void preparePeripherals() throws Exception;
        boolean peripheralsQuiet() throws Exception;
        void restorePeripherals() throws Exception;
    }
    private static final class Owned {
        final Volume original;
        boolean issued, unmounted, mountIssued;
        Owned(Volume original) { this.original = original; }
    }
    private static final class Work {
        final AccPowerCoordinator.Session session;
        final long generation;
        volatile AccPowerCoordinator.Cycle cycle;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        final Map<String, Owned> volumes = new LinkedHashMap<>();
        volatile Map<String, String> preparedIdentities = java.util.Collections.emptyMap();
        volatile boolean cancelled, expired;
        final AtomicBoolean graceEnded = new AtomicBoolean();
        final AtomicInteger rollbackOutcome = new AtomicInteger(); // 0 pending, 1 finished, 2 expired
        boolean focusAttempted, focusHeld, captured, rollbackStarted;
        boolean bluetoothCaptured, bluetoothRestore, bluetoothDisableIssued, bluetoothRestoreIssued;
        boolean bluetoothQuietLogged;
        boolean peripheralsIssued, peripheralsRestored;
        volatile boolean ready;
        AccPowerCoordinator.Lease rollbackTimer, graceTimer;
        Work(AccPowerCoordinator.Session session, long generation) {
            this.session = session; this.generation = generation;
        }
    }
    private final Platform platform;
    private final Executor worker;
    private final AccPowerCoordinator.Timer timer;
    private final Runnable revoke;
    private final Consumer<String> events;
    private final AtomicReference<Work> current = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean poisoned;
    private boolean watching;

    AndroidOwnedPreparation(Platform platform, Executor worker,
            AccPowerCoordinator.Timer timer, Runnable revoke) {
        this(platform, worker, timer, revoke, event -> {});
    }
    AndroidOwnedPreparation(Platform platform, Executor worker,
            AccPowerCoordinator.Timer timer, Runnable revoke, Consumer<String> events) {
        this.platform = Objects.requireNonNull(platform);
        this.worker = Objects.requireNonNull(worker);
        this.timer = Objects.requireNonNull(timer);
        this.revoke = Objects.requireNonNull(revoke);
        this.events = Objects.requireNonNull(events);
    }
    private void event(Work work, String message) {
        try { events.accept("Android preparation generation=" + work.generation + " " + message); }
        catch (RuntimeException loggingFailure) { /* Observation never grants or changes authority. */ }
    }

    @Override public void grace(AccPowerCoordinator.Session session, long generation) {
        Work work = new Work(Objects.requireNonNull(session), generation);
        if (generation <= 0 || poisoned || !current.compareAndSet(null, work))
            throw new IllegalStateException("previous participant owner unavailable/unclean");
        event(work, "grace accepted");
        try { work.graceTimer = timer.schedule(GRACE_MILLIS, () -> {
            if (work.graceEnded.compareAndSet(false, true)) { fail(work); signal(); }
        }); }
        catch (RuntimeException failure) { fail(work); signal(); throw failure; }
        if (work.cancelled) work.graceTimer.release();
        signal();
    }

    @Override public CompletionStage<Boolean> prepare(AccPowerCoordinator.Cycle cycle) {
        Objects.requireNonNull(cycle);
        Work work = current.get();
        if (poisoned || cycle.isCancelled() || work == null || work.cancelled
                || work.session != cycle.session || work.generation != cycle.generation)
            return CompletableFuture.completedFuture(false);
        if (!work.graceEnded.compareAndSet(false, true)) return CompletableFuture.completedFuture(false);
        work.cycle = cycle;
        if (work.graceTimer != null) work.graceTimer.release();
        return work.result;
    }

    @Override public void cancelGrace(AccPowerCoordinator.Session session, long generation) {
        Work work = current.get();
        if (work == null || work.session != session || work.generation != generation) return;
        work.cancelled = true;
        work.graceEnded.set(true);
        signal();
    }

    @Override public void cancel(AccPowerCoordinator.Cycle cycle) {
        Work work = current.get();
        if (work == null || work.session != cycle.session || work.generation != cycle.generation) return;
        work.cancelled = true; // Local revocation precedes any queued/framework cleanup.
        work.graceEnded.set(true);
        signal();
    }

    /** Initial check admits pending preparation; final check re-reads volume and adapter states. */
    boolean retained(AccPowerCoordinator.Cycle cycle) throws Exception {
        Work work = current.get();
        if (poisoned || work == null || work.session != cycle.session
                || work.generation != cycle.generation || work.cancelled || cycle.isCancelled()) return false;
        if (!work.ready) return true;
        if (!bluetoothQuiet() || !platform.peripheralsQuiet()) { fail(work); signal(); return false; }
        Map<String, String> expected = work.preparedIdentities;
        Map<String, Volume> volumes = snapshot();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Volume now = volumes.get(entry.getKey());
            if (now == null || !now.usbPublic || !entry.getValue().equals(now.identity)
                    || now.state != UNMOUNTED) { fail(work); signal(); return false; }
        }
        for (Volume now : volumes.values()) {
            if (now.usbPublic && now.state != UNMOUNTED && now.state != GONE) {
                fail(work); signal(); return false;
            }
        }
        return !cancelled(work);
    }

    private void signal() {
        if (!queued.compareAndSet(false, true)) return;
        try { worker.execute(() -> {
            queued.set(false);
            Work work = current.get();
            if (work == null) return;
            try { drive(work); }
            catch (Exception failure) {
                event(work, "failed " + failure.getClass().getSimpleName());
                fail(work);
                try { cleanup(work); }
                catch (Exception cleanupFailure) {
                    poisoned = true;
                    // Storage failure must not permanently retain our focus request.
                    try { releaseFocus(work); } catch (Exception focusFailure) { /* Owner remains poisoned. */ }
                }
            }
        }); } catch (RuntimeException rejected) {
            poisoned = true;
            Work work = current.get();
            if (work != null) fail(work);
        }
    }

    private boolean cancelled(Work work) {
        AccPowerCoordinator.Cycle cycle = work.cycle;
        return work.cancelled || (cycle != null && cycle.isCancelled());
    }

    private void fail(Work work) {
        work.cancelled = true;
        work.graceEnded.set(true);
        if (work.graceTimer != null) work.graceTimer.release();
        if (current.get() == work) {
            try { revoke.run(); } catch (RuntimeException failedRevocation) { poisoned = true; }
        } // No ownership/coordinator lock is held.
        work.result.complete(false); // Transport authority/FD shutdown precedes dependent cleanup.
    }

    private Map<String, Volume> snapshot() throws Exception {
        List<Volume> volumes = platform.volumes();
        if (volumes.size() > MAX_VOLUMES) throw new IllegalStateException("volume census overflow");
        Map<String, Volume> result = new LinkedHashMap<>();
        for (Volume volume : volumes) {
            if (result.put(volume.id, volume) != null) throw new IllegalStateException("duplicate volume");
        }
        return result;
    }

    private void drive(Work work) throws Exception {
        if (cancelled(work)) { cleanup(work); return; }
        if (!watching) { platform.watch(this::signal); watching = true; }
        if (cancelled(work)) { cleanup(work); return; }
        if (!work.focusAttempted) {
            work.focusAttempted = true;
            work.focusHeld = platform.requestTransientFocus(() -> {
                if (current.get() == work && !cancelled(work)) { fail(work); signal(); }
            });
            event(work, "transient focus " + (work.focusHeld ? "granted" : "denied"));
            if (!work.focusHeld) fail(work);
        }
        if (cancelled(work)) { cleanup(work); return; }
        if (!work.bluetoothCaptured) {
            work.bluetoothRestore = platform.bluetoothWanted();
            int state = platform.bluetoothState();
            work.bluetoothCaptured = true;
            work.peripheralsIssued = true; // Journal precedes potentially partial framework writes.
            platform.preparePeripherals();
            // Also cancel the manager's pending restart when a failing adapter is briefly OFF.
            if (work.bluetoothRestore || state != BT_OFF) {
                work.bluetoothDisableIssued = true; // Binder failure can follow a delivered request.
                event(work, "Bluetooth temporary disable requested state=" + state
                        + " restore=" + work.bluetoothRestore);
                if (!platform.disableBluetooth())
                    throw new IllegalStateException("Bluetooth temporary disable rejected");
            }
        }
        if (cancelled(work)) { cleanup(work); return; }
        Map<String, Volume> volumes = snapshot();
        if (!work.captured) {
            for (Volume volume : volumes.values()) {
                if (!volume.usbPublic) continue;
                if (volume.state == MOUNTED) work.volumes.put(volume.id, new Owned(volume));
                else if (volume.state != UNMOUNTED && volume.state != GONE)
                    throw new IllegalStateException("USB volume already transitioning");
            }
            work.captured = true;
        }
        for (Owned owned : work.volumes.values()) {
            if (cancelled(work)) { cleanup(work); return; }
            if (!owned.issued) {
                Volume before = snapshot().get(owned.original.id);
                if (!same(owned, before) || before.state != MOUNTED)
                    throw new IllegalStateException("volume changed before owned unmount");
                if (cancelled(work)) { cleanup(work); return; }
                owned.issued = true; // A failed Binder result may still have applied the request.
                event(work, "unmount requested id=" + owned.original.id);
                platform.unmount(owned.original.id);
            }
        }
        if (cancelled(work)) { cleanup(work); return; }
        volumes = snapshot();
        int bluetoothState = platform.bluetoothState();
        boolean ready = bluetoothState == BT_OFF && platform.peripheralsQuiet();
        if (!ready && work.ready) throw new IllegalStateException("prepared Bluetooth became active");
        if (ready && !work.bluetoothQuietLogged) {
            work.bluetoothQuietLogged = true;
            event(work, "Bluetooth quiescent state=" + bluetoothState
                    + (bluetoothState == BT_BLE_ON ? " (retained idle controller)" : " (stack OFF)"));
        }
        for (Owned owned : work.volumes.values()) {
            Volume now = volumes.get(owned.original.id);
            if (!same(owned, now) || now.state == GONE)
                throw new IllegalStateException("owned USB identity removed/changed");
            if (now.state == UNMOUNTED) {
                if (!owned.unmounted) event(work, "unmount observed id=" + now.id);
                owned.unmounted = true;
            }
            else {
                if (work.ready) throw new IllegalStateException("prepared volume became active");
                ready = false;
            }
        }
        for (Volume now : volumes.values()) {
            if (now.usbPublic && now.state != UNMOUNTED && now.state != GONE
                    && !work.volumes.containsKey(now.id))
                throw new IllegalStateException("new USB volume during preparation");
        }
        if (ready && !cancelled(work)) {
            if (!work.ready) event(work, "Android participants ready; physical mute is native-owned");
            Map<String, String> identities = new LinkedHashMap<>();
            for (Owned owned : work.volumes.values())
                identities.put(owned.original.id, owned.original.identity);
            work.preparedIdentities = java.util.Collections.unmodifiableMap(identities);
            work.ready = true;
            work.result.complete(true); // Focus + observed USB/BT quiescence, not physical output mute.
        }
    }

    private static boolean same(Owned owned, Volume now) {
        return now != null && now.usbPublic && owned.original.identity.equals(now.identity);
    }

    private boolean bluetoothQuiet() throws Exception {
        int state = platform.bluetoothState();
        // ACC is a physical radio-off boundary, not the awake logical-OFF policy.
        return state == BT_OFF;
    }

    private void cleanup(Work work) throws Exception {
        work.cancelled = true;
        work.result.complete(false);
        work.graceEnded.set(true);
        if (work.graceTimer != null) work.graceTimer.release();
        if (!work.rollbackStarted) {
            work.rollbackStarted = true;
            event(work, "rollback started");
            work.rollbackTimer = timer.schedule(ROLLBACK_MILLIS, () -> {
                if (!work.rollbackOutcome.compareAndSet(0, 2)) return;
                event(work, "rollback deadline expired; owner poisoned");
                work.expired = true;
                poisoned = true; // A stuck owner is never replaced by a new retry thread/cycle.
                signal();
            });
        }
        if (work.peripheralsIssued && !work.peripheralsRestored) {
            platform.restorePeripherals();
            work.peripheralsRestored = true;
            event(work, "radio/location/USB state restored");
        }
        if (work.bluetoothDisableIssued && work.bluetoothRestore && !work.bluetoothRestoreIssued) {
            work.bluetoothRestoreIssued = true;
            // disable(false) preserved the setting; a newer user OFF takes precedence.
            if (platform.bluetoothWanted()) {
                event(work, "Bluetooth restore requested");
                if (!platform.restoreBluetooth())
                    throw new IllegalStateException("Bluetooth restore rejected");
                // Ownership transfers to the manager on acceptance, not on eventual HCI startup.
            } else {
                event(work, "Bluetooth restore skipped; user preference now OFF");
            }
        }
        Map<String, Volume> volumes = snapshot();
        for (java.util.Iterator<Owned> it = work.volumes.values().iterator(); it.hasNext();) {
            Owned owned = it.next();
            Volume now = volumes.get(owned.original.id);
            if (!owned.issued || !same(owned, now) || now.state == GONE) { it.remove(); continue; }
            if (now.state == UNMOUNTED) {
                owned.unmounted = true;
                if (!owned.mountIssued) {
                    Volume before = snapshot().get(owned.original.id);
                    if (!same(owned, before) || before.state == GONE) { it.remove(); continue; }
                    if (before.state != UNMOUNTED) continue;
                    owned.mountIssued = true; // At most one owned remount; no repeated mount loop.
                    event(work, "remount requested id=" + owned.original.id);
                    platform.mount(owned.original.id);
                }
            } else if (owned.unmounted && now.state == MOUNTED) {
                event(work, "mount observed id=" + now.id);
                it.remove(); // Already restored by us or another owner; never mount again.
            }
        }
        if (work.volumes.isEmpty() || work.expired) {
            releaseFocus(work);
            if (work.volumes.isEmpty()) {
                if (!work.rollbackOutcome.compareAndSet(0, 1) && work.rollbackOutcome.get() == 2)
                    poisoned = true;
                if (work.rollbackTimer != null) work.rollbackTimer.release();
                current.compareAndSet(work, null);
                event(work, "rollback complete");
            }
            // Expired unresolved volumes retain identity ownership for late callback cleanup.
        }
    }

    private void releaseFocus(Work work) throws Exception {
        if (work.focusAttempted) {
            work.focusAttempted = false;
            platform.abandonFocus(); // No user volume change or explicit media auto-replay.
        }
    }
}
