package com.bengal.power;

import android.content.BroadcastReceiver;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** OEM-shaped peripheral preparation, with a disk journal for process/boot recovery. */
final class AndroidStandbyPeripherals {
    private static final String TAG = "BengalPower";
    private static final String ACC = "bengal_acc_quiesce";
    private static final String[] TEMP_SETTINGS = {
        "wifi_scan_always_enabled", "ble_scan_always_enabled", "adb_enabled"
    };
    private final Context context;
    private final WifiManager wifi;
    private final ConnectivityManager network;
    private final LocationManager location;
    private final UsbManager usb;
    private final SharedPreferences journal;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable changed = () -> {};
    private volatile boolean preparing;
    private volatile int generation;
    private final CameraIntegration camera;

    AndroidStandbyPeripherals(Context context, CameraIntegration camera) {
        this.context = context;
        this.camera = camera;
        wifi = context.getSystemService(WifiManager.class);
        network = context.getSystemService(ConnectivityManager.class);
        location = context.getSystemService(LocationManager.class);
        usb = context.getSystemService(UsbManager.class);
        journal = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("acc-peripheral-recovery", Context.MODE_PRIVATE);
        // A process death or cold boot must not strand the head unit in airplane mode
        // with ADB disabled. A failed restore leaves the journal for the next start.
        if (journal.getBoolean("pending", false)) {
            Log.w(TAG, "recovering interrupted ACC peripheral settings");
            boolean bluetoothWanted = journal.getBoolean("bluetooth", false);
            restore();
            if (bluetoothWanted && global("bluetooth_on", 0) != 0
                    && !context.getSystemService(BluetoothManager.class).getAdapter().enable())
                Log.e(TAG, "interrupted ACC Bluetooth restore rejected");
        }
    }

    private int global(String key, int fallback) {
        return Settings.Global.getInt(context.getContentResolver(), key, fallback);
    }
    private void put(String key, int value) {
        if (!Settings.Global.putInt(context.getContentResolver(), key, value))
            throw new IllegalStateException("unable to set " + key);
    }

