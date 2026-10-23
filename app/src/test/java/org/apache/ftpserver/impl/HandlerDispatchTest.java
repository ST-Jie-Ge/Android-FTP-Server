package org.apache.ftpserver.impl;

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ftpserver.command.CommandFactory;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.ftpletcontainer.FtpletContainer;
import org.apache.ftpserver.message.MessageResourceFactory;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IdleStatus;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class HandlerDispatchTest {
    private static class Session extends FtpIoSession {
        volatile boolean connected = true;
        Session(FtpServerContext context) { super(new DummySession(), context); }
        @Override public boolean isLoggedIn() { return true; }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isClosing() { return !connected; }
        @Override public FtpSession getFtpletSession() { return null; }
        @Override public WriteFuture write(Object reply) {
            return DefaultWriteFuture.newWrittenFuture(this);
        }
    }
    private static class Fixture {
        final DefaultFtpHandler handler = new DefaultFtpHandler();
        final Session session;
        Fixture(CommandFactory commands, Executor executor) {
            this(commands, executor, null);
        }
        Fixture(CommandFactory commands, Executor executor, Executor copyExecutor) {
            FtpletContainer ftplets = (FtpletContainer) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{FtpletContainer.class},
                    (p, m, a) -> m.getReturnType() == FtpletResult.class ? FtpletResult.DEFAULT : null);
            Object messages = new MessageResourceFactory().createMessageResource();
            FtpServerContext context = (FtpServerContext) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{FtpServerContext.class}, (p, m, a) -> {
                        if ("getCommandFactory".equals(m.getName())) return commands;
                        if ("getFtpletContainer".equals(m.getName())) return ftplets;
                        if ("getMessageResource".equals(m.getName())) return messages;
                        return null;
                    });
            session = new Session(context);
            session.setAttribute("org.apache.ftpserver.data-connection",
                    new IODataConnectionFactory(null, session));
            DefaultFtpHandler.setTransferExecutor(executor);
            DefaultFtpHandler.setCopyExecutor(copyExecutor);
            handler.init(context, null);
        }
        void send(String command) throws Exception {
            handler.messageReceived(session, new DefaultFtpRequest(command));
        }
    }
    @After public void resetInjection() {
        DefaultFtpHandler.setTransferExecutor(null);
        DefaultFtpHandler.setCopyExecutor(null);
    }

    /**
     * sessionIdle 跑在 MINA 的 I/O 线程上，而 CloseFuture 的完成要靠这条线程自己执行
     * removeNow() —— 等它等于等自己去完成自己正在等的事。本机实测（真实 NioProcessor）：
     * await 版本稳定吃满 10 秒，期间整条 processor 上的会话停摆；不等则是 0 毫秒。
     */
    @Test public void idleCloseDoesNotWaitForTheCloseFuture() throws Exception {
        Fixture f = new Fixture(name -> (s, c, r) -> {}, Runnable::run);
        long start = System.nanoTime();
        f.handler.sessionIdle(f.session, IdleStatus.BOTH_IDLE);
        assertTrue("sessionIdle 不能同步等待关闭完成",
                System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
    }
    /** RNTO 必须离开 MINA 的 I/O 线程，且不能占用按秒设计的传输池。 */
    @Test public void moveCommandIsDispatchedToTheCopyLane() throws Exception {
        List<Runnable> transferWorkers = new ArrayList<>(), copyWorkers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> calls.add(r.getCommand()),
                transferWorkers::add, copyWorkers::add);
        f.send("RNTO target");
        assertTrue("RNTO 不能留在调用线程上", calls.isEmpty());
        assertEquals(1, copyWorkers.size());
        assertTrue("RNTO 不能挤占传输池", transferWorkers.isEmpty());
        copyWorkers.remove(0).run();
        assertEquals(Arrays.asList("RNTO"), calls);
    }
    @Test public void moveCommandUsesDefaultCopyPoolWhenNotInjected() throws Exception {
        List<Runnable> transferWorkers = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        List<String> threads = new CopyOnWriteArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> {
            threads.add(Thread.currentThread().getName()); done.countDown();
        },
                transferWorkers::add);
        f.send("RNTO target");
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(transferWorkers.isEmpty());
        assertTrue(threads.get(0).startsWith("FTP-Copy-"));
    }

    @Test public void aborCancelsQueuedMoveWithoutRunningItInline() throws Exception {
        List<Runnable> copyWorkers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> calls.add(r.getCommand()),
                Runnable::run, copyWorkers::add);
        f.send("RNTO target");
        f.send("ABOR");
        assertEquals(Arrays.asList("ABOR"), calls);
        copyWorkers.get(0).run();
        assertEquals(Arrays.asList("ABOR"), calls);
    }

    @Test public void actualHandlerKeepsCommandOrder() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> calls.add(r.getCommand()), workers::add);
        f.send("RETR file"); f.send("CWD other");
        assertTrue(calls.isEmpty());
        workers.remove(0).run();
        assertEquals(Arrays.asList("RETR", "CWD"), calls);
    }

    @Test public void moveActivityIsPublishedAndClearedEvenOnFailure() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> {
            assertEquals(Boolean.TRUE, s.getAttribute("ftpshare.move-active"));
            assertFalse(s.isTransferActive());
            throw new java.io.IOException("simulated move failure");
        }, Runnable::run, workers::add);
        f.send("RNTO target");
        workers.get(0).run();
        assertNull(f.session.getAttribute("ftpshare.move-active"));
    }

    @Test public void localMoveIsExemptFromControlIdleTimeout() throws Exception {
        Fixture f = new Fixture(name -> (s, c, r) -> {}, Runnable::run);
        f.session.setAttribute("ftpshare.move-active", Boolean.TRUE);
        f.handler.sessionIdle(f.session, IdleStatus.BOTH_IDLE);
        assertFalse(f.session.getCloseFuture().isClosed());
        f.session.removeAttribute("ftpshare.move-active");
        f.handler.sessionIdle(f.session, IdleStatus.BOTH_IDLE);
        assertTrue(f.session.getCloseFuture().isClosed());
    }
    @Test public void disconnectedSessionsDoNotExecuteQueuedCommands() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> calls.add(r.getCommand()), workers::add);
        f.send("STOR file"); f.session.connected = false;
        workers.remove(0).run();
        assertTrue(calls.isEmpty());
    }
    @Test public void listenerCapturesItsOwnExecutor() throws Exception {
        List<Runnable> first = new ArrayList<>(), second = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> {}, first::add);
        new Fixture(name -> (s, c, r) -> {}, second::add);
        f.send("RETR file");
        assertEquals(1, first.size()); assertTrue(second.isEmpty());
        first.remove(0).run();
    }
    @Test public void abortCancelsTransfersThatHaveNotStarted() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Fixture f = new Fixture(name -> (s, c, r) -> calls.add(r.getCommand()), workers::add);
        f.send("RETR file"); f.send("CWD other"); f.send("STOR next"); f.send("ABOR");
        workers.remove(0).run();
        assertEquals(Arrays.asList("CWD", "ABOR"), calls);
    }
    @Test public void abortClosesAcceptWithoutWaitingForEitherLock() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), aborted = new CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        try (ServerSocket socket = new ServerSocket(0)) {
            Fixture f = new Fixture(name -> (s, c, r) -> {
                calls.add(r.getCommand());
                if ("RETR".equals(name)) {
                    synchronized (s.peekDataConnection()) {
                        entered.countDown();
                        try { socket.accept(); fail("Expected cancellation"); }
                        catch (SocketException expected) { }
                    }
                } else if ("ABOR".equals(name)) {
                    aborted.countDown();
                }
            }, executor);
            IODataConnectionFactory data = new IODataConnectionFactory(null, f.session);
            data.servSoc = socket;
            f.session.setAttribute("org.apache.ftpserver.data-connection", data);
            f.send("RETR file");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            f.send("ABOR");
            assertTrue("ABOR must not block on transfer locks", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            assertTrue(aborted.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("RETR", "ABOR"), calls);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
