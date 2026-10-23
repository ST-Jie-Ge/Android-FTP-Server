package com.github.stjiege.ftpshare.ftpserver.data;

import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.IODataConnectionFactory;
import org.apache.ftpserver.impl.ServerDataConnectionFactory;
import org.apache.ftpserver.listener.Listener;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** One data-channel owner per session, shared by migrated and upstream commands. */
public final class SessionDataContext {
    private static final String KEY = FtpIoSession.ATTRIBUTE_PREFIX + "data-connection";
    // Freeze the flag for a server listener, rather than changing existing sessions on UI edits.
    private static final Map<Listener, Boolean> LEGACY = Collections.synchronizedMap(new WeakHashMap<>());

    public static void configure(Listener listener, boolean legacy) { LEGACY.put(listener, legacy); }

    public static ServerDataConnectionFactory get(FtpIoSession session) {
        synchronized (session) {
            ServerDataConnectionFactory current = peek(session);
            if (current == null) {
                // Legacy's context argument is unused; state and TLS configuration live on the session.
                current = Boolean.TRUE.equals(LEGACY.get(session.getListener()))
                        ? new IODataConnectionFactory(null, session) : new DataConnectionManager(session);
                current.setServerControlAddress(((InetSocketAddress) session.getLocalAddress()).getAddress());
                session.setAttribute(KEY, current);
            }
            return current;
        }
    }

    /** Lock-free lookup: cancellation must never wait for accept/connect/handshake. */
    public static ServerDataConnectionFactory peek(FtpIoSession session) {
        return (ServerDataConnectionFactory) session.getAttribute(KEY);
    }

    public static void interrupt(FtpIoSession session) {
        ServerDataConnectionFactory current = peek(session);
        if (current instanceof DataConnectionManager) ((DataConnectionManager) current).interrupt();
        else if (current instanceof IODataConnectionFactory) ((IODataConnectionFactory) current).interruptDataConnection();
    }

    private SessionDataContext() { }
}
