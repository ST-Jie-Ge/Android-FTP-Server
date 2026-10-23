package com.github.stjiege.ftpshare.utils;

/** UID counter deltas. Rates are bytes/second; negative sentinels never become a speed. */
public final class TrafficRates {
    public static final double UNKNOWN = -1;
    public static final double PENDING = -2;
    private long lastTime = -1, lastTx = -1, lastRx = -1;
    public double[] sample(long tx, long rx, long timeMillis) {
        long elapsed = lastTime < 0 ? 0 : timeMillis - lastTime;
        double[] result = {rate(tx, lastTx, elapsed), rate(rx, lastRx, elapsed)};
        lastTime = timeMillis; lastTx = tx; lastRx = rx;
        return result;
    }
    private static double rate(long current, long previous, long elapsed) {
        if (current < 0) return UNKNOWN;
        if (previous < 0 || current < previous || elapsed <= 0) return PENDING;
        return (current - previous) * 1000.0 / elapsed;
    }
    /**
     * 非线性标尺：0 -> 0%，150 Mbps -> 50%，**600 Mbps -> 100%**。两段连续直线：
     * 低速段每 3 Mbps 涨 1%，高速段每 9 Mbps 涨 1% —— 常见的几十 Mbps 也能在条上看出变化，
     * 而不会因为量程拉到 600 Mbps 就被压成贴底的一条线。
     */
    public static double progress(double bytesPerSecond) {
        double mbps = Math.max(0, bytesPerSecond) * 8.0 / 1_000_000.0;
        return Math.min(100, mbps <= 150 ? mbps / 3.0 : 50 + (mbps - 150) / 9.0);
    }
}
