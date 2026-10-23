package com.github.stjiege.ftpshare.filesystem;

import android.content.Context;

import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;

import java.util.Set;

/** 用新的 {@link LocalFileSystem} 替换 Apache 原生文件系统工厂。 */
public final class ApacheFileSystemAdapter implements FileSystemFactory {

    private final Context context;
    private final Set<String> fullStorageUsers;

    public ApacheFileSystemAdapter(Context context, Set<String> fullStorageUsers) {
        this.context = context.getApplicationContext();
        this.fullStorageUsers = fullStorageUsers;
    }

    @Override
    public FileSystemView createFileSystemView(User user) throws FtpException {
        boolean fullStorage = user != null && fullStorageUsers.contains(user.getName());
        FtpFileSystem view = new LocalFileSystem(context, user, fullStorage);
        return new ApacheFileSystemViewAdapter(view);
    }
}
