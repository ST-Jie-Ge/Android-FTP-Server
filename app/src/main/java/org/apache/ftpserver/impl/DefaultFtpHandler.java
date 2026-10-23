/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ftpserver.impl;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.nio.charset.MalformedInputException;

import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.CommandFactory;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.ftpletcontainer.FtpletContainer;
import org.apache.ftpserver.listener.Listener;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.write.WriteToClosedSessionException;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 *
 */
public class DefaultFtpHandler implements FtpHandler {

    private final Logger LOG = LoggerFactory.getLogger(DefaultFtpHandler.class);

    // Clients may discover AUTH TLS before login. Keep these harmless probes aligned
    // with FtpsCommandGuard; authentication and file commands still require login/TLS.
    private final static String[] NON_AUTHENTICATED_COMMANDS = new String[] {
            "USER", "PASS", "AUTH", "QUIT", "PROT", "PBSZ", "FEAT", "SYST", "NOOP" };

    private static final String COMMAND_QUEUE = "ftpshare.command-queue";
    private static final String MOVE_ACTIVE = "ftpshare.move-active";
    /** 未登录被拒的命令只记一次，防止扫描器把它刷成噪音。 */
    private static final String PRE_LOGIN_DENIED_LOGGED = "ftpshare.log.preLoginDenied";
    private static volatile Executor configuredTransferExecutor;
    private static volatile Executor configuredCopyExecutor;
    private Executor transferExecutor;
    private Executor copyExecutor;

    /** Set before server start; each listener captures its own executor in init(). */
    public static void setTransferExecutor(Executor executor) {
        configuredTransferExecutor = executor;
    }

    /**
     * 跨卷移动（RNTO 触发的服务端本地拷贝）的专用车道。
     *
     * <p>RNTO 必须离开 MINA 的 I/O 线程：每个 {@code IoProcessor} 只有一条线程，且每个会话
     * 固定绑定其中一条，拷贝一旦在上面跑几分钟，那条线程上的全体会话就<b>既读不到命令也
     * 发不出应答</b>。它也不能用传输池——拷贝是分钟级的，会把按秒设计的池长期抽干。</p>
     */
    public static void setCopyExecutor(Executor executor) {
        configuredCopyExecutor = executor;
    }

    private SessionCommandQueue commandQueue(FtpIoSession session) {
        SessionCommandQueue queue = (SessionCommandQueue) session.getAttribute(COMMAND_QUEUE);
        if (queue == null) {
            queue = new SessionCommandQueue(transferExecutor);
            Object previous = session.setAttributeIfAbsent(COMMAND_QUEUE, queue);
            if (previous != null) queue = (SessionCommandQueue) previous;
        }
        return queue;
    }

    private static boolean isTransferCommand(String name) {
        return "RETR".equals(name) || "STOR".equals(name) || "STOU".equals(name)
                || "APPE".equals(name) || "LIST".equals(name) || "NLST".equals(name)
                || "MLSD".equals(name) || "HASH".equals(name);
    }

    /** Storage metadata commands can block in Android's FUSE/SD-card layer. */
    private static boolean isFilesystemCommand(String name) {
        return "CWD".equals(name) || "DELE".equals(name) || "MDTM".equals(name)
                || "MKD".equals(name) || "RMD".equals(name) || "RNFR".equals(name)
                || "SIZE".equals(name) || "STAT".equals(name);
    }

