package com.github.stjiege.ftpshare.ftpserver.data;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.LogManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;

/** Optional SO_RCVBUF/SO_SNDBUF requests for FTP data sockets. */
public final class TcpSocketBuffer {
    private static volatile int requestedBytes = Constants.PreferenceConsts.FTP_SOCKET_BUFFER_SYSTEM_DEFAULT;
    private static final ConcurrentHashMap<String, String> LAST_REPORTED = new ConcurrentHashMap<>();

    private TcpSocketBuffer() { }

    public static void configure(int value) {
        if (value == Constants.PreferenceConsts.FTP_SOCKET_BUFFER_4M
                || value == Constants.PreferenceConsts.FTP_SOCKET_BUFFER_8M
                || value == Constants.PreferenceConsts.FTP_SOCKET_BUFFER_16M) {
            requestedBytes = value;
        } else {
            requestedBytes = Constants.PreferenceConsts.FTP_SOCKET_BUFFER_SYSTEM_DEFAULT;
        }
    }

    public static int requestedBytes() { return requestedBytes; }

    /** Must run before bind so the listening socket can advertise a larger receive window. */
    public static void applyBeforeBind(ServerSocket socket) {
        int requested = requestedBytes;
        if (requested <= 0 || socket == null) return;
        try {
            socket.setReceiveBufferSize(requested);
        } catch (IOException | RuntimeException error) {
            LogManager.warning("申请被动数据连接接收缓存失败（请求 " + requested + " 字节）："
                    + error.getClass().getSimpleName());
        }
        report("被动监听", requested, receiveBufferSize(socket), -1);
    }

    /** Apply both directions before an active-mode TCP handshake. */
    public static void applyBeforeConnect(Socket socket) {
        applySocketBuffers(socket, "主动连接", true);
    }

    /** The passive listener already sets SO_RCVBUF before bind; set the accepted socket's send side. */
    public static void applyAccepted(Socket socket) {
        applySocketBuffers(socket, "被动连接", false);
    }

    private static void applySocketBuffers(Socket socket, String mode, boolean setReceive) {
        if (socket == null) return;
        int requested = requestedBytes;
        if (requested > 0) {
            if (setReceive) {
                try {
                    socket.setReceiveBufferSize(requested);
                } catch (SocketException | RuntimeException error) {
                    LogManager.warning("申请" + mode + "接收缓存失败（请求 " + requested + " 字节）："
                            + error.getClass().getSimpleName());
                }
            }
            try {
                socket.setSendBufferSize(requested);
            } catch (SocketException | RuntimeException error) {
                LogManager.warning("申请" + mode + "发送缓存失败（请求 " + requested + " 字节）："
                        + error.getClass().getSimpleName());
            }
        }
        report(mode, requested, receiveBufferSize(socket), sendBufferSize(socket));
    }

    private static int receiveBufferSize(ServerSocket socket) {
        try { return socket.getReceiveBufferSize(); } catch (IOException | RuntimeException ignored) { return -1; }
    }

    private static int receiveBufferSize(Socket socket) {
        try { return socket.getReceiveBufferSize(); } catch (SocketException | RuntimeException ignored) { return -1; }
    }

    private static int sendBufferSize(Socket socket) {
        try { return socket.getSendBufferSize(); } catch (SocketException | RuntimeException ignored) { return -1; }
    }

    private static void report(String mode, int requested, int receive, int send) {
        if (requested <= 0) return;
        String state = requested + "/" + receive + "/" + send;
        String previous = LAST_REPORTED.put(mode, state);
        if (!state.equals(previous)) {
            LogManager.info("TCP 数据连接缓存（" + mode + "）：请求 " + requested
                    + " 字节；系统回报接收 " + receive + " 字节"
                    + (send >= 0 ? "、发送 " + send + " 字节" : ""));
        }
    }
}
