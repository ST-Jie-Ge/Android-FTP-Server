package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.usermanager.impl.WriteRequest;

import com.github.stjiege.ftpshare.utils.LogManager;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** File implementation used by MultiRootFileSystemView. It never maps outside its volume root. */
final class MultiRootFtpFile implements FtpFile {
    private static final int STREAM_BUFFER_SIZE = 32 * 1024;
    private final String virtualPath;
    private final File file;
    private final User user;
    private final LinkedHashMap<String, File> roots;
    private final LinkedHashMap<String, String> rootCanonicalPaths;
    private final boolean virtualRoot;
    private final boolean restrictAndroidData;
    /**
     * 只有 {@code listFiles()} 产出的条目为 true。属性快照<b>只在这类实例上启用</b>。
     *
     * <p>原因：快照是实例级的、没有超时，它的正确性依赖"实例活不过当前这一步"。列举出来的条目
     * 天然满足——它们只在一次列举里被使用。但 {@code getFile()} 返回的实例会被<b>跨命令</b>持有：
     * RNFR 把它存进会话，RNTO 才取出来用（RNTO 会读 {@code doesExist()}）。那种情况下一个没有
     * 超时的快照就是陈旧数据。宁可放弃这条路上的优化，也不把正确性押在"凑巧没人跨命令读"上。</p>
     */
    private final boolean listingEntry;

    /**
     * 一次 lstat 的结果，本实例内复用。null 表示"还没取"或"这条路走不通"——后者时下面的
     * 每个 getter 都会自动回退到原来的 {@code File} 方法，语义与改动前完全一致。
     */
    private volatile FileStat.Info stat;
    /** 平台不支持 {@code FileStat} 时置真，之后不再重试，避免每个条目都白试一次。 */
    private volatile boolean statUnavailable;
    /** {@code getName()} 结果。列表排序要 O(n log n) 次比较，每次都 substring 太浪费。 */
    private volatile String name;

    private MultiRootFtpFile(String virtualPath, File file, User user,
                             LinkedHashMap<String, File> roots,
                             LinkedHashMap<String, String> rootCanonicalPaths, boolean virtualRoot,
                             boolean restrictAndroidData, boolean listingEntry) {
        this.virtualPath = virtualPath;
        this.file = file;
        this.user = user;
        this.roots = roots;
        this.rootCanonicalPaths = rootCanonicalPaths;
        this.virtualRoot = virtualRoot;
        this.restrictAndroidData = restrictAndroidData;
        this.listingEntry = listingEntry;
    }

    static MultiRootFtpFile virtualRoot(User user, LinkedHashMap<String, File> roots,
                                        LinkedHashMap<String, String> rootCanonicalPaths, boolean restrictAndroidData) {
        return new MultiRootFtpFile("/", null, user, roots, rootCanonicalPaths, true, restrictAndroidData, false);
    }
    static MultiRootFtpFile physical(String path, File file, User user, LinkedHashMap<String, File> roots,
                                     LinkedHashMap<String, String> rootCanonicalPaths, boolean restrictAndroidData) {
        return new MultiRootFtpFile(path, file, user, roots, rootCanonicalPaths, false, restrictAndroidData, false);
    }
    static MultiRootFtpFile missing(String path, User user, LinkedHashMap<String, File> roots,
                                    LinkedHashMap<String, String> rootCanonicalPaths, boolean restrictAndroidData) {
        return new MultiRootFtpFile(path, null, user, roots, rootCanonicalPaths, false, restrictAndroidData, false);
    }
    /** 列举条目：唯一允许缓存属性快照的一类实例，见 {@link #listingEntry}。 */
    static MultiRootFtpFile listingEntry(String path, File file, User user, LinkedHashMap<String, File> roots,
                                         LinkedHashMap<String, String> rootCanonicalPaths, boolean restrictAndroidData) {
        return new MultiRootFtpFile(path, file, user, roots, rootCanonicalPaths, false, restrictAndroidData, true);
    }

