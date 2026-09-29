package android.net;
import java.io.*;
public final class LocalSocket {
    public LocalSocket(FileDescriptor fd) throws IOException {}
    public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(new byte[0]); }
    public OutputStream getOutputStream() throws IOException { return new ByteArrayOutputStream(); }
    public FileDescriptor[] getAncillaryFileDescriptors() throws IOException { return null; }
}
