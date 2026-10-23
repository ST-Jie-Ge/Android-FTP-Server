package org.apache.ftpserver.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionCommandQueueTest {
    @Test public void queuedCancellationDoesNotWaitForWorkerAndStaleWorkerDoesNothing() {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(workers::add);
        long generation = queue.transferGeneration();
        queue.execute(() -> calls.add(generation == queue.transferGeneration() ? "RETR" : "425"),
                workers::add, false, true);
        queue.execute(() -> calls.add("CWD"), false);
        queue.cancelTransfers();
        queue.execute(() -> calls.add("226"), false, true);
        assertEquals(Arrays.asList("425", "CWD", "226"), calls);
        workers.get(0).run();
        assertEquals(Arrays.asList("425", "CWD", "226"), calls);
        queue.execute(() -> calls.add("NEXT"), true);
        assertEquals(2, workers.size());
        workers.get(1).run();
        assertEquals(Arrays.asList("425", "CWD", "226", "NEXT"), calls);
    }

    @Test public void freshTransferDuringCancellationIsOffloaded() {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(workers::add);
        queue.execute(() -> calls.add("425"), workers::add, false, true);
        queue.cancelTransfers();
        queue.execute(() -> {
            calls.add("226");
            queue.execute(() -> calls.add("NEW-STOR"), true);
        }, false, true);
        assertEquals(Arrays.asList("425", "226"), calls);
        workers.get(0).run();
        assertEquals(Arrays.asList("425", "226"), calls);
        workers.get(1).run();
        assertEquals(Arrays.asList("425", "226", "NEW-STOR"), calls);
    }

    @Test public void runningCommandKeepsExclusiveOwnershipDuringCancellation() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch aborted = new java.util.concurrent.CountDownLatch(1);
        try {
            SessionCommandQueue queue = new SessionCommandQueue(pool);
            queue.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, true);
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            queue.cancelTransfers();
            queue.execute(aborted::countDown, false, true);
            assertFalse(aborted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(aborted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void saturatedPoolReleasesQueuedWorkerCapacityOnCancellation() throws Exception {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1));
        java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            pool.execute(() -> {
                occupied.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(occupied.await(2, java.util.concurrent.TimeUnit.SECONDS));
            SessionCommandQueue queue = new SessionCommandQueue(pool);
            queue.execute(() -> {}, pool, false, true);
            assertEquals(1, pool.getQueue().size());
            queue.cancelTransfers();
            java.util.concurrent.CountDownLatch aborted = new java.util.concurrent.CountDownLatch(1);
            queue.execute(aborted::countDown, false, true);
            assertEquals(0, aborted.getCount());
            assertTrue(pool.getQueue().isEmpty());
            queue.execute(() -> {}, true);
            assertEquals(1, pool.getQueue().size());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test public void transferAndFollowingCommandsStayInReceiveOrder() {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(workers::add);
        queue.execute(() -> calls.add("RETR"), true);
        queue.execute(() -> calls.add("CWD"), false);
        queue.execute(() -> calls.add("PWD"), false);
        assertTrue(calls.isEmpty());
        assertEquals(1, workers.size());
        workers.remove(0).run();
        assertEquals(Arrays.asList("RETR", "CWD", "PWD"), calls);
    }
    @Test public void idleControlCommandsRemainInline() {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(workers::add);
        queue.execute(() -> calls.add("USER"), false);
        assertEquals(Arrays.asList("USER"), calls);
        assertTrue(workers.isEmpty());
    }
    @Test public void closedSessionDropsPendingWork() {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(workers::add);
        queue.execute(() -> calls.add("STOR"), true);
        queue.close();
        workers.remove(0).run();
        assertTrue(calls.isEmpty());
    }
    @Test public void overloadNeverRunsRejectedTransferInline() {
        SessionCommandQueue queue = new SessionCommandQueue(task -> {
            throw new RejectedExecutionException("stopped");
        });
        List<String> calls = new ArrayList<>();
        try {
            queue.execute(() -> calls.add("STOR"), true);
            fail("Must reject");
        } catch (RejectedExecutionException expected) { }
        assertTrue(calls.isEmpty());
    }
    @Test public void moveCommandRunsOnItsOwnLane() {
        List<Runnable> transferWorkers = new ArrayList<>();
        List<Runnable> moveWorkers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(transferWorkers::add);
        queue.execute(() -> calls.add("RNTO"), moveWorkers::add, false);
        assertTrue(transferWorkers.isEmpty());
        assertEquals(1, moveWorkers.size());
        moveWorkers.remove(0).run();
        assertEquals(Arrays.asList("RNTO"), calls);
    }
    @Test public void lanesSwitchWithoutReorderingCommands() {
        List<Runnable> transferWorkers = new ArrayList<>();
        List<Runnable> moveWorkers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(transferWorkers::add);
        queue.execute(() -> calls.add("RETR"), true);
        queue.execute(() -> calls.add("RNTO"), moveWorkers::add, false);
        queue.execute(() -> calls.add("PWD"), false);
        assertTrue(calls.isEmpty());
        transferWorkers.remove(0).run();
        assertEquals(Arrays.asList("RETR"), calls);
        assertEquals(1, moveWorkers.size());
        moveWorkers.remove(0).run();
        assertEquals(Arrays.asList("RETR", "RNTO", "PWD"), calls);
    }
    @Test public void queuedMoveIsNotDispatchedToItsLaneAfterCancellation() {
        List<Runnable> moveWorkers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        SessionCommandQueue queue = new SessionCommandQueue(task -> {});
        queue.execute(() -> calls.add("RNTO"), moveWorkers::add, false, true);
        assertEquals(1, moveWorkers.size());
        queue.cancelTransfers();
        moveWorkers.remove(0).run();
        assertEquals(Arrays.asList("RNTO"), calls);
        assertTrue(moveWorkers.isEmpty());
    }
    @Test public void overloadedLaneNeverRunsInline() {
        SessionCommandQueue queue = new SessionCommandQueue(task -> fail("wrong lane"));
        RejectedExecutionException lane = new RejectedExecutionException("lane full");
        try {
            queue.execute(() -> fail("must not run on the caller"), (Executor) task -> {
                throw lane;
            }, false);
            fail("Must reject");
        } catch (RejectedExecutionException expected) {
            assertSame(lane, expected);
        }
    }
    @Test public void pipelineHasFiniteCapacity() {
        SessionCommandQueue queue = new SessionCommandQueue(task -> {});
        for (int i = 0; i < 32; i++) queue.execute(() -> {}, true);
        try {
            queue.execute(() -> fail("must not execute"), true);
            fail("Must reject excessive pipeline");
        } catch (RejectedExecutionException expected) { }
    }
}
