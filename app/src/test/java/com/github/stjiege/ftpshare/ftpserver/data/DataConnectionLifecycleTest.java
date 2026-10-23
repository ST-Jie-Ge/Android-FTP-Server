package com.github.stjiege.ftpshare.ftpserver.data;

import org.apache.ftpserver.DataConnectionConfiguration;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.IODataConnectionFactory;
import org.apache.ftpserver.impl.ServerDataConnectionFactory;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.mina.core.session.DummySession;
import org.junit.Test;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class DataConnectionLifecycleTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private FtpIoSession session(DataConnectionConfiguration configuration) {
        FtpIoSession session = new FtpIoSession(new DummySession(), null) {
            @Override public SocketAddress getLocalAddress() { return new InetSocketAddress(LOOPBACK, 2121); }
            @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(LOOPBACK, 12345); }
        };
        ListenerFactory listener = new ListenerFactory();
        listener.setDataConnectionConfiguration(configuration); session.setListener(listener.createListener());
        return session;
    }
    private DataConnectionConfigurationFactory configuration() {
        DataConnectionConfigurationFactory config = new DataConnectionConfigurationFactory();
        config.setPassiveAddress(LOOPBACK.getHostAddress()); config.setIdleTime(2);
        return config;
    }
    @Test public void backendIsFrozenPerSessionAndBridgeUsesSameOwner() {
        FtpIoSession session = session(configuration().createDataConnectionConfiguration());
        assertNull(SessionDataContext.peek(session));
        assertTrue(SessionDataContext.get(session) instanceof DataConnectionManager);
        assertSame(SessionDataContext.get(session), session.getDataConnection());
        SessionDataContext.configure(session.getListener(), true);
        assertTrue(SessionDataContext.get(session) instanceof DataConnectionManager);
        FtpIoSession legacy = session(configuration().createDataConnectionConfiguration());
        SessionDataContext.configure(legacy.getListener(), true);
        assertTrue(SessionDataContext.get(legacy) instanceof IODataConnectionFactory);
    }
    @Test public void bindFailureReturnsReservation() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, LOOPBACK)) {
            DataConnectionConfigurationFactory config = configuration();
            config.setPassivePorts(Integer.toString(occupied.getLocalPort()));
            DataConnectionConfiguration pool = config.createDataConnectionConfiguration();
            occupied.close(); // Reserve a free port, then force bind to a non-local address to fail.
            try { new PassivePortAllocator(pool).bind(InetAddress.getByName("192.0.2.1")); fail("Bind must fail"); }
            catch (IOException expected) { }
            assertEquals(occupied.getLocalPort(), pool.requestPassivePort());
            pool.releasePassivePort(occupied.getLocalPort());
        }
    }
    @Test public void configuredPassivePoolContainsExactly64Ports() throws Exception {
        DataConnectionConfigurationFactory config = configuration();
        // No sockets required to establish that production's configured pool is exactly 64.
        config.setPassivePorts("50000-50063");
        DataConnectionConfiguration pool = config.createDataConnectionConfiguration();
        java.util.Set<Integer> allocated = new java.util.HashSet<>();
        for (int i = 0; i < 64; i++) assertTrue(allocated.add(pool.requestPassivePort()));
        assertEquals(-1, pool.requestPassivePort());
        for (int port : allocated) pool.releasePassivePort(port);
        assertTrue(pool.requestPassivePort() >= 50000);
    }
    @Test public void doubleCloseCannotReleaseANewerLease() throws Exception {
        DataConnectionConfigurationFactory config = configuration();
        try (ServerSocket free = new ServerSocket(0, 1, LOOPBACK)) {
            config.setPassivePorts(Integer.toString(free.getLocalPort()));
        }
        DataConnectionConfiguration pool = config.createDataConnectionConfiguration();
        PassivePortAllocator allocator = new PassivePortAllocator(pool);
        PassivePortAllocator.Lease first = allocator.bind(LOOPBACK);
        first.close();
        try (PassivePortAllocator.Lease second = allocator.bind(LOOPBACK)) {
            first.close();
            assertEquals(-1, pool.requestPassivePort());
        }
        int port = pool.requestPassivePort();
        assertTrue(port > 0); pool.releasePassivePort(port);
    }
    @Test public void abortUnblocksAcceptAndAllowsNextPasv() throws Exception {
        FtpIoSession session = session(configuration().createDataConnectionConfiguration());
        ServerDataConnectionFactory manager = SessionDataContext.get(session);
        manager.initPassiveDataConnection();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            Future<?> pending = worker.submit(() -> {
                entered.countDown();
                try { manager.openConnection(); throw new AssertionError("Cancelled accept must fail"); }
                catch (Exception expected) { }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            SessionDataContext.interrupt(session);
            pending.get(1, TimeUnit.SECONDS);
            assertNull(manager.getInetAddress());
            InetSocketAddress next = manager.initPassiveDataConnection();
            try (Socket peer = new Socket(next.getAddress(), next.getPort())) {
                assertTrue(manager.openConnection() instanceof SocketDataConnection);
            }
        } finally { SessionDataContext.interrupt(session); manager.dispose(); worker.shutdownNow(); }
    }
    @Test public void activeModeDisabledDoesNotConnect() throws Exception {
        DataConnectionConfigurationFactory config = configuration(); config.setActiveEnabled(false);
        ServerDataConnectionFactory manager = SessionDataContext.get(session(config.createDataConnectionConfiguration()));
        manager.initActiveDataConnection(new InetSocketAddress(LOOPBACK, 12345));
        try { manager.openConnection(); fail("Active mode must be disabled"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("disabled")); }
        assertNull(manager.getInetAddress());
    }
    @Test public void peerCheckNormalizesMappedIpv4() throws Exception {
        byte[] mapped = {0,0,0,0,0,0,0,0,0,0,(byte)255,(byte)255,127,0,0,1};
        assertTrue(ActiveConnectionFactory.sameAddress(InetAddress.getByName("127.0.0.1"),
                Inet6Address.getByAddress(null, mapped, -1)));
        assertFalse(ActiveConnectionFactory.sameAddress(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")));
    }
    @Test public void tlsWrapFailureClosesAcceptedSocket() throws Exception { tlsFailure(true); }
    @Test public void tlsHandshakeFailureClosesAcceptedSocket() throws Exception { tlsFailure(false); }

    @Test public void abortCanInterruptTlsSetupWithoutWaitingForItsLock() throws Exception {
        CountDownLatch wrapping = new CountDownLatch(1);
        SSLSocketFactory factory = new SSLSocketFactory() {
            @Override public Socket createSocket(Socket plain, String h, int p, boolean close) throws IOException {
                Socket tls = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, h, p, close);
                wrapping.countDown();
                return tls;
            }
            @Override public String[] getDefaultCipherSuites() { return new String[0]; }
            @Override public String[] getSupportedCipherSuites() { return new String[0]; }
            @Override public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress h, int p) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        };
        SslConfiguration ssl = (SslConfiguration) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{SslConfiguration.class}, (p,m,a) -> m.getName().equals("getSocketFactory") ? factory : null);
        DataConnectionConfigurationFactory config = configuration(); config.setSslConfiguration(ssl);
        FtpIoSession session = session(config.createDataConnectionConfiguration());
        ServerDataConnectionFactory manager = SessionDataContext.get(session); manager.setSecure(true);
        InetSocketAddress address = manager.initPassiveDataConnection();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Socket peer = new Socket(address.getAddress(), address.getPort())) {
            Future<?> pending = worker.submit(() -> {
                try { manager.openConnection(); throw new AssertionError("Cancelled handshake must fail"); }
                catch (Exception expected) { }
            });
            assertTrue(wrapping.await(1, TimeUnit.SECONDS));
            SessionDataContext.interrupt(session);
            pending.get(1, TimeUnit.SECONDS);
            assertNull(manager.getInetAddress());
        } finally { manager.dispose(); worker.shutdownNow(); }
    }

    private void tlsFailure(boolean failWrap) throws Exception {
        final Socket[] owned = new Socket[1];
        SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocketFactory failing = new SSLSocketFactory() {
            @Override public Socket createSocket(Socket plain, String host, int port, boolean close) throws IOException {
                owned[0] = plain;
                if (failWrap) throw new IOException("wrap failed");
                return delegate.createSocket(plain, host, port, close);
            }
            @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
            @Override public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress h, int p) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        };
        SslConfiguration ssl = (SslConfiguration) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{SslConfiguration.class}, (p,m,a) -> m.getName().equals("getSocketFactory") ? failing : null);
        DataConnectionConfigurationFactory config = configuration(); config.setSslConfiguration(ssl);
        ServerDataConnectionFactory manager = SessionDataContext.get(session(config.createDataConnectionConfiguration()));
        manager.setSecure(true);
        InetSocketAddress address = manager.initPassiveDataConnection();
        try (Socket peer = new Socket(address.getAddress(), address.getPort())) {
            peer.shutdownOutput();
            try { manager.openConnection(); fail("TLS failure expected"); } catch (IOException expected) { }
            assertNotNull(owned[0]); assertTrue(owned[0].isClosed()); assertNull(manager.getInetAddress());
        } finally { manager.dispose(); }
    }
}
