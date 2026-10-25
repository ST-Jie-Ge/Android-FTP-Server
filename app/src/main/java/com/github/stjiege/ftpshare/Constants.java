package com.github.stjiege.ftpshare;

import com.github.stjiege.ftpshare.utils.StorageUtil;

public class Constants {
    public static class SQLConsts {
        public static final String SQL_USERS_FILENAME = "ftp_accounts.db";
        public static final int SQL_VERSION = 3;
        public static final String TABLE_NAME = "ftp_account_table";
        public static final String COLUMN_ID = "_id";
        public static final String COLUMN_ACCOUNT_NAME = "name";
        public static final String COLUMN_PASSWORD = "password";
        public static final String COLUMN_PATH = "path";
        public static final String COLUMN_WRITABLE = "writable";
        public static final String COLUMN_FULL_STORAGE = "full_storage";
    }

    public static class FTPConsts {
        public static final int MAX_NAMED_ACCOUNTS = 5;
        public static final String NAME_ANONYMOUS = "anonymous";
        /**
         * Default number of concurrently open data-transfer streams (download + upload combined).
         * Multi-threaded FTP clients otherwise open one RandomAccessFile + buffer per transfer,
         * exhausting file descriptors and memory on Android, which crashes the whole process
         * during heavy I/O.
         *
         * Deliberately lower than the worker count (FTP_WORKER_THREADS_DEFAULT = 14): transfers
         * execute synchronously on the shared command workers, so a transfer cap equal to the
         * worker count lets N transfers starve every other command — including USER/PASS of a
         * new login, which then times out on the client. Keeping spare workers guarantees that
         * logins, NOOP, QUIT and ABOR are always answered even under full transfer load.
         *
         * 这只是默认值，用户可在设置页调整（见 [PreferenceConsts.FTP_TRANSFER_SLOTS]）。
         */
        public static final int FTP_MAX_CONCURRENT_TRANSFERS = 4;
        /**
         * 传输槽必须比工作线程数少几个，否则满载时没有空闲 worker 应答新登录。
         * 对话框里按这个预留量给出警告阈值，但不阻止用户保存（他可能确实知道自己在做什么）。
         */
        public static final int FTP_TRANSFER_SLOTS_WORKER_RESERVE = 4;
        /**
         * 单个账号（具名账号、匿名账号各算一个）最多能同时保持多少条 FTP 会话。
         *
         * 这是第四层并发限制里的第三层，靠给账号挂 {@code ConcurrentLoginPermission} 生效，
         * 与前两层（{@link PreferenceConsts#MAX_LOGIN_NUM} 全局总登录、
         * {@link PreferenceConsts#MAX_ANONYMOUS_NUM} 匿名总登录）是不同维度：那两层是"所有账号
         * 加在一起"，这一层是"这一个账号"。超限时 USER 命令回 421 直接断开。
         *
         * 取 12（刻意为之，不开放配置、不做 UI 入口）：默认档的全局上限是 30，
         * 若这里卡得过低，单账号场景会永远先撞这道闸门，UI 上调"最大连接数"就成了假控件。
         * 12 给默认档（30）留出余量，单设备多线程客户端也够用，同时仍拦得住单个账号把整池吃满。
         *
         * 值为 0 表示不限。这里刻意不给 per-IP 单独设限（第二参数传 0）：同一台机器上的
         * 多线程客户端本来就要开多条连接，按 IP 再砍一刀只会让它更难连上；真正防止单台机器
         * 占满服务的是全局 MAX_LOGIN_NUM 与 FTP_MAX_CONCURRENT_TRANSFERS。
         */
        public static final int FTP_MAX_LOGINS_PER_ACCOUNT = 12;
        /**
         * 传输专用线程池的容量 = 并发传输槽上限 + 本余量。
         *
         * 传输命令（RETR/STOR/STOU/APPE）与目录列举命令（LIST/NLST/MLSD）的整段执行体
         * 跑在这个池里，不再占用 MINA 的命令线程池 —— 否则传输与目录遍历会把线程占满、
         * 新连接连登录命令都排不到队。详见
         * {@code org.apache.ftpserver.impl.DefaultFtpHandler}。
         *
         * 取 4 的理由：槽本身就是硬闸门（超出的传输在打开文件前被 450 拒绝，等 3 秒后释放
         * 线程），所以同时真正在跑的传输不会超过槽数；但目录列举（LIST/NLST/MLSD）**不占
         * 传输槽**、却同样占池线程（含 accept 数据连接的等待，最长 idleTime=60s），需要单独
         * 的并发余量。4 个覆盖「正在等槽的线程」+「若干并发目录列举」，再大只会白占内存；
         * 池满时任务在无界队列排队，不会失败。
         *
         * 目录列举的并发上限由 {@code TransferSlots.LISTINGS} 按「本池容量 − 当前槽数」算出，
         * 两条路径加起来不超过池容量，且互不抢占。
         */
        public static final int FTP_TRANSFER_POOL_HEADROOM = 4;
    }

