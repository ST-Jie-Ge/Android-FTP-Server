package com.github.stjiege.ftpshare.command;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import com.github.stjiege.ftpshare.filesystem.MoveCancellation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 非标准批量移动命令 {@code MOVE}。
 *
 * <p>FTP 标准只有单路径的 RNFR/RNTO。这个扩展命令允许一次把多个文件或目录
 * 移进同一个目标目录，也支持单个文件/目录的重命名（移动）。</p>
 *
 * <p>语法（空格分隔，最后一个参数是目标）：
 * <pre>
 * MOVE src1 src2 src3 destDir
 * MOVE "path with space" another destDir
 * MOVE oldName newName
 * </pre>
 * </p>
 */
public final class MoveCommand extends AbstractCommand {

    private static final int REPLY_250 = 250;
    private static final int REPLY_426 = 426;
    private static final int REPLY_501 = 501;
    private static final int REPLY_550 = 550;

    @Override
    public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request)
            throws IOException, FtpException {

        String argument = request.getArgument();
        List<String> tokens = tokenize(argument);
        if (tokens.size() < 2) {
            session.write(new DefaultFtpReply(REPLY_501,
                    "Syntax error in parameters or arguments. Usage: MOVE src1 [src2 ...] dest"));
            return;
        }

        String destToken = tokens.get(tokens.size() - 1);
        List<String> sourceTokens = tokens.subList(0, tokens.size() - 1);
        FileSystemView fs = session.getFileSystemView();

        FtpFile dest = resolve(fs, destToken);
        if (dest == null) {
            session.write(new DefaultFtpReply(REPLY_550, "Destination not found"));
            return;
        }
        // 多源时目标必须是已存在的目录；单源时目标可以是不存在的路径（重命名）。
        if (sourceTokens.size() > 1 && (!dest.doesExist() || !dest.isDirectory())) {
            session.write(new DefaultFtpReply(REPLY_550,
                    "Destination must be an existing directory when moving multiple sources"));
            return;
        }
        if (!dest.isWritable()) {
            session.write(new DefaultFtpReply(REPLY_550, "Permission denied"));
            return;
        }

        int succeeded = 0;
        List<String> failed = new ArrayList<>();
        for (String srcToken : sourceTokens) {
            if (MoveCancellation.isCancelled()) {
                session.write(new DefaultFtpReply(REPLY_426, "Move aborted"));
                return;
            }

            FtpFile source = resolve(fs, srcToken);
            if (source == null || !source.doesExist()) {
                failed.add(srcToken + ": not found");
                continue;
            }
            if (!source.isReadable() || !source.isRemovable()) {
                failed.add(srcToken + ": permission denied");
                continue;
            }

            FtpFile target = dest;
            if (dest.doesExist() && dest.isDirectory()) {
                String targetPath = joinPath(dest.getAbsolutePath(), source.getName());
                target = resolve(fs, targetPath);
                if (target == null) {
                    failed.add(srcToken + ": bad target");
                    continue;
                }
            }

            if (source.move(target)) {
                succeeded++;
            } else {
                failed.add(srcToken + ": move failed");
            }
        }

        if (MoveCancellation.isCancelled()) {
            session.write(new DefaultFtpReply(REPLY_426,
                    "Move aborted; " + succeeded + " source(s) already moved"));
        } else if (failed.isEmpty()) {
            session.write(new DefaultFtpReply(REPLY_250, "MOVE completed"));
        } else {
            session.write(new DefaultFtpReply(REPLY_550,
                    String.format("MOVE partial: %d succeeded, %d failed (%s)",
                            succeeded, failed.size(), String.join("; ", failed))));
        }
    }

    /**
     * 简单分词：支持双引号包裹含空格的路径，引号本身被去掉。
     */
    private static List<String> tokenize(String argument) {
        List<String> tokens = new ArrayList<>();
        if (argument == null || argument.isEmpty()) return tokens;
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private static FtpFile resolve(FileSystemView fs, String path) {
        try {
            return fs.getFile(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String joinPath(String parent, String name) {
        if (parent.endsWith("/")) return parent + name;
        return parent + "/" + name;
    }
}
