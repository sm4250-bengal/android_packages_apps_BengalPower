package android.os;
public final class SELinux {
    private SELinux() {}
    public static String getContext() { return "u:r:bengal_power_app:s0:c255,c256,c512,c768"; }
}