    public static class PreferenceConsts {
        public static final String FILE_NAME = "settings";
        /**
         * this stands for a boolean value
         */
        public static final String ANONYMOUS_MODE = "anonymous_mode";
        public static final boolean ANONYMOUS_MODE_DEFAULT = true;
        /**
         * this stands for a string value
         */
        public static final String ANONYMOUS_MODE_PATH = "anonymous_mode_path";
        public static final String ANONYMOUS_MODE_PATH_DEFAULT = StorageUtil.getDefaultAnonymousPath();
        /** True only after the user deliberately confirms an anonymous home directory. */
        public static final String ANONYMOUS_MODE_PATH_USER_SELECTED = "anonymous_mode_path_user_selected";
        /**
         * this stands for a boolean value
         */
        public static final String ANONYMOUS_MODE_WRITABLE = "anonymous_mode_writable";
        public static final boolean ANONYMOUS_MODE_WRITABLE_DEFAULT = false;

        /**
         * CPU 与 Wi-Fi 锁没有自己的开关：默认开启，实验室里只暴露「关闭性能优化」这一个
         * 反向开关 —— 两个锁要么一起开、要么一起关，单独关一个没有意义。这里因此不再保留
         * 历史的 wake_lock / wifi_lock 两个 key（全工程无读写点，容易被误当作可调项）。
         */
        public static final String PERFORMANCE_OPTIMIZATION_DISABLED = "performance_optimization_disabled";
        public static final boolean PERFORMANCE_OPTIMIZATION_DISABLED_DEFAULT = false;
        public static final String FTPS_ENABLED = "ftps_enabled";
        /**
         * 实验室开关：FTPS 握手优先协商 ChaCha20-Poly1305（默认关，走 AES-GCM 优先）。
         * 收益只出现在没有 ARMv8 AES 指令集的中低端手机上（软件 AES-GCM ≈ 10–20 MB/s，
         * 软件 ChaCha20 ≈ 100+ MB/s）；有 AES 指令的手机开了反而略慢，所以不默认开。
         * 套件列表在服务启动构建 FtpsConfiguration 时确定，改动后下次启动生效。
         */
        public static final String FTPS_CHACHA20_PREFERRED = "ftps_chacha20_preferred";
        public static final boolean FTPS_CHACHA20_PREFERRED_DEFAULT = false;
        /**
         * 允许从 IPv6 访问：开启后 FTP 监听地址从 0.0.0.0 改为 ::（IPv6 双栈通配），
         * IP 列表才会展示 IPv6 地址（服务不监听 v6 时那些地址全部连不上，见
         * NetworkEnvironmentUtil.getLocalIpAddresses 的注释）。关闭（默认）时列表只显示 IPv4。
         * 绑定 :: 在部分 ROM 上会失败，失败时自动回退 0.0.0.0（见 FtpService.startFTPService）。
         */
        public static final String IPV6_ENABLED = "ipv6_enabled";
        public static final boolean IPV6_ENABLED_DEFAULT = false;
        /**
         * 开启主动模式（PORT）：默认关闭、只允许被动模式。主动模式会让服务端按客户端
         * 指定的地址发起数据连接。服务端会强制校验 PORT/EPRT 地址必须等于控制连接的
         * 对端地址，避免 FTP bounce；改动需服务完全停止。
         */
        public static final String ACTIVE_MODE_ENABLED = "active_mode_enabled";
        public static final boolean ACTIVE_MODE_ENABLED_DEFAULT = false;
        public static final String MDNS_ENABLED = "mdns_enabled";
        public static final boolean MDNS_ENABLED_DEFAULT = true;
        /** Allows other apps that declare CONTROL_FTP permission to send start/stop broadcasts. */
        public static final String EXTERNAL_CONTROL_ENABLED = "external_control_enabled";
        public static final boolean EXTERNAL_CONTROL_ENABLED_DEFAULT = false;
        public static final String FTP_ATTACK_LOCKDOWN = "ftp_attack_lockdown";
        /** Applies to complete-storage virtual roots. Kept on by default to avoid exposing app data. */
        public static final String RESTRICT_ANDROID_DATA = "restrict_android_data";
        public static final boolean RESTRICT_ANDROID_DATA_DEFAULT = true;
        /** true 使用新的 LocalFileSystem；关闭后回退到旧文件系统栈。 */
        public static final String NEW_FILESYSTEM_ENABLED = "new_filesystem_enabled";
        public static final boolean NEW_FILESYSTEM_ENABLED_DEFAULT = true;
        /** true 使用新的 CommandRouter；关闭后回退到旧命令调度栈。 */
        public static final String NEW_COMMAND_ROUTER_ENABLED = "new_command_router_enabled";
        public static final boolean NEW_COMMAND_ROUTER_ENABLED_DEFAULT = true;
        /** false uses the new data/TLS stack; true is the legacy rollback path. */
        public static final String USE_LEGACY_DATA_CONNECTION = "use_legacy_data_connection";
        public static final boolean USE_LEGACY_DATA_CONNECTION_DEFAULT = false;
        /** IP whitelist: when enabled, only addresses in the whitelist may connect to the FTP server. */
        public static final String IP_WHITELIST_ENABLED = "ip_whitelist_enabled";
        public static final boolean IP_WHITELIST_ENABLED_DEFAULT = false;
        /** Stored as a StringSet of "value|note|id" strings; the id preserves distinct empty rows. */
        public static final String IP_WHITELIST_ENTRIES = "ip_whitelist_entries";
        /** One-time bootstrap marker for fresh installs; upgrades must not seed whitelist entries. */
        public static final String IP_WHITELIST_INITIALIZED = "ip_whitelist_initialized";
        /** Default loopback entry so local access keeps working when the whitelist is first enabled. */
        public static final String[] IP_WHITELIST_DEFAULT_ENTRIES = {"127.0.0.1|Local device"};
        /** Desk clock: shows the WebView clock after the app idles on the FTP page. */
        public static final String DESKTOP_CLOCK_ENABLED = "desktop_clock_enabled";
        public static final boolean DESKTOP_CLOCK_ENABLED_DEFAULT = false;
        /** Idle time on the FTP page before the desk clock is raised. */
        public static final long DESKTOP_CLOCK_IDLE_MS = 60_000L;
        /** Whether the user has ever enabled the accessibility keep-alive service. */
        public static final String ACCESSIBILITY_KEEPALIVE_EXPECTED = "accessibility_keepalive_expected";
        /** Uptime millis of the last accessibility keep-alive heartbeat. */
        public static final String ACCESSIBILITY_KEEPALIVE_LAST_BEAT = "accessibility_keepalive_last_beat";
        /** Set when the user dismisses the keep-alive warning; cleared once a heartbeat returns. */
        public static final String ACCESSIBILITY_KEEPALIVE_MUTED = "accessibility_keepalive_muted";
        /** Shizuku user-service watchdog. It is started only after FTP is running. */
        public static final String SHIZUKU_WATCHDOG = "shizuku_watchdog";
        public static final boolean SHIZUKU_WATCHDOG_DEFAULT = false;
        /** Whether the user has added this app to the Doze whitelist through Shizuku. */
        public static final String SHIZUKU_DOZE_WHITELISTED = "shizuku_doze_whitelisted";
        public static final boolean SHIZUKU_DOZE_WHITELISTED_DEFAULT = false;
        /**
         * this stands for a int value
         */
        public static final String PORT_NUMBER = "port_number";
        public static final int PORT_NUMBER_DEFAULT = 5555;
        /**
         * this stands for a string value
        */
        public static final String CHARSET_TYPE = "charset_type";
        /** Adaptive mode defaults to GBK but permits a client to negotiate UTF-8. */
        public static final String CHARSET_TYPE_DEFAULT = "AUTO";
        /**
         * 匿名登录上限。注意匿名登录要连过两道检查：先查本值，再查 [MAX_LOGIN_NUM]，
         * 实际生效的是两者中较小的那个。默认 4 —— 匿名没有任何身份约束，给太高的额度
         * 等于把服务敞开，而一台手机同时服务的设备通常不超过两三台。
         */
        public static final String MAX_ANONYMOUS_NUM = "max_anonymous_logins";
        public static final int MAX_ANONYMOUS_NUM_DEFAULT = 4;
        /**
         * 全局登录上限（所有账号加在一起的同时在线数）。UI 提供四档预设，
         * 见 MainFragment.showMaxLoginDialog() 的 values 数组：严格 12 / 正常 20 / 高 30 / 极高 40。
         * 默认取"高"档（30）——FTP 客户端常开多条控制连接，额度低了会出现莫名的登录排队。
         */
        public static final String MAX_LOGIN_NUM = "max_logins";
        public static final int MAX_LOGIN_NUM_STRICT = 12;
        public static final int MAX_LOGIN_NUM_NORMAL = 20;
        public static final int MAX_LOGIN_NUM_HIGH = 30;
        public static final int MAX_LOGIN_NUM_EXTREME = 40;
        public static final int MAX_LOGIN_NUM_DEFAULT = MAX_LOGIN_NUM_HIGH;
        /** Maximum FTP command/transfer workers. Applied only to a newly created service. */
        /** Control-connection idle timeout in seconds. Only 0/300/900 are supported. */
        public static final String FTP_IDLE_TIMEOUT = "ftp_idle_timeout";
        public static final int FTP_IDLE_TIMEOUT_DEFAULT = 0;
        public static int normalizeIdleTimeout(int seconds) {
            return seconds == 300 || seconds == 900 ? seconds : FTP_IDLE_TIMEOUT_DEFAULT;
        }
        public static final String FTP_WORKER_THREADS = "ftp_worker_threads";
        public static final int FTP_WORKER_THREADS_DEFAULT = 14;
        /**
         * 可调范围 8–20，默认 14。
         *
         * 下限 8：数据传输是同步跑在这些 worker 上的，一条慢传输就占满一个 worker；
         * 池子太小（例如 4）时，一个多线程客户端或一次压力测试就能把 worker 全部占死，
         * 连新登录的 USER/PASS 都排不到队，表现为"连不上 / 登录超时"。
         * 上限 20：再多也不会更快——传输并发另有 [FTPConsts.FTP_MAX_CONCURRENT_TRANSFERS] 信号量
         * 限制，而且每条传输都要开一个 RandomAccessFile 加缓冲区，线程太多只会吃文件描述符和内存。
         *
         * 设置项本身只在读取处按上面范围钳制 UI 与生效值；旧版本升级带来的越界值不再额外纠正。
         */
        public static final int FTP_WORKER_THREADS_MIN = 8;
        public static final int FTP_WORKER_THREADS_MAX = 20;
        /**
         * 并发传输槽数量（同时能传几个文件）。只在服务**完全停止**时可改，下一次启动服务时生效。
         *
         * 下限 4：低于 4 时多设备访问会明显排队（一台机器列目录时另一台完全传不动），
         * 而省下的那点 fd/内存并不值得。
         * 上限 10：再多也换不来吞吐——每条传输都占一个传输池线程，槽数接近池容量时新登录会
         * 排不到队。真正的约束是 [FTPConsts.FTP_TRANSFER_SLOTS_WORKER_RESERVE]，不是这个上限。
         *
         * 在 TransferCommandGuard 里打开文件**之前**统一扣减，因此对**所有账户生效**
         * （不区分是否开启完整存储）；文件移动（服务端本地拷贝）也占一个槽。
         * 目录列举（LIST/NLST/MLSD）走 [TransferSlots] 里的另一把独立闸门，不占这里的槽。
         */
        public static final String FTP_TRANSFER_SLOTS = "ftp_transfer_slots";
        public static final int FTP_TRANSFER_SLOTS_DEFAULT = 6;
        public static final int FTP_TRANSFER_SLOTS_MIN = 4;
        public static final int FTP_TRANSFER_SLOTS_MAX = 10;
        /**
         * 单次 read/write 的传输块大小（字节）。提供 32 KB / 64 KB / 256 KB 三档，
         * 在 FTP 主界面切换；改完对**下一次**传输生效，正在飞的传输不受影响，
         * 所以不需要停止服务。
         *
         * 64 KB 是默认甜点：略高于千兆有线与常见 WiFi 的在途数据量，再往上不会更快，
         * 反而让 ABOR/QUIT 的响应排队更久，并在 ART 上多占一份堆。
         * 256 KB 给大文件/高延迟链路：系统调用次数降到四分之一，代价是每路传输多占 192 KB 堆。
         * 实际值由 TransferBuffer 持有，IODataConnection.transfer() 每次开传前读一次。
         */
        public static final String FTP_TRANSFER_BUFFER = "ftp_transfer_buffer";
        public static final int FTP_TRANSFER_BUFFER_TINY = 32 * 1024;
        public static final int FTP_TRANSFER_BUFFER_SMALL = 64 * 1024;
        public static final int FTP_TRANSFER_BUFFER_LARGE = 256 * 1024;
        public static final int FTP_TRANSFER_BUFFER_DEFAULT = FTP_TRANSFER_BUFFER_SMALL;

