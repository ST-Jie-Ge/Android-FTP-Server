package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FtpException;

/**
 * 中性的 FTP 文件系统视图。每个会话持有一个视图。
 */
public interface FtpFileSystem {

    FtpPath getHomeDirectory();
    FtpPath getWorkingDirectory();

    boolean changeWorkingDirectory(String dir) throws FtpException;
    FtpPath getFile(String path) throws FtpException;

    boolean isRandomAccessible();
    void dispose();
}