    /**
     * 命令该跑在哪条车道上；{@code null} = 留在调用线程上。会触碰存储/FUSE 的元数据
     * 命令也进入有界工作池，避免慢卡或 Android 存储层阻塞 MINA processor。
     *
     * <p>RNTO 单独走拷贝车道：它是唯一会触发分钟级本地拷贝的命令（见
     * {@code MultiRootFtpFile.move}）。RNFR 也可能访问存储层，因此进入传输工作池；两者
     * 同属一个会话队列，顺序不受车道切换影响。</p>
     *
     * <p>拷贝池没注入时使用进程级拷贝池，绝不借用网络传输池或 I/O 线程。</p>
     */
    private Executor laneFor(String command) {
        if ("AVBL".equals(command)) return transferExecutor;
        if (isTransferCommand(command)) return transferExecutor;
        if (isFilesystemCommand(command)) return transferExecutor;
        if ("RNTO".equals(command) || "MOVE".equals(command)) return copyExecutor != null ? copyExecutor
                : com.github.stjiege.ftpshare.filesystem.CopyExecutor.get();
        return null;
    }

    private void interruptDataConnection(FtpIoSession session) {
        com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext.interrupt(session);
    }

    private FtpServerContext context;

    private Listener listener;

    public void init(final FtpServerContext context, final Listener listener) {
        this.context = context;
        this.listener = listener;
        this.transferExecutor = configuredTransferExecutor;
        this.copyExecutor = configuredCopyExecutor;
    }

    public void sessionCreated(final FtpIoSession session) throws Exception {
        session.setListener(listener);

        ServerFtpStatistics stats = ((ServerFtpStatistics) context
                .getFtpStatistics());

        if (stats != null) {
            stats.setOpenConnection(session);
        }
    }

    public void sessionOpened(final FtpIoSession session) throws Exception {
        FtpletContainer ftplets = context.getFtpletContainer();

        FtpletResult ftpletRet;
        try {
            ftpletRet = ftplets.onConnect(session.getFtpletSession());
        } catch (Exception e) {
            LOG.debug("Ftplet threw exception", e);
            ftpletRet = FtpletResult.DISCONNECT;
        }
        if (ftpletRet == FtpletResult.DISCONNECT) {
            LOG.debug("Ftplet returned DISCONNECT, session will be closed");
            // Different paths, same hard evidence: sessionOpened() runs on the MINA I/O thread,
            // the future is completed by that very thread (see the note in sessionIdle()), so
            // awaiting it here freezes every session on this processor for the full timeout.
            session.close(false);
        } else {
            session.updateLastAccessTime();

            session.write(LocalizedFtpReply.translate(session, null, context,
                    FtpReply.REPLY_220_SERVICE_READY, null, null));
        }
    }

    public void sessionClosed(final FtpIoSession session) throws Exception {
        LOG.debug("Closing session");
        commandQueue(session).close();
        // Close sockets before touching the synchronized factory/session APIs.
        interruptDataConnection(session);
        try {
            context.getFtpletContainer().onDisconnect(
                    session.getFtpletSession());
        } catch (Exception e) {
            // swallow the exception, we're closing down the session anyways
            LOG.warn("Ftplet threw an exception on disconnect", e);
        }

        // make sure we close the data connection if it happens to be open
        try {
            ServerDataConnectionFactory dc = session.peekDataConnection();
            if(dc != null) {
                dc.closeDataConnection();
            }
        } catch (Exception e) {
            // swallow the exception, we're closing down the session anyways
            LOG.warn("Data connection threw an exception on disconnect", e);
        }

        FileSystemView fs = session.getFileSystemView();
        if(fs != null) {
            try  {
                fs.dispose();
            } catch (Exception e) {
                LOG.warn("FileSystemView threw an exception on disposal", e);
            }
        }

        ServerFtpStatistics stats = ((ServerFtpStatistics) context
                .getFtpStatistics());

        if (stats != null) {
            stats.setLogout(session);
            stats.setCloseConnection(session);
            LOG.debug("Statistics login and connection count decreased due to session close");
        } else {
            LOG.warn("Statistics not available in session, can not decrease login and connection count");
        }
        LOG.debug("Session closed");
    }

