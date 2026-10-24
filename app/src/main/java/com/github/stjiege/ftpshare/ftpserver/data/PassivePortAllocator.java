package com.github.stjiege.ftpshare.ftpserver.data;

import org.apache.ftpserver.DataConnectionConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the listener-wide configured pool (50000–50063 in production), including legacy sessions. */
public final class PassivePortAllocator {
    private final DataConnectionConfiguration configuration;

    public PassivePortAllocator(DataConnectionConfiguration configuration) { this.configuration = configuration; }

    public Lease bind(InetAddress address) throws IOException {
        int port = configuration.requestPassivePort();
        if (port < 0) throw new IOException("No passive port available");
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.setSoTimeout(configuration.getIdleTime() * 1000);
            TcpSocketBuffer.applyBeforeBind(socket);
            socket.bind(new InetSocketAddress(address, port));
            return new Lease(socket, port, configuration);
        } catch (IOException | RuntimeException error) {
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
            configuration.releasePassivePort(port);
            throw error;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ServerSocket socket;
        private final int reservedPort;
        private final DataConnectionConfiguration configuration;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(ServerSocket socket, int port, DataConnectionConfiguration configuration) {
            this.socket = socket; this.reservedPort = port; this.configuration = configuration;
        }
        public ServerSocket socket() { return socket; }
        /** Interrupt accept immediately; reservation stays owned until ordered cleanup. */
        public void interrupt() { try { socket.close(); } catch (IOException ignored) { } }
        @Override public void close() {
            interrupt();
            if (released.compareAndSet(false, true)) configuration.releasePassivePort(reservedPort);
        }
    }
}
