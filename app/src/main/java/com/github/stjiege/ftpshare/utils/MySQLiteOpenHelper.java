package com.github.stjiege.ftpshare.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.DatabaseUtils;
import com.github.stjiege.ftpshare.R;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.data.AccountItem;

public class MySQLiteOpenHelper extends SQLiteOpenHelper {


    public MySQLiteOpenHelper(Context context) {
        super(context, Constants.SQLConsts.SQL_USERS_FILENAME, null, Constants.SQLConsts.SQL_VERSION);
    }

    /**
     * 插入或者更新AccountItem到数据库
     *
     * @param id_update 不为空则更新指定行，指定为item.id即可
     * @return 执行结果
     */
    public static long saveOrUpdateAccountItem2DB(@NonNull Context context, AccountItem item, @Nullable Long id_update) throws Exception {
        SQLiteDatabase db = new MySQLiteOpenHelper(context).getWritableDatabase();
        db.beginTransaction();
        try {
            String duplicateWhere = Constants.SQLConsts.COLUMN_ACCOUNT_NAME + "=?"
                    + (id_update == null ? "" : " AND " + Constants.SQLConsts.COLUMN_ID + "<>?");
            String[] duplicateArgs = id_update == null ? new String[]{item.account}
                    : new String[]{item.account, String.valueOf(id_update)};
            if (DatabaseUtils.queryNumEntries(db, Constants.SQLConsts.TABLE_NAME, duplicateWhere, duplicateArgs) > 0) {
                throw new IllegalArgumentException(context.getString(R.string.account_duplicate));
            }
            if (id_update == null && DatabaseUtils.queryNumEntries(db, Constants.SQLConsts.TABLE_NAME)
                    >= Constants.FTPConsts.MAX_NAMED_ACCOUNTS) {
                throw new IllegalArgumentException(context.getString(R.string.account_limit_reached));
            }
            ContentValues contentValues = new ContentValues();
            contentValues.put(Constants.SQLConsts.COLUMN_ACCOUNT_NAME, item.account);
            contentValues.put(Constants.SQLConsts.COLUMN_PASSWORD, PasswordCipher.encrypt(item.password));
            contentValues.put(Constants.SQLConsts.COLUMN_PATH, item.path);
            contentValues.put(Constants.SQLConsts.COLUMN_WRITABLE, item.writable ? 1 : 0);
            contentValues.put(Constants.SQLConsts.COLUMN_FULL_STORAGE, item.fullStorage ? 1 : 0);
            long result = id_update == null ? db.insertOrThrow(Constants.SQLConsts.TABLE_NAME, null, contentValues)
                    : db.update(Constants.SQLConsts.TABLE_NAME, contentValues,
                            Constants.SQLConsts.COLUMN_ID + "=?", new String[]{String.valueOf(id_update)});
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    /**
     * 根据ID值删除指定行
     */
    public static long deleteRow(Context context, long id) {
        SQLiteDatabase database = new MySQLiteOpenHelper(context).getWritableDatabase();
        try {
            return database.delete(Constants.SQLConsts.TABLE_NAME,
                    Constants.SQLConsts.COLUMN_ID + "=?", new String[]{String.valueOf(id)});
        } finally {
            database.close();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table if not exists " + Constants.SQLConsts.TABLE_NAME + " ("
                + Constants.SQLConsts.COLUMN_ID + " integer primary key autoincrement not null,"
                + Constants.SQLConsts.COLUMN_ACCOUNT_NAME + " text,"
                + Constants.SQLConsts.COLUMN_PASSWORD + " text,"
                + Constants.SQLConsts.COLUMN_PATH + " text,"
                + Constants.SQLConsts.COLUMN_WRITABLE + " integer not null default 1,"
                + Constants.SQLConsts.COLUMN_FULL_STORAGE + " integer not null default 0);");
        createAccountNameGuards(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("alter table " + Constants.SQLConsts.TABLE_NAME + " add column "
                    + Constants.SQLConsts.COLUMN_FULL_STORAGE + " integer not null default 0;");
        }
        if (oldVersion < 3) createAccountNameGuards(db);
    }

    private static void createAccountNameGuards(SQLiteDatabase db) {
        // Existing duplicate records are preserved for user repair. Triggers prevent new
        // duplicates without a migration that deletes or silently renames credentials.
        String table = Constants.SQLConsts.TABLE_NAME;
        String name = Constants.SQLConsts.COLUMN_ACCOUNT_NAME;
        String id = Constants.SQLConsts.COLUMN_ID;
        db.execSQL("CREATE TRIGGER IF NOT EXISTS account_name_insert BEFORE INSERT ON " + table
                + " WHEN EXISTS (SELECT 1 FROM " + table + " WHERE " + name + "=NEW." + name + ")"
                + " BEGIN SELECT RAISE(ABORT, 'Duplicate FTP account'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS account_name_update BEFORE UPDATE OF " + name + " ON " + table
                + " WHEN NEW." + name + "<>OLD." + name + " AND EXISTS (SELECT 1 FROM " + table
                + " WHERE " + name + "=NEW." + name + " AND " + id + "<>OLD." + id + ")"
                + " BEGIN SELECT RAISE(ABORT, 'Duplicate FTP account'); END");
    }

}

