package android.system;
public final class OsConstants {
    public static final int AF_UNIX = 1, SOCK_SEQPACKET = 5, SOCK_CLOEXEC = 0x80000,
            SOCK_NONBLOCK = 0x800, O_CLOEXEC = 0x80000, O_NONBLOCK = 0x800,
            EAGAIN = 11, EINTR = 4, POLLIN = 1, SHUT_RDWR = 2;
    private OsConstants() {}
}
