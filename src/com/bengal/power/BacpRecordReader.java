package com.bengal.power;

import java.io.IOException;

/** One SEQPACKET receive, never stream readFully. Native truncation cleanup is a boundary contract. */
final class BacpRecordReader {
    interface Closer<T> { void close(T descriptor) throws Exception; }
    interface Input {
        int read(byte[] buffer) throws IOException;
        /** Drain and close every descriptor, even if one close fails; true rejects the packet. */
        boolean closeAncillary() throws IOException;
    }
    private BacpRecordReader() {}
    static <T> boolean closeAll(T[] descriptors, Closer<T> closer) throws IOException {
        if (descriptors == null) return false;
        Exception failure = null;
        for (T descriptor : descriptors) {
            try { closer.close(descriptor); }
            catch (Exception error) { failure = error; }
        }
        if (failure != null) throw new IOException("BACP ancillary close failed", failure);
        return descriptors.length != 0;
    }
    static BacpPacket read(Input input) throws IOException {
        byte[] bytes = new byte[BacpPacket.SIZE + 1];
        int size = -1;
        IOException failure = null;
        try { size = input.read(bytes); }
        catch (IOException error) { failure = error; }
        finally {
            if (input.closeAncillary()) {
                if (failure == null) failure = new IOException("BACP ancillary descriptors forbidden");
            }
        }
        if (failure != null) throw failure;
        if (size != BacpPacket.SIZE) throw new IOException("BACP record length/EOF: " + size);
        byte[] record = new byte[BacpPacket.SIZE];
        System.arraycopy(bytes, 0, record, 0, record.length);
        return BacpPacket.decode(record);
    }
}