    public void exceptionCaught(final FtpIoSession session,
            final Throwable cause) throws Exception {

        if(cause instanceof ProtocolDecoderException &&
                cause.getCause() instanceof MalformedInputException) {
            // client probably sent something which is not UTF-8 and we failed to
            // decode it

            LOG.warn(
                    "Client sent command that could not be decoded: {}",
                    ((ProtocolDecoderException)cause).getHexdump());
            session.write(new DefaultFtpReply(FtpReply.REPLY_501_SYNTAX_ERROR_IN_PARAMETERS_OR_ARGUMENTS, "Invalid character in command"));
        } else if (cause instanceof WriteToClosedSessionException) {
            WriteToClosedSessionException writeToClosedSessionException =
                (WriteToClosedSessionException) cause;
            LOG.warn(
                            "Client closed connection before all replies could be sent, last reply was {}",
                            writeToClosedSessionException.getRequest());
            // 不同步等待关闭完成：exceptionCaught 跑在 MINA 的 I/O 线程上，await 最多 10 秒
            // 会把这条线程钉死——异常密集时（扫描器、断线风暴）等同于拒绝服务，其他会话的
            // 命令全都排队等它。关闭请求入队后 MINA 自己会走完余下流程，这里不需要结果。
            session.close(false);
        } else {
            LOG.error("Exception caught, closing session", cause);
            session.close(false);
        }


    }

    private boolean isCommandOkWithoutAuthentication(String command) {
        boolean okay = false;
        for (String allowed : NON_AUTHENTICATED_COMMANDS) {
            if (allowed.equals(command)) {
                okay = true;
                break;
            }
        }
        return okay;
    }

    /** 每个会话最多补一条：未登录就被拒的命令发生在这个 early return 上，早于任何 ftplet 回调，
     *  因此客户端"连不上"时日志里可能连一行都没有。 */
    private static void logPreLoginRejection(FtpIoSession session, String commandName) {
        try {
            if (session.getAttribute(PRE_LOGIN_DENIED_LOGGED) != null) return;
            session.setAttribute(PRE_LOGIN_DENIED_LOGGED, Boolean.TRUE);
            java.net.SocketAddress remote = session.getRemoteAddress();
            String address = "未知地址";
            if (remote instanceof java.net.InetSocketAddress) {
                java.net.InetAddress resolved = ((java.net.InetSocketAddress) remote).getAddress();
                address = resolved == null
                        ? ((java.net.InetSocketAddress) remote).getHostString()
                        : resolved.getHostAddress();
            } else if (remote != null) {
                address = remote.toString();
            }
            com.github.stjiege.ftpshare.utils.LogManager.addLogin(
                    "登录阶段 " + commandName + " 被拒 @ " + address + "（530 Not logged in.）");
        } catch (RuntimeException ignored) {
            // 日志失败绝不能影响命令处理。
        }
    }

