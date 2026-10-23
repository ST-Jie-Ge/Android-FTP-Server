package com.github.stjiege.ftpshare.filesystem;

import android.content.Context;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.StorageUtil;

import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;

/** Chooses a virtual multi-volume root only for accounts that explicitly enable it. */
public final class MultiRootFileSystemFactory implements FileSystemFactory {
    private final Context context;
    private final Set<String> multiRootUsers;
    private final NativeFileSystemFactory nativeFactory = new NativeFileSystemFactory();

    public MultiRootFileSystemFactory(Context context, Set<String> multiRootUsers) {
        this.context = context.getApplicationContext();
        this.multiRootUsers = multiRootUsers == null ? Collections.<String>emptySet() : multiRootUsers;
    }

    @Override
    public FileSystemView createFileSystemView(User user) throws FtpException {
        if (!multiRootUsers.contains(user.getName())) return nativeFactory.createFileSystemView(user);
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
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
        } catch (Exception ignored) {
            // A missing or unmounted volume is simply not exposed in the virtual root.
        }
        if (roots.isEmpty()) throw new FtpException("No mounted storage is available");
        boolean restrictAndroidData = CommonUtils.getSettingSharedPreferences(context).getBoolean(
                Constants.PreferenceConsts.RESTRICT_ANDROID_DATA,
                Constants.PreferenceConsts.RESTRICT_ANDROID_DATA_DEFAULT);
        return new MultiRootFileSystemView(user, roots, restrictAndroidData);
    }
}
