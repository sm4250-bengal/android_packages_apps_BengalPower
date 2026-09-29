package com.bengal.power;

/** Android's location switch is a request; the native owner supplies cleanup proof. */
final class GnssStandbyState {
    private GnssStandbyState() {}
    static boolean quiet(boolean providerExpected, String nativeState) {
        return !providerExpected || "idle".equals(nativeState);
    }
}
