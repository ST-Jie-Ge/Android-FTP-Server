package com.github.stjiege.ftpshare.filesystem;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransferProgressTest {
    @Test public void aggregatesFastBlocksAndFlushesExactTail() {
        List<Integer> reports = new ArrayList<>();
        TransferProgress progress = new TransferProgress(reports::add, () -> 0L);
        for (int i = 0; i < 128; i++) progress.add(64 * 1024);
        assertEquals(8, reports.size()); // 128 per-block callbacks become 8.
        progress.add(7); progress.flush(); progress.flush();
        assertEquals(9, reports.size());
        assertEquals(8L * 1024 * 1024 + 7, reports.stream().mapToLong(Integer::longValue).sum());
    }
    @Test public void slowTransfersPublishOnTimeWithoutWaitingForFullBatch() {
        long[] now = {0};
        List<Integer> reports = new ArrayList<>();
        TransferProgress progress = new TransferProgress(reports::add, () -> now[0]);
        progress.add(10);
        now[0] = 250_000_000L;
        progress.add(5);
        assertEquals(java.util.Arrays.asList(15), reports);
        now[0] += 250_000_000L;
        progress.add(0);
        assertEquals(1, reports.size());
    }
    @Test public void largeIncrementsDoNotOverflow() {
        List<Integer> reports = new ArrayList<>();
        TransferProgress progress = new TransferProgress(reports::add, () -> 0L);
        progress.add(10); progress.add(Integer.MAX_VALUE); progress.flush();
        assertEquals((long) Integer.MAX_VALUE + 10, reports.stream().mapToLong(Integer::longValue).sum());
    }
}
