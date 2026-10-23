package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import java.io.File;
import java.util.LinkedHashMap;

/** Real LocalFileSystem per login, with Android volume discovery replaced by a test home. */
public final class LocalFileSystemTestFactory implements FileSystemFactory {
    @Override public FileSystemView createFileSystemView(User user) throws FtpException {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        roots.put("", new File(user.getHomeDirectory()));
        return new ApacheFileSystemViewAdapter(new LocalFileSystem(user, false, false, roots));
    }
}
