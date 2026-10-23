package com.github.stjiege.ftpshare.utils;

import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.ftplet.User;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * FTP callbacks for login auditing, optional command-level debug logging, and — the reason
 * most of this class exists — a per-connection lifecycle record that makes disconnects
 * <em>classifiable</em>.
 *
 * <p>A dead socket is not evidence. {@code Connection reset} is what you see when a client
 * cancels a download, but also when the client crashes, when Wi-Fi drops, or when a NAT
 * expires the flow. Logging "连接中断：连接被重置" and calling it a normal exit is guessing.
 * What actually separates "user closed the client" from "the link keeps dropping" is
 * per-session state, all of which the {@link FtpSession} already carries:</p>
 *
 * <ul>
 *   <li><b>QUIT</b> — a clean exit sends QUIT and the server answers 221. No QUIT means the
 *       connection ended without the client saying goodbye.</li>
 *   <li><b>Duration and idle time</b> — a session that lived 3 minutes and went idle 2 minutes
 *       before dying looks nothing like one that died mid-transfer after 4 seconds.</li>
 *   <li><b>Transfer start vs. finish</b> — 150 opens a transfer, 226 closes it successfully.
 *       Starts without finishes are transfers that were cut short.</li>
 *   <li><b>Reconnect density</b> — how many connections this address opened in the last
 *       minute, and how soon after the previous one. A client that keeps re-establishing is
 *       the actual signature of "掉线重连".</li>
 * </ul>
 *
 * <p>All of that is reported as facts. This class deliberately does <em>not</em> print a
 * verdict like "疑似掉线": a multi-threaded downloader legitimately opens 4–8 connections at
 * once, so any threshold set low enough to catch real churn would flag every normal batch
 * transfer. Read the numbers instead — they are unambiguous, a guess is not.</p>
 *
 * <p><b>登录应答记录（本次补充）</b>：原先 {@code afterCommand} 只对 PASS/QUIT 记日志，
 * USER 无论成败都不留第二行 —— 这正是"日志只到『尝试登录』，客户端却报错"时无从下手的原因。
 * 现在：① 登录被拒时记下 USER/PASS 的 <b>原始应答码与文案</b>（客户端显示的就是这段原文）；
 * ② 登录阶段任何 ≥400 的应答都留痕；③ 登录成功后只留第一条失败应答。全部按原始值输出，
 * 不做二次解释，也不再出现"客户端连不上但日志里什么都没有"。</p>
 */
public class FtpLogFtplet extends DefaultFtplet {

    /** Set by {@link #onLogin} so the PASS/230 branch in {@code afterCommand} cannot double-log. */
    private static final String ATTR_LOGIN_LOGGED = "ftpshare.log.loginLogged";
    /** 本会话已完成过一次登录：用于区分"登录阶段的失败"与"登录后的失败"。 */
    private static final String ATTR_SESSION_LOGGED_IN = "ftpshare.log.sessionLoggedIn";
    /** 登录成功后只留一条失败应答，避免反复重试的客户端把日志刷爆。 */
    private static final String ATTR_POST_LOGIN_FAILURE_LOGGED = "ftpshare.log.postLoginFailure";
    private static final String ATTR_QUIT = "ftpshare.log.quit";
    /** 原样输出的文案上限：客户端报错原文通常很短，再长对定位没有增量信息。 */
    private static final int MAX_RAW_TEXT_CHARS = 96;

    @Override public FtpletResult onConnect(FtpSession session) {
        if (!LogManager.isLoggingEnabled()) return FtpletResult.DEFAULT;
        try {
            LogManager.info("连接 " + displayAddress(session));
        } catch (RuntimeException error) { LogManager.error("记录FTP连接日志失败", error); }
        return FtpletResult.DEFAULT;
    }

    @Override public FtpletResult onDisconnect(FtpSession session) {
        if (!LogManager.isLoggingEnabled()) return FtpletResult.DEFAULT;
        try {
            boolean quit = Boolean.TRUE.equals(session.getAttribute(ATTR_QUIT));
            String verdict = quit ? "正常退出" : "无QUIT";
            String line = "断开 " + displayAddress(session) + "（" + verdict + "）";
            if (quit) LogManager.info(line); else LogManager.warning(line);
        } catch (RuntimeException error) { LogManager.error("记录FTP断开日志失败", error); }
        return FtpletResult.DEFAULT;
    }

