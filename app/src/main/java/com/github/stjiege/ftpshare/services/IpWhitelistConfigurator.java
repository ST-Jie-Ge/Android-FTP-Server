package com.github.stjiege.ftpshare.services;

import android.content.SharedPreferences;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.LogManager;

import org.apache.ftpserver.ipfilter.IpFilterType;
import org.apache.ftpserver.ipfilter.RemoteIpFilter;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.mina.filter.firewall.Subnet;

import java.net.InetAddress;
import java.util.Set;

/**
 * IP 白名单的解析与安装。从 {@code FtpService} 抽出，纯粹是一组无状态的静态工具方法。
 *
 * 白名单是「允许列表」：只有列出的地址（及子网）能建立控制连接，其余一律在认证之前拒绝。
 * 存储的无效条目会被跳过，单条拼写错误不会让整个服务停摆。
 */
public final class IpWhitelistConfigurator {

    private IpWhitelistConfigurator() {
    }

    /** 白名单开启时给监听器装上一个 allow-list {@link RemoteIpFilter}。 */
    public static void apply(ListenerFactory listenerFactory, SharedPreferences settings) {
        if (!settings.getBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED,
                Constants.PreferenceConsts.IP_WHITELIST_ENABLED_DEFAULT)) {
            return;
        }
        Set<String> rawEntries = settings.getStringSet(Constants.PreferenceConsts.IP_WHITELIST_ENTRIES, null);
        if (rawEntries == null) rawEntries = java.util.Collections.emptySet();
        RemoteIpFilter filter = new RemoteIpFilter(IpFilterType.ALLOW) {
            @Override
            public boolean accept(org.apache.mina.core.session.IoSession session) {
                boolean accepted = super.accept(session);
                if (!accepted) {
                    LogManager.warning("IP白名单拦截连接: " + session.getRemoteAddress());
                }
                return accepted;
            }
        };
        int added = 0;
        for (String entry : rawEntries) {
            try {
                String trimmed = entry == null ? "" : entry.trim();
                // 条目按 "value|note" 持久化，只有 value 参与过滤。
                int noteSeparator = trimmed.indexOf('|');
                if (noteSeparator >= 0) trimmed = trimmed.substring(0, noteSeparator).trim();
                // 没有地址的行是「停用」状态：既不落盘也不匹配任何东西。跳过它可避免空行
                // 被解析成回环地址、从而悄悄把服务开放给 localhost。
                if (trimmed.isEmpty()) continue;
                added += addSubnets(filter, trimmed);
            } catch (Exception error) {
                LogManager.warning("忽略无效的IP白名单条目: " + entry);
            }
        }
        // An enabled allow-list with no valid entries allows nobody, never everybody.
        listenerFactory.setSessionFilter(filter);
        if (added == 0) LogManager.warning("IP白名单已启用但没有有效地址，所有连接将被拒绝");
        LogManager.info("已启用IP白名单，共 " + added + " 条地址");
    }

    /**
     * 把一条存储值的所有地址加进过滤器，返回实际加了多少条。
     * 值可以是单个主机（"192.168.1.5"）、CIDR 段（"192.168.1.0/24"）或自由区间
     * （"192.168.1.1-192.168.1.5"）。自由区间限制在同一 /24 内，逐个展开成主机路由
     * （最多 256 条），而不是用更大的子网近似，以免误放行邻居地址。
     */
    private static int addSubnets(RemoteIpFilter filter, String value) throws Exception {
        int dash = value.indexOf('-');
        if (dash > 0) {
            int[] from = parseIpv4Octets(value.substring(0, dash).trim());
            int[] to = parseIpv4Octets(value.substring(dash + 1).trim());
            if (from == null || to == null) throw new IllegalArgumentException("bad range: " + value);
            if (from[0] != to[0] || from[1] != to[1] || from[2] != to[2]) {
                throw new IllegalArgumentException("range leaves the /24: " + value);
            }
            int low = Math.min(from[3], to[3]);
            int high = Math.max(from[3], to[3]);
            int added = 0;
            for (int host = low; host <= high; host++) {
                filter.add(new Subnet(InetAddress.getByAddress(new byte[]{
                        (byte) from[0], (byte) from[1], (byte) from[2], (byte) host}), 32));
                added++;
            }
            return added;
        }
        int slash = value.indexOf('/');
        String hostPart = slash >= 0 ? value.substring(0, slash) : value;
        int[] octets = parseIpv4Octets(hostPart);
        if (octets == null) throw new IllegalArgumentException("bad IPv4 address: " + value);
        InetAddress address = InetAddress.getByAddress(new byte[]{
                (byte) octets[0], (byte) octets[1], (byte) octets[2], (byte) octets[3]});
        int prefix = slash >= 0
                ? Integer.parseInt(value.substring(slash + 1))
                : 32;
        if (prefix < 1 || prefix > 32) throw new IllegalArgumentException("bad prefix: " + value);
        if (slash >= 0 && hasHostBits(octets, prefix)) {
            throw new IllegalArgumentException("CIDR address contains host bits: " + value);
        }
        filter.add(new Subnet(address, prefix));
        return 1;
    }

    private static boolean hasHostBits(int[] octets, int prefix) {
        long address = ((octets[0] & 255L) << 24)
                | ((octets[1] & 255L) << 16)
                | ((octets[2] & 255L) << 8)
                | (octets[3] & 255L);
        long hostMask = (1L << (32 - prefix)) - 1L;
        return (address & hostMask) != 0;
    }

    /** 返回点分十进制 IPv4 字面量的四个字节，不是合法 IPv4 时返回 null。 */
    private static int[] parseIpv4Octets(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            String part = parts[index];
            if (!part.matches("[0-9]{1,3}")) return null;
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException error) {
                return null;
            }
            if (number < 0 || number > 255) return null;
            octets[index] = number;
        }
        return octets;
    }
}
