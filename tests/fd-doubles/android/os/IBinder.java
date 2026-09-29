package android.os;
public interface IBinder {
    interface DeathRecipient { void binderDied(); }
    void linkToDeath(DeathRecipient recipient, int flags);
    boolean unlinkToDeath(DeathRecipient recipient, int flags);
}