    @Override public String getAbsolutePath() { return virtualPath; }
    @Override public String getName() {
        String cached = name;
        if (cached == null) {
            cached = "/".equals(virtualPath) ? "/" : virtualPath.substring(virtualPath.lastIndexOf('/') + 1);
            name = cached;
        }
        return cached;
    }
    @Override public boolean isHidden() { return !virtualRoot && file != null && file.isHidden(); }
    @Override public boolean isDirectory() {
        if (virtualRoot) return true;
        FileStat.Info info = stat();
        return info != null ? info.directory : file != null && file.isDirectory();
    }
    @Override public boolean isFile() {
        if (virtualRoot) return false;
        FileStat.Info info = stat();
        return info != null ? info.regular : file != null && file.isFile();
    }
    @Override public boolean doesExist() {
        if (virtualRoot) return true;
        FileStat.Info info = stat();
        return info != null ? info.exists : file != null && file.exists();
    }
    // isReadable/isWritable 刻意不走缓存：它们是 access(2) 而不是 stat，sdcardfs/FUSE 会伪造
    // 权限位，不能拿 st_mode 推；而且每个条目本来就只问一次，缓存也没有收益。
    @Override public boolean isReadable() { return virtualRoot || (file != null && file.canRead()); }
    @Override public String getOwnerName() { return "user"; }
    @Override public String getGroupName() { return "group"; }
    /**
     * 保持原来的 3/1 假值，不用 {@code st_nlink}：那会改变 LIST 输出的第二个字段，属于改动
     * 可见行为。这里的收益只在于它内部那次 {@code isDirectory()} 现在命中缓存，不再多问一次。
     */
    @Override public int getLinkCount() { return isDirectory() ? 3 : 1; }
    @Override public long getLastModified() {
        FileStat.Info info = stat();
        return info != null ? info.modifiedMillis : file == null ? 0L : file.lastModified();
    }

    /**
     * 本实例的属性快照，只查一次。
     *
     * <p>返回 null 只表示"这条路走不通"（JVM 单元测试的桩、或 ROM 不支持），调用方一律回退到
     * 原来的 {@code File} 方法。它<b>不表示文件不存在</b>——不存在用 {@code Info.exists}。</p>
     */
    private FileStat.Info stat() {
        if (!listingEntry || virtualRoot || file == null) return null;
        FileStat.Info cached = stat;
        if (cached != null || statUnavailable) return cached;
        FileStat.Info fresh = FileStat.of(file);
        if (fresh == null) {
            statUnavailable = true;
            return null;
        }
        stat = fresh;
        return fresh;
    }
    @Override public boolean setLastModified(long time) {
        if (file == null || virtualRoot || !isWritable()) return false;
        try (FileOperationLocks lease = FileOperationLocks.acquire(file)) {
            return lease != null && file.setLastModified(time);
        } catch (IOException e) { return false; }
    }
    @Override public long getSize() {
        FileStat.Info info = stat();
        return info != null ? info.size : file == null ? 0L : file.length();
    }
    @Override public Object getPhysicalFile() { return file; }

    @Override
    public boolean isWritable() {
        if (virtualRoot || file == null || user.authorize(new WriteRequest(virtualPath)) == null) return false;
        return !file.exists() || file.canWrite();
    }

    @Override
    public boolean isRemovable() {
        if (virtualRoot || file == null || virtualPath.indexOf('/', 1) < 0
                || user.authorize(new WriteRequest(virtualPath)) == null) return false;
        File parent = file.getAbsoluteFile().getParentFile();
        return parent != null && parent.canWrite();
    }

    @Override public boolean delete() {
        if (!isRemovable()) return false;
        try (FileOperationLocks lease = FileOperationLocks.acquire(file)) {
            return lease != null && file.delete();
        } catch (IOException e) { return false; }
    }

    /**
     * 移动一律交给 {@link MoveSupport}：匿名与具名、完整存储与非完整存储都走同一条路径。
     *
     * <p>这里刻意<b>不对 destination 做 instanceof</b>。上游 {@code NativeFtpFile.move()} 首句
     * 是 {@code ((NativeFtpFile) dest).file}，而 {@link SafeUploadFileSystemFactory} 会把视图
     * 返回的每个 FtpFile 换成 JDK 动态代理，强转直接抛 ClassCastException；本类原先的
     * {@code !(destination instanceof MultiRootFtpFile)} 同样恒为 false（直接返回 553）。
     * 表现就是"重命名全挂、上传正常"。改为只用接口方法（{@code getPhysicalFile()}）后，
     * 对代理天然免疫。</p>
     */
    @Override
    public boolean move(FtpFile destination) {
        return MoveSupport.move(this, destination);
    }