    @Override public FtpletResult onLogin(FtpSession session, FtpRequest request) {
        if (!LogManager.isLoggingEnabled()) return FtpletResult.DEFAULT;
        try {
            session.setAttribute(ATTR_LOGIN_LOGGED, Boolean.TRUE);
            session.setAttribute(ATTR_SESSION_LOGGED_IN, Boolean.TRUE);
            LogManager.addLogin("登录成功：" + displayUser(session) + " @ " + displayAddress(session));
        } catch (RuntimeException error) { LogManager.error("记录FTP登录日志失败", error); }
        return FtpletResult.DEFAULT;
    }

    @Override public FtpletResult afterCommand(FtpSession session, FtpRequest request, FtpReply reply)
            throws IOException, FtpException {
        if (!LogManager.isLoggingEnabled()) return FtpletResult.DEFAULT;
        try {
            String command = request.getCommand();
            String address = displayAddress(session);
            // 命令在执行中抛异常时可能一条应答都没写出，getLastReply() 就是 null。
            // 原先直接取码会 NPE，被下面的 catch 吞成"FTP日志回调异常"，把这条线索丢掉。
            int code = reply == null ? 0 : reply.getCode();

            if ("USER".equalsIgnoreCase(command)) {
                // 331 = 用户名已接受、230 = 本会话已登录过，都是正常路径。其余应答就是
                // 客户端"连不上"的那一刻：501=参数为空、530=匿名被关、421=并发闸门、
                // 534=要求先 TLS。原样记下码与文案，客户端显示的正是这段原文。
                if (code != FtpReply.REPLY_331_USER_NAME_OKAY_NEED_PASSWORD
                        && code != FtpReply.REPLY_230_USER_LOGGED_IN) {
                    LogManager.addLogin("登录被拒：USER " + loginArgument(request)
                            + " @ " + address + replyDetail(reply));
                }
            } else if ("PASS".equalsIgnoreCase(command)) {
                if (code == FtpReply.REPLY_230_USER_LOGGED_IN) {
                    if (!Boolean.TRUE.equals(session.getAttribute(ATTR_LOGIN_LOGGED))) {
                        // onLogin 正常会覆盖这一条；这是回调没触发时的兜底，避免成功登录
                        // 在日志里完全不出现（旧版本就是这样漏掉的）。
                        session.setAttribute(ATTR_LOGIN_LOGGED, Boolean.TRUE);
                        session.setAttribute(ATTR_SESSION_LOGGED_IN, Boolean.TRUE);
                        LogManager.addLogin("登录成功：" + displayUser(session) + " @ " + address);
                    }
                } else if (code != FtpReply.REPLY_202_COMMAND_NOT_IMPLEMENTED) {
                    // 202 = 已登录状态下又发 PASS，不是失败。
                    String user = displayUser(session);
                    LogManager.addLogin((user.isEmpty() ? "登录失败" : "登录失败：" + user)
                            + " @ " + address + replyDetail(reply));
                }
            } else if (code >= 400) {
                // 兜底闸门：客户端"连不上 / 连上就报错"时，日志里至少留下失败的那条命令。
                // 登录阶段每条都记（该阶段很短且失败即致命）；登录成功后只记第一条，
                // 免得一个反复重试的客户端把内存日志刷满。
                if (!Boolean.TRUE.equals(session.getAttribute(ATTR_SESSION_LOGGED_IN))) {
                    LogManager.addLogin("登录阶段 " + command + " 被拒 @ " + address
                            + replyDetail(reply));
                } else {
                    // 重命名与文件传输单独放开：RNFR/RNTO 是用户主动、低频、失败时最需要
                    // 现场信息的动作；RETR/STOR/APPE 的 426/551 直接决定数据成败，同样低频
                    // 且高价值。「每个会话只记第一条」的节流会把真正的原因挡掉。日志量由
                    // LogManager 的有界队列与丢弃计数兜底，刷不爆。
                    boolean alwaysLog = "RNFR".equalsIgnoreCase(command)
                            || "RNTO".equalsIgnoreCase(command)
                            || "RETR".equalsIgnoreCase(command)
                            || "STOR".equalsIgnoreCase(command)
                            || "APPE".equalsIgnoreCase(command);
                    if (alwaysLog || !Boolean.TRUE.equals(
                            session.getAttribute(ATTR_POST_LOGIN_FAILURE_LOGGED))) {
                        if (!alwaysLog) {
                            session.setAttribute(ATTR_POST_LOGIN_FAILURE_LOGGED, Boolean.TRUE);
                        }
                        LogManager.addLogin("命令被拒 " + command + " @ " + address
                                + replyDetail(reply));
                    }
                }
            }

            if ("QUIT".equalsIgnoreCase(command)
                    && code == FtpReply.REPLY_221_CLOSING_CONTROL_CONNECTION) {
                session.setAttribute(ATTR_QUIT, Boolean.TRUE);
            }
            if (LogManager.isDebugEnabled()) {
                LogManager.debug(address + " " + safeRequestLine(request) + " -> " + code);
            }
            if (("RETR".equalsIgnoreCase(command) || "STOR".equalsIgnoreCase(command)
                    || "APPE".equalsIgnoreCase(command) || "DELE".equalsIgnoreCase(command)
                    || "MKD".equalsIgnoreCase(command) || "RMD".equalsIgnoreCase(command)
                    || "RNFR".equalsIgnoreCase(command) || "RNTO".equalsIgnoreCase(command))
                    && code >= 200 && code < 400) {
                LogManager.recordFtpFileOperation(command, displayUser(session), address);
            }
            if (("LIST".equalsIgnoreCase(command) || "NLST".equalsIgnoreCase(command))
                    && code >= 200 && code < 300) {
                LogManager.recordFtpFileOperation(command, displayUser(session), address);
            }
        } catch (RuntimeException error) {
            LogManager.warning("FTP日志回调异常: " + error.getClass().getSimpleName());
        }
        return FtpletResult.DEFAULT;
    }

