package com.github.stjiege.ftpshare.filesystem;

import android.content.Context;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.StorageUtil;

import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.usermanager.impl.WriteRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地文件系统视图，替代 {@code NativeFileSystemFactory + MultiRootFileSystemFactory}。
 *
 * <p>每个会话一个实例；根据用户是否开启“完整存储”选择单根或多根视图。
 * 不再返回 {@link org.apache.ftpserver.ftplet.FtpFile} 动态代理，属性一次性聚合，
 * 写文件时不先截断原文件。</p>
 */
public final class LocalFileSystem implements FtpFileSystem {

    private final User user;
    private final boolean writable;
    private final boolean restrictAndroidData;
    private final LinkedHashMap<String, File> roots;
    private final LinkedHashMap<String, String> rootCanonicalPaths;
    private final boolean multiRoot;
    private String currentDirectory = "/";

    public LocalFileSystem(Context context, User user, boolean fullStorage) throws FtpException {
        this(user, fullStorage, CommonUtils.getSettingSharedPreferences(context.getApplicationContext())
                .getBoolean(Constants.PreferenceConsts.RESTRICT_ANDROID_DATA,
                        Constants.PreferenceConsts.RESTRICT_ANDROID_DATA_DEFAULT),
                discoverRoots(context, user, fullStorage));
    }

    /** Explicit roots keep filesystem behavior independent of Android storage discovery. */
    LocalFileSystem(User user, boolean fullStorage, boolean restrictAndroidData,
                    LinkedHashMap<String, File> roots) throws FtpException {
        this.user = user;
        this.writable = user != null && user.authorize(new WriteRequest("/")) != null;
        this.multiRoot = fullStorage;
        this.restrictAndroidData = restrictAndroidData;
        this.roots = new LinkedHashMap<>(roots);
        this.rootCanonicalPaths = new LinkedHashMap<>();
        if (roots.isEmpty()) throw new FtpException("No usable storage root");
        for (String name : roots.keySet()) {
            rootCanonicalPaths.put(name, roots.get(name).getPath());
        }
    }

    private static LinkedHashMap<String, File> discoverRoots(Context context, User user, boolean fullStorage) {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        if (fullStorage) {
            try {
                File primary = new File(StorageUtil.getMainStoragePath()).getCanonicalFile();
                if (primary.isDirectory()) roots.put("ROM", primary);
                for (String storagePath : StorageUtil.getAvailableStoragePaths(context)) {
                    File candidate = new File(storagePath).getCanonicalFile();
                    if (candidate.isDirectory() && !candidate.equals(primary)) {
                        roots.put("SDcard", candidate);
                        break;
                    }
                }
            } catch (IOException ignored) { }
        } else {
            String home = user == null ? "" : user.getHomeDirectory();
            try { roots.put("", new File(home).getCanonicalFile()); }
            catch (IOException ignored) { roots.put("", new File(home)); }
        }
        return roots;
    }

    @Override public FtpPath getHomeDirectory() { return fileFor("/"); }
    @Override public FtpPath getWorkingDirectory() { return fileFor(currentDirectory); }

    @Override
    public boolean changeWorkingDirectory(String dir) throws FtpException {
        String path = normalize(dir);
        FtpPath target = fileFor(path);
        if (!target.doesExist() || !target.isDirectory()) return false;
        currentDirectory = path;
        return true;
    }

    @Override public FtpPath getFile(String file) throws FtpException { return fileFor(normalize(file)); }
    @Override public boolean isRandomAccessible() { return true; }
    @Override public void dispose() { }

    FtpPath fileFor(String virtualPath) {
        if (isAndroidDataBlocked(virtualPath)) {
            return new LocalFtpPath(this, virtualPath, null, null, false);
        }
        if ("/".equals(virtualPath)) {
            File root = multiRoot ? null : roots.get("");
            return new LocalFtpPath(this, "/", root, null, true);
        }
        String[] parts = virtualPath.substring(1).split("/");
        if (multiRoot) {
            String rootName = parts[0];
            File root = roots.get(rootName);
            String rootPath = rootCanonicalPaths.get(rootName);
            if (root == null || rootPath == null) {
                return new LocalFtpPath(this, virtualPath, null, null, false);
            }
            File target = root;
            for (int i = 1; i < parts.length; i++) target = new File(target, parts[i]);
            return resolvePath(virtualPath, target, rootPath, rootName);
        } else {
            File root = roots.get("");
            String rootPath = rootCanonicalPaths.get("");
            File target = root;
            for (String part : parts) target = new File(target, part);
            return resolvePath(virtualPath, target, rootPath, null);
        }
    }