    public void messageReceived(final FtpIoSession session,
            final FtpRequest request) throws Exception {
        if (!session.isConnected() || session.isClosing()) return;
        SessionCommandQueue queue = commandQueue(session);
        // Only cancellation is out-of-band; state/reply/audit hooks remain ordered.
        int invalidatedQueuedCommands = 0;
        boolean abortObservedFullQueue = false;
        if ("ABOR".equals(request.getCommand())) {
            abortObservedFullQueue = queue.isAtCapacity();
            invalidatedQueuedCommands = queue.cancelTransfers();
            interruptDataConnection(session);
            if (abortObservedFullQueue) {
                try {
                    java.net.SocketAddress remote = session.getRemoteAddress();
                    com.github.stjiege.ftpshare.utils.LogManager.warning(
                            "队列已满时收到 ABOR，将尝试使用取消命令预留容量；已作废排队传输命令 "
                                    + invalidatedQueuedCommands + " 个；客户端="
                                    + (remote != null ? remote.toString() : "未知地址"));
                } catch (RuntimeException ignored) {
                    // Logging must not interfere with cancellation.
                }
            } else if (invalidatedQueuedCommands > 0) {
                try {
                    com.github.stjiege.ftpshare.utils.LogManager.info(
                            "ABOR 已作废排队传输命令 " + invalidatedQueuedCommands + " 个");
                } catch (RuntimeException ignored) {
                    // Logging must not interfere with cancellation.
                }
            }
        }
        final long generation = queue.transferGeneration();
        final String commandName = request.getCommand();
        final boolean cancelOnTransferGeneration = isTransferCommand(commandName)
                || "RNTO".equals(commandName) || "MOVE".equals(commandName);
        try {
            queue.execute(() -> {
                if (!session.isConnected() || session.isClosing()
                        || Thread.currentThread().isInterrupted()) return;
                com.github.stjiege.ftpshare.filesystem.MoveCancellation.bind(() ->
                        generation != queue.transferGeneration()
                                || !session.isConnected() || session.isClosing());
                try {
                    processRequest(session, request, generation);
                } catch (IOException error) {
                    LOG.warn("FTP command failed; closing session", error);
                    commandQueue(session).close();
                    interruptDataConnection(session);
                    session.closeNow();
                } finally {
                    com.github.stjiege.ftpshare.filesystem.MoveCancellation.clear();
                }
            }, laneFor(commandName), "ABOR".equals(commandName), cancelOnTransferGeneration);
        } catch (RejectedExecutionException overloaded) {
            commandQueue(session).close();
            interruptDataConnection(session);
            // Never fall back to a blocking transfer on MINA's command workers.
            session.write(new DefaultFtpReply(421, "Command queue unavailable; reconnect later."));
            session.closeOnFlush();
            // 之前这里完全无日志：客户端表现为"传着传着被断开"，服务端现场无迹可循。
            // 一行留痕，把会话饱和归因到具体客户端地址。
            try {
                java.net.SocketAddress remote = session.getRemoteAddress();
                com.github.stjiege.ftpshare.utils.LogManager.warning(
                        "命令队列饱和，已以 421 断开 "
                                + (remote != null ? remote.toString() : "未知地址"));
            } catch (RuntimeException ignored) {
                // 日志失败不能影响断开流程。
            }
        }
    }