    @Override public boolean mkdir() {
        if (virtualRoot || file == null || !isWritable()) return false;
        try (FileOperationLocks lease = FileOperationLocks.acquire(file)) {
            return lease != null && file.mkdir();
        } catch (IOException e) { return false; }
    }

    @Override
    public List<? extends FtpFile> listFiles() {
        if (virtualRoot) {
            List<FtpFile> result = new ArrayList<>();
            for (String name : roots.keySet()) {
                result.add(physical("/" + name, roots.get(name), user, roots, rootCanonicalPaths, restrictAndroidData));
            }
            return Collections.unmodifiableList(result);
        }
        if (file == null || !file.isDirectory()) return null;
        File[] children = file.listFiles();
        if (children == null) {
            // Permission-restricted directory (common on Android 11+ without All Files
            // Access) or an I/O error. Surface it: DirectoryLister turns this null into a
            // client-visible 4xx instead of a directory that looks empty.
            warnListingIssueThrottled(virtualPath,
                    "目录不可读或已断开，无法列出内容（检查存储权限/挂载状态）");
            return null;
        }
        List<FtpFile> result = new ArrayList<>();
        for (File child : children) {
            String childVirtualPath = childPath(child.getName());
            if (isAndroidDataBlocked(childVirtualPath)) continue;
            MultiRootFtpFile entry =
                    listingEntry(childVirtualPath, child, user, roots, rootCanonicalPaths, restrictAndroidData);
            // 先建条目再判逃逸：判逃逸要查一次 lstat，而这次查询的结果正是该条目的属性缓存
            // （见 stat()），一次调用同时办成"防逃逸"和"取属性"两件事。
            if (entry.insideCurrentRoot()) {
                result.add(entry);
            }
        }
        Collections.sort(result, new Comparator<FtpFile>() {
            @Override public int compare(FtpFile left, FtpFile right) { return left.getName().compareTo(right.getName()); }
        });
        return Collections.unmodifiableList(result);
    }

