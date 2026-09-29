package com.bengal.power;

/** Focused actual-core control: opting in cannot manufacture preparation or deep readiness. */
public final class UnavailablePowerReadinessTest {
    public static void main(String[] args) {
        UnavailablePowerReadiness readiness = new UnavailablePowerReadiness();
        if (readiness.prepare(null).toCompletableFuture().join() || readiness.ready(null)) {
            throw new AssertionError("missing owners became ready");
        }
        int[] calls = new int[4]; // acquire, release, sleep, wake
        AccPowerCoordinator.Host host = new AccPowerCoordinator.Host() {
            @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) {
                calls[0]++;
                return () -> calls[1]++;
            }
            @Override public long uptimeMillis() { return 100; }
            @Override public void sleep(long time) { calls[2]++; }
            @Override public void wake(long time) { calls[3]++; }
        };
        AccPowerCoordinator.Timer timer = new AccPowerCoordinator.Timer() {
            @Override public long monotonicMillis() { return 100; }
            @Override public AccPowerCoordinator.Lease schedule(long timeout, Runnable task) {
                return () -> {};
            }
        };
        AccPowerCoordinator core = new AccPowerCoordinator(true, 2000, host, timer,
                readiness, readiness, Runnable::run, Runnable::run);
        AccPowerCoordinator.Session session = core.connect("test", 0);
        core.beginOff(session, 1);
        core.finalHostWrite(session, 1, true);
        if (core.state() != AccPowerCoordinator.State.TARGET_UNAVAILABLE
                || calls[0] != 1 || calls[1] != 1 || calls[2] != 0 || calls[3] != 0) {
            throw new AssertionError("opt-in missing readiness did not release/fail closed");
        }
        core.disconnected(session);
        core.connect("reopen", 1);
        core.close();
        if (calls[0] != 1 || calls[1] != 1 || calls[2] != 0 || calls[3] != 0) {
            throw new AssertionError("snapshot/death fabricated power action");
        }
        System.out.println("PASS unavailable readiness: real-core opt-in lease cleanup, no sleep/wake");
    }
}
