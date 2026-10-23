package com.github.stjiege.ftpshare.utils;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.services.FtpAccessibilityService;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Liveness state of the keep-alive accessibility service.
 *
 * <p>The service itself lives in this app's process, so "process alive" alone proves nothing.
 * What this class tracks is whether the system still keeps the service bound and feeding it:
 * every {@link #HEARTBEAT_INTERVAL_MS} the service reports a heartbeat, and every delivered
 * accessibility event increments a counter. Both are exposed so the UI can tell
 * "granted and running" apart from "granted but revoked/stalled".</p>
 *
 * <p>The last heartbeat is persisted as {@link SystemClock#elapsedRealtime()} (uptime, not wall
 * clock) so a process restart or a user changing the clock can never fake a fresh heartbeat.</p>
 */
public final class AccessibilityKeepAlive {

    /** How often the service reports back while it is connected. */
    public static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L;
    /** Grace window: two missed intervals plus slack before the permission counts as lost. */
    public static final long HEARTBEAT_TIMEOUT_MS = 7L * 60L * 1000L;
    /** Silence this long is the only heartbeat gap worth writing to the log. */
    public static final long HEARTBEAT_SEVERE_TIMEOUT_MS = 30L * 60L * 1000L;

    private static final String PREF_EXPECTED_ENABLED = Constants.PreferenceConsts.ACCESSIBILITY_KEEPALIVE_EXPECTED;
    private static final String PREF_LAST_HEARTBEAT = Constants.PreferenceConsts.ACCESSIBILITY_KEEPALIVE_LAST_BEAT;
    private static final String PREF_MUTED = Constants.PreferenceConsts.ACCESSIBILITY_KEEPALIVE_MUTED;

    public enum State {
        /** Never enabled by the user: nothing to warn about. */
        NOT_EXPECTED,
        /** Bound by the system and reporting inside the grace window. */
        RUNNING,
        /** Was enabled, but the system no longer lists the service: the grant was revoked. */
        GRANT_REVOKED,
        /** Still listed by the system, but no heartbeat for longer than the grace window. */
        NO_HEARTBEAT,
        /**
         * Enabled, but this boot has no heartbeat baseline yet. After a reboot the persisted
         * timestamp belongs to the previous boot, so the gap would be negative: without this
         * state the UI would claim "silent for 0 seconds" immediately after startup.
         */
        NO_BASELINE,
        /** The user dismissed the warning; it is re-armed automatically once heartbeats resume. */
        MUTED
    }

    private static final AtomicLong EVENT_COUNT = new AtomicLong(0L);

    private static volatile boolean connected;
    private static volatile long connectedAtElapsed;
    private static volatile long heartbeatCount;
    private static volatile long lastHeartbeatElapsed;
    private static volatile long lastEventElapsed;
    private static volatile boolean lastSelfCheckBound;
    /** Last outage already written to the log, so one incident produces one line. */
    private static volatile State reportedState;

    private AccessibilityKeepAlive() { }

    /** Called from {@code onServiceConnected}: the system has bound the service. */
    public static void onServiceConnected(Context context, boolean selfCheckBound) {
        Context appContext = context.getApplicationContext();
        boolean firstOfProcess = !connected;
        connected = true;
        connectedAtElapsed = elapsed();
        setExpectedEnabled(appContext, true);
        recordHeartbeat(appContext, selfCheckBound);
        LogManager.debug("无障碍保活服务" + (firstOfProcess ? "已连接" : "已重新连接")
                + "，自检绑定=" + selfCheckBound);
    }

    /** Called when the service is destroyed or unbound. */
    public static void onServiceStopped(Context context) {
        if (!connected) return;
        connected = false;
        LogManager.debug("无障碍保活服务已断开连接");
    }

    /** A periodic "I am still bound and alive" report from the service. */
    public static void recordHeartbeat(Context context, boolean selfCheckBound) {
        long now = elapsed();
        lastHeartbeatElapsed = now;
        lastSelfCheckBound = selfCheckBound;
        heartbeatCount++;
        persistLastHeartbeat(context.getApplicationContext(), now);
    }

    /** The system delivered an accessibility event: proves the binding is really live. */
    public static void recordEvent() {
        EVENT_COUNT.incrementAndGet();
        lastEventElapsed = elapsed();
    }

    /** True while the service is bound by the system and reports inside the grace window. */
    public static boolean isAlive(Context context) {
        return evaluate(context) == State.RUNNING;
    }

    /**
     * Re-evaluates the grant and, on a transition, writes the only two events worth logging:
     * a revoked grant (hard fact) and a heartbeat silence longer than
     * {@link #HEARTBEAT_SEVERE_TIMEOUT_MS}. Everything else stays out of the log.
     */
    public static State evaluate(Context context) {
        if (!isExpectedEnabled(context)) {
            reportedState = null;
            return State.NOT_EXPECTED;
        }
        long since = millisSinceLastHeartbeat(context);
        if (since < 0L) {
            // No heartbeat in this boot yet, so there is nothing to compare against.
            reportedState = null;
            return State.NO_BASELINE;
        }
        boolean granted = isEnabledInSystem(context);
        State state = since <= HEARTBEAT_TIMEOUT_MS
                ? State.RUNNING
                : (granted ? State.NO_HEARTBEAT : State.GRANT_REVOKED);
        if (state == State.RUNNING && isWarningMuted(context)) {
            // Service is healthy again: re-arm so the next outage is reported.
            setWarningMuted(context, false);
        }
        if (isWarningMuted(context)) {
            reportedState = null;
            return State.MUTED;
        }
        reportStateChange(context, state, since);
        return state;
    }

    /**
     * One log line per incident, not per poll: the UI and the FTP service both poll this class,
     * so without the latch a single outage would fill the ring buffer with identical lines.
     */
    private static void reportStateChange(Context context, State state, long since) {
        if (state == State.RUNNING) {
            if (reportedState != null) LogManager.debug("无障碍保活已恢复正常");
            reportedState = null;
            return;
        }
        if (state == State.GRANT_REVOKED) {
            if (reportedState == State.GRANT_REVOKED) return;
            reportedState = State.GRANT_REVOKED;
            LogManager.warning("无障碍保活权限已失效：系统已不再启用该服务，最后一次心跳距今 "
                    + formatDuration(context, since));
            return;
        }
        // Still granted but silent: only a long outage is worth a log line.
        if (since < HEARTBEAT_SEVERE_TIMEOUT_MS) return;
        if (reportedState == State.NO_HEARTBEAT) return;
        reportedState = State.NO_HEARTBEAT;
        LogManager.warning("无障碍保活服务已 " + formatDuration(context, since)
                + " 没有心跳，累计心跳 " + heartbeatCount + " 次、系统事件 " + EVENT_COUNT.get() + " 个");
    }

    /** Negative when no heartbeat has ever been recorded (or after a reboot). */
    public static long millisSinceLastHeartbeat(Context context) {
        long last = Math.max(lastHeartbeatElapsed, persistedLastHeartbeat(context));
        if (last <= 0L) return -1L;
        return elapsed() - last;
    }

    public static long heartbeatCount() { return heartbeatCount; }
    public static long eventCount() { return EVENT_COUNT.get(); }
    public static boolean isConnected() { return connected; }
    public static boolean isLastSelfCheckBound() { return lastSelfCheckBound; }

    /** Uptime millis since the service was last (re)bound in this process, negative if never. */
    public static long millisSinceConnected() {
        return connectedAtElapsed <= 0L ? -1L : elapsed() - connectedAtElapsed;
    }

    public static long millisSinceLastEvent() {
        return lastEventElapsed <= 0L ? -1L : elapsed() - lastEventElapsed;
    }

    /** Whether the system currently lists our service as an enabled accessibility service. */
    public static boolean isEnabledInSystem(Context context) {
        try {
            Object service = context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (!(service instanceof AccessibilityManager)) return false;
            List<AccessibilityServiceInfo> enabled = ((AccessibilityManager) service)
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return false;
            ComponentName expected = new ComponentName(context, FtpAccessibilityService.class);
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null) continue;
                if (expected.equals(ComponentName.unflattenFromString(info.getId()))) return true;
            }
            return false;
        } catch (Exception error) {
            LogManager.debug("读取无障碍服务状态失败: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** Persisted opt-in: the user did enable the service at least once. */
    public static boolean isExpectedEnabled(Context context) {
        return preferences(context).getBoolean(PREF_EXPECTED_ENABLED, false);
    }

    public static void setExpectedEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(PREF_EXPECTED_ENABLED, enabled).apply();
        if (!enabled) reportedState = null;
    }

    /**
     * Dismisses the warning banner. Unlike clearing {@link #PREF_EXPECTED_ENABLED}, this survives
     * the service being rebound, so "ignore" actually sticks until the service reports healthy
     * again (see {@link #evaluate}), at which point the banner is armed for the next outage.
     */
    public static boolean isWarningMuted(Context context) {
        try {
            return preferences(context).getBoolean(PREF_MUTED, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void setWarningMuted(Context context, boolean muted) {
        try {
            preferences(context).edit().putBoolean(PREF_MUTED, muted).apply();
        } catch (Exception ignored) {
            // A UI preference must never be a crash source.
        }
        if (muted) reportedState = null;
    }

    private static void persistLastHeartbeat(Context context, long uptimeMillis) {
        try {
            preferences(context).edit().putLong(PREF_LAST_HEARTBEAT, uptimeMillis).apply();
        } catch (Exception ignored) {
            // A heartbeat must never be a crash source.
        }
    }

    private static long persistedLastHeartbeat(Context context) {
        try {
            return preferences(context).getLong(PREF_LAST_HEARTBEAT, 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return CommonUtils.getSettingSharedPreferences(context);
    }

    private static long elapsed() { return SystemClock.elapsedRealtime(); }

    /** Coarse human-readable duration, e.g. "40 秒", "7 分钟", "2 小时 5 分钟". */
    public static String formatDuration(Context context, long millis) {
        if (millis < 0L) return context.getString(R.string.duration_seconds, 0);
        long seconds = millis / 1000L;
        if (seconds < 60L) return context.getString(R.string.duration_seconds, seconds);
        long minutes = seconds / 60L;
        if (minutes < 60L) return context.getString(R.string.duration_minutes, minutes);
        return context.getString(R.string.duration_hours_minutes, minutes / 60L, minutes % 60L);
    }
}