    @Override
    public OutputStream createOutputStream(long offset) throws IOException {
        if (!isWritable()) throw new IOException("No write permission : " + getName());
        if (offset < 0L) throw new IOException("Negative output offset");
        // TransferCommandGuard already owns the slot before this destructive open.
        final FileOperationLocks lease = FileOperationLocks.acquire(file);
        if (lease == null) throw new IOException("File busy: " + getName());
        final RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "rw");
        } catch (IOException | RuntimeException error) {
            lease.close();
            throw error;
        }
        final long fileOffset = offset;
        try {
            // 截断推迟到第一次真正落盘：开放出流时立刻 setLength(offset) 的话，客户端只要
            // 走完 STOR 的前置流程（数据连接已建立）却一个字节都没发就断开——ABOR 抢在
            // 第一个包之前、连接被网络掐断——原文件就已经被清成 0 字节了。
            // 真开始写再截断，语义不变（offset 之后的旧内容仍会被丢掉，APPE/REST 续传也
            // 一样从 offset 处接上），但"什么都没传"不再等于"删掉原文件"。
            raf.seek(offset);
            return new BufferedOutputStream(new OutputStream() {
                private boolean closed;
                private boolean truncated;

                private void ensureTruncated() throws IOException {
                    if (truncated) return;
                    truncated = true;
                    raf.setLength(fileOffset);
                    raf.seek(fileOffset);
                }

                @Override public void write(int value) throws IOException {
                    ensureTruncated();
                    raf.write(value);
                }
                @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                    ensureTruncated();
                    raf.write(buffer, offset, length);
                }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try { raf.close(); } finally { lease.close(); }
                }
            }, STREAM_BUFFER_SIZE);
        } catch (IOException | RuntimeException error) {
            closeQuietly(raf);
            lease.close();
            throw error;
        }
    }

    @Override
    public InputStream createInputStream(long offset) throws IOException {
        if (!isReadable() || file == null) throw new IOException("No read permission : " + getName());
        if (offset < 0L) throw new IOException("Negative input offset");
        // TransferCommandGuard already owns the slot.
        final RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (IOException | RuntimeException error) {
            throw error;
        }
        try {
            raf.seek(offset);
            return new BufferedInputStream(new InputStream() {
                private boolean closed;

                @Override public int read() throws IOException { return raf.read(); }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    return raf.read(buffer, offset, length);
                }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    raf.close();
                }
            }, STREAM_BUFFER_SIZE);
        } catch (IOException | RuntimeException error) {
            closeQuietly(raf);
            throw error;
        }
    }

    private static void closeQuietly(RandomAccessFile file) {
        try {
            file.close();
        } catch (IOException ignored) {
            // Best effort after an unsuccessful stream setup; do not hide the original I/O error.
        }
    }

    private String childPath(String name) { return "/".equals(virtualPath) ? "/" + name : virtualPath + "/" + name; }

    private boolean isAndroidDataBlocked(String path) {
        if (!restrictAndroidData) return false;
        String[] parts = path.substring(1).split("/");
        return parts.length >= 3 && ("ROM".equals(parts[0]) || "SDcard".equals(parts[0]))
                && "Android".equalsIgnoreCase(parts[1]) && "data".equalsIgnoreCase(parts[2]);
    }

    /**
     * 防符号链接逃逸。根路径在视图创建时就解析过了，不必每个子项再解析一遍。
     *
     * <p><b>快速路径的依据</b>：本实例的 {@code file} 本身就是 canonical
     * （{@code MultiRootFileSystemView} 传进来的就是 canonicalFile），而 {@code listFiles()}
     * 给出的子项只是「parent + "/" + name」——文件名不允许含分隔符。所以这个子项的路径在结构上
     * 不可能指到根外，<b>除非它自己就是符号链接</b>。因此只有符号链接才需要解析真实路径。</p>
     *
     * <p>拿不到 lstat（JVM 单元测试的桩、ROM 不支持、或该文件 stat 失败）时，<b>一律退回原来的
     * canonicalize 检查</b>，绝不因为"查不到就当作不是链接"而放行——那是把安全边界赌在一个
     * 失败的查询上。</p>
     *
     * <p>硬链接与 bind mount 的情况：旧实现同样检测不到（realpath 不解析硬链接），新旧等价。</p>
     */
    private boolean insideCurrentRoot() {
        int slash = virtualPath.indexOf('/', 1);
        String rootName = slash < 0 ? virtualPath.substring(1) : virtualPath.substring(1, slash);
        String rootPath = rootCanonicalPaths.get(rootName);
        if (rootPath == null) return false;

        // 免检条件刻意收紧：必须是 lstat 明确给出"存在、且是普通文件或目录"的条目。
        // 只判"不是符号链接"是不够的——万一某个 ROM 的 st_mode 是损坏值（例如全 0），
        // 它既不是链接也不是常规类型，放行就等于把安全边界赌在一条可能异常的查询结果上。
        // 非常规类型（socket/fifo/设备节点/无法判定）一律退回下面的真实路径解析。
        FileStat.Info info = stat();
        if (info != null && info.exists && !info.symlink && (info.directory || info.regular)) {
            return true;
        }
        try {
            String childPath = file.getCanonicalPath();
            return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
        } catch (IOException error) {
            // Canonicalisation fails on some FUSE/sdcardfs entries (very long names, special
            // characters, a half-ejected volume). Silently denying here made existing files
            // invisible in client listings with no trace. The child came from
            // File.listFiles() of a directory that is itself already inside the root, so a
            // lexical absolute-path check cannot be abused to escape; use it as a fallback.
            warnListingIssueThrottled(virtualPath,
                    "文件名解析失败，已按字面路径列出（" + error.getClass().getSimpleName() + "）");
            String childPath = file.getAbsolutePath();
            return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
        }
    }

    /** Rate-limits listing warnings: one per distinct path per minute keeps logs usable. */
    private static volatile String lastListWarnPath;
    private static volatile long lastListWarnTimeMs;

    private static void warnListingIssueThrottled(String path, String message) {
        long now = System.currentTimeMillis();
        if (path.equals(lastListWarnPath) && now - lastListWarnTimeMs < 60_000L) return;
        lastListWarnPath = path;
        lastListWarnTimeMs = now;
        LogManager.warning(message + " path=" + path);
    }
}
