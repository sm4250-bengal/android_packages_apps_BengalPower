package com.bengal.power;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Process-lived camera participant; BACP remains the only ACC authority. */
final class CameraIntegration {
    private static final int MAGIC = 0x42434331;
    private final Context context;
    private final CameraPolicyState state = new CameraPolicyState();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            job -> new Thread(job, "BengalCamera-policy-write"));
    private static final class Connection {
        final LocalSocket socket;
        final DataOutputStream output;
        int sent; // Writer lane only.
        Connection(LocalSocket socket) throws IOException {
            this.socket = socket; output = new DataOutputStream(socket.getOutputStream());
        }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
    private volatile Connection connection;
    private volatile Runnable changed = () -> {};
    private boolean presented;

    CameraIntegration(Context context) {
        this.context = context;
        new Thread(this::readLoop, "BengalCamera-policy-read").start();
    }
    void watch(Runnable callback) { changed = callback; }
    boolean quiet() { return state.quiet(); }
    void hold() { accAwake(false); }
    void disconnected() {
        try { accAwake(false); }
        finally { Connection current = connection; if (current != null) current.close(); }
    }
    void event(BacpPacket packet) {
        if (packet.kind == BacpPacket.SNAPSHOT)
            accAwake(CameraPolicyState.snapshotAwake(packet.phase));
        else if (packet.kind == BacpPacket.GRACE || packet.kind == BacpPacket.PREPARE)
            accAwake(false);
        else if (packet.kind == BacpPacket.RESUME) accAwake(true);
    }
    private void accAwake(boolean value) {
        state.awake(value);
        notifyChanged();
        sendLatest();
    }
    private void notifyChanged() {
        main.post(() -> {
            changed.run();
            boolean want = state.reverse();
            if (want == presented) return;
            presented = want;
            if (want) {
                try {
                    context.startActivity(new Intent().setClassName("com.meinte.xyautomcutoolbox",
                            "com.meinte.hucamera.ReverseActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                    Log.i("BengalPower", "reverse camera enter (authenticated native input)");
                } catch (RuntimeException failure) {
                    Log.e("BengalPower", "reverse activity unavailable", failure);
                }
            } else {
                context.sendBroadcast(new Intent("com.bengal.camera.REVERSE_EXIT")
                        .setPackage("com.meinte.xyautomcutoolbox"));
                Log.i("BengalPower", "reverse camera exit / ACC hold");
            }
        });
    }
    private void sendLatest() {
        try { writer.execute(() -> {
            Connection current = connection;
            if (current == null) return;
            int epoch; boolean awake;
            synchronized (state) { epoch = state.generation(); awake = state.awake(); }
            if (epoch <= current.sent) return;
            try {
                current.output.writeInt(MAGIC); current.output.writeInt(epoch);
                current.output.writeInt(awake ? 1 : 0); current.output.flush();
                current.sent = epoch;
            } catch (IOException failure) { current.close(); }
        }); } catch (RejectedExecutionException coalesced) {
            // The one pending job reads the latest state/connection when run.
        }
    }
    private void readLoop() {
        for (;;) {
            LocalSocket current = new LocalSocket();
            try {
                current.connect(new LocalSocketAddress("bengal-camera-policy-v1",
                        LocalSocketAddress.Namespace.ABSTRACT));
                if (current.getPeerCredentials().getUid() != 0)
                    throw new IOException("camera broker peer must be root");
                state.connected(true);
                connection = new Connection(current);
                DataInputStream in = new DataInputStream(current.getInputStream());
                sendLatest();
                Log.i("BengalPower", "camera policy connected; waiting for generation receipt");
                for (;;) {
                    if (in.readInt() != MAGIC) throw new IOException("camera receipt magic");
                    int epoch = in.readInt(), flags = in.readInt();
                    state.receipt(epoch, flags);
                    Log.i("BengalPower", "camera receipt generation=" + epoch + " flags=" + flags);
                    notifyChanged();
                }
            } catch (IOException | RuntimeException failure) {
                Log.w("BengalPower", "camera broker disconnected: " + failure);
            } finally {
                connection = null;
                try { current.close(); } catch (IOException ignored) { }
                state.connected(false); notifyChanged();
            }
            // No wake alarm or lease: freezes normally in deep sleep. Backoff
            // also accommodates init/package startup ordering after cold boot.
            try { Thread.sleep(2000); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }
}
