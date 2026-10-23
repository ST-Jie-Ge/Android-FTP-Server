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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import com.github.stjiege.ftpshare.utils.LogManager;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.ftpserver.DataConnectionConfiguration;
import org.apache.ftpserver.DataConnectionException;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 * 
 * We can get the FTP data connection using this class. It uses either PORT or
 * PASV command.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class IODataConnectionFactory implements ServerDataConnectionFactory {

    /**
     * 数据连接握手超过这个毫秒数就记一条应用日志。
     *
     * 私钥已在进程内（{@code FtpsIdentity}），局域网握手的正常值在几十毫秒量级；
     * 越过这条线说明还有别的慢路径在起作用，值得留痕。
     * 只记异常值——每次传输都写一条会把日志刷满。
     */
    private static final long SLOW_HANDSHAKE_MS = 150L;

    /**
     * accept() 等待超过这个毫秒数就记一条应用日志。
     *
     * PASV 应答之后，数据口什么时候被客户端连上完全由客户端决定。传输"开头 5-10 秒
     * 零流量、然后速度突然出现"时，这条日志能把等待归因到客户端延迟建连，与握手慢、
     * 文件打开慢区分开。
     */
    private static final long SLOW_ACCEPT_MS = 1_000L;

    /**
     * FTPS 数据连接握手复用统计（full/resumed 计数）。
     *
     * 复用命中率是会话复用三层防线（票证 / ID 缓存 / 被动端口收敛，见 FtpsConfiguration）
     * 是否生效的唯一量化证据。静态累计：数据连接工厂每会话一个，计数必须跨实例。
     * 每 {@link #HANDSHAKE_STATS_INTERVAL} 次握手在 DEBUG 日志汇总一次；正式包不刷日志，
     * 只在慢握手警告（SLOW_HANDSHAKE_MS）里顺带带上当前命中率。
     */
    private static final java.util.concurrent.atomic.AtomicLong FULL_HANDSHAKES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong RESUMED_HANDSHAKES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long HANDSHAKE_STATS_INTERVAL = 20L;

    private void logAcceptWait(long setupStart) {
        long waited = System.currentTimeMillis() - setupStart;
        if (waited >= SLOW_ACCEPT_MS) {
            LogManager.warning("数据端口等了 " + waited
                    + " ms 才等到客户端连接（客户端在 PASV 后延迟建连）");
        }
    }

    /**
     * 主动模式数据连接的 TCP 连接建立超时（毫秒）。
     *
     * 主动模式（PORT）下服务端要反向连接客户端通告的地址：该地址经 NAT/防火墙后常不可达，
     * 无超时的 connect() 会阻塞到平台默认值（可达数分钟），期间占住一个传输 worker。
     * 只限制 TCP 三次握手阶段；随后的 SSL 握手在 startHandshake() 另行计时诊断。
     */
    private static final int ACTIVE_CONNECT_TIMEOUT_MS = 10_000;

    private final Logger LOG = LoggerFactory
            .getLogger(IODataConnectionFactory.class);

    private FtpServerContext serverContext;

    private volatile Socket dataSoc;

    volatile ServerSocket servSoc;

    /** Port reserved from the passive-port allocator, even before bind succeeds. */
    private int reservedPassivePort = -1;

    private volatile boolean interrupted;

    InetAddress address;

    int port = 0;

    long requestTime = 0L;

    boolean passive = false;

    boolean secure = false;

    private boolean isZip = false;

    InetAddress serverControlAddress;

    FtpIoSession session;

    public IODataConnectionFactory(final FtpServerContext serverContext,
            final FtpIoSession session) {
        this.session = session;
        this.serverContext = serverContext;
        if ((session != null) && (session.getListener() != null) && 
            session.getListener().getDataConnectionConfiguration().isImplicitSsl()) {
            secure = true;
        }
    }

    /**
     * Close data socket.
     * This method must be idempotent as we might call it multiple times during disconnect.
     */
    /** Interrupt blocking accept/read/handshake without acquiring either transfer lock. */
    public void interruptDataConnection() {
        interrupted = true;
        Socket data = dataSoc;
        ServerSocket listener = servSoc;
        try { if (listener != null) listener.close(); }
        catch (IOException error) { LOG.debug("Could not interrupt data listener", error); }
        try { if (data != null) data.close(); }
        catch (IOException error) { LOG.debug("Could not interrupt data socket", error); }
        // The ordered command path owns port release and field cleanup.
    }

    public synchronized void closeDataConnection() {

        // close client socket if any
        if (dataSoc != null) {
            try {
                dataSoc.close();
            } catch (Exception ex) {
                LOG.warn("FtpDataConnection.closeDataSocket()", ex);
            }
            dataSoc = null;
        }

        // close server socket if any
        if (servSoc != null) {
            try {
                servSoc.close();
            } catch (Exception ex) {
                LOG.warn("FtpDataConnection.closeDataSocket()", ex);
            }

            servSoc = null;
        }

        // The allocator reserves the port before ServerSocket.bind().  Bind can fail before
        // servSoc is assigned, so release the reservation independently of the socket field.
        if (reservedPassivePort >= 0 && session != null && session.getListener() != null) {
            DataConnectionConfiguration dcc = session.getListener().getDataConnectionConfiguration();
            if (dcc != null) dcc.releasePassivePort(reservedPassivePort);
        }
        reservedPassivePort = -1;

        // reset request time
        requestTime = 0L;
    }

    /**
     * Port command.
     */
    public synchronized void initActiveDataConnection(
            final InetSocketAddress address) {

        // close old sockets if any
        closeDataConnection();
        interrupted = false;

        // set variables
        passive = false;
        this.address = address.getAddress();
        port = address.getPort();
        requestTime = System.currentTimeMillis();
    }

    private SslConfiguration getSslConfiguration() {
        DataConnectionConfiguration dataCfg = session.getListener()
                .getDataConnectionConfiguration();

        SslConfiguration configuration = dataCfg.getSslConfiguration();

        // fall back if no configuration has been provided on the data connection config
        if (configuration == null) {
            configuration = session.getListener().getSslConfiguration();
        }

        return configuration;
    }

    /**
     * Initiate a data connection in passive mode (server listening). 
     */
    public synchronized InetSocketAddress initPassiveDataConnection()
            throws DataConnectionException {
        LOG.debug("Initiating passive data connection");
        // close old sockets if any
        closeDataConnection();
        interrupted = false;

        // get the passive port
        int passivePort = session.getListener()
                .getDataConnectionConfiguration().requestPassivePort();
        if (passivePort == -1) {
            servSoc = null;
            throw new DataConnectionException(
                    "Cannot find an available passive port.");
        }
        reservedPassivePort = passivePort;

        // open passive server socket and get parameters
        try {
            DataConnectionConfiguration dataCfg = session.getListener()
                    .getDataConnectionConfiguration();

            String passiveAddress = dataCfg.getPassiveAddress();

            if (passiveAddress == null) {
                address = serverControlAddress;
            } else {
                address = resolveAddress(dataCfg.getPassiveAddress());
            }

            if (secure) {
                LOG
                        .debug(
                                "Opening SSL passive data connection on address \"{}\" and port {}",
                                address, passivePort);
                SslConfiguration ssl = getSslConfiguration();
                if (ssl == null) {
                    throw new DataConnectionException(
                            "Data connection SSL required but not configured.");
                }

                // this method does not actually create the SSL socket, due to a JVM bug 
                // (https://issues.apache.org/jira/browse/FTPSERVER-241).
                // Instead, it creates a regular
                // ServerSocket that will be wrapped as a SSL socket in createDataSocket()
                servSoc = newServerSocket(address, passivePort);
                LOG
                        .debug(
                                "SSL Passive data connection created on address \"{}\" and port {}",
                                address, passivePort);
            } else {
                LOG
                        .debug(
                                "Opening passive data connection on address \"{}\" and port {}",
                                address, passivePort);
                servSoc = newServerSocket(address, passivePort);
                LOG
                        .debug(
                                "Passive data connection created on address \"{}\" and port {}",
                                address, passivePort);
            }
            port = servSoc.getLocalPort();
            servSoc.setSoTimeout(dataCfg.getIdleTime() * 1000);

            // set different state variables
            passive = true;
            requestTime = System.currentTimeMillis();

            return new InetSocketAddress(address, port);
        } catch (Exception ex) {
            closeDataConnection();
            throw new DataConnectionException(
                    "Failed to initate passive data connection: "
                            + ex.getMessage(), ex);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.ftpserver.FtpDataConnectionFactory2#getInetAddress()
     */
    public InetAddress getInetAddress() {
        return address;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.ftpserver.FtpDataConnectionFactory2#getPort()
     */
    public int getPort() {
        return port;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.ftpserver.FtpDataConnectionFactory2#openConnection()
     */
    public DataConnection openConnection() throws Exception {
        Socket socket = createDataSocket();
        if (socket == null) {
            throw new IOException("Data connection rejected.");
        }
        return new IODataConnection(socket, session, this);
    }

    /**
     * Get the data socket. In case of error returns null.
     */
    private synchronized Socket createDataSocket() throws Exception {
        // 传输开始前的"零流量窗口"定位：从本命令进入数据建连到 accept() 返回的等待时长。
        // 这段等待完全发生在客户端一侧（客户端 PASV 之后迟迟不来连数据口），服务器只能
        // 被动等；把它和下面的握手耗时分开记录，才能区分"客户端延迟建连"与"握手慢"。
        final long setupStart = System.currentTimeMillis();
        if (interrupted) throw new IOException("Data connection cancelled.");

        // get socket depending on the selection
        dataSoc = null;
        DataConnectionConfiguration dataConfig = session.getListener()
                .getDataConnectionConfiguration();
        try {
            if (!passive) {
                InetAddress localAddr = resolveAddress(dataConfig
                        .getActiveLocalAddress());

                // if no local address has been configured, make sure we use the same as the client connects from
                if(localAddr == null) {
                    localAddr = ((InetSocketAddress)session.getLocalAddress()).getAddress();
                }

                SocketAddress localSocketAddress = new InetSocketAddress(localAddr, dataConfig.getActiveLocalPort());

                if (secure) {
                    LOG.debug("Opening secure active data connection");
                    SslConfiguration ssl = getSslConfiguration();
                    if (ssl == null) {
                        throw new FtpException(
                                "Data connection SSL not configured");
                    }

                    // get socket factory
                    SSLSocketFactory socFactory = ssl.getSocketFactory();

                    // 先建裸 socket、bind+connect 完成 TCP 连接，再把 SSL 层叠上去。无参
                    // createSocket() 返回的未连接 SSLSocket 需要各 JSSE 实现把 bind()/connect()
                    // 原样转发到底层 socket，部分 ROM/Conscrypt 版本并不保证；主动模式是服务端
                    // 反向连客户端，用「裸连接 + createSocket(Socket, host, port, autoClose) 叠层」
                    // 才是可移植写法（被动分支已在用同一种方式）。
                    Socket plainSocket = new Socket();
                    dataSoc = plainSocket;
                    plainSocket.setReuseAddress(true);
                    // 在 connect() 之前设缓冲区：TCP 窗口大小在三次握手时协商，握手直接带上 256 KB 窗口。
                    applyDataSocketOptions(plainSocket);
                    LOG.debug("Binding active data connection to {}", localSocketAddress);
                    plainSocket.bind(localSocketAddress);
                    plainSocket.connect(new InetSocketAddress(address, port), ACTIVE_CONNECT_TIMEOUT_MS);

                    SSLSocket ssoc = (SSLSocket) socFactory.createSocket(plainSocket,
                            address.getHostAddress(), port, true);
                    dataSoc = ssoc;
                    ssoc.setUseClientMode(false);

                    // initialize socket
                    if (ssl.getEnabledCipherSuites() != null) {
                        ssoc.setEnabledCipherSuites(ssl.getEnabledCipherSuites());
                    }
                    dataSoc = ssoc;
                } else {
                    LOG.debug("Opening active data connection");
                    dataSoc = new Socket();

                    dataSoc.setReuseAddress(true);
                    // 在 connect() 之前设缓冲区：TCP 窗口大小在三次握手时协商，
                    // 连接建立后再改 SO_RCVBUF/SO_SNDBUF 只能影响窗口扩展（window scaling），
                    // 首轮通告窗口仍是默认值。主动模式在此处设置可让握手直接带上 256 KB 窗口。
                    applyDataSocketOptions(dataSoc);

                    LOG.debug("Binding active data connection to {}", localSocketAddress);
                    dataSoc.bind(localSocketAddress);

                    dataSoc.connect(new InetSocketAddress(address, port), ACTIVE_CONNECT_TIMEOUT_MS);
                }
            } else {

                if (secure) {
                    LOG.debug("Opening secure passive data connection");
                    // this is where we wrap the unsecured socket as a SSLSocket. This is 
                    // due to the JVM bug described in FTPSERVER-241.

                    // get server socket factory
                    SslConfiguration ssl = getSslConfiguration();
                    
                    // we've already checked this, but let's do it again
                    if (ssl == null) {
                        throw new FtpException(
                                "Data connection SSL not configured");
                    }

                    SSLSocketFactory ssocketFactory = ssl.getSocketFactory();

                    // 与下面的明文分支同因：ABOR/取消可能已经关掉了监听 socket。
                    // 加密分支曾经漏了这道检查，FTPS 下会直接 NPE 变成一次传输失败。
                    if (servSoc == null) {
                        throw new IOException(
                                "Passive data connection is not open (aborted or never initialized).");
                    }
                    Socket serverSocket = servSoc.accept();
                    logAcceptWait(setupStart);
                    // Own the accepted socket before TLS wrapping: provider failures must
                    // reach closeDataConnection(), and ABOR must be able to interrupt it.
                    dataSoc = serverSocket;

                    SSLSocket sslSocket = (SSLSocket) ssocketFactory
                            .createSocket(serverSocket, serverSocket
                                    .getInetAddress().getHostAddress(),
                                    serverSocket.getPort(), true);
                    dataSoc = sslSocket;
                    sslSocket.setUseClientMode(false);

                    // initialize server socket
                    if (ssl.getClientAuth() == ClientAuth.NEED) {
                        sslSocket.setNeedClientAuth(true);
                    } else if (ssl.getClientAuth() == ClientAuth.WANT) {
                        sslSocket.setWantClientAuth(true);
                    }

                    if (ssl.getEnabledCipherSuites() != null) {
                        sslSocket.setEnabledCipherSuites(ssl
                                .getEnabledCipherSuites());
                    }

                    dataSoc = sslSocket;
                } else {
                    LOG.debug("Opening passive data connection");

                    // ABOR 快速通道（见 DefaultFtpHandler）可能在本命令排队但尚未执行时
                    // 把监听 socket 关掉（servSoc 置 null）。上游命令严格串行不会出现
                    // 这个窗口，池化后存在；不防御的话这里会直接 NPE。抛 IOException
                    // 走命令层既有的 catch，客户端收到 425/550，语义正确。
                    if (servSoc == null) {
                        throw new IOException(
                                "Passive data connection is not open (aborted or never initialized).");
                    }
                    dataSoc = servSoc.accept();
                    logAcceptWait(setupStart);
                }
                
                if (dataConfig.isPassiveIpCheck()) {
					// Let's make sure we got the connection from the same
					// client that we are expecting
					InetAddress remoteAddress = ((InetSocketAddress) session.getRemoteAddress()).getAddress();
					InetAddress dataSocketAddress = dataSoc.getInetAddress();
					if (!sameAddress(dataSocketAddress, remoteAddress)) {
						LOG.warn("Passive IP Check failed. Closing data connection from "
							+ dataSocketAddress
							+ " as it does not match the expected address "
							+ remoteAddress);
                        closeDataConnection();
                        throw new IOException("Passive data connection peer does not match control connection.");
					}
				}
                
                DataConnectionConfiguration dataCfg = session.getListener()
                    .getDataConnectionConfiguration();
                
                dataSoc.setSoTimeout(dataCfg.getIdleTime() * 1000);
                LOG.debug("Passive data connection opened");
            }
        } catch (Exception ex) {
            closeDataConnection();
            LOG.warn("FtpDataConnection.getDataSocket()", ex);
            throw ex;
        }
        if (interrupted) {
            closeDataConnection();
            throw new IOException("Data connection cancelled.");
        }
        dataSoc.setSoTimeout(dataConfig.getIdleTime() * 1000);

        // 统一兜底：主动模式在上面 connect() 前已设过，被动模式则在此处补上
        // TCP_NODELAY（监听 socket 不会把该选项传给 accept() 出来的连接，
        // 而缓冲区已由 newServerSocket 继承过来了）。重复设置是幂等的。
        applyDataSocketOptions(dataSoc);

        // Make sure we initiate the SSL handshake, or we'll
        // get an error if we turn out not to send any data
        // e.g. during the listing of an empty directory
        if (dataSoc instanceof SSLSocket) {
            // 握手耗时诊断：私钥是进程内软件密钥（见 FtpsIdentity），局域网里一次握手
            // 应该只有几十毫秒，几乎全花在网络往返上。这条 log4j 日志只在 DEBUG 构建
            // 可见（Logcat appender 只在那时挂载），所以下面对超时的情况额外留一条
            // 走应用日志的记录——正式包里也能看到。
            long handshakeStart = System.currentTimeMillis();
            ((SSLSocket) dataSoc).startHandshake();
            long handshakeMillis = System.currentTimeMillis() - handshakeStart;

            // 把"慢"拆开，否则只知道一个总时长没法定位：
            //  - resumed：命中会话复用。复用握手只有几次往返、不做密钥交换，
            //    这种情况下还慢，时间必然是<b>客户端</b>在握手中途干等（证书链校验、
            //    联网查根证书、弹信任确认框），服务端这边改不动。
            //  - full：每次都完整握手。至少知道复用没生效，值得往服务端查。
            // 判定方法：会话复用时 SSLSession 保留的是<b>原</b>会话的创建时间，
            //          明显早于"现在"即为复用（跨 SSL 实现通用）。
            String mode = "unknown";
            String suite = "unknown";
            Boolean resumedFlag = null;
            try {
                SSLSession established = ((SSLSocket) dataSoc).getSession();
                boolean resumed = System.currentTimeMillis() - established.getCreationTime() > 1000L;
                resumedFlag = resumed;
                mode = resumed ? "resumed" : "full";
                suite = String.valueOf(established.getCipherSuite());
            } catch (RuntimeException ignored) {
                // 取会话信息失败不该影响一次已经成功的握手。
            }
            String hitRate = "";
            if (resumedFlag != null) {
                long resumedTotal = RESUMED_HANDSHAKES.get() + (resumedFlag ? 1 : 0);
                long fullTotal = FULL_HANDSHAKES.get() + (resumedFlag ? 0 : 1);
                if (resumedFlag) {
                    RESUMED_HANDSHAKES.lazySet(resumedTotal);
                } else {
                    FULL_HANDSHAKES.lazySet(fullTotal);
                }
                long total = resumedTotal + fullTotal;
                hitRate = "，复用率 " + (resumedTotal * 100 / total) + "%（" + resumedTotal + "/" + total + "）";
                if (total % HANDSHAKE_STATS_INTERVAL == 0) {
                    LOG.info("FtpsHandshake stats: {} resumed of {} data handshakes ({}%)",
                            resumedTotal, total, resumedTotal * 100 / total);
                }
            }
            LOG.info("FtpsHandshake: data connection handshake took {} ms, {}, suite={}",
                    handshakeMillis, mode, suite);
            if (handshakeMillis >= SLOW_HANDSHAKE_MS) {
                LogManager.warning("FTPS 数据连接握手耗时 " + handshakeMillis + " ms（" + mode
                        + "，套件 " + suite + hitRate + "），超过 " + SLOW_HANDSHAKE_MS + " ms");
            }
        }

        return dataSoc;
    }

    /**
     * Leave send/receive buffer sizing to the kernel, including TCP autotuning.
     * Explicit SO_SNDBUF/SO_RCVBUF requests lock automatic sizing on Linux, even
     * when the requested size exceeds the initial default. Do not set a fixed floor.
     * TCP_NODELAY helps small replies; it does not guarantee faster bulk transfers.
     * Option failures are logged and do not fail the transfer.
     */
    private void applyDataSocketOptions(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            if (!socket.getTcpNoDelay()) {
                socket.setTcpNoDelay(true);
            }


        } catch (java.net.SocketException | RuntimeException ex) {
            // SocketException 是 checked（extends IOException），单独列一类；其余捕获的是
            // SSLSocket / 厂商 ROM 实现抛出的运行时异常（如非法参数、状态不符）。
            // 三者一律降级为系统默认值——调优选项设不上不该让一次传输失败。
            LOG.warn("Failed to apply data socket performance options, "
                    + "falling back to system defaults", ex);
        }
    }

    /*
     *  (non-Javadoc)
     *   Returns an InetAddress object from a hostname or IP address.
     */
    private InetAddress resolveAddress(String host)
            throws DataConnectionException {
        if (host == null) {
            return null;
        } else {
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException ex) {
                throw new DataConnectionException("Failed to resolve address", ex);
            }
        }
    }

    /**
     * 比较两个地址是否指向同一主机，归一化 IPv4-mapped IPv6（::ffff:a.b.c.d）。
     *
     * Java 的 InetAddress.equals 不会把 ::ffff:1.2.3.4 与 1.2.3.4 视为相等。同一 IPv4
     * 客户端经双栈监听 accept 出来的数据连接地址可能是 mapped 形式，而控制连接的远端地址
     * 是纯 IPv4；直接 equals 会误判为「被动 IP 校验失败」而拒绝合法连接。
     */
    private static boolean sameAddress(InetAddress first, InetAddress second) {
        byte[] firstBytes = unwrapIpv4Mapped(first.getAddress());
        byte[] secondBytes = unwrapIpv4Mapped(second.getAddress());
        return java.util.Arrays.equals(firstBytes, secondBytes);
    }

    private static byte[] unwrapIpv4Mapped(byte[] address) {
        if (address.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (address[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && address[10] == (byte) 0xff && address[11] == (byte) 0xff) {
                return java.util.Arrays.copyOfRange(address, 12, 16);
            }
        }
        return address;
    }

    /**
     * 创建被动监听 socket，并在 bind 之前开启 SO_REUSEADDR。
     *
     * 原实现是 {@code new ServerSocket(port, backlog, addr)}（三参构造，Java 默认
     * SO_REUSEADDR=false）。下载结束服务端主动关闭数据连接会进入 TIME_WAIT（约 60 秒），
     * 下一个 PASV 再 bind 同一端口就抛 BindException；快速传输海量小文件时，被动端口很快被
     * TIME_WAIT 占满，新的数据连接连不上。开启 SO_REUSEADDR 后允许 bind 到 TIME_WAIT 状态的
     * 端口，端口即可复用。
     *
     * 注意这不是删掉 TIME_WAIT：旧 socket 仍会等满 60 秒才释放，只是让新监听不被它挡住。
     * FTP 被动每次连接的四元组（客户端 IP + 临时端口）不同，加上 TCP 随机序列号保护，串扰
     * 风险可忽略——这是 nginx / vsftpd 等高性能服务端的标准做法。
     *
     * Receive buffer size is inherited from kernel defaults; do not lock autotuning.
     */
    private ServerSocket newServerSocket(InetAddress address, int port)
            throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);

            socket.bind(new InetSocketAddress(address, port));
            return socket;
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.ftpserver.DataConnectionFactory#isSecure()
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Set the security protocol.
     */
    public void setSecure(final boolean secure) {
        this.secure = secure;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.ftpserver.DataConnectionFactory#isZipMode()
     */
    public boolean isZipMode() {
        return isZip;
    }

    /**
     * Set zip mode.
     */
    public void setZipMode(final boolean zip) {
        isZip = zip;
    }

    /**
     * Check the data connection idle status.
     */
    public synchronized boolean isTimeout(final long currTime) {

        // data connection not requested - not a timeout
        if (requestTime == 0L) {
            return false;
        }

        // data connection active - not a timeout
        if (dataSoc != null) {
            return false;
        }

        // no idle time limit - not a timeout
        int maxIdleTime = session.getListener()
                .getDataConnectionConfiguration().getIdleTime() * 1000;
        if (maxIdleTime == 0) {
            return false;
        }

        // idle time is within limit - not a timeout
        if ((currTime - requestTime) < maxIdleTime) {
            return false;
        }

        return true;
    }

    /**
     * Dispose data connection - close all the sockets.
     */
    public void dispose() {
        closeDataConnection();
    }

    /**
     * Sets the server's control address.
     */
    public void setServerControlAddress(final InetAddress serverControlAddress) {
        this.serverControlAddress = serverControlAddress;
    }
}
