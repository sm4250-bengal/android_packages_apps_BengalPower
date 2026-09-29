package android.os;
import android.system.Os;
import android.system.ErrnoException;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class ParcelFileDescriptor implements AutoCloseable {
    public final AtomicInteger closes = new AtomicInteger();
    private final FileDescriptor fd;
    private ParcelFileDescriptor(FileDescriptor fd) { this.fd = fd; }
    public static ParcelFileDescriptor dup(FileDescriptor fd) throws IOException { return new ParcelFileDescriptor(Os.duplicate(fd)); }
    public boolean isClosed() { return !fd.valid(); }
    @Override public void close() throws IOException {
        if (closes.getAndIncrement() == 0) try { Os.close(fd); } catch (ErrnoException error) { throw new IOException(error); }
    }
}
