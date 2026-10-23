package com.github.stjiege.ftpshare.filesystem;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An {@link OutputStream} delegate that ignores {@link #close()} and only flushes.
 * Used by commands that must keep explicit control over a staged stream's
 * {@code commit()}/{@code rollback()} lifecycle: the data-transfer helper closes
 * its output stream, but the command wrapper prevents the staged stream from
 * auto-committing before the command has decided success or failure.
 */
public final class NonClosingOutputStream extends FilterOutputStream {
    public NonClosingOutputStream(OutputStream out) { super(out); }
    @Override public void close() throws IOException { out.flush(); }
    @Override public void write(int b) throws IOException { out.write(b); }
    @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); }
}
