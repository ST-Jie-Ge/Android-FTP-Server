package com.github.stjiege.ftpshare.services;

import org.apache.ftpserver.ConnectionConfig;
import org.apache.ftpserver.impl.DefaultConnectionConfig;

/**
 * FTP 连接配置。原来作为 {@code FtpService} 的内部类存在，抽出来一是为了让上帝类瘦身，
 * 二是它本身已经是一个完整的、可独立测试的配置值对象。
 *
 * 只覆盖四个业务可调项（匿名开关、匿名上限、全局登录上限、工作线程数）；登录失败次数
 * {@code maxLoginFailures} 保持 Apache 默认值（透传），失败延时 {@code loginFailureDelay}
 * 固定为 {@link #LOGIN_FAILURE_DELAY_MS}。
 */
public final class FtpConnectionConfig implements ConnectionConfig {
    /**
     * 登录失败后的强制延时（毫秒）。
     *
     * Apache 默认 500 ms：客户端每重试一次就多等半秒，而 {@code maxLoginFailures} 只有 3 次，
     * 最坏要等 1.5 秒才拿到响应——很多客户端自己先超时了，表现为"偶尔连不上，再点一次就好"。
     * 固定 200 ms 仍足以拖慢暴力破解（每秒最多 5 次尝试，且只有 3 次机会），又不至于被判超时。
     */
    public static final int LOGIN_FAILURE_DELAY_MS = 200;
    private final DefaultConnectionConfig defaults = new DefaultConnectionConfig();
    private int maxAnonymousLogins = defaults.getMaxAnonymousLogins();
    private int maxLogins = defaults.getMaxLogins();
    private int maxThreads = defaults.getMaxThreads();
    private boolean anonymousEnabled = defaults.isAnonymousLoginEnabled();

    public void setAnonymousEnabled(boolean anonymousEnabled) {
        this.anonymousEnabled = anonymousEnabled;
    }

    @Override
    public int getMaxLoginFailures() {
        return defaults.getMaxLoginFailures();
    }

    @Override
    public int getLoginFailureDelay() {
        return LOGIN_FAILURE_DELAY_MS;
    }

    @Override
    public int getMaxAnonymousLogins() {
        return maxAnonymousLogins;
    }

    public void setMaxAnonymousLogins(int maxAnonymousLogins) {
        this.maxAnonymousLogins = maxAnonymousLogins;
    }

    @Override
    public int getMaxLogins() {
        return maxLogins;
    }

    public void setMaxLogins(int maxLogins) {
        this.maxLogins = maxLogins;
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    @Override
    public boolean isAnonymousLoginEnabled() {
        return anonymousEnabled;
    }

    @Override
    public int getMaxThreads() {
        return maxThreads;
    }
}
