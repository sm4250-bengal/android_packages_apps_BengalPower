package android.os;
public final class ServiceManager {
    public static volatile IBinder service;
    private ServiceManager() {}
    public static IBinder checkService(String name) { return service; }
}
