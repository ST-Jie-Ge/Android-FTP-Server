package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FtpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 中性的 FTP 路径抽象。命令层通过 {@link ApacheFtpFileAdapter} 转成 Apache {@code FtpFile}。
 *
 * <p>设计目标：
 * <ul>
 *   <li>属性一次性聚合在 {@link FtpPathAttributes} 中，LIST/MLSD 不用反复反射；</li>
 *   <li>打开输出流时不再先截断原文件，覆盖/REST 续传由 {@link StagedOutputStream} 保护；</li>
 *   <li>移动/删除直接基于路径实现，不再依赖 {@code instanceof NativeFtpFile}。</li>
 * </ul>
 */
public interface FtpPath {

    String getAbsolutePath();

    FtpPathAttributes attributes();

    boolean doesExist();
    boolean isDirectory();
    boolean isFile();
    boolean isReadable();
    boolean isWritable();
    boolean isRemovable();

    long getSize();
    long getLastModified();

    /** 固定假值，保持与上游 LIST 输出一致。 */
    String getOwnerName();
    /** 固定假值，保持与上游 LIST 输出一致。 */
    String getGroupName();
    /** 固定假值，保持与上游 LIST 输出一致。 */
    int getLinkCount();

    FtpPath getParent();
    List<FtpPath> listFiles() throws FtpException;

    InputStream createInputStream(long offset) throws IOException;
    OutputStream createOutputStream(long offset) throws IOException;

    boolean mkdir() throws FtpException;
    boolean delete() throws FtpException;

    /** 移动到同视图内的目标路径。 */
    boolean move(FtpPath destination) throws FtpException;

    /** 底层物理文件，主要用于移动时跨卷拷贝；不存在时返回 null。 */
    java.io.File getPhysicalFile();
}
