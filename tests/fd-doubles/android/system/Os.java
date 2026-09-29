package android.system;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Synthetic FD numbers only. Hooks model raw-FD borrowing, not Android syscalls. */
public final class Os {
    public static volatile Consumer<FileDescriptor> writing = fd -> {}, shuttingDown = fd -> {};
    public static final AtomicInteger closes = new AtomicInteger();
    private static final AtomicInteger next = new AtomicInteger(1000);
    private Os() {}
    private static void number(FileDescriptor fd, int number) {
        try { Field field = FileDescriptor.class.getDeclaredField("fd"); field.setAccessible(true); field.setInt(fd, number); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void valid(FileDescriptor fd) { if (!fd.valid()) throw new AssertionError("borrowed closed/reused FD"); }
    public static FileDescriptor duplicate(FileDescriptor original) {
        valid(original); FileDescriptor result = new FileDescriptor(); number(result, next.incrementAndGet()); return result;
    }
    public static void socketpair(int domain, int type, int protocol, FileDescriptor a, FileDescriptor b) throws ErrnoException {
        number(a, next.incrementAndGet()); number(b, next.incrementAndGet());
    }
    public static FileDescriptor[] pipe2(int flags) throws ErrnoException {
        FileDescriptor a = new FileDescriptor(), b = new FileDescriptor(); socketpair(0, 0, 0, a, b); return new FileDescriptor[] {a, b};
    }
    public static int write(FileDescriptor fd, byte[] bytes, int offset, int length) throws ErrnoException {
        valid(fd); writing.accept(fd); valid(fd); return length;
    }
    public static int read(FileDescriptor fd, byte[] bytes, int offset, int length) throws ErrnoException { throw new ErrnoException(11); }
    public static int poll(StructPollfd[] fds, int timeout) throws ErrnoException { return 0; }
    public static void shutdown(FileDescriptor fd, int how) throws ErrnoException {
        valid(fd); shuttingDown.accept(fd); valid(fd);
    }
    public static void close(FileDescriptor fd) throws ErrnoException { valid(fd); number(fd, -1); closes.incrementAndGet(); }
}
