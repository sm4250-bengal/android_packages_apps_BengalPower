package com.bengal.power;

public final class GnssStandbyStateTest {
    public static void main(String[] args) {
        String[] states = {null, "", "starting", "running", "stopping", "failed", "idle", "IDLE"};
        for (String state : states) {
            if (!GnssStandbyState.quiet(false, state)) throw new AssertionError("absent provider");
            if (GnssStandbyState.quiet(true, state) != "idle".equals(state))
                throw new AssertionError("incorrect native cleanup acceptance: " + state);
        }
        System.out.println("PASS GNSS standby receipt: 16 cases, only explicit idle when installed");
    }
}
