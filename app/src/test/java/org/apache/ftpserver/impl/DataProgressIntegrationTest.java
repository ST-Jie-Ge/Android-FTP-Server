package org.apache.ftpserver.impl;

import java.io.*;
import java.net.Socket;
import org.apache.ftpserver.ftplet.DataType;
import org.apache.mina.core.session.DummySession;
import org.junit.Test;
import static org.junit.Assert.*;

public class DataProgressIntegrationTest {
    private static class Session extends FtpIoSession {
        long sent, received;
        Session() { super(new DummySession(), null); setDataType(DataType.BINARY); }
        @Override public void increaseWrittenDataBytes(int bytes) { sent += bytes; }
        @Override public void increaseReadDataBytes(int bytes) { received += bytes; }
    }
    private IODataConnection connection(Session session) {
        Socket socket = new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[70001]); }
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int b) {}
                    @Override public void write(byte[] b, int o, int n) {}
                };
            }
        };
        return new IODataConnection(socket, session, new IODataConnectionFactory(null, session));
    }
    @Test public void successfulShortTransferPublishesAllBytesAndClearsActive() throws Exception {
        Session session = new Session();
        byte[] bytes = new byte[70001];
        assertEquals(bytes.length, connection(session).transferToClient(session.getFtpletSession(), new ByteArrayInputStream(bytes)));
        assertEquals(bytes.length, session.sent);
        assertFalse(session.isTransferActive());
    }
    @Test public void readFailurePublishesCompletedBlocksAndClearsActive() throws Exception {
        Session session = new Session();
        InputStream in = new InputStream() {
            boolean first = true;
            @Override public int read() { throw new UnsupportedOperationException(); }
            @Override public int read(byte[] b, int o, int n) throws IOException {
                if (!first) throw new IOException("injected read failure");
                first = false; return Math.min(64 * 1024, n);
            }
        };
        try {
            connection(session).transferToClient(session.getFtpletSession(), in);
            fail("Expected failure");
        } catch (IOException expected) { assertEquals("injected read failure", expected.getMessage()); }
        assertEquals(64 * 1024, session.sent);
        assertFalse(session.isTransferActive());
    }
    @Test public void asciiTailIsIncludedInPublishedWireBytes() throws Exception {
        Session session = new Session(); session.setDataType(DataType.ASCII);
        assertEquals(3, connection(session).transferToClient(session.getFtpletSession(),
                new ByteArrayInputStream(new byte[]{'x', '\r'})));
        assertEquals(3, session.sent);
        assertFalse(session.isTransferActive());
    }
    @Test public void uploadPublishesReceivedBytes() throws Exception {
        Session session = new Session(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(70001, connection(session).transferFromClient(session.getFtpletSession(), out));
        assertEquals(70001, session.received); assertEquals(70001, out.size());
        assertFalse(session.isTransferActive());
    }
}