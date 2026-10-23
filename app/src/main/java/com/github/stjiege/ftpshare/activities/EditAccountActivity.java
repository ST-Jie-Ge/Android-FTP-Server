package com.github.stjiege.ftpshare.activities;

import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.MySQLiteOpenHelper;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.google.android.material.snackbar.Snackbar;

public class EditAccountActivity extends AccountActivity {

    public static final String EXTRA_SERIALIZED_ACCOUNT_ITEM = "account_item";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getSupportActionBar().setTitle(getResources().getString(R.string.activity_title_edit));
        } catch (Exception e) {
            LogManager.warning("设置账户编辑页标题失败: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void initializeAccountItem() {
        try {
            item = (AccountItem) getIntent().getSerializableExtra(EXTRA_SERIALIZED_ACCOUNT_ITEM);
        } catch (Exception e) {
            LogManager.error("读取待编辑账户失败", e);
            Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /** 已有账户才有删除动作；新建页没有。 */
    @Override
    protected boolean showDeleteAction() {
        return true;
    }

    @Override
    protected void onSaveRequested() {
        save2DB(this.item.id);
    }

    /**
     * 删除改用真正的确认弹窗。
     *
     * <p>旧实现是"1 秒内连点两次"——按钮在顶栏、和保存图标相邻、还没有文案，
     * 这种防误触强度对一个不可撤销的操作来说明显不够。</p>
     */
    @Override
    protected void onDeleteRequested() {
        if (FtpService.isFTPServiceRunning()) {
            Snackbar.make(findViewById(android.R.id.content), getResources().getString(R.string.attention_ftp_is_running), Snackbar.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.account_delete_confirm_title)
                .setMessage(R.string.account_delete_confirm_message)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setPositiveButton(R.string.action_account_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (deleteRow(EditAccountActivity.this.item.id) <= 0) {
                            Toast.makeText(EditAccountActivity.this, "Can not find the row", Toast.LENGTH_SHORT).show();
                        }
                        setResult(RESULT_OK);
                        finish();
                    }
                })
                .show();
    }

    private long deleteRow(long id) {
        try {
            return MySQLiteOpenHelper.deleteRow(this, id);
        } catch (Exception e) {
            LogManager.error("删除访问账户失败", e);
        }
        return -1;
    }

}

