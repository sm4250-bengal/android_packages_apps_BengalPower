package com.bengal.power;

import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed platform adapter only. No service registration, permission grant or policy activation. */
public final class AndroidPowerHost implements AccPowerCoordinator.Host {
    static final long MAX_PREPARATION_MILLIS = 30_000;
    private final PowerManager power;

    public AndroidPowerHost(PowerManager power) {
        this.power = Objects.requireNonNull(power);
    }

    static void checkTimeout(long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > MAX_PREPARATION_MILLIS) {
            throw new IllegalArgumentException("preparation timeout must be 1..30000 ms");
        }
    }

    @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeoutMillis) {
        checkTimeout(timeoutMillis);
        // Never share a WakeLock between cycles: an old release must not release a new lease.
        PowerManager.WakeLock wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "BengalPower:prepare");
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire(timeoutMillis);
        } catch (RuntimeException failure) {
            try { wakeLock.release(); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            // Non-reference-counted release is safe if the framework timeout already fired,
            // including a timeout racing this call. Avoid an isHeld()/release() check race.
            if (released.compareAndSet(false, true)) wakeLock.release();
        };
    }

    @Override public long uptimeMillis() { return SystemClock.uptimeMillis(); }

    @Override public void sleep(long earlierHostSample) {
        // Re-sample at the actual call even if the coordinator's sample is now stale.
        power.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
    }

    @Override public void wake(long earlierHostSample) {
        power.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_APPLICATION,
                "BengalPower:acc");
    }
}
