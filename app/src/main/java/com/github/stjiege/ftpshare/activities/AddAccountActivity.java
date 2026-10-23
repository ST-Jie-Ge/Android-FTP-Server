package com.github.stjiege.ftpshare.activities;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.services.FtpService;

public class AddAccountActivity extends AccountActivity {

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getSupportActionBar().setTitle(getResources().getString(R.string.activity_title_add));
        } catch (Exception e) {
            LogManager.warning("设置新建账户页标题失败: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void initializeAccountItem() {
        item = new AccountItem();
        item.account = "user";
        // Fill the numbered default name after reading the account count off the main thread.
        FtpService.getUserAccountListAsync(this, accounts -> {
            if (isFinishing() || isDestroyed() || !"user".equals(item.account)) return;
            String suggested = "user" + (accounts.size() + 1);
            item.account = suggested;
            originalAccount = suggested;
            if (tv_account != null) tv_account.setText(suggested);
        });
    }

    /** 新建页没有删除动作，底部只显示一个撑满的保存按钮。 */
    @Override
    protected void onSaveRequested() {
        save2DB(null);
    }
}

