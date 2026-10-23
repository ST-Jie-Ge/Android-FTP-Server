package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.StringTokenizer;

/** A user view whose virtual root contains only /device and an optional /sdcard. */
final class MultiRootFileSystemView implements FileSystemView {
    private final User user;
    private final LinkedHashMap<String, File> roots;
    /** Canonical root paths are immutable for a connected session. Keep them out of the list hot path. */
    private final LinkedHashMap<String, String> rootCanonicalPaths;
    private final boolean restrictAndroidData;
    private String currentDirectory = "/";

    MultiRootFileSystemView(User user, LinkedHashMap<String, File> roots, boolean restrictAndroidData) {
        this.user = user;
        this.roots = new LinkedHashMap<>(roots);
        this.rootCanonicalPaths = new LinkedHashMap<>();
        for (String name : this.roots.keySet()) {
            // MultiRootFileSystemFactory already canonicalizes each root before constructing this view.
            this.rootCanonicalPaths.put(name, this.roots.get(name).getPath());
        }
        this.restrictAndroidData = restrictAndroidData;
    }

    @Override public FtpFile getHomeDirectory() { return fileFor("/"); }
    @Override public FtpFile getWorkingDirectory() { return fileFor(currentDirectory); }

    @Override
    public boolean changeWorkingDirectory(String dir) throws FtpException {
        String path = normalize(dir);
        FtpFile target = fileFor(path);
        if (!target.doesExist() || !target.isDirectory()) return false;
        currentDirectory = path;
        return true;
    }

    @Override public FtpFile getFile(String file) throws FtpException { return fileFor(normalize(file)); }
    @Override public boolean isRandomAccessible() { return true; }
    @Override public void dispose() { }

    private FtpFile fileFor(String virtualPath) {
        if ("/".equals(virtualPath)) return MultiRootFtpFile.virtualRoot(user, roots, rootCanonicalPaths, restrictAndroidData);
        if (isAndroidDataBlocked(virtualPath)) return MultiRootFtpFile.missing(virtualPath, user, roots, rootCanonicalPaths, restrictAndroidData);
        String[] parts = virtualPath.substring(1).split("/");
        File root = roots.get(parts[0]);
        String rootPath = rootCanonicalPaths.get(parts[0]);
        if (root == null || rootPath == null) return MultiRootFtpFile.missing(virtualPath, user, roots, rootCanonicalPaths, restrictAndroidData);
        File target = root;
        for (int i = 1; i < parts.length; i++) target = new File(target, parts[i]);
        try {
            File canonical = target.getCanonicalFile();
            String targetPath = canonical.getPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                return MultiRootFtpFile.missing(virtualPath, user, roots, rootCanonicalPaths, restrictAndroidData);
            }
            return MultiRootFtpFile.physical(virtualPath, canonical, user, roots, rootCanonicalPaths, restrictAndroidData);
        } catch (IOException ignored) {
            return MultiRootFtpFile.missing(virtualPath, user, roots, rootCanonicalPaths, restrictAndroidData);
        }
    }

    private boolean isAndroidDataBlocked(String path) {
        if (!restrictAndroidData) return false;
        String[] parts = path.substring(1).split("/");
        return parts.length >= 3 && ("ROM".equals(parts[0]) || "SDcard".equals(parts[0]))
                && "Android".equalsIgnoreCase(parts[1]) && "data".equalsIgnoreCase(parts[2]);
    }

    private String normalize(String input) {
        String raw = input == null ? "" : input.replace('\\', '/');
        Deque<String> parts = new ArrayDeque<>();
        if (!raw.startsWith("/")) addParts(parts, currentDirectory);
        if (raw.startsWith("~")) parts.clear();
        addParts(parts, raw);
        StringBuilder result = new StringBuilder("/");
        for (String part : parts) {
            if (result.length() > 1) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    private void addParts(Deque<String> parts, String raw) {
        StringTokenizer tokens = new StringTokenizer(raw, "/");
        while (tokens.hasMoreTokens()) {
            String part = tokens.nextToken();
            if (part.length() == 0 || ".".equals(part) || "~".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(part);
        }
    }
}
