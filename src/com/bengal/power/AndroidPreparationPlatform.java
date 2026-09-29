package com.bengal.power;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.DiskInfo;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Actual framework focus, public USB storage and temporary Bluetooth ownership. */
final class AndroidPreparationPlatform implements AndroidOwnedPreparation.Platform {
    private final Context context;
    private final BluetoothAdapter bluetooth;
    private final Method bluetoothLeState;
    private final AudioManager audio;
    private final StorageManager storage;
    private final AndroidStandbyPeripherals peripherals;
    private final Object identities = new Object();
    private final Map<String, Long> incarnation = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private long nextIdentity;
    private AudioFocusRequest focus;

    AndroidPreparationPlatform(Context context, AudioManager audio, StorageManager storage,
            CameraIntegration camera) {
        this.context = Objects.requireNonNull(context);
        this.peripherals = new AndroidStandbyPeripherals(context, camera);
        this.bluetooth = Objects.requireNonNull(
                context.getSystemService(BluetoothManager.class).getAdapter());
        try {
            // Exact24 exposes this at runtime, but not in the module-lib app stubs.
            // This platform-signed app can use the hidden API; do not widen module
            // visibility or substitute classic getState(), which masks BLE startup.
            bluetoothLeState = BluetoothAdapter.class.getMethod("getLeState");
            android.util.Log.i("BengalPower", "Bluetooth ACC adapter ready internal_state="
                    + bluetoothLeState.invoke(bluetooth));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("internal Bluetooth state API unavailable", failure);
        }
        this.audio = Objects.requireNonNull(audio);
        this.storage = Objects.requireNonNull(storage);
    }

    @Override public void watch(Runnable changed) {
        peripherals.watch(changed);
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { changed.run(); }
        }, filter, android.Manifest.permission.BLUETOOTH_PRIVILEGED, null, Context.RECEIVER_EXPORTED);
        storage.registerListener(new StorageEventListener() {
            @Override public void onVolumeStateChanged(VolumeInfo volume, int oldState, int newState) {
                if (newState == VolumeInfo.STATE_REMOVED || newState == VolumeInfo.STATE_BAD_REMOVAL) {
                    synchronized (identities) { incarnation.remove(volume.getId()); }
                }
                changed.run();
            }
            @Override public void onDiskDestroyed(DiskInfo disk) {
                // Invalidate every cached incarnation conservatively; never remount a replugged disk.
                synchronized (identities) { incarnation.clear(); }
                changed.run();
            }
        }); // One process-lived listener, not one listener per cycle.
    }

    @Override public boolean requestTransientFocus(Runnable lost) {
        if (focus != null) throw new IllegalStateException("old focus ownership not cleaned");
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    if (change != AudioManager.AUDIOFOCUS_GAIN) lost.run();
                }, new Handler(Looper.getMainLooper()))
                .build();
        // GRANTED proves transient focus ownership/loss dispatch, not player compliance or mute.
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override public void abandonFocus() {
        AudioFocusRequest owned = focus;
        if (owned == null) return;
        if (audio.abandonAudioFocusRequest(owned) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("focus abandon failed");
        focus = null;
    }

    @Override public List<AndroidOwnedPreparation.Volume> volumes() {
        List<VolumeInfo> volumes = storage.getVolumes(); // Binder outside the identity lock.
        if (volumes.size() > AndroidOwnedPreparation.MAX_VOLUMES)
            throw new IllegalStateException("too many volumes");
        List<AndroidOwnedPreparation.Volume> result = new ArrayList<>();
        synchronized (identities) {
            Set<String> present = new HashSet<>();
            for (VolumeInfo volume : volumes) {
                String id = volume.getId();
                present.add(id);
                if (!incarnation.containsKey(id)) {
                    if (nextIdentity == Long.MAX_VALUE) throw new IllegalStateException("volume epoch exhausted");
                    incarnation.put(id, ++nextIdentity);
                }
                DiskInfo disk = volume.getDisk();
                boolean selected = volume.getType() == VolumeInfo.TYPE_PUBLIC && disk != null && disk.isUsb();
                int state = AndroidOwnedPreparation.TRANSITION;
                if (volume.getState() == VolumeInfo.STATE_MOUNTED
                        || volume.getState() == VolumeInfo.STATE_MOUNTED_READ_ONLY)
                    state = AndroidOwnedPreparation.MOUNTED;
                else if (volume.getState() == VolumeInfo.STATE_UNMOUNTED)
                    state = AndroidOwnedPreparation.UNMOUNTED;
                else if (volume.getState() == VolumeInfo.STATE_REMOVED
                        || volume.getState() == VolumeInfo.STATE_BAD_REMOVAL)
                    state = AndroidOwnedPreparation.GONE;
                result.add(new AndroidOwnedPreparation.Volume(id,
                        incarnation.get(id) + ":" + volume.getDiskId() + ":" + volume.getFsUuid(),
                        selected, state));
            }
            for (String old : seen) if (!present.contains(old)) incarnation.remove(old);
            seen.clear(); seen.addAll(present);
        }
        return result;
    }

    @Override public void unmount(String id) { storage.unmount(id); }
    @Override public void mount(String id) { storage.mount(id); }

    @Override public int bluetoothState() throws ReflectiveOperationException {
        return (Integer) bluetoothLeState.invoke(bluetooth);
    }

    @Override public boolean bluetoothWanted() {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BLUETOOTH_ON, 0) != 0;
    }

    @Override public boolean disableBluetooth() {
        // Do not overwrite the user's persisted ON/OFF choice for a temporary ACC cycle.
        return bluetooth.disable(false);
    }

    @Override public void preparePeripherals() { peripherals.prepare(); }
    @Override public boolean peripheralsQuiet() { return peripherals.quiet(); }
    @Override public void restorePeripherals() { peripherals.restore(); }

    @SuppressWarnings("deprecation") // Platform-signed system app is exempt from the public API ban.
    @Override public boolean restoreBluetooth() { return bluetooth.enable(); }
}
