package com.github.stjiege.ftpshare.utils;

import android.util.Log;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

/**
 * Bridges Apache log4j 1.x events to Android logcat. This is intended for the
 * FTPServer/MINA dependency chain, which logs through SLF4J → log4j but ships
 * without any appender. Without this appender those internal diagnostics are
 * silently discarded, making FTP stalls and port exhaustion invisible in
 * bugreports.
 */
public final class Log4jLogcatAppender extends AppenderSkeleton {

    private static final int MAX_MESSAGE_LENGTH = 4 * 1024;
    private static final String TAG = "FtpServerInternal";

    @Override
    protected void append(LoggingEvent event) {
        if (event == null) return;

        String message = layout != null
                ? layout.format(event)
                : String.valueOf(event.getMessage());
        message = truncate(message);

        Throwable throwable = null;
        ThrowableInformation ti = event.getThrowableInformation();
        if (ti != null) throwable = ti.getThrowable();

        Level level = event.getLevel();
        if (level == null) {
            Log.i(TAG, message, throwable);
        } else if (level.isGreaterOrEqual(Level.ERROR)) {
            Log.e(TAG, message, throwable);
        } else if (level.isGreaterOrEqual(Level.WARN)) {
            Log.w(TAG, message, throwable);
        } else if (level.isGreaterOrEqual(Level.INFO)) {
            Log.i(TAG, message, throwable);
        } else if (level.isGreaterOrEqual(Level.DEBUG)) {
            Log.d(TAG, message, throwable);
        } else {
            Log.v(TAG, message, throwable);
        }
    }

    @Override
    public void close() {
        // No persistent resources to release.
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }

    private static String truncate(String value) {
        if (value == null) return "null";
        if (value.length() <= MAX_MESSAGE_LENGTH) return value;
        return value.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }
}
