package android.os;

import java.util.ArrayList;
import java.util.List;

/** Minimal host double. Real names/signatures are separately checked against the exact platform jar. */
public final class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public static final int GO_TO_SLEEP_REASON_APPLICATION = 0;
    public static final int GO_TO_SLEEP_FLAG_NO_DOZE = 1;
    public static final int WAKE_REASON_APPLICATION = 2;
    public final List<WakeLock> locks = new ArrayList<>();
    public long sleepTime, wakeTime;
    public int sleepReason, sleepFlags, wakeReason;
    public String wakeDetails;
    public boolean failAcquire, failSleep, failWake;
    public WakeLock newWakeLock(int flags, String tag) {
        WakeLock lock = new WakeLock(flags, tag);
        locks.add(lock);
        return lock;
    }
    public void goToSleep(long time, int reason, int flags) {
        sleepTime = time; sleepReason = reason; sleepFlags = flags;
        if (failSleep) throw new SecurityException("sleep denied");
    }
    public void wakeUp(long time, int reason, String details) {
        wakeTime = time; wakeReason = reason; wakeDetails = details;
        if (failWake) throw new SecurityException("wake denied");
    }
    public final class WakeLock {
        public final int flags;
        public final String tag;
        public boolean referenceCounted = true;
        public boolean held;
        public long timeout;
        public int releases;
        WakeLock(int flags, String tag) { this.flags = flags; this.tag = tag; }
        public void setReferenceCounted(boolean value) { referenceCounted = value; }
        public synchronized void acquire(long timeoutMillis) {
            timeout = timeoutMillis; held = true;
            if (failAcquire) throw new SecurityException("acquire outcome uncertain");
        }
        public synchronized void release() {
            if (referenceCounted && !held) throw new IllegalStateException("under-locked");
            releases++; held = false;
        }
        public synchronized void expire() { held = false; }
    }
}
