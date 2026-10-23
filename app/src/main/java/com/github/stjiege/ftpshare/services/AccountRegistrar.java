package com.github.stjiege.ftpshare.services;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.utils.ReadOnlyPermission;
import com.github.stjiege.ftpshare.utils.StorageUtil;

import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把匿名账户与具名账户组装成 {@link BaseUser} 并注册进 {@link FtpServerFactory}。
 * 从 {@code FtpService.startFTPService()} 抽出，是纯账户装配逻辑，不碰服务生命周期。
 *
 * <p><b>匿名与具名走同一条装配管线</b>：home 解析、注册行为、目录失效时的处理、日志口径全部
 * 一致。早先具名账户在目录失效时被 {@code continue} 跳过、匿名却照旧注册，同一个"目录没配好"
 * 在两类账户上有两种结局（一个登不进、一个登得进却什么都做不了），排查时很难对上。</p>
 */
public final class AccountRegistrar {

    private AccountRegistrar() {
    }

    /**
     * 注册匿名账户（若开启）与全部具名账户，并为开启了完整存储的账户挂上虚拟根文件系统。
     *
     * <p>目录暂时不可用的账户<b>照旧注册</b>：匿名早先就是这么做的（否则匿名直接不可用），
     * 现在具名也一致——账户不会静默消失，代价是"能登录但列目录/传输会失败"，这个代价由
     * {@link #warnUnusableHome} 的一条警告说清楚。</p>
     */
    /**
     * @return 开启了“完整存储”的账户名集合，供上层选择文件系统实现。
     */
    public static Set<String> registerAccounts(FtpServerFactory factory, List<AccountItem> accounts,
                                               Context context, SharedPreferences settings) throws Exception {
        int[] unusableHomes = new int[1];
        List<Authority> writable = new ArrayList<>();
        writable.add(new WritePermission());
        // 第三层并发限制：每个账号各自最多 [Constants.FTPConsts.FTP_MAX_LOGINS_PER_ACCOUNT] 条会话。
        // 不加这一条时 PropertiesUserManager 读回账号会自动补一个 (0,0)，等于不限；显式挂上才生效。
        // 第二参数 0 = 不按 IP 再限一层（理由见 FTP_MAX_LOGINS_PER_ACCOUNT 的注释）。
        writable.add(new ConcurrentLoginPermission(
                Constants.FTPConsts.FTP_MAX_LOGINS_PER_ACCOUNT, 0));

        Set<String> fullStorageAccounts = new HashSet<>();
        int registered = 0;

        if (settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE,
                Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT)) {
            String configuredAnonymousPath = settings.getString(
                    Constants.PreferenceConsts.ANONYMOUS_MODE_PATH,
                    Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_DEFAULT);
            String anonymousHome = resolveHome(context, configuredAnonymousPath);
            LogManager.info("匿名账户路径: configured=" + configuredAnonymousPath
                    + " home=" + anonymousHome);
            // 启动时绝不覆盖用户已确认的路径；卷被卸载时只记日志，不暴露一个新目录。
            warnUnusableHome(unusableHomes, Constants.FTPConsts.NAME_ANONYMOUS, anonymousHome);
            BaseUser baseUser = new BaseUser();
            baseUser.setName(Constants.FTPConsts.NAME_ANONYMOUS);
            baseUser.setPassword("");
            baseUser.setHomeDirectory(anonymousHome);
            List<Authority> anonymousAuthorities = new ArrayList<>();
            if (settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE,
                    Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE_DEFAULT)) {
                anonymousAuthorities.add(new WritePermission());
            } else {
                anonymousAuthorities.add(new ReadOnlyPermission());
            }
            // 匿名也是一个"账号"，同样受单账号并发上限约束（与可写与否无关，两个分支都加）。
            anonymousAuthorities.add(new ConcurrentLoginPermission(
                    Constants.FTPConsts.FTP_MAX_LOGINS_PER_ACCOUNT, 0));
            baseUser.setAuthorities(anonymousAuthorities);
            factory.getUserManager().save(baseUser);
            registered++;
        }

        // 具名账户一律拥有完整读写权限。
        for (AccountItem item : accounts) {
            String homeDirectory = resolveHome(context,
                    item.fullStorage ? StorageUtil.getMainStoragePath() : item.path);
            warnUnusableHome(unusableHomes, item.account, homeDirectory);
            BaseUser baseUser = new BaseUser();
            baseUser.setName(item.account);
            baseUser.setPassword(item.password);
            baseUser.setHomeDirectory(homeDirectory);
            baseUser.setAuthorities(writable);
            factory.getUserManager().save(baseUser);
            registered++;
            if (item.fullStorage) fullStorageAccounts.add(item.account);
        }

        LogManager.info("账户注册完成：共 " + registered + " 个，其中 "
                + unusableHomes[0] + " 个的目录当前不可用；完整存储账户 "
                + fullStorageAccounts.size() + " 个");
        return fullStorageAccounts;
    }

    /** 匿名与具名共用：空路径回退默认共享目录，其余按卷名大小写迁移后原样返回。 */
    private static String resolveHome(Context context, String configuredPath) {
        return StorageUtil.resolveHomeDirectory(context, configuredPath);
    }

    /** @return true 表示这个 home 当前不可用（调用方负责计数）。 */
    private static boolean warnUnusableHome(int[] unusableHomes, String account, String homeDirectory) {
        if (homeDirectory != null && homeDirectory.length() > 0
                && new File(homeDirectory).isDirectory()) {
            return false;
        }
        unusableHomes[0]++;
        // 账户仍然注册：能登录，但列目录与传输全部失败。必须把这层因果写出来，
        // 否则现场只剩一句"目录不可用"，看不出它会导致什么。
        LogManager.warning("账户目录不可用，账户仍会注册：能登录但列目录/传输会失败 user="
                + account + " path=" + homeDirectory);
        return true;
    }
}
