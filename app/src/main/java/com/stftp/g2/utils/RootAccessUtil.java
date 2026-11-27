package com.stftp.g2.utils;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Root-only, temporary support for configured FTP ports below 1024. */
public final class RootAccessUtil {
    private static final String PORT_START_PATH = "/proc/sys/net/ipv4/ip_unprivileged_port_start";
    private static final int REDIRECT_BASE = 20000;
    private static final long ROOT_REPLY_TIMEOUT_SECONDS = 30L;
    private static final long ROOT_EXIT_TIMEOUT_MS = 5000L;

    private static final java.util.Set<PortBindingLease> FAILED_LEASES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<PortBindingLease, Boolean>());
    private RootAccessUtil() { }

    /** These commands are fixed by the app; no user-supplied shell arguments. */
    public static boolean hasDozeRootAccess() {
        return runDozeCommand("test \"$(id -u)\" = 0");
    }

    public static boolean setDozeWhitelist(boolean enable) {
        String tool = android.os.Build.VERSION.SDK_INT >= 24 ? "cmd deviceidle" : "dumpsys deviceidle";
        return runDozeCommand(tool + " whitelist " + (enable ? "+" : "-")
                + com.stftp.g2.BuildConfig.APPLICATION_ID);
    }

    private static boolean runDozeCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command + " >/dev/null 2>&1")
                    .redirectErrorStream(true).start();
            final Process child = process;
            FutureTask<Integer> exit = new FutureTask<>(() -> {
                try (java.io.InputStream output = child.getInputStream()) {
                    byte[] buffer = new byte[512];
                    while (output.read(buffer) != -1) { /* Drain root-manager messages. */ }
                }
                return child.waitFor();
            });
            Thread reader = new Thread(exit, "DozeRootCommand");
            reader.setDaemon(true);
            reader.start();
            return exit.get(ROOT_REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS) == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unavailable) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Backend listener port. Older kernels need a root-managed NAT redirect. */
    public static int getListenerPort(int configuredPort) {
        if (configuredPort <= 0 || configuredPort >= 1024 || android.os.Process.myUid() == 0) return configuredPort;
        return new File(PORT_START_PATH).exists() ? configuredPort : REDIRECT_BASE + configuredPort;
    }

    /**
     * Requests authorization from the configured root manager and holds a narrowly scoped lease until FTP shuts down.
     * Newer kernels temporarily lower the unprivileged threshold only for the bind operation;
     * older Android kernels use a per-port NAT redirect to an unprivileged local listener.
     */
    public static PortBindingLease requestPortBinding(Context context, int port, boolean ipv6) throws IOException {
        if (port < 1 || port >= 1024) throw new IllegalArgumentException("port must be 1..1023");
        if (android.os.Process.myUid() == 0) return PortBindingLease.noop();
        // Do not discard failed cleanup ownership when FtpService releases its field.
        for (PortBindingLease pending : FAILED_LEASES) pending.close();
        int backend = getListenerPort(port);
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String chain4 = "FT4" + suffix, chain6 = "FT6" + suffix;
        String cleanup = natCleanupScript(port, chain4, chain6);
        String script = "p='" + PORT_START_PATH + "'; port=" + port + "; backend=" + backend + "; ipv6=" + (ipv6 ? "1" : "0") + "; "
                + "if [ -e \"$p\" ]; then [ -r \"$p\" ] && [ -w \"$p\" ] || exit 60; "
                + "old=$(cat \"$p\") || exit 42; case \"$old\" in ''|*[!0-9]*) exit 43;; esac; changed=0; "
                + "restore() { if [ \"$changed\" = 1 ]; then now=$(cat \"$p\" 2>/dev/null) || return 1; "
                + "if [ \"$now\" = \"$port\" ]; then printf '%s\\n' \"$old\" > \"$p\" || return 1; fi; fi; echo CLEANED; }; "
                + "trap restore EXIT; trap 'exit 0' HUP INT TERM; "
                + "if [ \"$old\" -gt \"$port\" ]; then changed=1; printf '%s\\n' \"$port\" > \"$p\" || exit 44; fi; "
                + "echo READY:sysctl; IFS= read -r _ || true; exit 0; fi; "
                + "command -v netstat >/dev/null 2>&1 || exit 50; "
                + "listening=$(netstat -lnt 2>/dev/null) || exit 50; "
                + "case \"$listening\" in *:\"$port\"' '*) exit 51;; esac; "
                + "v4=" + chain4 + "; v6=" + chain6 + "; "
                + "cleanup() { " + cleanup + "; }; "
                + "trap cleanup EXIT; trap 'exit 0' HUP INT TERM; "
                + "iptables -t nat -N $v4 || exit 52; iptables -t nat -A $v4 -p tcp --dport \"$port\" -j DNAT --to-destination :\"$backend\" || exit 53; "
                + "iptables -t nat -I PREROUTING 1 -p tcp --dport \"$port\" -j $v4 || exit 54; "
                + "iptables -t nat -I OUTPUT 1 -p tcp -d 127.0.0.1/32 --dport \"$port\" -j $v4 || exit 55; "
                + "ipv6_forwarded=0; if [ \"$ipv6\" = 1 ]; then "
                + "if ip6tables -t nat -N $v6 2>/dev/null && "
                + "ip6tables -t nat -A $v6 -p tcp --dport \"$port\" -j DNAT --to-destination :\"$backend\" && "
                + "ip6tables -t nat -I PREROUTING 1 -p tcp --dport \"$port\" -j $v6 && "
                + "ip6tables -t nat -I OUTPUT 1 -p tcp -d ::1/128 --dport \"$port\" -j $v6; then ipv6_forwarded=1; "
                + "else ip6tables -t nat -D PREROUTING -p tcp --dport \"$port\" -j $v6 2>/dev/null; "
                + "ip6tables -t nat -D OUTPUT -p tcp -d ::1/128 --dport \"$port\" -j $v6 2>/dev/null; "
                + "ip6tables -t nat -F $v6 2>/dev/null; ip6tables -t nat -X $v6 2>/dev/null; fi; fi; "
                + "echo READY:nat:$ipv6_forwarded; IFS= read -r _ || true; exit 0";

        final Process process;
        try {
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException(context.getString(com.stftp.g2.R.string.attention_low_port_root_unavailable), error);
        }
        final BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
        final BufferedWriter input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        FutureTask<String> response = new FutureTask<>(() -> {
            String line;
            while ((line = output.readLine()) != null) if (line.startsWith("READY:") || line.startsWith("ERR:")) return line;
            return null;
        });
        Thread reader = new Thread(response, "FtpRootBindHandshake");
        reader.setDaemon(true);
        reader.start();
        try {
            String line = response.get(ROOT_REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (line == null || !line.startsWith("READY:")) {
                closeQuietly(input, output, process);
                throw new IOException(context.getString(com.stftp.g2.R.string.attention_low_port_root_denied)
                        + " (" + (line == null ? "su 已退出" : line) + ")");
            }
            return new PortBindingLease(process, input, output, true,
                    line.startsWith("READY:sysctl") || line.endsWith(":1"),
                    line.startsWith("READY:nat") ? "ipv6_forwarded=" + (line.endsWith(":1") ? "1; " : "0; ") + cleanup : null);
        } catch (Exception error) {
            closeQuietly(input, output, process);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("等待 Root 管理器授权失败", error);
        }
    }

    static String natCleanupScript(int port, String v4, String v6) {
        StringBuilder script = new StringBuilder();
        String[] tools = {"iptables", "ip6tables"};
        String[] chains = {v4, v6};
        String[] loopbacks = {"127.0.0.1/32", "::1/128"};
        for (int i = 0; i < tools.length; i++) {
            String tool = tools[i], chain = chains[i];
            script.append(tool).append(" -t nat -D PREROUTING -p tcp --dport ").append(port)
                    .append(" -j ").append(chain).append(" 2>/dev/null; ");
            script.append(tool).append(" -t nat -D OUTPUT -p tcp -d ").append(loopbacks[i])
                    .append(" --dport ").append(port).append(" -j ").append(chain).append(" 2>/dev/null; ");
            script.append(tool).append(" -t nat -F ").append(chain).append(" 2>/dev/null; ")
                    .append(tool).append(" -t nat -X ").append(chain).append(" 2>/dev/null; ");
            // IPv6 NAT may not exist at all. Verify IPv4 unconditionally, IPv6 when its table is available.
            script.append("rules=$(").append(tool).append(" -t nat -S 2>/dev/null); status=$?; ");
            if (i == 0) script.append("[ $status = 0 ] || return 1; ");
            else script.append("if [ \"${ipv6_forwarded:-0}\" = 1 ]; then [ $status = 0 ] || return 1; fi; ");
            script.append("if [ $status = 0 ]; then case \"$rules\" in *")
                    .append(chain).append("*) return 1;; esac; fi; ");
        }
        return "verify_cleanup() { " + script + "echo CLEANED; }; verify_cleanup";
    }

    private static void closeQuietly(BufferedWriter input, BufferedReader output, Process process) {
        try { input.close(); } catch (Exception ignored) { }
        try { output.close(); } catch (Exception ignored) { }
        process.destroy();
    }

    public static final class PortBindingLease implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BufferedReader output;
        private final boolean ipv6Forwarded;
        private boolean closed;
        private final String cleanupScript;
        private PortBindingLease(Process process, BufferedWriter input, BufferedReader output,
                                 boolean active, boolean ipv6Forwarded, String cleanupScript) {
            this.cleanupScript = cleanupScript;
            this.process = process; this.input = input; this.output = output;
            this.closed = !active; this.ipv6Forwarded = ipv6Forwarded;
        }
        private static PortBindingLease noop() { return new PortBindingLease(null, null, null, false, true, null); }
        public boolean isIpv6Forwarded() { return ipv6Forwarded; }
        @Override public synchronized void close() throws IOException {
            try {
                closeOnce();
                FAILED_LEASES.remove(this);
            } catch (IOException failure) {
                FAILED_LEASES.add(this);
                throw failure;
            }
        }
        private void closeOnce() throws IOException {
            if (closed) return;
            // 关 stdin 让 shell 读到 EOF 并退出，脚本的 EXIT trap 才会删 iptables 规则。
            // 这一行失败不能中断后面的清理链：早期版本直接 input.close() 后往下走，
            // 一旦它抛 IOException（su 已死/管道断开），exitValue()/destroy() 全被跳过，
            // 结果 FTSHARE4/FTSHARE6 规则与 su 进程双残留，下次 iptables -N 必然 exit 52。
            IOException closeError = null;
            try {
                input.close();
            } catch (IOException error) {
                closeError = error;
            }

            boolean exited = waitForExit();
            if (!exited) {
                // destroy() 发 SIGTERM，被脚本的 trap 'exit 0' TERM 捕获后转成正常退出，
                // 从而触发 EXIT trap 清理规则；SIGKILL 不会跑 trap，所以这里不能强杀。
                process.destroy();
                // 给 trap 里那几条 iptables 命令留出执行时间，避免紧接着的 restore/重绑
                // 撞上尚未删除的链。调用方都在后台线程（SERVER_EXECUTOR），1 秒可接受。
                exited = waitForExit(1000L);
            }
            boolean confirmed = false;
            if (exited) {
                try {
                    String line;
                    while ((line = output.readLine()) != null) {
                        if ("CLEANED".equals(line)) confirmed = true;
                    }
                } catch (IOException error) { closeError = error; }
            }
            // The retry script names only this lease's unique chains.
            if (!confirmed && cleanupScript != null) confirmed = runDozeCommand(cleanupScript);
            if (!confirmed) throw new IOException("Low-port cleanup unconfirmed; retry required", closeError);
            closed = true;
            try { output.close(); } catch (IOException ignored) { }
        }

        private boolean waitForExit() {
            return waitForExit(ROOT_EXIT_TIMEOUT_MS);
        }

        /** Polls for process exit without blocking the caller past {@code timeoutMs}. */
        private boolean waitForExit(long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                try {
                    process.exitValue();
                    return true;
                } catch (IllegalThreadStateException running) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        }
    }
}
