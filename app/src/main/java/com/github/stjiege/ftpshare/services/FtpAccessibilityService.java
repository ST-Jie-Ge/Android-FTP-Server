package com.github.stjiege.ftpshare.services;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.github.stjiege.ftpshare.utils.AccessibilityKeepAlive;
import com.github.stjiege.ftpshare.utils.LogManager;

/**
 * Minimal keep-alive accessibility service.
 *
 * <p>It performs no assistive work: it does not retrieve window contents, inspect text, inject
 * gestures, register the system accessibility button, or react to app events. Its purpose is to
 * let the user enable a bare "accessibility service" binding so that Android grants this app a
 * higher process priority (a lower oom_adj), which makes the system much less likely to kill the
 * FTP service in the background.</p>
 *
 * <p>What it does beyond existing is self-reporting: every {@link AccessibilityKeepAlive#
 * HEARTBEAT_INTERVAL_MS} it checks that the system still keeps it bound and reports a heartbeat
 * back to the app, together with a count of the events the system has delivered. That turns the
 * binding from an invisible assumption into an observable state: the UI can tell "granted and
 * running" from "the system revoked the grant", and warn the user instead of silently losing the
 * protection.</p>
 */
public final class FtpAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            handler.postDelayed(this, AccessibilityKeepAlive.HEARTBEAT_INTERVAL_MS);
            try {
                AccessibilityKeepAlive.recordHeartbeat(FtpAccessibilityService.this, isStillBound());
            } catch (Exception error) {
                LogManager.warning("无障碍保活心跳上报失败: " + error.getClass().getSimpleName());
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(heartbeat);
        try {
            AccessibilityKeepAlive.onServiceConnected(this, isStillBound());
        } catch (Exception error) {
            LogManager.warning("无障碍保活状态初始化失败: " + error.getClass().getSimpleName());
        }
        // Report immediately so a fresh grant is visible without waiting for the first interval.
        handler.postDelayed(heartbeat, AccessibilityKeepAlive.HEARTBEAT_INTERVAL_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No assistive reaction: the event is only evidence that the system keeps feeding us.
        AccessibilityKeepAlive.recordEvent();
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(heartbeat);
        try {
            AccessibilityKeepAlive.onServiceStopped(this);
        } catch (Exception ignored) {
            // Tear-down must never throw.
        }
        super.onDestroy();
    }

    /** Non-null service info means the system still holds our binding. */
    private boolean isStillBound() {
        try {
            return getServiceInfo() != null;
        } catch (Exception error) {
            return false;
        }
    }
}
