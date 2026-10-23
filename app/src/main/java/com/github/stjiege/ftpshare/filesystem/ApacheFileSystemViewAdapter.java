package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;

/** 把 {@link FtpFileSystem} 适配成 Apache {@link FileSystemView}。 */
final class ApacheFileSystemViewAdapter implements FileSystemView {

    private final FtpFileSystem view;

    ApacheFileSystemViewAdapter(FtpFileSystem view) { this.view = view; }

    @Override public FtpFile getHomeDirectory() {
        return new ApacheFtpFileAdapter(view.getHomeDirectory());
    }

    @Override public FtpFile getWorkingDirectory() {
        return new ApacheFtpFileAdapter(view.getWorkingDirectory());
    }

    @Override public boolean changeWorkingDirectory(String dir) throws FtpException {
        return view.changeWorkingDirectory(dir);
    }

    @Override public FtpFile getFile(String file) throws FtpException {
        return new ApacheFtpFileAdapter(view.getFile(file));
    }

    @Override public void dispose() { view.dispose(); }
    @Override public boolean isRandomAccessible() { return view.isRandomAccessible(); }
}
