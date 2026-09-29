package com.bengal.power;

import android.net.LocalSocket;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SELinux;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import vendor.xyauto.accpower.IAccPowerChannel;

/** Unselected own-UID owner. Root must supply exclusive service-add policy and real readiness. */
public final class AndroidAccPowerChannel implements AutoCloseable {
    private static final String SERVICE = "vendor.xyauto.accpower.IAccPowerChannel/default";
    private final ThreadPoolExecutor io = lane("BengalPower-io"), bootstrap = lane("BengalPower-bootstrap");
    private final BacpChannel channel;

    public AndroidAccPowerChannel(boolean physicalPolicy, AccPowerCoordinator.Host host,
            AccPowerCoordinator.Timer timer, AccPowerCoordinator.Participants participants,
            AccPowerCoordinator.DeepCapability deep, Executor preparation, Executor power) {
        this(physicalPolicy, host, timer, participants, deep, preparation, power, packet -> {});
    }
    /** Observation runs after validation on I/O; keep it finite/nonblocking. It grants no authority. */
    public AndroidAccPowerChannel(boolean physicalPolicy, AccPowerCoordinator.Host host,
            AccPowerCoordinator.Timer timer, AccPowerCoordinator.Participants participants,
            AccPowerCoordinator.DeepCapability deep, Executor preparation, Executor power,
            Consumer<BacpPacket> accepted) {
        channel = new BacpChannel(AndroidEndpoint::new,
                (events, send, failed) -> new AccPowerSession(physicalPolicy, host, timer,
                        participants, deep, preparation, power, events, send, failed), timer, io, bootstrap, accepted);
    }
    private static ThreadPoolExecutor lane(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), action -> {
            Thread thread = new Thread(action, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    /** Explicit retry only once the old I/O worker and any blocked bootstrap have returned. */
    public void open() { channel.open(); }
    public void disconnect() { channel.disconnect(); }
    public void onDisconnect(Runnable callback) { channel.onDisconnect(callback); }
    @Override public void close() {
        channel.close(); io.shutdown(); bootstrap.shutdown();
        // Never shut down the supplied preparation/power executors: wake compensation may be pending.
    }

    private static final class AndroidEndpoint implements BacpChannel.Endpoint {
        private static final BacpRecordReader.Closer<FileDescriptor> CLOSE_DESCRIPTOR = Os::close;
        private final FileDescriptor local = new FileDescriptor();
        private final AtomicReference<ParcelFileDescriptor> offered = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(), disposed = new AtomicBoolean();
        // Only local nonblocking descriptor operations, never Binder/participant calls, hold this lock.
        private final Object fdLock = new Object();
        private FileDescriptor[] pipe;
        private LocalSocket socket;
        private volatile IBinder binder;
        private final IBinder.DeathRecipient death = this::close;
        private volatile Runnable died = () -> {};

        AndroidEndpoint() throws Exception {
            BacpPeerAuth.authenticateOwnIdentity(Process.myUid(), SELinux.getContext());
            FileDescriptor remote = new FileDescriptor();
            boolean success = false;
            try {
                Os.socketpair(OsConstants.AF_UNIX,
                        OsConstants.SOCK_SEQPACKET | OsConstants.SOCK_CLOEXEC | OsConstants.SOCK_NONBLOCK,
                        0, local, remote);
                // This private receive endpoint never enables SO_PASSPIDFD; JNI owns RIGHTS only.
                offered.set(ParcelFileDescriptor.dup(remote));
                pipe = Os.pipe2(OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK);
                socket = new LocalSocket(local); // Non-owning; dispose owns the original descriptor.
                success = true;
            } finally {
                closeFd(remote);
                if (!success) { close(); dispose(); }
            }
        }
        @Override public void offer(Runnable failed) throws Exception {
            died = failed;
            // Move, do not borrow: timeout may close a pending offer, never this serialization owner.
            ParcelFileDescriptor endpoint = offered.getAndSet(null);
            try {
                if (closed.get() || endpoint == null) throw new IOException("closed BACP bootstrap");
                // Service-manager policy, not socketpair peer credentials, authenticates HUD.
                IBinder canonical = ServiceManager.checkService(SERVICE);
                if (canonical == null) throw new IOException("BACP canonical service unavailable");
                binder = canonical;
                canonical.linkToDeath(death, 0);
                if (closed.get()) { canonical.unlinkToDeath(death, 0); throw new IOException("BACP died during attach"); }
                IAccPowerChannel service = IAccPowerChannel.Stub.asInterface(canonical);
                if (IAccPowerChannel.VERSION != 1 || service.getInterfaceVersion() != 1
                        || !IAccPowerChannel.HASH.equals(service.getInterfaceHash()))
                    throw new IOException("BACP frozen V1 compatibility mismatch");
                if (closed.get()) throw new IOException("BACP expired during bootstrap");
                service.offerChannel(endpoint); // Oneway submission is NOT admission or bounded Binder liveness.
            } finally {
                if (endpoint != null) try { endpoint.close(); } catch (IOException ignored) { /* Invalidated. */ }
            }
        }
        private void closeOffered() {
            ParcelFileDescriptor descriptor = offered.getAndSet(null);
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { /* Invalidated. */ }
        }
        @Override public void signal() throws Exception {
            synchronized (fdLock) { signalLocked(); }
        }
        private void signalLocked() throws Exception {
            if (pipe == null || disposed.get()) return;
            try { Os.write(pipe[1], new byte[] {1}, 0, 1); }
            catch (ErrnoException failure) { if (failure.errno != OsConstants.EAGAIN) throw failure; }
        }
        @Override public boolean awaitPacket() throws Exception {
            if (closed.get()) return false;
            StructPollfd data = new StructPollfd(), wake = new StructPollfd();
            data.fd = local; data.events = (short) OsConstants.POLLIN;
            wake.fd = pipe[0]; wake.events = (short) OsConstants.POLLIN;
            try { Os.poll(new StructPollfd[] {data, wake}, -1); }
            catch (ErrnoException error) { if (error.errno == OsConstants.EINTR) return false; throw error; }
            if (wake.revents != 0) {
                byte[] drain = new byte[64];
                try { while (Os.read(pipe[0], drain, 0, drain.length) > 0) {} }
                catch (ErrnoException error) { if (error.errno != OsConstants.EAGAIN) throw error; }
            }
            if (closed.get()) return false;
            return data.revents != 0; // EOF/POLLERR also enter the one-record receive and fail closed.
        }
        @Override public BacpPacket read() throws IOException {
            return BacpRecordReader.read(new BacpRecordReader.Input() {
                @Override public int read(byte[] bytes) throws IOException { return socket.getInputStream().read(bytes); }
                @Override public boolean closeAncillary() throws IOException {
                    FileDescriptor[] descriptors = socket.getAncillaryFileDescriptors();
                    return BacpRecordReader.closeAll(descriptors, CLOSE_DESCRIPTOR);
                }
            });
        }
        @Override public void send(BacpPacket packet) throws Exception {
            byte[] bytes = packet.encode();
            if (closed.get()) throw new IOException("BACP closed record send");
            // The FD is NONBLOCK. Exact24 JNI uses MSG_NOSIGNAL; SEQPACKET sends all or errors.
            // Thus its stream-style short-write fallback is unreachable for this typed endpoint.
            socket.getOutputStream().write(bytes);
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeOffered();
            synchronized (fdLock) {
                if (!disposed.get()) {
                    try { if (local.valid()) Os.shutdown(local, OsConstants.SHUT_RDWR); } catch (ErrnoException ignored) {}
                    try { signalLocked(); } catch (Exception ignored) {}
                }
            }
            died.run();
        }
        @Override public void dispose() {
            synchronized (fdLock) {
                if (!disposed.compareAndSet(false, true)) return;
                closeFd(local);
                if (pipe != null) { closeFd(pipe[0]); closeFd(pipe[1]); }
            }
            closeOffered();
            IBinder current = binder;
            if (current != null) try { current.unlinkToDeath(death, 0); } catch (RuntimeException ignored) {}
        }
        private static void closeFd(FileDescriptor descriptor) {
            if (descriptor != null && descriptor.valid()) try { Os.close(descriptor); } catch (ErrnoException ignored) {}
        }
    }
}
