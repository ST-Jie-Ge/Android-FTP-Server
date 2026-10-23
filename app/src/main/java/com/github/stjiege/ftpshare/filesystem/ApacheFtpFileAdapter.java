package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FtpFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 把新的 {@link FtpPath} 适配成 Apache {@link FtpFile}，供现有命令层使用。 */
final class ApacheFtpFileAdapter implements FtpFile {

    private final FtpPath path;

    ApacheFtpFileAdapter(FtpPath path) { this.path = path; }

    @Override public String getAbsolutePath() { return path.getAbsolutePath(); }

    @Override public String getName() {
        String p = path.getAbsolutePath();
        if ("/".equals(p)) return "";
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    @Override public boolean doesExist() { return path.doesExist(); }
    @Override public boolean isDirectory() { return path.isDirectory(); }
    @Override public boolean isFile() { return path.isFile(); }
    @Override public boolean isReadable() { return path.isReadable(); }
    @Override public boolean isWritable() { return path.isWritable(); }
    @Override public boolean isRemovable() { return path.isRemovable(); }
    @Override public String getOwnerName() { return path.getOwnerName(); }
    @Override public String getGroupName() { return path.getGroupName(); }
    @Override public int getLinkCount() { return path.getLinkCount(); }
    @Override public long getSize() { return path.getSize(); }
    @Override public long getLastModified() { return path.getLastModified(); }

    @Override
    public boolean setLastModified(long time) {
        File f = path.getPhysicalFile();
        return f != null && f.setLastModified(time);
    }

    @Override
    public boolean mkdir() {
        try { return path.mkdir(); } catch (Exception e) { return false; }
    }

    @Override
    public boolean delete() {
        try { return path.delete(); } catch (Exception e) { return false; }
    }

    @Override
    public boolean move(FtpFile destination) {
        if (!(destination instanceof ApacheFtpFileAdapter)) return false;
        try { return path.move(((ApacheFtpFileAdapter) destination).path); }
        catch (Exception e) { return false; }
    }

    @Override
    public List<FtpFile> listFiles() {
        List<FtpPath> children;
        try { children = path.listFiles(); } catch (Exception e) { return null; }
        if (children == null) return null;
        List<FtpFile> result = new ArrayList<>(children.size());
        for (FtpPath child : children) result.add(new ApacheFtpFileAdapter(child));
        return result;
    }

    @Override public InputStream createInputStream(long offset) throws IOException {
        return path.createInputStream(offset);
    }

    @Override public OutputStream createOutputStream(long offset) throws IOException {
        return path.createOutputStream(offset);
    }

    @Override public boolean isHidden() {
        File f = path.getPhysicalFile();
        return f != null && f.isHidden();
    }

    @Override public Object getPhysicalFile() { return path.getPhysicalFile(); }
}
