package com.github.stjiege.ftpshare.services;

import android.content.Context;

import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.SslConfiguration;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * 本机的 FTPS 服务端配置：每台设备一份自签身份，证书不内置、私钥不共享。
 *
 * <p><b>私钥是进程内的软件密钥，不在 AndroidKeyStore 里</b>（见 {@link FtpsIdentity}）。
 * 这是刻意的性能取舍：FTP 每传一个文件、每列一次目录都要新建一条数据连接，也就是一次
 * TLS 握手、一次证书私钥签名。私钥留在安全硬件里时，这次签名是一次 Binder IPC + TEE
 * 调用（真机 10–100 ms），并发时还会撞上 KeyMint 的 operation 槽位上限而直接失败。
 * 换成软件密钥后签名是纯 CPU（微秒级），握手只剩网络往返。
 */
public final class FtpsConfiguration implements SslConfiguration {

    private static final String KEY_ALIAS = "ftps";

    private final SSLContext context;
    private final String[] suites;
    private final X509Certificate certificate;
    private final boolean chaCha20Active;

    public FtpsConfiguration(Context appContext) throws Exception {
        this(appContext, false);
    }

    /**
     * @param preferChaCha20 实验室开关（Constants.PreferenceConsts.FTPS_CHACHA20_PREFERRED）：
     *        true 时把 ChaCha20-Poly1305 排到套件首选位。只在无 AES 指令集的手机上有收益，
     *        见该常量的注释。设备/运行时不支持该套件时静默回退 AES（supported 检测兜底），
     *        {@link #chaCha20Active()} 可查询最终是否真正启用。
     */
    public FtpsConfiguration(Context appContext, boolean preferChaCha20) throws Exception {
        this(FtpsIdentity.load(appContext), preferChaCha20);
    }

    private FtpsConfiguration(FtpsIdentity identity, boolean preferChaCha20) throws GeneralSecurityException {
        this(identity.privateKey(), identity.certificate(), preferChaCha20);
    }

    // Separate Android identity persistence from the TLS configuration for JVM testing.
    FtpsConfiguration(final PrivateKey key, final X509Certificate certificate)
            throws GeneralSecurityException {
        this(key, certificate, false);
    }

    private FtpsConfiguration(final PrivateKey key, final X509Certificate certificate,
            boolean preferChaCha20) throws GeneralSecurityException {
        this.certificate = certificate;

        X509ExtendedKeyManager manager = new X509ExtendedKeyManager() {
            /**
             * 证书是 EC，JSSE 通常来要 "EC"；部分 ROM / 老实现会传 "ECDSA"。
             * 两种都认——只认 "EC" 的话，那些机器上 {@code chooseServerAlias} 返回 null，
             * 服务端拿不到证书和私钥，握手直接失败。
             */
            private boolean supportedKey(String type) {
                return "EC".equals(type) || "ECDSA".equals(type);
            }

            @Override public String[] getClientAliases(String t, Principal[] i) { return null; }
            @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return null; }
            @Override public String[] getServerAliases(String t, Principal[] i) {
                return supportedKey(t) ? new String[]{KEY_ALIAS} : null;
            }
            @Override public String chooseServerAlias(String t, Principal[] i, Socket s) {
                return supportedKey(t) ? KEY_ALIAS : null;
            }
            @Override public String chooseEngineServerAlias(String t, Principal[] i, SSLEngine e) {
                return supportedKey(t) ? KEY_ALIAS : null;
            }
            @Override public X509Certificate[] getCertificateChain(String a) {
                return KEY_ALIAS.equals(a) ? new X509Certificate[]{certificate} : null;
            }
            @Override public PrivateKey getPrivateKey(String a) {
                return KEY_ALIAS.equals(a) ? key : null;
            }
        };

        context = SSLContext.getInstance("TLSv1.2");
        context.init(new KeyManager[]{manager}, null, null);

        // 会话复用三层防线（不做 TLS 1.3，全靠 TLS 1.2 机制）：
        //  1. 会话票证（RFC 5077）：Conscrypt 服务端默认签发 NewSessionTicket，票证自包含、
        //     与端口无关——支持票证的客户端（FileZilla / WinSCP / 新版 curl）跨数据端口直接
        //     复用，这层不吃缓存容量。
        //  2. 会话 ID 缓存：按 session ID 索引，服务端查找与客户端连哪个端口无关。容量与
        //     寿命放大到 256 条 / 8 小时——局域网多客户端 + 全天反复连接也兜得住
        //     （每条约 1–2 KB，256 条上限也就半 MB 堆）。
        //  3. 被动端口收敛（FtpService 里 64 个端口）：按 host:port 索引缓存的老客户端
        //     （libcurl 等）只有端口复用了才命中，端口池越小命中率越高。
        // 真正兜底握手开销的是上面那把软件私钥（签名微秒级，见 FtpsIdentity）。
        SSLSessionContext serverCtx = context.getServerSessionContext();
        serverCtx.setSessionCacheSize(256);
        serverCtx.setSessionTimeout(8 * 3600);

        List<String> supported = Arrays.asList(context.getSupportedSSLParameters().getCipherSuites());
        List<String> enabled = new ArrayList<>();
        // 全部 ECDHE_ECDSA，与本机 EC 身份匹配。
        // 默认 GCM 优先；CBC 两项给不支持 GCM 的老客户端兜底，否则它们在握手阶段就被拒。
        // 实验室开关打开时 ChaCha20-Poly1305 排第一：没有 ARMv8 AES 指令集的手机上软件
        // ChaCha20 比软件 AES-GCM 快 3–5 倍；运行时不支持该套件则静默跳过（Android 6 没有）。
        List<String> preferred = new ArrayList<>();
        if (preferChaCha20) {
            preferred.add("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        }
        preferred.add("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        preferred.add("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384");
        preferred.add("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA");
        preferred.add("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA");
        for (String suite : preferred) {
            if (supported.contains(suite)) {
                enabled.add(suite);
            }
        }
        if (enabled.isEmpty()) {
            throw new GeneralSecurityException("TLS 1.2 cipher unavailable");
        }
        suites = enabled.toArray(new String[0]);
        chaCha20Active = suites.length > 0
                && "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256".equals(suites[0]);
    }

    /** 暴露给集成测试核对「身份在两次启动之间保持一致」。 */
    public X509Certificate certificate() {
        return certificate;
    }

    /** ChaCha20 是否真正排到了首选位（开关开了但运行时不支持时为 false）。 */
    public boolean chaCha20Active() {
        return chaCha20Active;
    }

    @Override public SSLSocketFactory getSocketFactory() { return context.getSocketFactory(); }
    @Override public SSLContext getSSLContext() { return context; }
    @Override public SSLContext getSSLContext(String protocol) { return context; }
    @Override public String[] getEnabledCipherSuites() { return suites.clone(); }
    @Override public ClientAuth getClientAuth() { return ClientAuth.NONE; }
}
