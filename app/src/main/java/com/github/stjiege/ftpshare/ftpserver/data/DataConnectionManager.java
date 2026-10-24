package com.github.stjiege.ftpshare.ftpserver.data;

import com.github.stjiege.ftpshare.utils.LogManager;
import org.apache.ftpserver.DataConnectionConfiguration;
import org.apache.ftpserver.DataConnectionException;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.ServerDataConnectionFactory;
import org.apache.ftpserver.ssl.SslConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Per-session data endpoint, socket ownership, protection and cancellation lifecycle. */
public final class DataConnectionManager implements ServerDataConnectionFactory {
    private final FtpIoSession session;
    private final DataConnectionConfiguration configuration;
    private final PassivePortAllocator ports;
    private final ActiveConnectionFactory activeFactory = new ActiveConnectionFactory();
    private final TlsSocketFactory tlsFactory = new TlsSocketFactory();
    private volatile Socket socket;
    private volatile PassivePortAllocator.Lease passiveLease;
    private volatile boolean interrupted;
    private volatile boolean secure;
    private volatile boolean zip;
    private volatile InetSocketAddress endpoint;
    private InetAddress controlLocal;
    private volatile long requestedAt;

    public DataConnectionManager(FtpIoSession session) {
        this.session = session;
        configuration = session.getListener().getDataConnectionConfiguration();
        ports = new PassivePortAllocator(configuration);
        secure = configuration.isImplicitSsl();
    }

    @Override public synchronized void initActiveDataConnection(InetSocketAddress endpoint) {
        closeDataConnection();
        interrupted = false;
        this.endpoint = endpoint;
        requestedAt = System.nanoTime();
    }

    @Override public synchronized InetSocketAddress initPassiveDataConnection() throws DataConnectionException {
        closeDataConnection();
        interrupted = false;
        try {
            if (secure && sslConfiguration() == null) throw new IOException("Data TLS is not configured");
            InetAddress bindAddress = configuration.getPassiveAddress() == null
                    ? controlLocal : InetAddress.getByName(configuration.getPassiveAddress());
            passiveLease = ports.bind(bindAddress);
            checkCancelled();
            endpoint = new InetSocketAddress(passiveLease.socket().getInetAddress(), passiveLease.socket().getLocalPort());
            requestedAt = System.nanoTime();
            return endpoint;
        } catch (Exception error) {
            closeDataConnection();
            throw new DataConnectionException("Cannot initialize passive connection", error);
        }
    }

    @Override public synchronized DataConnection openConnection() throws Exception {
        try {
            checkCancelled();
            if (endpoint == null || socket != null) throw new IOException("Issue PORT or PASV before transfer");
            InetAddress peer = ((InetSocketAddress) session.getRemoteAddress()).getAddress();
            if (passiveLease != null) {
                long started = System.nanoTime();
                publish(passiveLease.socket().accept());
                long waited = (System.nanoTime() - started) / 1_000_000L;
                if (waited >= 1000) LogManager.warning("数据端口等了 " + waited + " ms 才等到客户端连接");
                // Check the peer before spending resources on TLS.
                if (configuration.isPassiveIpCheck() && !ActiveConnectionFactory.sameAddress(peer, socket.getInetAddress())) {
                    throw new IOException("Passive peer does not match control connection");
                }
                // Stop accepting extra peers, but keep the port reserved through transfer cleanup.
                passiveLease.interrupt();
            } else {
                activeFactory.connect(configuration, endpoint, peer, controlLocal, this::publish);
            }
            checkCancelled();
            if (passiveLease != null) TcpSocketBuffer.applyAccepted(socket);
            int timeout = configuration.getIdleTime() * 1000;
            socket.setSoTimeout(timeout);
            // Optional SO_RCVBUF/SO_SNDBUF requests were applied at bind/connect/accept.
            socket.setTcpNoDelay(true);
            if (secure) tlsFactory.secure(socket, sslConfiguration(), timeout, this::publish);
            checkCancelled();
            return new SocketDataConnection(socket, session, this);
        } catch (Exception error) {
            closeDataConnection();
            throw error;
        }
    }

    private SslConfiguration sslConfiguration() {
        SslConfiguration ssl = configuration.getSslConfiguration();
        return ssl == null ? session.getListener().getSslConfiguration() : ssl;
    }

    private void publish(Socket acquired) throws IOException {
        socket = acquired;
        // Covers ABOR between accept/createSocket returning and ownership publication.
        if (interrupted) {
            try { acquired.close(); } catch (IOException ignored) { }
            throw new IOException("Data connection cancelled");
        }
    }

    private void checkCancelled() throws IOException {
        if (interrupted) throw new IOException("Data connection cancelled");
    }

    /** Never acquires the lock held by a blocking accept/connect/TLS handshake. */
    public void interrupt() {
        interrupted = true;
        PassivePortAllocator.Lease lease = passiveLease;
        if (lease != null) lease.interrupt();
        Socket active = socket;
        try { if (active != null) active.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void closeDataConnection() {
        Socket previous = socket;
        socket = null;
        try { if (previous != null) previous.close(); } catch (IOException ignored) { }
        PassivePortAllocator.Lease lease = passiveLease;
        passiveLease = null;
        if (lease != null) lease.close();
        endpoint = null;
        requestedAt = 0;
    }

    @Override public boolean isTimeout(long ignoredWallClock) {
        long since = requestedAt;
        return since != 0 && socket == null && configuration.getIdleTime() > 0
                && (System.nanoTime() - since) / 1_000_000_000L >= configuration.getIdleTime();
    }
    @Override public void dispose() { interrupt(); closeDataConnection(); }
    @Override public void setServerControlAddress(InetAddress address) { controlLocal = address; }
    @Override public InetAddress getInetAddress() { InetSocketAddress current = endpoint; return current == null ? null : current.getAddress(); }
    @Override public int getPort() { InetSocketAddress current = endpoint; return current == null ? 0 : current.getPort(); }
    @Override public boolean isSecure() { return secure; }
    @Override public void setSecure(boolean value) { secure = value; }
    @Override public boolean isZipMode() { return zip; }
    @Override public void setZipMode(boolean value) { zip = value; }
}