        /** TCP 数据连接 SO_RCVBUF/SO_SNDBUF 申请值；0 保持系统默认及自动调节。 */
        public static final String FTP_SOCKET_BUFFER_REQUEST = "ftp_socket_buffer_request";
        public static final int FTP_SOCKET_BUFFER_SYSTEM_DEFAULT = 0;
        public static final int FTP_SOCKET_BUFFER_4M = 4 * 1024 * 1024;
        public static final int FTP_SOCKET_BUFFER_8M = 8 * 1024 * 1024;
        public static final int FTP_SOCKET_BUFFER_16M = 16 * 1024 * 1024;
        /**
         * int value
         */
        public static final String LANGUAGE_SETTING = "language_setting";
        public static final int LANGUAGE_FOLLOW_SYSTEM = 0;
        public static final int LANGUAGE_SIMPLIFIED_CHINESE = 1;
        public static final int LANGUAGE_ENGLISH = 2;
        public static final int LANGUAGE_SETTING_DEFAULT = LANGUAGE_FOLLOW_SYSTEM;
        /** 一次性迁移标记：旧 LANGUAGE_SETTING 已迁到 AppCompat 应用内语言机制。 */
        public static final String LANGUAGE_MIGRATED = "language_migrated_to_appcompat";
        /**
         * boolean value
         */
        public static final String START_AFTER_BOOT = "start_after_boot";
        public static final boolean START_AFTER_BOOT_DEFAULT = false;
        /**
         * 用户最后一次明确的服务意图：true=希望 FTP 在跑，false=已手动停止。
         *
         * <p>只靠 FtpService 的静态 lifecycleState 判断"该不该自愈"是不行的——进程被系统杀掉后
         * 静态变量归零，新进程里看到的永远是 STOPPED，无从区分"用户自己关的"和"被系统杀的"。
         * 这个标志由 FtpService 在启动请求/停止时写入，自愈 Worker 只认它。</p>
         */
        public static final String FTP_EXPECTED_RUNNING = "ftp_expected_running";
        public static final boolean FTP_EXPECTED_RUNNING_DEFAULT = false;
        public static final String LOG_AUTO_SAVE_HOURS = "log_auto_save_hours";
        public static final int LOG_AUTO_SAVE_HOURS_DEFAULT = 0;
        public static final String LOG_ENABLED = "log_enabled";
        public static final boolean LOG_ENABLED_DEFAULT = false;
        public static final String LOG_LEVEL = "log_level";
        public static final String LOG_LEVEL_WARNING = "warning";
        public static final String LOG_LEVEL_INFO = "info";
        public static final String LOG_LEVEL_DEBUG = "debug";
        public static final String LOG_LEVEL_DEFAULT = LOG_LEVEL_WARNING;
        public static final String LOG_MAX_LINES = "log_max_lines";
        public static final int LOG_MAX_LINES_DEFAULT = 500;
        /**
         * 持久化记录 ResourcePanel 采用的 CPU 采样方法。
         * 空字符串表示未探测；"cpuacct" / "procstat" 表示已知有效方法；
         * "none" 表示两路都不可用，应用重启后也不再尝试读取 CPU。
         */
        public static final String CPU_SAMPLER_METHOD = "cpu_sampler_method";
        public static final String CPU_SAMPLER_METHOD_DEFAULT = "";
        public static final String CPU_SAMPLER_METHOD_CPUACCT = "cpuacct";
        public static final String CPU_SAMPLER_METHOD_PROCSTAT = "procstat";
        public static final String CPU_SAMPLER_METHOD_NONE = "none";
        /** Three consecutive battery-temperature read failures disable further reads on this device. */
        public static final String BATTERY_TEMPERATURE_UNAVAILABLE = "battery_temperature_unavailable";
        public static final boolean BATTERY_TEMPERATURE_UNAVAILABLE_DEFAULT = false;
        public static final String BATTERY_TEMPERATURE_FAILURES = "battery_temperature_failures";
    }

    public static class Charset {
        public static final String CHARSET_AUTO = "AUTO";
        public static final String CHAR_UTF = "UTF-8";
        public static final String CHAR_GBK = "GBK";
    }

}