    private FtpPath resolvePath(String virtualPath, File target, String rootPath, String rootName) {
        try {
            File canonical = target.getCanonicalFile();
            String targetPath = canonical.getPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                return new LocalFtpPath(this, virtualPath, null, null, false);
            }
            return new LocalFtpPath(this, virtualPath, canonical, rootName, false);
        } catch (IOException ignored) {
            // 路径不可解析（例如父目录不存在但允许 mkdir），仍返回一个可操作的缺失路径。
            return new LocalFtpPath(this, virtualPath, target, rootName, false);
        }
    }

    private boolean isAndroidDataBlocked(String path) {
        if (!restrictAndroidData) return false;
        String[] parts = path.substring(1).split("/");
        return parts.length >= 3
                && (multiRoot ? ("ROM".equals(parts[0]) || "SDcard".equals(parts[0])) : true)
                && "Android".equalsIgnoreCase(parts[multiRoot ? 1 : 0])
                && "data".equalsIgnoreCase(parts[multiRoot ? 2 : 1]);
    }

    String normalize(String input) {
        String raw = input == null ? "" : input.replace('\\', '/');
        StringBuilder result = new StringBuilder(raw.length() + currentDirectory.length() + 1);
        result.append('/');
        int[] segmentStarts = new int[raw.length() + currentDirectory.length() + 1];
        int count = 0;
        if (!raw.startsWith("/")) count = appendParts(result, segmentStarts, count, currentDirectory);
        if (raw.startsWith("~")) {
            result.setLength(1);
            count = 0;
        }
        appendParts(result, segmentStarts, count, raw);
        return result.toString();
    }

    private int appendParts(StringBuilder result, int[] segmentStarts, int count, String raw) {
        int index = 0;
        while (index < raw.length()) {
            while (index < raw.length() && raw.charAt(index) == '/') index++;
            int start = index;
            while (index < raw.length() && raw.charAt(index) != '/') index++;
            int length = index - start;
            if (length == 0 || (length == 1 && (raw.charAt(start) == '.' || raw.charAt(start) == '~'))) continue;
            if (length == 2 && raw.charAt(start) == '.' && raw.charAt(start + 1) == '.') {
                if (count > 0) result.setLength(segmentStarts[--count]);
                continue;
            }
            segmentStarts[count++] = result.length();
            if (result.length() > 1) result.append('/');
            result.append(raw, start, index);
        }
        return count;
    }

    boolean isWritable() { return writable; }
    boolean isMultiRoot() { return multiRoot; }
    Map<String, File> getRoots() { return Collections.unmodifiableMap(roots); }
    File getRoot(String name) { return roots.get(name); }

    /** 供 {@link LocalFtpPath#listFiles()} 使用。 */
    List<FtpPath> childrenOf(String virtualPath, File physical) throws FtpException {
        if (physical == null || !physical.isDirectory()) {
            throw new FtpException("Directory unavailable: " + virtualPath);
        }
        File[] files = physical.listFiles();
        if (files == null) {
            com.github.stjiege.ftpshare.utils.LogManager.warning("目录无法读取（检查权限或挂载状态）: " + virtualPath);
            throw new FtpException("Cannot list directory: " + virtualPath);
        }
        List<FtpPath> result = new ArrayList<>(files.length);
        for (File child : files) {
            String childVirtual = virtualPath.endsWith("/") ? virtualPath + child.getName() : virtualPath + "/" + child.getName();
            result.add(fileFor(childVirtual));
        }
        return result;
    }

    List<FtpPath> rootChildren() {
        List<FtpPath> result = new ArrayList<>(roots.size());
        for (String name : roots.keySet()) {
            result.add(fileFor("/" + name));
        }
        return result;
    }
}
