package com.github.stjiege.ftpshare;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.activities.AddAccountActivity;
import com.github.stjiege.ftpshare.services.FtpService;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ServiceRestartRegressionTest {
    @Test public void queuedRestartRunsAfterOldListenerStops() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue(FtpService.isFTPServiceFullyStopped());
        SharedPreferences settings = context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE);
        int oldPort = settings.getInt(Constants.PreferenceConsts.PORT_NUMBER, Constants.PreferenceConsts.PORT_NUMBER_DEFAULT);
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        settings.edit().putInt(Constants.PreferenceConsts.PORT_NUMBER, port).commit();
        AddAccountActivity activity = (AddAccountActivity) instrumentation.startActivitySync(
                new Intent(context, AddAccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch firstStart = new CountDownLatch(1);
        CountDownLatch restarted = new CountDownLatch(1);
        CountDownLatch destroyed = new CountDownLatch(1);
        CountDownLatch allowStopWorker = new CountDownLatch(1);
        AtomicBoolean requestRestart = new AtomicBoolean(true);
        AtomicInteger starts = new AtomicInteger();
        FtpService.OnFTPServiceStatusChangedListener listener = new FtpService.OnFTPServiceStatusChangedListener() {
            @Override public void onFTPServiceStarted() {
                if (starts.incrementAndGet() == 1) firstStart.countDown();
                else restarted.countDown();
            }
            @Override public void onFTPServiceStartError(Exception error) { }
            @Override public void onFTPServiceDestroyed() {
                if (requestRestart.compareAndSet(true, false)) {
                    assertTrue(FtpService.startService(activity));
                    destroyed.countDown();
                }
            }
        };
        FtpService.addOnFtpServiceStatusChangedListener(listener);
        try {
            instrumentation.runOnMainSync(() -> assertTrue(FtpService.startService(activity)));
            assertTrue("Initial FTP startup timed out", firstStart.await(15, TimeUnit.SECONDS));
            Field executorField = FtpService.class.getDeclaredField("SERVER_EXECUTOR");
            executorField.setAccessible(true);
            ExecutorService executor = (ExecutorService) executorField.get(null);
            executor.execute(() -> {
                try { allowStopWorker.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            instrumentation.runOnMainSync(FtpService::stopService);
            assertTrue("Stop callback did not queue restart", destroyed.await(5, TimeUnit.SECONDS));
            allowStopWorker.countDown();
            assertTrue("Deferred restart never ran", restarted.await(15, TimeUnit.SECONDS));
            assertTrue(FtpService.isFTPServiceRunning());
            assertEquals(2, starts.get());
        } finally {
            allowStopWorker.countDown();
            FtpService.removeOnFtpServiceStatusChangedListener(listener);
            instrumentation.runOnMainSync(FtpService::stopService);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!FtpService.isFTPServiceFullyStopped() && System.nanoTime() < deadline) Thread.sleep(50);
            settings.edit().putInt(Constants.PreferenceConsts.PORT_NUMBER, oldPort).commit();
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
