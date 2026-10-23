package com.github.stjiege.ftpshare.shizuku;

interface IShizukuCommandService {
    boolean setDozeWhitelist(boolean enabled);
    boolean isDozeWhitelisted();
    void startWatch();
    void stopWatch();
    boolean isWatching();
    String getWatchStatus();
}
