package com.bengal.power;

public final class CameraPolicyStateTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    public static void main(String[] ignored) {
        for (int phase = 0; phase <= 4; phase++) {
            CameraPolicyState cold = new CameraPolicyState();
            cold.connected(true);
            cold.awake(CameraPolicyState.snapshotAwake(phase));
            check(cold.awake() == (phase <= 1));
            cold.connected(false); check(!cold.reverse());
        }
        for (int invalid : new int[] {-1, 5}) {
            try { CameraPolicyState.snapshotAwake(invalid); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { }
        }
        CameraPolicyState s = new CameraPolicyState();
        check(!s.quiet() && !s.reverse());
        s.connected(true); check(!s.quiet());
        s.receipt(1, 12); check(s.quiet() && !s.reverse());
        s.awake(true); check(!s.quiet() && !s.reverse());
        s.receipt(1, 13); check(!s.reverse());
        s.receipt(2, 13); check(s.reverse());
        s.receipt(2, 5); check(!s.reverse());
        s.receipt(2, 1); check(!s.reverse());
        s.receipt(2, 15); check(s.reverse());
        s.awake(false); check(!s.quiet() && !s.reverse());
        s.receipt(2, 12); check(!s.quiet());
        s.receipt(3, 14); check(!s.quiet()); // Held, DMA not released yet.
        s.receipt(3, 12); check(s.quiet());
        s.connected(false); check(!s.quiet() && !s.reverse());
        s.connected(true); check(!s.quiet());
        s.awake(true); s.receipt(4, 13); check(s.reverse());
        s.connected(false); check(!s.reverse());
        try { s.receipt(5, 0); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        try { s.receipt(4, 16); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS camera policy: cold-boot/5 snapshots/invalid phases plus 19 hold/freshness/reverse/loss checks");
    }
}
