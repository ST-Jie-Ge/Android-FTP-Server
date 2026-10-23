package org.apache.ftpserver.impl;

import java.io.IOException;
import java.io.OutputStream;

/** Streaming NVT-ASCII conversion. State survives read-buffer boundaries. */
public final class AsciiTransfer {
    private final boolean download;
    private boolean pendingCr;

    public AsciiTransfer(boolean download) { this.download = download; }

    public long write(byte[] bytes, int count, OutputStream out) throws IOException {
        long written = 0;
        for (int i = 0; i < count; i++) {
            if (!pendingCr && bytes[i] != '\r' && bytes[i] != '\n') {
                int start = i;
                while (i < count && bytes[i] != '\r' && bytes[i] != '\n') i++;
                out.write(bytes, start, i - start);
                written += i - start;
                i--;
                continue;
            }
            int b = bytes[i] & 255;
            if (pendingCr) {
                pendingCr = false;
                if (b == '\n') {
                    if (download) { out.write('\r'); written++; }
                    out.write('\n'); written++;
                    continue;
                }
                out.write('\r'); written++;
                if (download) { out.write(0); written++; }
                else if (b == 0) { continue; }
            }
            if (b == '\r') pendingCr = true;
            else {
                if (download && b == '\n') { out.write('\r'); written++; }
                out.write(b); written++;
            }
        }
        return written;
    }

    public long finish(OutputStream out) throws IOException {
        if (!pendingCr) return 0;
        pendingCr = false;
        out.write('\r');
        if (download) { out.write(0); return 2; }
        return 1;
    }
}
