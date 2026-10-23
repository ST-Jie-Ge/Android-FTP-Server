package org.apache.ftpserver.command.impl.listing;

import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Android's File.isHidden() reports dot-prefixed paths as hidden. The stock
 * FtpServer lister wraps normal LIST/NLST requests in VisibleFileFilter, which
 * hides those files. This replacement preserves pattern filtering but only
 * lists files that are not hidden, keeping the returned entry count consistent
 * with what a client actually renders (otherwise the "N files" count disagrees
 * with the visible list).
 */
public class DirectoryLister {
    /**
     * 一趟遍历，目录在前、常规文件在后：原实现把整张列表扫两遍（先挑目录、再挑文件），
     * 等于每个条目都把 {@code isHidden()} 与 {@code isDirectory()} 各问了两遍。这两个方法
     * 在 {@code MultiRootFtpFile} 上不是免费查表——{@code isDirectory()} 是一次 stat，而
     * 用户目录多半落在 sdcardfs/FUSE 上，单次 stat 可能上百微秒，多问一遍就多一倍。
     *
     * <p>改动点只有一个：每个条目仍然只问一次 {@code isDirectory()}，既不在筛选前重复问，
     * 也不因为输出顺序把列表再走一遍。返回值与旧实现逐字节相同（目录在前、各自内部顺序
     * 不变），因此这是纯删开销、不含语义变化的一步。</p>
     */
    private String traverseFiles(List<? extends FtpFile> files, FileFilter filter, FileFormater formatter) {
        StringBuilder directories = new StringBuilder();
        StringBuilder regularFiles = new StringBuilder();
        for (FtpFile file : files) {
            if (file == null || file.isHidden()) continue;
            if (filter != null && !filter.accept(file)) continue;
            StringBuilder target = file.isDirectory() ? directories : regularFiles;
            target.append(formatter.format(file));
        }
        if (directories.length() == 0) return regularFiles.toString();
        if (regularFiles.length() == 0) return directories.toString();
        return directories.append(regularFiles).toString();
    }

    public String listFiles(ListArgument argument, FileSystemView fileSystemView, FileFormater formatter) throws IOException {
        StringBuilder result = new StringBuilder();
        List<? extends FtpFile> files = listFiles(fileSystemView, argument.getFile());
        if (files != null) {
            FileFilter filter = argument.getPattern() == null ? null : new RegexFileFilter(argument.getPattern(), null);
            result.append(traverseFiles(files, filter, formatter));
        }
        return result.toString();
    }

    private List<? extends FtpFile> listFiles(FileSystemView fileSystemView, String path) throws IOException {
        try {
            FtpFile file = fileSystemView.getFile(path);
            if (file == null || !file.doesExist()) throw new IOException("Path unavailable: " + path);
            if (file.isFile()) {
                List<FtpFile> one = new ArrayList<>();
                one.add(file);
                return one;
            }
            List<? extends FtpFile> files = file.listFiles();
            if (files == null) {
                // The directory exists but cannot be read (scoped-storage permission, I/O
                // error). Previously this produced a fake-empty listing, hiding real files
                // from the client without any error. Fail the command instead: LIST/NLST/MLSD
                // translate the IOException into a client-visible error reply.
                throw new IOException("Directory exists but cannot be listed: " + path);
            }
            return files;
        } catch (FtpException error) {
            throw new IOException("Cannot resolve directory: " + path, error);
        }
    }
}
