package org.apache.ftpserver.impl;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AsciiTransferTest {
    private byte[] convert(String input, boolean download, int chunk) throws Exception {
        AsciiTransfer converter = new AsciiTransfer(download);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] source = input.getBytes(StandardCharsets.ISO_8859_1);
        long written = 0;
        for (int i = 0; i < source.length; i += chunk) {
            byte[] part = java.util.Arrays.copyOfRange(source, i, Math.min(source.length, i + chunk));
            written += converter.write(part, part.length, out);
        }
        written += converter.finish(out);
        assertEquals(out.size(), written);
        assertEquals(0, converter.finish(out));
        return out.toByteArray();
    }
    @Test public void downloadAcrossEveryBoundary() throws Exception {
        for(int chunk=1;chunk<20;chunk++) assertArrayEquals(
            "a\r\nb\r\nc\r\u0000d\r\u0000".getBytes(StandardCharsets.ISO_8859_1),
            convert("a\nb\r\nc\rd\r",true,chunk));
    }
    @Test public void uploadAcrossEveryBoundary() throws Exception {
        for(int chunk=1;chunk<20;chunk++) assertArrayEquals(
            "a\nb\nc\rd\r".getBytes(StandardCharsets.ISO_8859_1),
            convert("a\r\nb\nc\r\u0000d\r",false,chunk));
    }
    @Test public void emptyAndNonAsciiBytes() throws Exception {
        assertArrayEquals(new byte[0],convert("",true,1));
        assertArrayEquals(new byte[]{(byte)255,0},convert("\u00ff\u0000",false,1));
    }
}
