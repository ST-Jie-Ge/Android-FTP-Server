package com.github.stjiege.ftpshare;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.utils.MySQLiteOpenHelper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AccountStorageRegressionTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void migrationPreservesLegacyDuplicatesAndRejectsNewOnes() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE ftp_account_table (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, password TEXT, path TEXT, writable INTEGER NOT NULL DEFAULT 1, full_storage INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("INSERT INTO ftp_account_table(name) VALUES('legacy'),('legacy')");
            new MySQLiteOpenHelper(context()).onUpgrade(db, 2, 3);
            assertEquals(2L, DatabaseUtils.queryNumEntries(db, "ftp_account_table"));
            ContentValues duplicate = new ContentValues();
            duplicate.put("name", "legacy");
            try {
                db.insertOrThrow("ftp_account_table", null, duplicate);
                fail("New duplicate must be rejected");
            } catch (SQLiteConstraintException expected) { }
            // A legacy record remains available and can be repaired by changing its name.
            ContentValues repaired = new ContentValues();
            repaired.put("name", "repaired");
            assertEquals(1, db.update("ftp_account_table", repaired, "_id=1", null));
            try {
                db.update("ftp_account_table", duplicate, "_id=1", null);
                fail("Renaming into another account must be rejected");
            } catch (SQLiteConstraintException expected) { }
        }
    }

    @Test public void concurrentRepositorySavesCannotCreateDuplicateNames() throws Exception {
        String name = "review-race-" + System.nanoTime();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successful = new AtomicInteger();
        Runnable save = () -> {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                AccountItem item = new AccountItem();
                item.account = name;
                item.password = "test";
                MySQLiteOpenHelper.saveOrUpdateAccountItem2DB(context(), item, null);
                successful.incrementAndGet();
            } catch (Exception expectedForOneWriter) {
                // The result below also verifies that at least one writer succeeds.
            } finally {
                done.countDown();
            }
        };
        try {
            new Thread(save, "account-race-1").start();
            new Thread(save, "account-race-2").start();
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertEquals(1, successful.get());
            try (SQLiteDatabase db = new MySQLiteOpenHelper(context()).getReadableDatabase()) {
                assertEquals(1L, DatabaseUtils.queryNumEntries(db, "ftp_account_table", "name=?", new String[]{name}));
            }
        } finally {
            try (SQLiteDatabase db = new MySQLiteOpenHelper(context()).getWritableDatabase()) {
                db.delete("ftp_account_table", "name=?", new String[]{name});
            }
        }
    }
}
