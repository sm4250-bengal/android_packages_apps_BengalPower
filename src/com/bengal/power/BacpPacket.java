package com.bengal.power;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Frozen BACPv1, one 64-byte SEQPACKET record. Nonces are identity fences, not authentication. */
public final class BacpPacket {
    public static final int SIZE = 64;
    public static final int HELLO = 1, SNAPSHOT = 2, START = 3, GRACE = 4, PREPARE = 5,
            READY = 6, FINAL_RESULT = 7, AUTHORIZE = 8, RESUME = 9, CANCEL = 10, CLOSE = 11;
    public final int kind, phase;
    public final long sequence, generation, deadline;
    private final byte[] session, registration;

    public BacpPacket(int kind, int phase, byte[] session, byte[] registration,
            long sequence, long generation, long deadline) {
        if (session == null || registration == null || session.length != 16 || registration.length != 16)
            throw new IllegalArgumentException("nonce size");
        this.kind = kind; this.phase = phase;
        this.session = session.clone(); this.registration = registration.clone();
        this.sequence = sequence; this.generation = generation; this.deadline = deadline;
        validate();
    }
    private void validate() {
        if (kind < HELLO || kind > CLOSE || sequence < 0 || generation < 0 || deadline < 0)
            throw new IllegalArgumentException("kind or unsigned overflow");
        if (phase < 0 || (kind == SNAPSHOT ? phase > 4 : kind == FINAL_RESULT
                ? phase != 1 && phase != 2 : phase != 0)) throw new IllegalArgumentException("phase");
        if (kind == HELLO) {
            if (!zero(session) || !zero(registration) || sequence != 0 || generation != 0 || deadline != 0)
                throw new IllegalArgumentException("HELLO fields");
        } else if (zero(session) || zero(registration)) throw new IllegalArgumentException("zero nonce");
        if (kind == CLOSE && (sequence != 0 || generation != 0)) throw new IllegalArgumentException("CLOSE fields");
        if (kind == PREPARE || kind == READY || kind == AUTHORIZE) {
            if (deadline == 0) throw new IllegalArgumentException("missing deadline");
        } else if (kind != FINAL_RESULT && deadline != 0) throw new IllegalArgumentException("unexpected deadline");
    }
    private static boolean zero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }
    public static BacpPacket hello() { return new BacpPacket(HELLO, 0, new byte[16], new byte[16], 0, 0, 0); }
    public BacpPacket control(int newKind, long reference, long cycle, long cutoff) {
        return new BacpPacket(newKind, 0, session, registration, reference, cycle, cutoff);
    }
    public boolean sameConnection(BacpPacket other) {
        return Arrays.equals(session, other.session) && Arrays.equals(registration, other.registration);
    }
    public byte[] encode() {
        return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN).putInt(0x42414350)
                .put((byte) 1).put((byte) kind).put((byte) phase).put((byte) 0)
                .put(session).put(registration).putLong(sequence).putLong(generation).putLong(deadline).array();
    }
    public static BacpPacket decode(byte[] record) {
        if (record == null || record.length != SIZE) throw new IllegalArgumentException("record length");
        ByteBuffer bytes = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        if (bytes.getInt() != 0x42414350 || bytes.get() != 1) throw new IllegalArgumentException("magic/version");
        int kind = Byte.toUnsignedInt(bytes.get()), phase = Byte.toUnsignedInt(bytes.get());
        if (bytes.get() != 0) throw new IllegalArgumentException("reserved");
        byte[] session = new byte[16], registration = new byte[16]; bytes.get(session).get(registration);
        return new BacpPacket(kind, phase, session, registration, bytes.getLong(), bytes.getLong(), bytes.getLong());
    }
}
