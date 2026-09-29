package android.system;
public final class ErrnoException extends Exception {
    private static final long serialVersionUID = 1;
    public final int errno;
    public ErrnoException(int errno) { this.errno = errno; }
}
