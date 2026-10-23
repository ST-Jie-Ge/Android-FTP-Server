package com.github.stjiege.ftpshare.ftpserver.command;

import com.github.stjiege.ftpshare.filesystem.DirectoryNames;
import com.github.stjiege.ftpshare.filesystem.MoveCancellation;
import org.apache.ftpserver.command.impl.listing.FileFilter;
import org.apache.ftpserver.command.impl.listing.FileFormater;
import org.apache.ftpserver.command.impl.listing.ListArgument;
import org.apache.ftpserver.command.impl.listing.RegexFileFilter;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Pull-based listing: no producer thread, complete-directory array, or complete reply string. */
final class ListingInputStream extends InputStream {
    private final FileSystemView view;
    private final FileFormater formatter;
    private final FileFilter filter;
    private final Charset charset;
    private final String parent;
    private DirectoryNames names;
    private Iterator<? extends FtpFile> virtualEntries;
    private byte[] batch = new byte[0];
    private final byte[] singleByte = new byte[1];
    private int position;
    private boolean closed;

    ListingInputStream(FileSystemView view, FtpFile target, ListArgument argument,
                       FileFormater formatter, Charset charset) throws IOException {
        this.view = view;
        this.formatter = formatter;
        this.charset = charset;
        filter = argument.getPattern() == null ? null : new RegexFileFilter(argument.getPattern(), null);
        parent = target.getAbsolutePath();
        if (target.isFile()) {
            virtualEntries = Collections.singletonList(target).iterator();
        } else if (target.getPhysicalFile() instanceof File) {
            names = DirectoryNames.open((File) target.getPhysicalFile());
        } else {
            // The multi-volume virtual root contains only the mounted volume entries.
            List<? extends FtpFile> roots = target.listFiles();
            if (roots == null) throw new IOException("Cannot enumerate virtual root");
            virtualEntries = roots.iterator();
        }
    }

    private FtpFile next() throws IOException {
        checkCancelled();
        if (names == null) return virtualEntries.hasNext() ? virtualEntries.next() : null;
        String name = names.next();
        if (name == null) return null;
        try {
            // Resolve each entry through the account view: keep home/Android-data restrictions.
            return view.getFile((parent.endsWith("/") ? parent : parent + "/") + name);
        } catch (FtpException error) { throw new IOException("Cannot resolve listing entry", error); }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (MoveCancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Directory listing cancelled");
        }
    }

    private boolean fill() throws IOException {
        checkCancelled();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        int count = 0;
        while (bytes.size() < 16 * 1024 && count < 64) {
            FtpFile file = next();
            if (file == null) break;
            if (!file.doesExist() || file.isHidden() || (filter != null && !filter.accept(file))) continue;
            byte[] line = formatter.format(file).getBytes(charset);
            bytes.write(line, 0, line.length);
            count++;
        }
        batch = bytes.toByteArray();
        position = 0;
        return batch.length != 0;
    }

    @Override public int read(byte[] destination, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > destination.length - length) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("Listing stream closed");
        if (length == 0) return 0;
        checkCancelled();
        if (position == batch.length && !fill()) return -1;
        int count = Math.min(length, batch.length - position);
        System.arraycopy(batch, position, destination, offset, count);
        position += count;
        return count;
    }

    @Override public int read() throws IOException {
        return read(singleByte, 0, 1) < 0 ? -1 : singleByte[0] & 255;
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        batch = new byte[0];
        if (names != null) names.close();
    }
}
