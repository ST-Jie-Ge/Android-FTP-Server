package com.github.stjiege.ftpshare.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;

/** Local address helpers based on public network APIs. */
public final class NetworkEnvironmentUtil {
    private NetworkEnvironmentUtil() { }

    /** Returns the active Wi-Fi IPv4 address, if one is available. */
    public static @Nullable String getWifiIp(@NonNull Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            // 优先 active network；热点/以太网/USB 共享等场景下 active network 可能不是 Wi-Fi，
            // 此时遍历所有 network，找带 Wi-Fi 传输且含 IPv4 的那一个，避免副标题落到回环地址。
            Network active = manager.getActiveNetwork();
            Network[] all = manager.getAllNetworks();
            Network[] candidates = active == null ? all : prepend(all, active);
            if (candidates == null) return null;
            for (Network network : candidates) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception error) {
            LogManager.warning("读取 Wi-Fi 地址失败: " + error.getClass().getSimpleName());
        }
        return null;
    }

    /** Returns {@code all} with {@code active} moved to the front (allocates a new array). */
    private static Network[] prepend(Network[] all, Network active) {
        if (all == null || all.length == 0) return new Network[]{active};
        Network[] result = new Network[all.length + 1];
        result[0] = active;
        System.arraycopy(all, 0, result, 1, all.length);
        return result;
    }

    public static ArrayList<String> getLocalIpv4Addresses() {
        final ArrayList<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // 每个接口单独 try-catch：Android 上部分接口（VPN、蜂窝 rmnet、已 down 的接口）
                // 的 getInetAddresses() 会抛 SocketException，若放到外层会中断整个枚举，
                // 导致后面真正带 IPv4 的接口（如热点 ap0 / wlan0）被漏掉，副标题就显示"无 IPv4"。
                try {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                            String ip = address.getHostAddress();
                            if (!result.contains(ip)) result.add(ip);
                        }
                    }
                } catch (Exception ignored) {
                    // 单个接口枚举失败不阻塞其余接口。
                }
            }
        } catch (Exception error) {
            LogManager.warning("枚举本机 IPv4 地址失败: " + error.getClass().getSimpleName());
        }
        return result;
    }

    /** Returns usable local IPv4 and global/site-local IPv6 addresses. */
    /**
     * Returns local addresses the FTP listener can actually be reached on.
     *
     * <p>默认只返回 IPv4：服务端默认绑定 0.0.0.0（见 FtpService.startFTPService），
     * 列表里出现 IPv6 地址全是连不上的——尤其 Android 的隐私机制（RFC 4941）会给每个
     * WLAN 接口生成多个会轮换的临时 IPv6 地址，展示出来只会误导用户。
     * 仅当用户开启「允许从IPv6访问」且服务成功绑定 :: 双栈时才包含 IPv6。</p>
     *
     * @param includeIpv6 是否包含 global/site-local IPv6 地址（需服务已绑定双栈）
     */
    public static ArrayList<String> getLocalIpAddresses(boolean includeIpv6) {
        final ArrayList<String> result = getLocalIpv4Addresses();
        if (!includeIpv6) return result;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                try {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet6Address && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()) {
                            String ip = address.getHostAddress();
                            int scope = ip.indexOf('%');
                            if (scope >= 0) ip = ip.substring(0, scope);
                            if (!result.contains(ip)) result.add(ip);
                        }
                    }
                } catch (Exception ignored) {
                    // 同 getLocalIpv4Addresses：单接口异常不阻塞其余接口。
                }
            }
        } catch (Exception error) {
            LogManager.warning("枚举本机 IPv6 地址失败: " + error.getClass().getSimpleName());
        }
        return result;
    }

    public static boolean isWifiConnected(@NonNull Context context) {
        return hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI);
    }

    static boolean isCellularNetworkConnected(@NonNull Context context) {
        return hasTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private static boolean hasTransport(Context context, int transport) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(transport);
        } catch (Exception error) {
            LogManager.warning("读取网络状态失败: " + error.getClass().getSimpleName());
            return false;
        }
    }
}

