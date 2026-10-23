package com.github.stjiege.ftpshare.activities;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.core.content.PermissionChecker;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.StorageUtil;
import com.google.android.material.snackbar.Snackbar;

/** Editor for the built-in anonymous account. It cannot be renamed or assigned a password. */
public class AnonymousAccountActivity extends BaseActivity {
    private String path;
    private CheckBox enabled;
    private CheckBox writable;
    private TextView pathValue;
    private String originalPath;
    private boolean originalEnabled;
    private boolean originalWritable;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installBackHandler();
        if (FtpService.isFTPServiceRunning()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_anonymous_account);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(Constants.FTPConsts.NAME_ANONYMOUS);
        }
        pathValue = findViewById(R.id.anonymous_account_path_value);
        android.content.SharedPreferences preferences = CommonUtils.getSettingSharedPreferences(this);
        path = StorageUtil.resolveHomeDirectory(this,
                preferences.getString(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH,
                        Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_DEFAULT));
        pathValue.setText(path);
        enabled = findViewById(R.id.anonymous_account_enabled_cb);
        enabled.setChecked(CommonUtils.getSettingSharedPreferences(this)
                .getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE, Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT));
        writable = findViewById(R.id.anonymous_account_writable_cb);
        writable.setChecked(CommonUtils.getSettingSharedPreferences(this)
                .getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE, Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE_DEFAULT));
        originalPath = path;
        originalEnabled = enabled.isChecked();
        originalWritable = writable.isChecked();
        findViewById(R.id.anonymous_account_enabled).setOnClickListener(v -> enabled.toggle());
        findViewById(R.id.anonymous_account_writable).setOnClickListener(v -> writable.toggle());
        findViewById(R.id.anonymous_account_path).setOnClickListener(v -> selectPath());
        findViewById(R.id.anonymous_account_action_save).setOnClickListener(v -> saveAnonymousSettings());
    }

    private void selectPath() {
        if (Build.VERSION.SDK_INT >= 30 && !StorageUtil.hasAllFilesAccess()) {
            Snackbar.make(findViewById(android.R.id.content), R.string.permission_all_files_access, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.snackbar_action_goto, v -> StorageUtil.openAllFilesAccessSettings(this)).show();
            return;
        }
        // minSdk 23，SDK_INT >= 23 恒真（原判断是 minSdk 更低时的遗留）。
        if (Build.VERSION.SDK_INT < 30
                && PermissionChecker.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PermissionChecker.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            return;
        }
        Intent intent = new Intent(this, FolderSelectorActivity.class);
        intent.putExtra(FolderSelectorActivity.getEXTRA_CURRENT_PATH(), path);
        intent.putExtra(FolderSelectorActivity.getEXTRA_RESTRICT_TO_STORAGE_ROOT(), true);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            String selected = data.getStringExtra(FolderSelectorActivity.getEXTRA_SELECTED_PATH());
            if (selected != null && !selected.trim().isEmpty()) {
                path = StorageUtil.resolveHomeDirectory(this, selected);
                pathValue.setText(path);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            checkChangesAndExit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 保存动作从 ActionBar 图标搬到底部按钮，逻辑原样保留。 */
    private void saveAnonymousSettings() {
            if (FtpService.isFTPServiceRunning()) {
                Snackbar.make(findViewById(android.R.id.content), R.string.attention_ftp_is_running, Snackbar.LENGTH_SHORT).show();
                return;
            }
            String selectedPath = StorageUtil.resolveHomeDirectory(this, path);
            if (selectedPath == null || selectedPath.trim().isEmpty()
                    || !new java.io.File(selectedPath).isDirectory()) {
                Snackbar.make(findViewById(android.R.id.content),
                        R.string.account_path_unusable, Snackbar.LENGTH_SHORT).show();
                return;
            }
            CommonUtils.getSettingSharedPreferences(this).edit()
                    .putString(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH, selectedPath)
                    .putBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_USER_SELECTED, true)
                    .putBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE, enabled.isChecked())
                    .putBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE, writable.isChecked())
                    // apply：这是主线程上的一次磁盘写，返回值本来也没被用。
                    // 落盘时机不必担心——系统在 pause/stop 时会等待 SharedPreferences 的
                    // 异步写完成，本 Activity 紧接着 setResult + finish，写不会丢。
                    .apply();
            setResult(RESULT_OK);
            finish();
    }

    /**
     * 返回键改走 AndroidX 的 OnBackPressedDispatcher —— 原先重写 onBackPressed() 且不调 super，
     * 在 Android 13+ 的预测式返回手势下会被当成"事件没人处理"（预览动画缺失、极端情况不退出）。
     * 执行体仍是 checkChangesAndExit()，行为等价。
     */
    private void installBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                checkChangesAndExit();
            }
        });
    }

    private void checkChangesAndExit() {
        boolean changed = !android.text.TextUtils.equals(originalPath, path)
                || originalEnabled != enabled.isChecked()
                || originalWritable != writable.isChecked();
        if (!changed) {
            finish();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("当前修改尚未保存，是否放弃？")
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setPositiveButton("放弃修改", (dialog, which) -> finish())
                .show();
    }
}

