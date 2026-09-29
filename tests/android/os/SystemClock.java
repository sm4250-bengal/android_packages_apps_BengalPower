package android.os;

/** Minimal host double, never included in platform compilation. */
public final class SystemClock {
    public static volatile long uptime;
    public static volatile long elapsed;
    private SystemClock() {}
    public static long uptimeMillis() { return uptime; }
    public static long elapsedRealtime() { return elapsed; }
}