    void watch(Runnable callback) {
        changed = callback;
        if (camera != null) camera.watch(callback);
        IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        filter.addAction(UsbManager.ACTION_USB_STATE);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { changed.run(); }
        }, filter, Context.RECEIVER_EXPORTED); // Wake a re-read only; broadcasts grant no readiness.
    }

    @SuppressWarnings("deprecation") // Platform-signed policy owner, not an ordinary application.
    void prepare() {
        if (camera != null) camera.hold();
        if (journal.getBoolean("pending", false))
            throw new IllegalStateException("unclean peripheral owner");
        SharedPreferences.Editor saved = journal.edit().clear()
                .putBoolean("pending", true)
                .putBoolean("bluetooth", global("bluetooth_on", 0) != 0)
                .putBoolean("airplane", global("airplane_mode_on", 0) != 0)
                .putBoolean("wifi", wifi.isWifiEnabled()
                        || wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLING)
                .putBoolean("hotspot", wifi.isWifiApEnabled())
                .putBoolean("location", location.isLocationEnabledForUser(UserHandle.SYSTEM))
                .putLong("usb", usb.getCurrentFunctions())
                .putInt("acc", global(ACC, 0));
        for (String key : TEMP_SETTINGS)
            saved.putString(key, Settings.Global.getString(context.getContentResolver(), key));
        if (!saved.commit()) throw new IllegalStateException("peripheral recovery journal failed");
        preparing = true;
        final int cycle = ++generation;
        put(ACC, 1); // Paired Bluetooth policy disallows retained BLE while ACC is off.
        network.setAirplaneMode(true);
        for (String key : TEMP_SETTINGS) put(key, 0);
        if (!wifi.setWifiEnabled(false)) throw new IllegalStateException("Wi-Fi shutdown rejected");
        if (journal.getBoolean("hotspot", false))
            context.getSystemService(TetheringManager.class)
                    .stopTethering(TetheringManager.TETHERING_WIFI);
        location.setLocationEnabledForUser(false, UserHandle.SYSTEM);
        usb.setCurrentFunctions(0);
        Log.i(TAG, "ACC peripheral shutdown requested: radios/location/USB; journal committed");
        // Framework functions/state are asynchronous. Poll only during bounded preparation;
        // no alarm/heartbeat runs after quiet, cancellation or actual system sleep.
        handler.post(new Runnable() {
            @Override public void run() {
                if (!preparing || generation != cycle) return;
                changed.run();
                try {
                    if (!quiet()) handler.postDelayed(this, 250);
                } catch (RuntimeException failure) {
                    // The worker re-reads and rolls back; never crash the main looper.
                    Log.e(TAG, "ACC peripheral readiness read failed", failure);
                }
            }
        });
    }

    boolean quiet() {
        return preparing && global(ACC, 0) == 1 && global("airplane_mode_on", 0) == 1
                && (camera == null || camera.quiet())
                && wifi.getWifiState() == WifiManager.WIFI_STATE_DISABLED
                && wifi.getWifiApState() == WifiManager.WIFI_AP_STATE_DISABLED
                && !location.isLocationEnabledForUser(UserHandle.SYSTEM)
                && GnssStandbyState.quiet(
                        SystemProperties.getBoolean("ro.vendor.bengal.gnss_loc", false),
                        SystemProperties.get("vendor.bengal.gnss.state", ""))
                && global("wifi_scan_always_enabled", 0) == 0
                && global("ble_scan_always_enabled", 0) == 0
                && global("adb_enabled", 0) == 0 && usb.getCurrentFunctions() == 0
                && "none".equals(SystemProperties.get("sys.usb.state"));
    }

    @SuppressWarnings("deprecation")
    void restore() {
        preparing = false;
        ++generation;
        if (!journal.getBoolean("pending", false)) return;
        RuntimeException failure = null;
        // Attempt every restore even if one service is unavailable; retain the journal on error.
        try { put(ACC, journal.getInt("acc", 0)); }
        catch (RuntimeException e) { failure = e; }
        try {
            if (!journal.getBoolean("airplane", false) && global("airplane_mode_on", 0) == 1)
                network.setAirplaneMode(false);
        } catch (RuntimeException e) { failure = e; }
        for (String key : TEMP_SETTINGS) {
            try {
                if (global(key, 0) == 0 && !Settings.Global.putString(context.getContentResolver(),
                        key, journal.getString(key, null)))
                    throw new IllegalStateException("restore failed: " + key);
            } catch (RuntimeException e) { failure = e; }
        }
        try {
            if (usb.getCurrentFunctions() == 0) usb.setCurrentFunctions(journal.getLong("usb", 0));
        } catch (RuntimeException e) { failure = e; }
        try {
            if (journal.getBoolean("wifi", false) && !wifi.isWifiEnabled()
                    && !wifi.setWifiEnabled(true)) throw new IllegalStateException("Wi-Fi restore rejected");
        } catch (RuntimeException e) { failure = e; }
        try {
            if (journal.getBoolean("hotspot", false) && !wifi.isWifiApEnabled()) {
                CompletableFuture<Void> started = new CompletableFuture<>();
                context.getSystemService(TetheringManager.class).startTethering(
                        TetheringManager.TETHERING_WIFI, Runnable::run,
                        new TetheringManager.StartTetheringCallback() {
                            @Override public void onTetheringStarted() { started.complete(null); }
                            @Override public void onTetheringFailed(int error) {
                                started.completeExceptionally(new IllegalStateException(
                                        "hotspot restore rejected: " + error));
                            }
                        });
                try { started.get(5, TimeUnit.SECONDS); }
                catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new IllegalStateException("hotspot restore incomplete", e);
                }
            }
        } catch (RuntimeException e) { failure = e; }
        try {
            if (journal.getBoolean("location", false)
                    && !location.isLocationEnabledForUser(UserHandle.SYSTEM))
                location.setLocationEnabledForUser(true, UserHandle.SYSTEM);
        } catch (RuntimeException e) { failure = e; }
        if (failure != null) throw failure;
        if (!journal.edit().clear().commit()) throw new IllegalStateException("recovery journal clear failed");
        Log.i(TAG, "ACC peripheral settings restored; recovery journal cleared");
    }
}
