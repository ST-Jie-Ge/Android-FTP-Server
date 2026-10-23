package com.github.stjiege.ftpshare.utils;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

/** All desired-state transitions and asynchronous callbacks are serialized on the main thread. */
public final class FtpDiscoveryManager {
    private final NsdManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Registration active;
    private int port;
    private String name;
    private boolean tls;
    private int retries;
    private final Runnable retry = this::reconcile;

    public FtpDiscoveryManager(Context context) {
        manager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }
    public void register(int port, String name, boolean tls) {
        handler.post(() -> {
            this.port = port; this.name = name; this.tls = tls;
            retries = 0; handler.removeCallbacks(retry); reconcile();
        });
    }
    public void unregister() {
        handler.post(() -> {
            port = 0; name = null; retries = 0;
            handler.removeCallbacks(retry); reconcile();
        });
    }
    private void scheduleRetry() {
        if (++retries > 3) return;
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 15000L * retries);
    }
    private void reconcile() {
        if (manager == null) return;
        if (active != null) {
            if (active.matches() || active.removing) return;
            active.removing = true;
            try { manager.unregisterService(active); }
            catch (IllegalArgumentException absent) { active = null; reconcile(); }
            catch (RuntimeException error) {
                active.removing = false; LogManager.error("NSD 注销异常", error); scheduleRetry();
            }
            return;
        }
        if (port <= 0 || name == null) return;
        Registration registration = new Registration();
        active = registration;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        // Explicit AUTH TLS uses the FTP service and port, not an implicit-TLS service.
        info.setServiceType("_ftp._tcp.");
        info.setPort(port);
        info.setAttribute("app", "FTP Server G2");
        if (tls) info.setAttribute("tls", "required");
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration); }
        catch (RuntimeException error) { active = null; LogManager.error("NSD 注册异常", error); scheduleRetry(); }
    }
    private final class Registration implements NsdManager.RegistrationListener {
        final int publishedPort = port;
        final String publishedName = name;
        final boolean publishedTls = tls;
        boolean registered, removing;
        boolean matches() { return publishedPort == port && publishedName.equals(name) && publishedTls == tls; }
        @Override public void onServiceRegistered(NsdServiceInfo info) {
            handler.post(() -> {
                if (active != this) return;
                registered = true; retries = 0;
                LogManager.info("NSD 已发布 " + info.getServiceName() + " port=" + publishedPort);
                reconcile();
            });
        }
        @Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
            handler.post(() -> {
                if (active != this) return;
                active = null; LogManager.warning("NSD 注册失败 code=" + error);
                if (port > 0) scheduleRetry();
            });
        }
        @Override public void onServiceUnregistered(NsdServiceInfo info) {
            handler.post(() -> {
                if (active != this) return;
                active = null; retries = 0; LogManager.info("NSD 已注销"); reconcile();
            });
        }
        @Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {
            handler.post(() -> {
                if (active != this) return;
                removing = false; LogManager.warning("NSD 注销失败 code=" + error); scheduleRetry();
            });
        }
    }
}
