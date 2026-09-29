package com.bengal.power;

/** Pure state: old acknowledgements cannot authorize suspend or reopen video. */
final class CameraPolicyState {
    /** BACP phase0 is the running cold-boot idle state, not ACC-off. */
    static boolean snapshotAwake(int phase) {
        if (phase < 0 || phase > 4) throw new IllegalArgumentException("camera snapshot phase");
        return phase == 0 || phase == 1;
    }
    private int generation = 1;
    private boolean awake, connected;
    private int acknowledged, flags;
    synchronized int generation() { return generation; }
    synchronized boolean awake() { return awake; }
    synchronized void awake(boolean value) {
        if (awake == value) return;
        if (generation == Integer.MAX_VALUE) throw new IllegalStateException("camera epoch exhausted");
        awake = value; ++generation; acknowledged = 0;
    }
    synchronized void connected(boolean value) {
        connected = value; acknowledged = 0; flags = 0;
    }
    synchronized void receipt(int epoch, int value) {
        if (epoch < 0 || epoch > generation || (value & ~15) != 0)
            throw new IllegalArgumentException("camera receipt");
        if (epoch == generation) { acknowledged = epoch; flags = value; }
    }
    synchronized boolean quiet() {
        return connected && !awake && acknowledged == generation && (flags & 3) == 0;
    }
    synchronized boolean reverse() {
        return connected && awake && acknowledged == generation && (flags & 13) == 13;
    }
}