    private void processRequest(final FtpIoSession session,
            final FtpRequest request, final long generation) throws IOException {
        try {
            session.updateLastAccessTime();

            String commandName = request.getCommand();
            CommandFactory commandFactory = context.getCommandFactory();
            Command command = commandFactory.getCommand(commandName);

            // make sure the user is authenticated before he issues commands
            if (!session.isLoggedIn()
                    && !isCommandOkWithoutAuthentication(commandName)) {
                logPreLoginRejection(session, commandName);
                session.write(LocalizedFtpReply.translate(session, request,
                        context, FtpReply.REPLY_530_NOT_LOGGED_IN,
                        "permission", null));
                return;
            }

            FtpletContainer ftplets = context.getFtpletContainer();

            FtpletResult ftpletRet;
            try {
                ftpletRet = ftplets.beforeCommand(session.getFtpletSession(),
                        request);
            } catch (Exception e) {
                LOG.debug("Ftplet container threw exception", e);
                ftpletRet = FtpletResult.DISCONNECT;
            }
            if (ftpletRet == FtpletResult.DISCONNECT) {
                LOG.debug("Ftplet returned DISCONNECT, session will be closed");
                session.close(false);
                return;
            } else if (ftpletRet != FtpletResult.SKIP) {

                if (command != null) {
                    // 命令执行不再持有 session 锁。同一会话的命令已由 SessionCommandQueue
                    // 串行化，而 FtpIoSession 上有 synchronized 方法（getDataConnection 等），
                    // 传输期间一直握着这把锁会让任何需要它的线程等到传输结束——一次大文件
                    // 传输就是几十秒甚至几分钟，这正是控制连接偶发卡顿的来源之一。
                    // publish 与取消检查仍在同一线程上连续完成：getDataConnection() 自身是
                    // synchronized 的，生成号在入队时就快照好了；ABOR 无论插在哪一处，要么
                    // 被下面的 generation 判定为已取消（425），要么被 interrupted 标志拦下。
                    boolean transfer = isTransferCommand(commandName);
                    // Publish the factory before checking cancellation: ABOR cannot miss
                    // a connection created just after its lock-free lookup.
                    if (transfer) com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext.get(session);
                    if ((transfer || "RNTO".equals(commandName) || "MOVE".equals(commandName))
                            && generation != commandQueue(session).transferGeneration()) {
                        session.write(new DefaultFtpReply(425, "Data transfer cancelled before starting."));
                    } else if (session.isConnected() && !session.isClosing()) {
                        boolean move = "RNTO".equals(commandName) || "MOVE".equals(commandName)
                                || "HASH".equals(commandName);
                        if (move) session.setAttribute(MOVE_ACTIVE, Boolean.TRUE);
                        com.github.stjiege.ftpshare.filesystem.CommandContext.set(commandName);
                        try {
                            command.execute(session, context, request);
                        } finally {
                            com.github.stjiege.ftpshare.filesystem.CommandContext.clear();
                            if (move) {
                                session.removeAttribute(MOVE_ACTIVE);
                                session.updateLastAccessTime();
                            }
                        }
                    }
                } else {
                    session.write(LocalizedFtpReply.translate(session, request,
                            context,
                            FtpReply.REPLY_502_COMMAND_NOT_IMPLEMENTED,
                            "not.implemented", null));
                }

                try {
                    ftpletRet = ftplets.afterCommand(
                            session.getFtpletSession(), request, session
                                    .getLastReply());
                } catch (Exception e) {
                    LOG.debug("Ftplet container threw exception", e);
                    ftpletRet = FtpletResult.DISCONNECT;
                }
                if (ftpletRet == FtpletResult.DISCONNECT) {
                    LOG.debug("Ftplet returned DISCONNECT, session will be closed");

                    session.close(false);
                    return;
                }
            }

        } catch (Exception ex) {

            // send error reply
            try {
                session.write(LocalizedFtpReply.translate(session, request,
                        context, FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        null, null));
            } catch (Exception ex1) {
            }

            if (ex instanceof java.io.IOException) {
                throw (IOException) ex;
            } else {
                LOG.warn("RequestHandler.service()", ex);
            }
        }

    }

    public void sessionIdle(final FtpIoSession session, final IdleStatus status)
            throws Exception {
        // Data traffic is carried on a separate socket, so MINA's control-session idle
        // clock does not see it. Do not tear down a healthy long transfer when the
        // optional control idle timeout fires; the data socket has its own timeout and
        // ABOR/session close still interrupts it.
        // Local copies also produce no control-socket traffic, but are not idle sessions.
        if (session.isTransferActive() || Boolean.TRUE.equals(session.getAttribute(MOVE_ACTIVE))) {
            session.updateLastAccessTime();
            return;
        }
        LOG.info("Session idle, closing");
        // 不要在这里 await 关闭完成 —— 实测证据（本机 JVM + 真实 NioProcessor）：
        // sessionIdle() 跑在 MINA 的 I/O 线程上（日志里是 NioProcessor-N），而 CloseFuture
        // 的完成要靠这条线程自己跑完 AbstractPollingIoProcessor.removeNow()
        // → fireSessionDestroyed() → fireChainSessionClosed()。等这条线程就是等它自己去完成
        // 自己正在等的事，实测稳定吃满 10 秒才返回；去掉 await 后同一处是 0 毫秒。
        // 一个 idle 超时会冻结一整条 processor 线程，上面所有会话都读不到命令也发不出应答。
        // 关闭请求入队后 MINA 自己会走完余下流程，这里不需要它的结果。
        session.close(false);
    }

    public void messageSent(final FtpIoSession session, final FtpReply reply)
            throws Exception {
        // do nothing

    }
}
