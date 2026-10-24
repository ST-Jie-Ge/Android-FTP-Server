package com.github.stjiege.ftpshare.ftpserver.data;

import org.apache.ftpserver.DataConnectionConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/** Active TCP establishment, with the same peer restrictions as PORT/EPRT. */
public final class ActiveConnectionFactory {
    public interface SocketOwner { void publish(Socket socket) throws IOException; }

    public Socket connect(DataConnectionConfiguration configuration, InetSocketAddress remote,
                          InetAddress controlPeer, InetAddress controlLocal, SocketOwner owner) throws IOException {
        if (!configuration.isActiveEnabled()) throw new IOException("Active connections disabled");
        if (remote == null || remote.isUnresolved() || remote.getPort() == 0) throw new IOException("Invalid active endpoint");
        if (configuration.isActiveIpCheck() && !sameAddress(controlPeer, remote.getAddress())) {
            throw new IOException("Active peer does not match control connection");
        }
        String configuredLocal = configuration.getActiveLocalAddress();
        InetAddress local = configuredLocal == null ? controlLocal : InetAddress.getByName(configuredLocal);
        Socket socket = new Socket();
        try {
            owner.publish(socket); // ABOR can close the socket even while connect is blocked.
            TcpSocketBuffer.applyBeforeConnect(socket);
            socket.setReuseAddress(true);
            socket.setTcpNoDelay(true);
            socket.bind(new InetSocketAddress(local, configuration.getActiveLocalPort()));
            socket.connect(remote, 10_000);
            return socket;
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    public static boolean sameAddress(InetAddress left, InetAddress right) {
        return left != null && right != null && Arrays.equals(unmap(left.getAddress()), unmap(right.getAddress()));
    }
    private static byte[] unmap(byte[] address) {
        if (address.length != 16) return address;
        for (int i = 0; i < 10; i++) if (address[i] != 0) return address;
        return address[10] == (byte) 255 && address[11] == (byte) 255
                ? Arrays.copyOfRange(address, 12, 16) : address;
    }
}
