package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FtpException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

/**
 * {@link FtpPath} 的本地文件实现。
 */
final class LocalFtpPath implements FtpPath {

    private final LocalFileSystem view;
    private final String virtualPath;
    private final File physical;
    private final String rootName;
    private final boolean virtualRoot;

    private volatile FtpPathAttributes attrs;

    LocalFtpPath(LocalFileSystem view, String virtualPath, File physical,
                 String rootName, boolean virtualRoot) {
        this.view = view;
        this.virtualPath = virtualPath;
        this.physical = physical;
        this.rootName = rootName;
        this.virtualRoot = virtualRoot;
    }

    @Override public String getAbsolutePath() { return virtualPath; }

    @Override public FtpPathAttributes attributes() {
        if (attrs == null) attrs = computeAttributes();
        return attrs;
    }

    private FtpPathAttributes computeAttributes() {
        if (virtualRoot && view.isMultiRoot()) {
            return new FtpPathAttributes(true, true, 0L, 0L, true, false, false);
        }
        if (physical == null || !physical.exists()) {
            return FtpPathAttributes.missing();
        }
        boolean dir = physical.isDirectory();
        long size = dir ? 0L : physical.length();
        long modified = physical.lastModified();
        boolean readable = physical.canRead();
        boolean writable = view.isWritable() && physical.canWrite();
        boolean removable = writable && !isRoot();
        return new FtpPathAttributes(true, dir, size, modified, readable, writable, removable);
    }

    @Override public boolean doesExist() { return attributes().exists; }
    @Override public boolean isDirectory() { return attributes().directory; }
    @Override public boolean isFile() { return attributes().exists && !attributes().directory; }
    @Override public boolean isReadable() { return attributes().readable; }

    @Override
    public boolean isWritable() {
        if (!view.isWritable()) return false;
        if (virtualRoot) {
            // 多根模式下的全局虚拟根 "/" 没有物理位置，不可写；
            // 单根模式下的 "/" 就是用户主目录，按物理目录权限判断。
            return !view.isMultiRoot() && physical != null && physical.canWrite();
        }
        if (physical != null && physical.exists()) return physical.canWrite();
        // 文件不存在时，可写性取决于父目录是否可写（STOR 新建文件、RNTO 新目标名、MKD）。
        FtpPath parent = getParent();
        return parent != null && parent.doesExist() && parent.isDirectory() && parent.isWritable();
    }

    @Override public boolean isRemovable() {
        return !virtualRoot && !isRoot() && !isVolumeRoot() && isWritable();
    }

    @Override public long getSize() { return attributes().size; }
    @Override public long getLastModified() { return attributes().lastModified; }

    @Override public String getOwnerName() { return "user"; }
    @Override public String getGroupName() { return "group"; }
    @Override public int getLinkCount() { return 0; }

    @Override public FtpPath getParent() {
        if ("/".equals(virtualPath)) return null;
        int idx = virtualPath.lastIndexOf('/');
        String parent = idx <= 0 ? "/" : virtualPath.substring(0, idx);
        return view.fileFor(parent);
    }

    @Override public List<FtpPath> listFiles() throws FtpException {
        if (virtualRoot && view.isMultiRoot()) return view.rootChildren();
        return view.childrenOf(virtualPath, physical);
    }

    @Override public InputStream createInputStream(long offset) throws IOException {
        if (physical == null || !physical.isFile()) throw new IOException("File not found: " + virtualPath);
        FileInputStream in = new FileInputStream(physical);
        if (offset > 0) {
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            if (skipped < offset) throw new IOException("Cannot skip to offset " + offset);
        }
        return in;
    }

    @Override public OutputStream createOutputStream(long offset) throws IOException {
        if (!view.isWritable()) throw new IOException("Permission denied: " + virtualPath);
        File target = physical;
        if (target == null || !target.exists()) {
            // REST offset 只有在原文件存在时才有续传含义。不能悄悄从头创建，
            // 否则客户端认为从 offset 开始写入，实际文件却从字节 0 开始。
            if (offset != 0L) {
                throw new IOException("Cannot resume upload to a missing file: " + virtualPath);
            }
            // 文件不存在：创建新文件并直接写入，无需 staging。
            FtpPath parent = getParent();
            if (parent == null || !parent.isDirectory()) throw new IOException("Parent directory missing: " + virtualPath);
            File parentFile = parent.getPhysicalFile();
            if (parentFile == null) throw new IOException("Parent directory missing: " + virtualPath);
            target = new File(parentFile, nameFrom(virtualPath));
            if ("APPE".equals(CommandContext.get())) return new FileOutputStream(target, true);
            return LocalStagedOutputStream.overwrite(target);
        }
        if (!target.isFile()) throw new IOException("Not a file: " + virtualPath);

        long currentSize = target.length();
        if ("APPE".equals(CommandContext.get())) {
            // APPE 永远追加到文件尾。
            return new FileOutputStream(target, true);
        }
        if (offset == 0L) {
            return LocalStagedOutputStream.overwrite(target);
        }
        if (offset == currentSize) {
            // REST offset 正好在 EOF，与追加等价，无截断风险。
            return new FileOutputStream(target, true);
        }
        return LocalStagedOutputStream.resume(target, offset);
    }

    @Override public boolean mkdir() throws FtpException {
        if (physical != null && physical.exists()) return false;
        FtpPath parent = getParent();
        if (parent == null || !parent.isDirectory()) return false;
        File parentFile = parent.getPhysicalFile();
        if (parentFile == null) return false;
        File dir = new File(parentFile, nameFrom(virtualPath));
        return dir.mkdirs();
    }

    @Override public boolean delete() throws FtpException {
        if (physical == null || !physical.exists()) return false;
        if (physical.isDirectory()) {
            File[] children = physical.listFiles();
            if (children != null && children.length > 0) return false;
        }
        return physical.delete();
    }

    @Override public boolean move(FtpPath destination) throws FtpException {
        return MoveSupport.move(this, destination);
    }

    @Override public File getPhysicalFile() { return physical; }

    private boolean isRoot() {
        return "/".equals(virtualPath);
    }

    private boolean isVolumeRoot() {
        return view.isMultiRoot() && rootName != null
                && ("/" + rootName).equals(virtualPath);
    }

    private static String nameFrom(String virtualPath) {
        int idx = virtualPath.lastIndexOf('/');
        return idx < 0 ? virtualPath : virtualPath.substring(idx + 1);
    }
}
