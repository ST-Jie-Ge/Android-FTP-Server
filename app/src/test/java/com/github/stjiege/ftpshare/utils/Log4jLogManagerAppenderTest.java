package com.github.stjiege.ftpshare.utils;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the disconnect classifier in {@link Log4jLogManagerAppender}.
 *
 * <p>This runs on the JVM on purpose: the rest of that class needs Android's
 * {@code LogManager}, but the classifier is pure string/type logic and is exactly the
 * part that decides whether a routine client disconnect shows up as a scary error.</p>
 */
public class Log4jLogManagerAppenderTest {

    @Test public void clientDisconnectIsRecognised() throws Exception {
        assertEquals("连接被重置", reason(new SocketException("Connection reset")));
        assertEquals("连接被重置", reason(new IOException("Connection reset by peer")));
        assertEquals("连接已关闭", reason(new SocketException("Socket closed")));
        assertEquals("连接已关闭", reason(new SocketException("Socket is closed")));
        assertEquals("管道已断开", reason(new IOException("Broken pipe")));
        assertEquals("连接被中止", reason(new SocketException("Software caused connection abort")));
        assertEquals("连接已关闭", reason(new ClosedChannelException()));
    }

    /**
     * A {@code SocketTimeoutException} is our own {@code SO_TIMEOUT} (60 s on the FTP data
     * socket) expiring — the server gave up waiting, which is evidence of a stall rather than
     * of a peer that hung up. It must escape the disconnect branch, otherwise the one event
     * class that can prove a fault gets downgraded to a silent one-line warning.
     */
    @Test public void ourOwnTimeoutIsNotAPeerDisconnect() throws Exception {
        assertNull(reason(new SocketTimeoutException("Read timed out")));
        assertNull(reason(new SocketTimeoutException("connect timed out")));
        assertTrue(serverTimeout(new SocketTimeoutException("Read timed out")));
        assertTrue(serverTimeout(new SocketException("Read timed out")));
        assertTrue(serverTimeout(
                new IllegalStateException("transfer failed", new SocketTimeoutException("Read timed out"))));
    }

    @Test public void peerVanishedIsNotATimeout() throws Exception {
        assertFalse(serverTimeout(new SocketException("Connection reset")));
        assertFalse(serverTimeout(new IOException("Broken pipe")));
        assertFalse(serverTimeout(new SocketException("Socket closed")));
        assertFalse(serverTimeout(null));
    }

    @Test public void disconnectHidingInsideAWrapperIsStillFound() throws Exception {
        assertEquals("连接被重置",
                reason(new IllegalStateException("transfer failed", new SocketException("Connection reset"))));
    }

    @Test public void realFaultsKeepTheirStackTrace() throws Exception {
        assertNull(reason(new IllegalStateException("boom")));
        assertNull(reason(new IOException("No space left on device")));
        assertNull(reason(new SocketException("Permission denied")));
        assertNull(reason(null));
    }

    private static Object reason(Throwable cause) throws Exception {
        Method method = Log4jLogManagerAppender.class
                .getDeclaredMethod("disconnectReason", Throwable.class);
        method.setAccessible(true);
        return method.invoke(null, cause);
    }

    private static boolean serverTimeout(Throwable cause) throws Exception {
        Method method = Log4jLogManagerAppender.class
                .getDeclaredMethod("isServerSideTimeout", Throwable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, cause);
    }
}
