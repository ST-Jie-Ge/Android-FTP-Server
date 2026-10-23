package com.github.stjiege.ftpshare.filesystem;

import java.io.File;

/**
 * 一个路径的"展示属性"，理想情况下一次系统调用全部取回。
 *
 * <p>{@code MultiRootFtpFile} 原来的每个 getter 都各自去问一次 {@code File}：
 * {@code isDirectory} / {@code isFile} / {@code getSize} / {@code getLastModified} /
 * {@code getLinkCount} 彼此互不知情，于是列表里每个条目都要把同一份 stat 结果反复重取好几遍
 * （实测 LIST 每条目约 10 次系统调用，而 vsftpd 是 1 次）。这里把它们合成一次查询。</p>
 *
 * <h3>为什么必须能失败</h3>
 * 快路径依赖 {@code android.system.Os}（API 21+，已核实是公开 API）。但：
 * <ul>
 *   <li><b>JVM 单元测试里它是个桩</b>——android.jar 里的 stub 方法一调用就抛
 *       {@code RuntimeException("Stub!")}，而本项目没有 Robolectric。直接调用会让现有全部
 *       单元测试崩掉。</li>
 *   <li><b>个别 ROM 也可能不支持</b>。</li>
 * </ul>
 * 因此这里做<b>一次性探测</b>：首次使用试探一次，失败就把快路径永久关掉，之后所有调用走
 * {@code null} 返回，调用方自然回退到原来的 {@code File} 方法。探测只做一次，不在热路径上。
 *
 * <h3>为什么同时要 symlink 位</h3>
 * {@code File.isDirectory()} 用的是 stat（<b>跟随</b>符号链接）。如果这里改用 lstat 的
 * {@code S_ISDIR}，指向目录的软链接会从 {@code d} 变成 {@code -}，LIST 输出就变了。所以：
 * <ul>
 *   <li>先 lstat，拿 {@code symlink} 位（判逃逸要用，且 lstat 才看得到）；</li>
 *   <li>非软链接时 lstat 与 stat 结果相同，直接用，<b>零额外调用</b>；</li>
 *   <li>只有软链接才补一次 stat 去跟随——软链接是少数，且这时候语义必须跟 Java 一致。</li>
 * </ul>
 */
final class FileStat {

    /** 一个路径的一份快照。{@code exists=false} 表示 stat 失败（含指向不存在的软链接）。 */
    static final class Info {
        final boolean exists;
        final boolean directory;
        final boolean regular;
        final boolean symlink;
        final long size;
        final long modifiedMillis;

        Info(boolean exists, boolean directory, boolean regular, boolean symlink,
             long size, long modifiedMillis) {
            this.exists = exists;
            this.directory = directory;
            this.regular = regular;
            this.symlink = symlink;
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }
    }

    /**
     * 取属性。返回 {@code null} 表示"这条路走不通"，调用方必须回退到原来的 {@code File} 方法，
     * 不能当成"文件不存在"——那是完全不同的含义。
     */
    interface Source {
        Info probe(File file);
    }

    private static volatile Source source;
    private static volatile boolean unavailable;

    private FileStat() {
    }

    /**
     * 返回 {@code null} 时调用方回退到 {@code File} 方法。这个返回值<b>绝不能</b>被解释成
     * "文件不存在"：不存在用 {@link Info#exists} 表示。
     */
    static Info of(File file) {
        if (file == null) return null;
        Source active = source;
        if (active == null && !unavailable) {
            active = detect();
        }
        if (active == null) return null;
        return active.probe(file);
    }

    private static synchronized Source detect() {
        if (source != null || unavailable) return source;
        Source candidate = null;
        try {
            // 试探一次真实调用，而不是只看类在不在：桩方法的类是可以加载成功的，
            // 只有真正调用才会暴露。
            candidate = new AndroidSource();
            candidate.probe(new File("."));
        } catch (Throwable unsupported) {
            candidate = null;
        }
        if (candidate == null) {
            unavailable = true;
            return null;
        }
        source = candidate;
        return candidate;
    }

    /** 测试用：注入一个假的取数实现，以便在 JVM 上验证"每条目只查一次"。 */
    static synchronized void setSourceForTest(Source injected) {
        source = injected;
        unavailable = injected == null;
    }

    /**
     * Android 实现。刻意放在独立的嵌套类里：只有探测成功后才会被加载，因此 JVM 单元测试上
     * 永远不会去碰 {@code android.system.*}。
     */
    private static final class AndroidSource implements Source {

        // Linux/Android 的 stat 模式位，ABI 固定。这里写死字面量而不是引用
        // android.system.OsConstants，是为了少一处对 stub 类的静态初始化依赖。
        private static final int S_IFMT = 0170000;
        private static final int S_IFREG = 0100000;
        private static final int S_IFDIR = 0040000;
        private static final int S_IFLNK = 0120000;

        @Override public Info probe(File file) {
            try {
                android.system.StructStat linked = android.system.Os.lstat(file.getPath());
                boolean symlink = (linked.st_mode & S_IFMT) == S_IFLNK;
                // 非软链接时 lstat 与 stat 结果相同，直接用，省掉第二次调用。
                android.system.StructStat resolved = symlink ? follow(file.getPath()) : linked;
                if (resolved == null) {
                    // 指向不存在的目标：Java 的 File.exists() 同样是 false。
                    return new Info(false, false, false, true, 0L, 0L);
                }
                int type = resolved.st_mode & S_IFMT;
                return new Info(true, type == S_IFDIR, type == S_IFREG, symlink,
                        resolved.st_size, millis(resolved, file));
            } catch (Throwable error) {
                // 包括 JVM 上的 stub 异常、ROM 不支持、以及真实的 ErrnoException。
                // 一律交给调用方回退，绝不在这里编造结果。
                return null;
            }
        }

        /** 跟随符号链接。目标不存在时返回 null（不是异常语义）。 */
        private static android.system.StructStat follow(String path) {
            try {
                return android.system.Os.stat(path);
            } catch (android.system.ErrnoException gone) {
                return null;
            }
        }

        /**
         * 毫秒时间戳。
         *
         * <p>{@code st_mtime} 只有<b>秒</b>，直接用会让 MDTM / MLSD 的 Modify 丢掉亚秒部分，
         * 属于改可见输出。纳秒精度的 {@code st_mtim}（以及整个 {@code StructTimespec}）是
         * <b>API 27 才有的</b>——minSdk 是 23，在 23-26 上这个字段可能根本不存在，访问会直接
         * {@code NoSuchFieldError}，光判 null 救不了。所以按版本分流：</p>
         * <ul>
         *   <li>API 27+：从这份 stat 结果里直接取纳秒精度，<b>零额外调用</b>；</li>
         *   <li>API 23-26：回退一次真实的 {@code File.lastModified()}。代价是这类设备上每条目
         *       多一次 stat（1 次 lstat + 1 次 lastModified），仍然远好于原来的 ~10 次。</li>
         * </ul>
         */
        private static long millis(android.system.StructStat stat, File file) {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                android.system.StructTimespec precise = stat.st_mtim;
                if (precise != null) {
                    return precise.tv_sec * 1000L + precise.tv_nsec / 1_000_000L;
                }
            }
            return file.lastModified();
        }
    }
}
