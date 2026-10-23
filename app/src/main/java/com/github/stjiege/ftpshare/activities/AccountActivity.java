package com.github.stjiege.ftpshare.activities;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.PermissionChecker;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.utils.StorageUtil;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.util.regex.Pattern;

public abstract class AccountActivity extends BaseActivity {
    public AccountItem item;
    public TextView tv_account, tv_password, tv_path;
    public CheckBox cb_writable, cb_full_storage;
    protected String originalAccount;
    private String originalPassword;
    private String originalPath;
    private boolean originalFullStorage;
    private boolean saving;
    private static final String STATE_DRAFT = "account.draft";
    /** 自建账户的密码规则：不允许为空，至少 6 位，且只允许英文字母与数字。 */
    static final int PASSWORD_MIN_LENGTH = 6;
    private static final Pattern PASSWORD_ALLOWED = Pattern.compile("[A-Za-z0-9]+");
    /**
     * 输入时就把非字母数字的字符挡掉。
     *
     * 契约（见 InputFilter.filter 的官方文档）：返回 null = 原样接受这次改动；返回非 null 的
     * CharSequence = 用它替换 source[start, end)。文档同时明确要求"不要拒绝 0 长度替换，
     * 因为删除文本时就是 0 长度替换"——也就是删除时 source 为空，必须返回 null。
     * 早期版本无论什么情况都返回 kept.toString()（连删除也返回空串），删除被当成"用空串替换
     * 选区"，输入法回填后字符又被提交一次，表现就是删不掉、越删越多（a → aab → aabaabc）。
     */
    private static final InputFilter PASSWORD_CHARSET_FILTER = (source, start, end, dest, dstart, dend) -> {
        // 删除文本时 source 是空串，直接放行，绝不能返回 ""。
        if (end <= start) return null;
        StringBuilder kept = new StringBuilder(end - start);
        boolean allAllowed = true;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                kept.append(c);
            } else {
                allAllowed = false;
            }
        }
        // 全部合法就原样接受：少一次替换，也避开部分输入法整段回传时的重复提交。
        return allAllowed ? null : kept.toString();
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installBackHandler();
        setContentView(R.layout.activity_account);
        try {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        } catch (Exception e) {
            LogManager.warning("初始化账户编辑页失败: " + e.getClass().getSimpleName());
        }
        tv_account = findViewById(R.id.account_user_value);
        tv_password = findViewById(R.id.account_password_value);
        tv_path = findViewById(R.id.account_path_value);
        cb_writable = findViewById(R.id.account_writable_cb);
        cb_full_storage = findViewById(R.id.account_full_storage_cb);
        if (savedInstanceState != null && savedInstanceState.getSerializable(STATE_DRAFT) instanceof AccountItem) {
            item = (AccountItem) savedInstanceState.getSerializable(STATE_DRAFT);
        } else {
            initializeAccountItem();
        }
        try {
            if (item == null) item = new AccountItem();
            // Named accounts always have full read/write access. Keep the legacy
            // database field true, but do not expose a switch that has no effect.
            item.writable = true;
            tv_account.setText(item.account);
            tv_password.setText(getPasswordDisplayValue(item.password));
            cb_full_storage.setChecked(item.fullStorage);
            updateFullStorageUi();
            cb_writable.setChecked(true);
            findViewById(R.id.account_writable).setVisibility(View.GONE);
            originalAccount = item.account;
            originalPassword = item.password;
            originalPath = item.path;
            originalFullStorage = item.fullStorage;
            if (savedInstanceState != null) {
                originalAccount = savedInstanceState.getString("account.originalName", originalAccount);
                originalPassword = savedInstanceState.getString("account.originalPassword", originalPassword);
                originalPath = savedInstanceState.getString("account.originalPath", originalPath);
                originalFullStorage = savedInstanceState.getBoolean("account.originalFullStorage", originalFullStorage);
            }

            findViewById(R.id.account_user).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    View dialogView = LayoutInflater.from(AccountActivity.this).inflate(R.layout.layout_with_edittext, null);
                    final EditText editText = dialogView.findViewById(R.id.dialog_edittext);
                    editText.setText(item.account);
                    // 2026-09-30 改动：提示文字必须设在 TextInputLayout 上才会显示为浮动标签。
                    // 设在 EditText 上不生效 —— TextInputLayout 只在绑定子输入框的那一刻读取一次提示。
                    final TextInputLayout editTextLayout = dialogView.findViewById(R.id.dialog_edittext_layout);
                    editTextLayout.setHint(getResources().getString(R.string.account_user_dialog_edittext_hint));
                    final AlertDialog dialog = new AlertDialog.Builder(AccountActivity.this)
                            .setTitle(getResources().getString(R.string.account_user_dialog_title))
                            .setView(dialogView)
                            .setPositiveButton(getResources().getString(R.string.dialog_button_confirm), null)
                            .setNegativeButton(getResources().getString(R.string.dialog_button_cancel), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                }
                            })
                            .show();
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            item.account = editText.getText().toString().trim();
                            dialog.cancel();
                            tv_account.setText(item.account);
                        }
                    });
                }
            });

            findViewById(R.id.account_password).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    View dialogView = LayoutInflater.from(AccountActivity.this).inflate(R.layout.layout_with_edittext, null);
                    final EditText editText = dialogView.findViewById(R.id.dialog_edittext);
                    editText.setText(item.password);
                    editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
                    editText.setTag(true);
                    // 同上：提示设在 TextInputLayout 上才是浮动标签，设在 EditText 上不生效。
                    final TextInputLayout passwordLayout = dialogView.findViewById(R.id.dialog_edittext_layout);
                    passwordLayout.setHint(getResources().getString(R.string.account_password_dialog_edittext_hint));
                    // 追加而不是替换：布局里以后若再加 maxLength 之类的过滤器不会被顶掉。
                    InputFilter[] existing = editText.getFilters();
                    InputFilter[] merged = new InputFilter[existing.length + 1];
                    System.arraycopy(existing, 0, merged, 0, existing.length);
                    merged[existing.length] = PASSWORD_CHARSET_FILTER;
                    editText.setFilters(merged);
                    final AlertDialog dialog = new AlertDialog.Builder(AccountActivity.this)
                            .setTitle(getResources().getString(R.string.account_password_dialog_title))
                            .setView(dialogView)
                            .setPositiveButton(getResources().getString(R.string.dialog_button_confirm), null)
                            .setNegativeButton(getResources().getString(R.string.dialog_button_cancel), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                }
                            })
                            .setNeutralButton(getResources().getString(R.string.dialog_button_show_password), null)
                            .show();
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                Boolean b = (Boolean) editText.getTag();
                                editText.setTransformationMethod(b ? SingleLineTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
                                editText.setTag(!b);
                            } catch (Exception e) {
                            }
                        }
                    });
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String candidate = editText.getText().toString().trim();
                            String problem = passwordError(candidate);
                            if (problem != null) {
                                editText.setError(problem);
                                return;
                            }
                            item.password = candidate;
                            dialog.cancel();
                            tv_password.setText(getPasswordDisplayValue(item.password));
                        }
                    });
                }
            });

            findViewById(R.id.account_path).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 30 && !StorageUtil.hasAllFilesAccess()) {
                        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), getResources().getString(R.string.permission_all_files_access), Snackbar.LENGTH_INDEFINITE);
                        snackbar.setAction(getResources().getString(R.string.snackbar_action_goto), new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                StorageUtil.openAllFilesAccessSettings(AccountActivity.this);
                            }
                        });
                        snackbar.show();
                        return;
                    }
                    // minSdk 23，SDK_INT >= 23 恒真（原判断是 minSdk 更低时的遗留）。
                    if (Build.VERSION.SDK_INT < 30
                            && PermissionChecker.checkSelfPermission(AccountActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PermissionChecker.PERMISSION_GRANTED) {
                        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), getResources().getString(R.string.permission_write_external), Snackbar.LENGTH_SHORT);
                        snackbar.setAction(getResources().getString(R.string.snackbar_action_goto), new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Intent appdetail = new Intent();
                                appdetail.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                appdetail.setData(Uri.fromParts("package", getApplication().getPackageName(), null));
                                startActivity(appdetail);
                            }
                        });
                        snackbar.show();
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
                        return;
                    }
                    Intent intent = new Intent(AccountActivity.this, FolderSelectorActivity.class);
                    intent.putExtra(FolderSelectorActivity.getEXTRA_CURRENT_PATH(), StorageUtil.normalizeExistingPath(AccountActivity.this, item.path));
                    intent.putExtra(FolderSelectorActivity.getEXTRA_RESTRICT_TO_STORAGE_ROOT(), true);
                    startActivityForResult(intent, 0);
                }
            });

            findViewById(R.id.account_full_storage).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!item.fullStorage && !canUseFullStorage()) return;
                    item.fullStorage = !item.fullStorage;
                    cb_full_storage.setChecked(item.fullStorage);
                    updateFullStorageUi();
                }
            });

            findViewById(R.id.account_writable).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CheckBox cb = findViewById(R.id.account_writable_cb);
                    cb.toggle();
                    item.writable = cb.isChecked();
                }
            });

            bindActionButtons();
        } catch (Exception e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 保存 / 删除从 ActionBar 搬到底部操作栏。
     *
     * <p>原来它们是两个相邻的无文案图标，删除的二次确认还是"1 秒内连点两次"。
     * 底部布局给了两件事各自的位置：删除在左、弱化为危险色描边按钮，保存在右、主色实心。
     * 新建页没有删除，此时保存按钮撑满整行。</p>
     */
    private void bindActionButtons() {
        View delete = findViewById(R.id.account_action_delete);
        View save = findViewById(R.id.account_action_save);
        if (delete != null) {
            if (showDeleteAction()) {
                delete.setVisibility(View.VISIBLE);
                delete.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onDeleteRequested(); }
                });
            } else {
                delete.setVisibility(View.GONE);
            }
        }
        if (save != null) {
            if (delete == null || !showDeleteAction()) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) save.getLayoutParams();
                params.weight = 1f;
                save.setLayoutParams(params);
            }
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onSaveRequested(); }
            });
        }
    }

    /** 只有编辑已有账户的页面才有删除动作。 */
    protected boolean showDeleteAction() {
        return false;
    }

    protected void onDeleteRequested() {
    }

    protected void onSaveRequested() {
        save2DB(null);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (item != null) outState.putSerializable(STATE_DRAFT, new AccountItem(item));
        outState.putString("account.originalName", originalAccount);
        outState.putString("account.originalPassword", originalPassword);
        outState.putString("account.originalPath", originalPath);
        outState.putBoolean("account.originalFullStorage", originalFullStorage);
    }

    public abstract void initializeAccountItem();

    /** @return 密码不合规时返回提示文案，合规返回 null。 */
    private String passwordError(String password) {
        if (password == null || password.length() == 0) return getString(R.string.account_password_empty);
        if (password.length() < PASSWORD_MIN_LENGTH)
            return getString(R.string.account_password_too_short, PASSWORD_MIN_LENGTH);
        if (!PASSWORD_ALLOWED.matcher(password).matches()) return getString(R.string.account_password_charset);
        return null;
    }

    private String getPasswordDisplayValue(String password) {
        if (password == null || password.trim().length() == 0)
            return getResources().getString(R.string.account_password_att);
        StringBuilder builder = new StringBuilder("");
        for (int i = 0; i < password.length(); i++) {
            builder.append("*");
            if (i > 16) break;
        }
        return builder.toString();
    }

    /** Complete storage needs the same broad storage permission as directory selection. */
    private boolean canUseFullStorage() {
        if (Build.VERSION.SDK_INT >= 30 && !StorageUtil.hasAllFilesAccess()) {
            Snackbar.make(findViewById(android.R.id.content), R.string.permission_all_files_access,
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.snackbar_action_goto,
                            v -> StorageUtil.openAllFilesAccessSettings(AccountActivity.this))
                    .show();
            return false;
        }
        // minSdk 23，SDK_INT >= 23 恒真（原判断是 minSdk 更低时的遗留）。
        if (Build.VERSION.SDK_INT < 30
                && PermissionChecker.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PermissionChecker.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            return false;
        }
        return true;
    }

    private void updateFullStorageUi() {
        View pathArea = findViewById(R.id.account_path);
        boolean customPathEnabled = !item.fullStorage;
        pathArea.setEnabled(customPathEnabled);
        pathArea.setAlpha(customPathEnabled ? 1f : 0.45f);
        tv_path.setText(customPathEnabled ? item.path : getString(R.string.account_full_storage_value));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0 && resultCode == RESULT_OK && data != null) {
            final String path = data.getStringExtra(FolderSelectorActivity.getEXTRA_SELECTED_PATH());
            item.path = path;
            ((TextView) findViewById(R.id.account_path_value)).setText(path);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                checkChangesAndExit();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    public void save2DB(@Nullable Long id_update) {
        if (saving) return;
        if (!FtpService.isFTPServiceFullyStopped()) {
            Snackbar.make(findViewById(android.R.id.content), getResources().getString(R.string.attention_ftp_is_running), Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (this.item.account.equals("")) {
            Snackbar.make(findViewById(android.R.id.content), getResources().getString(R.string.account_null_att), Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (this.item.account.equals(Constants.FTPConsts.NAME_ANONYMOUS)) {
            Snackbar.make(findViewById(android.R.id.content), getResources().getString(R.string.account_anonymous_name_set_att), Snackbar.LENGTH_SHORT).show();
            return;
        }
        String passwordProblem = passwordError(this.item.password);
        if (passwordProblem != null) {
            Snackbar.make(findViewById(android.R.id.content), passwordProblem, Snackbar.LENGTH_SHORT).show();
            return;
        }
        // 与匿名账户一致：保存前先确认目录可用。解析规则用服务端同一套
        // （StorageUtil.resolveHomeDirectory），避免 UI 判定与运行时行为各说一套。
        if (!this.item.fullStorage) {
            String resolvedHome = StorageUtil.resolveHomeDirectory(this, this.item.path);
            if (resolvedHome == null || resolvedHome.trim().isEmpty()
                    || !new java.io.File(resolvedHome).isDirectory()) {
                Snackbar.make(findViewById(android.R.id.content),
                        getResources().getString(R.string.account_path_unusable), Snackbar.LENGTH_SHORT).show();
                return;
            }
            this.item.path = resolvedHome;
            tv_path.setText(resolvedHome);
        }
        saving = true;
        this.item.writable = true;
        FtpService.saveAccountAsync(this, this.item, id_update, (success, error) -> {
            saving = false;
            if (isFinishing() || isDestroyed()) return;
            if (success) {
                setResult(RESULT_OK);
                finish();
            } else if (error != null) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    void checkChangesAndExit() {
        if (hasUnsavedChanges()) {
            new AlertDialog.Builder(this)
                    .setMessage("当前修改尚未保存，是否放弃？")
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .setPositiveButton("放弃修改", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            discardAndFinish();
                        }
                    }).show();
            return;
        }
        discardAndFinish();
    }

    /**
     * 返回键改走 AndroidX 的 OnBackPressedDispatcher。
     *
     * 原先是直接重写 Activity.onBackPressed() 且不调 super —— 那套写法在 Android 13+ 的
     * 预测式返回手势下会被系统当成"事件没人处理"：返回预览动画缺失，极端情况 Activity 不退出。
     * 迁移后执行体仍是 checkChangesAndExit()，行为等价，且全版本由 AndroidX 统一调度。
     */
    private void installBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                checkChangesAndExit();
            }
        });
    }

    private boolean hasUnsavedChanges() {
        return !TextUtils.equals(originalAccount, item.account)
                || !TextUtils.equals(originalPassword, item.password)
                || !TextUtils.equals(originalPath, item.path)
                || originalFullStorage != item.fullStorage;
    }

    private void discardAndFinish() {
        setResult(RESULT_CANCELED);
        finish();
    }
}