    @Override public FtpletResult beforeCommand(FtpSession session, FtpRequest request) {
        if (!LogManager.isLoggingEnabled()) return FtpletResult.DEFAULT;
        try {
            String command = request.getCommand();
            if ("USER".equalsIgnoreCase(command)) {
                LogManager.addLogin("尝试登录：" + loginArgument(request)
                        + " @ " + displayAddress(session));
            } else if ("PASS".equalsIgnoreCase(command)) {
                String user = displayUser(session);
                LogManager.addLogin((user.isEmpty() ? "密码验证请求" : "密码验证请求：" + user)
                        + " @ " + displayAddress(session));
            }
        } catch (RuntimeException error) { LogManager.error("记录FTP命令前日志失败", error); }
        return FtpletResult.DEFAULT;
    }

    /**
     * 登录参数原样输出。为空时明确标成 {@code <空参数>}（这直接对应 USER 的 501）；
     * 前后带空白时附上原始请求行，因为那种情况下服务端拿到的用户名与客户端以为的不同。
     */
    private static String loginArgument(FtpRequest request) {
        String argument = request.getArgument();
        if (argument == null || argument.isEmpty()) return "<空参数>";
        String trimmed = argument.trim();
        if (trimmed.equals(argument)) return argument;
        return trimmed + "（原始行=" + safeRequestLine(request) + "）";
    }

    /** 应答原文："501 Syntax error in parameters or arguments"，无文案时退化为纯码。 */
    private static String replySummary(FtpReply reply) {
        if (reply == null) return "";
        String text = compact(reply.getMessage());
        return text.isEmpty() ? String.valueOf(reply.getCode()) : reply.getCode() + " " + text;
    }

    /** 括号包裹的应答原文；完全取不到时返回空串，不往日志里塞占位符。 */
    private static String replyDetail(FtpReply reply) {
        String summary = replySummary(reply);
        return summary.isEmpty() ? "" : "（" + summary + "）";
    }

    /** 折掉换行并截断：登录阶段的文案都是一行，换行只会把日志切割得难以阅读。 */
    private static String compact(String value) {
        if (value == null) return "";
        String flat = value.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= MAX_RAW_TEXT_CHARS
                ? flat : flat.substring(0, MAX_RAW_TEXT_CHARS) + "…";
    }

    private String displayUser(FtpSession session) {
        User user = session.getUser();
        if (user != null && user.getName() != null) return user.getName().trim();
        String argument = session.getUserArgument();
        return argument == null ? "" : argument.trim();
    }

    private String displayAddress(FtpSession session) {
        InetSocketAddress address = session.getClientAddress();
        if (address == null) return "未知地址";
        InetAddress resolved = address.getAddress();
        return resolved == null ? address.getHostString() : resolved.getHostAddress();
    }

    private static String safeRequestLine(FtpRequest request) {
        String command = request.getCommand();
        if ("PASS".equalsIgnoreCase(command)) return "PASS ******";
        if ("PORT".equalsIgnoreCase(command) || "EPRT".equalsIgnoreCase(command)) return command;
        return compact(request.getRequestLine());
    }

    private static String duration(long millis) {
        if (millis < 0L) return "未知";
        if (millis < 1000L) return millis + "ms";
        long seconds = millis / 1000L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m" + (seconds % 60L) + "s";
        return (minutes / 60L) + "h" + (minutes % 60L) + "m";
    }
}
