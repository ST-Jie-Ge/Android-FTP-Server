package com.github.stjiege.ftpshare.filesystem;

/**
 * 文件/目录属性的只读快照，供 LIST/MLSD 一次查询后多次使用，避免每次属性访问都重复 stat。
 */
public final class FtpPathAttributes {
    public final boolean exists;
    public final boolean directory;
    public final long size;
    public final long lastModified;
    public final boolean readable;
    public final boolean writable;
    public final boolean removable;

    public FtpPathAttributes(boolean exists, boolean directory, long size, long lastModified,
                             boolean readable, boolean writable, boolean removable) {
        this.exists = exists;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.readable = readable;
        this.writable = writable;
        this.removable = removable;
    }

    public static FtpPathAttributes missing() {
        return new FtpPathAttributes(false, false, 0L, 0L, false, false, false);
    }
}
