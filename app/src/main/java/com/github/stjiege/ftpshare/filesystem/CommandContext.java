package com.github.stjiege.ftpshare.filesystem;

/**
 * 命令名上下文，供文件系统层判断当前命令语义（例如 APPE 不应走 staged 上传）。
 *
 * <p>由命令调度层（当前是 {@code DefaultFtpHandler}）在调用具体命令前设置，
 * 命令结束后清除，避免池化线程复用时把上一个命令的标识带到下一次任务。</p>
 */
public final class CommandContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static void set(String command) { CURRENT.set(command); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    private CommandContext() { }
}
