package com.bengal.power;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;
import android.media.AudioManager;
import android.os.storage.StorageManager;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-lived user0 owner; observation by default, explicitly opted-in policy still fail-closed. */
public final class ObserverApplication extends Application {
    private static final String TAG = "BengalPower";
    static final String POLICY_PROPERTY = "persist.sys.bengal.acc_power_policy";
    private AndroidAccPowerChannel channel;
    private AndroidPreparationTimer timer;
    private CameraIntegration camera;

    @Override public void onCreate() {
        super.onCreate();
        // Do not instantiate timers/workers for a secondary user's process.
        if (Process.myUid() < 10000 || Process.myUid() > 19999) {
            Log.w(TAG, "observer disabled outside user0 application UID range");
            return;
        }
        timer = new AndroidPreparationTimer();
        // Boot/process-start selection only. A property change never grants an in-flight cycle authority.
        boolean policyRequested = SystemProperties.getBoolean(POLICY_PROPERTY, false);
        if (policyRequested) {
            // Runtime latch is set only after the native service has started.
            // It clears on cold boot: an absent camera pilot must not prevent
            // the already working non-camera ACC path from sleeping.
            if (SystemProperties.getBoolean("sys.bengal.camera_policy", false)
                    || SystemProperties.getBoolean("persist.sys.bengal.camera_service", false))
                camera = new CameraIntegration(this);
            channel = policyChannel(new InstalledDeepCapability());
            Log.w(TAG, "policy requested; Android focus/USB/Bluetooth preparation and fresh installed-backend "
                    + "eligibility checks active; native AUTHORIZE required; physical wake unaccepted");
        } else {
            channel = observerChannel();
            Log.i(TAG, "observation only; no READY, leases or sleep; enforcing identity not yet accepted");
        }
        if (camera != null) channel.onDisconnect(camera::disconnected);
        // Finite startup opportunities accommodate vendor service startup order.
        // open() rejects an occupied owner; never replaces a stuck bootstrap lane.
        Handler startup = new Handler(Looper.getMainLooper());
        startup.post(this::open);
        startup.postDelayed(this::open, 5000);
        startup.postDelayed(this::open, 15000);
        // No periodic retry, wake alarm, heartbeat or background polling timer.
        // Application lifetime owns these resources; process death closes FDs.
    }

    /** Injectable installed-backend eligibility; native AUTHORIZE additionally proves cycle arm. */
    private AndroidAccPowerChannel policyChannel(AccPowerCoordinator.DeepCapability kernel) {
        AtomicReference<AndroidAccPowerChannel> owner = new AtomicReference<>();
        AndroidOwnedPreparation participants = new AndroidOwnedPreparation(
                new AndroidPreparationPlatform(this, getSystemService(AudioManager.class),
                        getSystemService(StorageManager.class), camera), lane("BengalPower-participants"), timer,
                () -> {
                    AndroidAccPowerChannel current = owner.get();
                    if (current != null) current.disconnect();
                }, message -> Log.i(TAG, message));
        AndroidAccPowerChannel result = new AndroidAccPowerChannel(true,
                new AndroidPowerHost(getSystemService(PowerManager.class)), timer,
                participants, cycle -> kernel.ready(cycle) && participants.retained(cycle),
                lane("BengalPower-prepare"), lane("BengalPower-power"), packet -> {
                    if (camera != null) camera.event(packet);
                    logPacket(packet, true);
                });
        owner.set(result);
        return result;
    }

    private AndroidAccPowerChannel observerChannel() {
        Executor forbidden = action -> {
            throw new RejectedExecutionException("observer has no preparation or power executor");
        };
        return new AndroidAccPowerChannel(false, new AccPowerCoordinator.Host() {
            @Override public AccPowerCoordinator.Lease acquirePreparationLease(long timeout) {
                throw new IllegalStateException("observer cannot acquire a power lease");
            }
            @Override public long uptimeMillis() {
                throw new IllegalStateException("observer cannot run a power job");
            }
            @Override public void sleep(long uptime) {
                throw new IllegalStateException("observer cannot sleep");
            }
            @Override public void wake(long uptime) {
                throw new IllegalStateException("observer cannot wake");
            }
        }, timer, new AccPowerCoordinator.Participants() {
            @Override public java.util.concurrent.CompletionStage<Boolean> prepare(
                    AccPowerCoordinator.Cycle cycle) {
                return CompletableFuture.completedFuture(false);
            }
            @Override public void cancel(AccPowerCoordinator.Cycle cycle) { }
        }, cycle -> false, forbidden, forbidden, packet -> logPacket(packet, false));
    }

    private static ThreadPoolExecutor lane(String name) {
        // Process lifetime: do not shut down either supplied lane while compensation can be pending.
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                action -> {
                    Thread thread = new Thread(action, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static void logPacket(BacpPacket packet, boolean policyRequested) {
        Log.i(TAG, "protocol event kind=" + packet.kind + " phase=" + packet.phase
                + " seq=" + packet.sequence + " generation=" + packet.generation
                + " deadline_elapsed_ms=" + packet.deadline
                + " policy_requested=" + policyRequested + " physical_policy=" + policyRequested);
    }

    private void open() {
        try {
            channel.open();
            Log.i(TAG, "connection attempt submitted; admission not yet established");
        } catch (IllegalStateException occupied) {
            Log.i(TAG, "observer connection owner occupied or closed; no replacement");
        } catch (RuntimeException failure) {
            Log.e(TAG, "observer startup failed", failure);
        }
    }
}
