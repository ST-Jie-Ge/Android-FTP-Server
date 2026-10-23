package com.github.stjiege.ftpshare;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.app.Instrumentation;
import android.content.Intent;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.Stage;
import java.util.function.Consumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.activities.AddAccountActivity;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.MySQLiteOpenHelper;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReviewRegressionTest {
 private static final class Scenario implements AutoCloseable {
  private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  private AddAccountActivity activity;
  Scenario() {
   Intent intent=new Intent(instrumentation.getTargetContext(),AddAccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   activity=(AddAccountActivity)instrumentation.startActivitySync(intent);
   instrumentation.waitForIdleSync();
  }
  void onActivity(Consumer<AddAccountActivity> action) { instrumentation.runOnMainSync(() -> action.accept(activity)); }
  void recreate() throws Exception {
   AddAccountActivity old=activity; CountDownLatch resumed=new CountDownLatch(1);
   ActivityLifecycleCallback callback=(a,stage) -> { if(a instanceof AddAccountActivity && a!=old && stage==Stage.RESUMED){activity=(AddAccountActivity)a;resumed.countDown();} };
   instrumentation.runOnMainSync(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback));
   try { onActivity(a -> a.recreate()); assertTrue("Activity recreation timed out",resumed.await(10,TimeUnit.SECONDS));instrumentation.waitForIdleSync(); }
   finally { instrumentation.runOnMainSync(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)); }
  }
  public void close() { onActivity(a -> {if(!a.isFinishing())a.finish();}); instrumentation.waitForIdleSync(); }
 }
 private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
 private void drainAccounts() throws Exception {
  CountDownLatch done = new CountDownLatch(1);
  FtpService.getUserAccountListAsync(context(), accounts -> done.countDown());
  assertTrue(done.await(10, TimeUnit.SECONDS));
  InstrumentationRegistry.getInstrumentation().waitForIdleSync();
 }
 private int count(String name) {
  try(SQLiteDatabase db = new MySQLiteOpenHelper(context()).getReadableDatabase();
      Cursor c = db.rawQuery("SELECT COUNT(*) FROM ftp_account_table WHERE name=?", new String[]{name})) {
   c.moveToFirst(); return c.getInt(0);
  }
 }
 private void remove(String name) {
  try(SQLiteDatabase db = new MySQLiteOpenHelper(context()).getWritableDatabase()) {
   db.delete("ftp_account_table", "name=?", new String[]{name});
  }
 }
 @Test public void doubleSaveCreatesOneAccount() throws Exception {
  String name = "review-double-" + System.nanoTime();
  try(Scenario scenario = new Scenario()) {
   drainAccounts();
   scenario.onActivity(a -> { a.item.account=name; a.item.password="test"; a.save2DB(null); a.save2DB(null); });
   // Read callbacks enqueue writes, so wait until both asynchronous phases have drained.
   drainAccounts(); drainAccounts();
   assertEquals("Two clicks must create one account", 1, count(name));
  } finally { remove(name); }
 }
 @Test public void recreationPreservesDraft() throws Exception {
  try(Scenario scenario = new Scenario()) {
   drainAccounts();
   scenario.onActivity(a -> { a.item.account="review-draft"; a.item.password="draft-secret"; a.item.path="/storage/emulated/0/Download"; a.item.fullStorage=true; });
   scenario.recreate(); drainAccounts();
   scenario.onActivity(a -> { assertEquals("review-draft",a.item.account); assertEquals("draft-secret",a.item.password); assertEquals("/storage/emulated/0/Download",a.item.path); assertTrue(a.item.fullStorage); });
  }
 }
 @Test public void restartWhileStoppingDoesNotBlockMainThread() throws Exception {
  Field state = FtpService.class.getDeclaredField("lifecycleState");state.setAccessible(true);
  Object oldState=state.get(null);
  assertTrue(FtpService.isFTPServiceFullyStopped());
  Object stopping=Enum.valueOf((Class)oldState.getClass(),"STOPPING");
  long[] elapsed = new long[1];
  try {
   state.set(null,stopping);
   InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
    long before=SystemClock.elapsedRealtime(); FtpService.startService(context()); elapsed[0]=SystemClock.elapsedRealtime()-before;
   });
   assertTrue("Main-thread startup waited " + elapsed[0] + " ms",elapsed[0]<300);
  } finally { FtpService.stopService(); state.set(null,oldState); }
 }
}
