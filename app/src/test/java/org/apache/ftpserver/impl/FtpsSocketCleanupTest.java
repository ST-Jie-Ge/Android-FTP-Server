package org.apache.ftpserver.impl;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.mina.core.session.DummySession;
import org.junit.Test;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.*;
import static org.junit.Assert.*;

public class FtpsSocketCleanupTest {
    private static final class FailingTlsFactory extends SSLSocketFactory {
        Socket accepted;
        boolean failWrap = true;
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
            accepted = socket;
            if (failWrap) throw new IOException("Simulated TLS provider wrapping failure");
            accepted = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, close);
            return accepted;
        }
        @Override public String[] getDefaultCipherSuites() { return new String[0]; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress h, int p) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
    }
    @Test public void passiveWrapFailureClosesAcceptedSocket() throws Exception {
        checkCleanup(true);
    }
    @Test public void handshakeFailureClosesDataSocketWithoutCallerCleanup() throws Exception {
        checkCleanup(false);
    }
    private void checkCleanup(boolean failWrap) throws Exception {
        FailingTlsFactory tls = new FailingTlsFactory();
        tls.failWrap = failWrap;
        SslConfiguration ssl = (SslConfiguration) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{SslConfiguration.class}, (p, m, a) -> {
                    if ("getSocketFactory".equals(m.getName())) return tls;
                    return null;
                });
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress("127.0.0.1"); data.setSslConfiguration(ssl); data.setIdleTime(2);
        ListenerFactory listener = new ListenerFactory();
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        FtpIoSession session = new FtpIoSession(new DummySession(), null);
        session.setListener(listener.createListener());
        IODataConnectionFactory factory = new IODataConnectionFactory(null, session);
        factory.setSecure(true);
        InetSocketAddress address = factory.initPassiveDataConnection();
        try (Socket peer = new Socket(address.getAddress(), address.getPort())) {
            if (!failWrap) peer.shutdownOutput();
            try { factory.openConnection(); fail("Expected TLS failure"); }
            catch (IOException expected) { if (failWrap) assertTrue(expected.getMessage().contains("wrapping failure")); }
            assertNotNull(tls.accepted);
            assertTrue("Data socket leaked when TLS setup failed", tls.accepted.isClosed());
        } finally {
            factory.closeDataConnection();
            if (tls.accepted != null) tls.accepted.close();
        }
    }
}
