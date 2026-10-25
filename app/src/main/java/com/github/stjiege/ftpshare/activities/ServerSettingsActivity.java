package com.github.stjiege.ftpshare.activities;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.MyApplication;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.ui.IntSliderDialog;
import com.github.stjiege.ftpshare.ui.RadioSelectionDialog;
import com.github.stjiege.ftpshare.ui.SegmentedChoiceDialog;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 服务端设置二级页。端口、连接数、性能回收、工作线程数、并发传输数、分块大小
 * 原先都摊在 FTP 主界面上，现在统一收进这里，主界面只保留一个「服务端设置」入口。
 *
 * 每项依旧沿用原来的前置校验：能改与不能改的边界不变（端口 / 连接数要求服务未运行，
 * 性能回收 / 线程数 / 并发数要求服务完全停止，分块大小随时可改）。
 */
public class ServerSettingsActivity extends BaseActivity implements View.OnClickListener {

    private final SharedPreferences settings =
            CommonUtils.getSettingSharedPreferences(MyApplication.getGlobalBaseContext());

    private View root;
    private TextView tvPort;
    private TextView tvAnonymousNum;
    private TextView tvLoginNum;
    private TextView tvReclaim;
    private TextView tvWorkerThreads;
    private TextView tvTransferSlots;
    private TextView tvTransferBuffer;
    private TextView tvCharset;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_server_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.item_server_settings);
        }
        root = findViewById(R.id.server_settings_scroll);
        tvPort = findViewById(R.id.port_value);
        tvAnonymousNum = findViewById(R.id.anonymous_connect_num_value);
        tvLoginNum = findViewById(R.id.login_connect_num_value);
        tvReclaim = findViewById(R.id.performance_reclaim_value);
        tvWorkerThreads = findViewById(R.id.worker_threads_value);
        tvTransferSlots = findViewById(R.id.transfer_slots_value);
        tvTransferBuffer = findViewById(R.id.transfer_buffer_value);
        tvCharset = findViewById(R.id.charset_value);

        findViewById(R.id.port_area).setOnClickListener(this);
        findViewById(R.id.anonymous_connect_num_area).setOnClickListener(this);
        findViewById(R.id.login_connect_num_area).setOnClickListener(this);
        findViewById(R.id.performance_reclaim_area).setOnClickListener(this);
        findViewById(R.id.worker_threads_area).setOnClickListener(this);
        findViewById(R.id.transfer_slots_area).setOnClickListener(this);
        findViewById(R.id.transfer_buffer_area).setOnClickListener(this);
        findViewById(R.id.charset_area).setOnClickListener(this);
        refreshContents();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.port_area:
                showPortDialog();
                break;
            case R.id.anonymous_connect_num_area:
                if (FtpService.isFTPServiceRunning()) {
                    CommonUtils.showSnackBarOfFtpServiceIsRunning(this);
                    return;
                }
                inlineMethod(Constants.PreferenceConsts.MAX_ANONYMOUS_NUM, R.string.item_anonymous_num);
                break;
            case R.id.login_connect_num_area:
                showMaxLoginDialog();
                break;
            case R.id.performance_reclaim_area:
                showPerformanceReclaimDialog();
                break;
            case R.id.worker_threads_area:
                showWorkerThreadsDialog();
                break;
            case R.id.transfer_slots_area:
                showTransferSlotsDialog();
                break;
            case R.id.transfer_buffer_area:
                showTransferBufferDialog();
                break;
            case R.id.charset_area:
                showCharsetDialog();
                break;
            default:
                break;
        }
    }

    private void refreshContents() {
        tvPort.setText(String.valueOf(settings.getInt(Constants.PreferenceConsts.PORT_NUMBER,
                Constants.PreferenceConsts.PORT_NUMBER_DEFAULT)));
        tvAnonymousNum.setText(String.valueOf(settings.getInt(Constants.PreferenceConsts.MAX_ANONYMOUS_NUM,
                Constants.PreferenceConsts.MAX_ANONYMOUS_NUM_DEFAULT)));
        tvLoginNum.setText(maxLoginLabel(settings.getInt(Constants.PreferenceConsts.MAX_LOGIN_NUM,
                Constants.PreferenceConsts.MAX_LOGIN_NUM_DEFAULT)));
        tvReclaim.setText(reclaimLabel(idleTimeout()));
        tvWorkerThreads.setText(getString(R.string.item_worker_threads_value, settings.getInt(
                Constants.PreferenceConsts.FTP_WORKER_THREADS,
                Constants.PreferenceConsts.FTP_WORKER_THREADS_DEFAULT)));
        tvTransferSlots.setText(getString(R.string.item_transfer_slots_value, transferSlots()));
        tvTransferBuffer.setText(getString(R.string.item_transfer_buffer_value, transferBuffer() / 1024));
        tvCharset.setText(CommonUtils.getDisplayCharsetValue(this));
    }

    private void showPortDialog() {
        if (FtpService.isFTPServiceRunning()) {
            CommonUtils.showSnackBarOfFtpServiceIsRunning(this);
            return;
        }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.layout_with_edittext, null);
        final EditText edit = dialogView.findViewById(R.id.dialog_edittext);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setSingleLine(true);
        // 2026-09-30 改动：提示文字设在 TextInputLayout 上才会显示为浮动标签；
        // 设在 EditText 上不生效（TextInputLayout 只在绑定子输入框时读取一次提示）。
        final TextInputLayout portLayout = dialogView.findViewById(R.id.dialog_edittext_layout);
        portLayout.setHint(getString(R.string.item_port_hint));
        edit.setText(String.valueOf(settings.getInt(Constants.PreferenceConsts.PORT_NUMBER,
                Constants.PreferenceConsts.PORT_NUMBER_DEFAULT)));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.item_port))
                .setMessage(getString(R.string.attention_low_port_requires_root))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_button_confirm), null)
                .setNegativeButton(getString(R.string.dialog_button_cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .show();
        com.github.stjiege.ftpshare.ui.ModernDialogStyle.apply(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int port;
                try {
                    port = Integer.parseInt(edit.getText().toString().trim());
                    if (!(port >= 1024 && port <= 65535)) {
                        Toast.makeText(ServerSettingsActivity.this,
                                getString(R.string.attention_port_number_out_of_range), Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (Exception e) {
                    Toast.makeText(ServerSettingsActivity.this,
                            getString(R.string.attention_invalid_port_number), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (FtpService.isFTPServiceRunning()) {
                    Toast.makeText(ServerSettingsActivity.this,
                            getString(R.string.attention_ftp_is_running), Toast.LENGTH_SHORT).show();
                    return;
                }
                settings.edit().putInt(Constants.PreferenceConsts.PORT_NUMBER, port).apply();
                dialog.cancel();
                tvPort.setText(String.valueOf(port));
            }
        });
    }

    /** 匿名连接数那一行的数字输入框。 */
    private void inlineMethod(String key, int titleRes) {
        final AlertDialog dialog = createAndShowEditDialog(titleRes);
        dialog.show();
        EditText editText = dialog.findViewById(R.id.dialog_edittext);
        editText.setKeyListener(DigitsKeyListener.getInstance("1234567890"));
        editText.setText(String.valueOf(settings.getInt(key,
                Constants.PreferenceConsts.MAX_ANONYMOUS_NUM_DEFAULT)));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int value;
                try {
                    value = Integer.parseInt(editText.getText().toString());
                } catch (Exception e) {
                    Toast.makeText(ServerSettingsActivity.this,
                            getString(R.string.toast_invalid_value), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (value <= 0) {
                    Toast.makeText(ServerSettingsActivity.this,
                            getString(R.string.toast_invalid_value), Toast.LENGTH_SHORT).show();
                    return;
                }
                settings.edit().putInt(key, value).apply();
                dialog.cancel();
                refreshContents();
            }
        });
    }

    private AlertDialog createAndShowEditDialog(int titleRes) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.layout_with_edittext, null);
        final EditText edit = dialogView.findViewById(R.id.dialog_edittext);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setSingleLine(true);
        // 同上：提示设在 TextInputLayout 上才是浮动标签。
        final TextInputLayout maxNumLayout = dialogView.findViewById(R.id.dialog_edittext_layout);
        maxNumLayout.setHint(getString(R.string.item_max_num_hint));
        edit.setText(String.valueOf(settings.getInt(Constants.PreferenceConsts.MAX_ANONYMOUS_NUM,
                Constants.PreferenceConsts.MAX_ANONYMOUS_NUM_DEFAULT)));
        return new AlertDialog.Builder(this)
                .setTitle(getString(titleRes))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_button_confirm), null)
                .setNegativeButton(getString(R.string.dialog_button_cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .create();
    }

    /** 最大连接数：严格 / 正常 / 高 / 极高 共 4 档，改成分块按钮。档位名保持原样，不追加具体数值。 */
    private void showMaxLoginDialog() {
        if (FtpService.isFTPServiceRunning()) {
            CommonUtils.showSnackBarOfFtpServiceIsRunning(this);
            return;
        }
        final int[] values = {Constants.PreferenceConsts.MAX_LOGIN_NUM_STRICT,
                Constants.PreferenceConsts.MAX_LOGIN_NUM_NORMAL,
                Constants.PreferenceConsts.MAX_LOGIN_NUM_HIGH,
                Constants.PreferenceConsts.MAX_LOGIN_NUM_EXTREME};
        final int[] labels = {R.string.login_num_strict, R.string.login_num_normal,
                R.string.login_num_high, R.string.login_num_extreme};
        int current = settings.getInt(Constants.PreferenceConsts.MAX_LOGIN_NUM,
                Constants.PreferenceConsts.MAX_LOGIN_NUM_DEFAULT);
        // 存的值不在预设档位里就不预选：高亮"最接近"的档位等于替用户选了他没选过的值。
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }
        CharSequence[] items = new CharSequence[labels.length];
        for (int i = 0; i < labels.length; i++) {
            items[i] = getString(labels[i]);
        }
        // 点一下就生效，弹窗随即关闭；前置校验仍在打开弹窗时做一次（与改造前一致）。
        SegmentedChoiceDialog.show(this, R.string.item_login_num, items, checked, which -> {
            settings.edit().putInt(Constants.PreferenceConsts.MAX_LOGIN_NUM, values[which]).apply();
            refreshContents();
        });
    }

    private String maxLoginLabel(int value) {
        if (value == Constants.PreferenceConsts.MAX_LOGIN_NUM_STRICT) return getString(R.string.login_num_strict);
        if (value == Constants.PreferenceConsts.MAX_LOGIN_NUM_NORMAL) return getString(R.string.login_num_normal);
        if (value == Constants.PreferenceConsts.MAX_LOGIN_NUM_HIGH) return getString(R.string.login_num_high);
        if (value == Constants.PreferenceConsts.MAX_LOGIN_NUM_EXTREME) return getString(R.string.login_num_extreme);
        return getString(R.string.login_num_custom);
    }

    private int idleTimeout() {
        return Constants.PreferenceConsts.normalizeIdleTimeout(settings.getInt(
                Constants.PreferenceConsts.FTP_IDLE_TIMEOUT, Constants.PreferenceConsts.FTP_IDLE_TIMEOUT_DEFAULT));
    }

    private int reclaimLabel(int value) {
        return value == 300 ? R.string.reclaim_normal : value == 900 ? R.string.reclaim_light : R.string.reclaim_off;
    }

    /** 性能回收：关闭 / 正常 / 轻度 共 3 档，改成分块按钮。档位名保持原样，不追加秒数。 */
    private void showPerformanceReclaimDialog() {
        if (!FtpService.isFTPServiceFullyStopped()) {
            Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped, Snackbar.LENGTH_LONG).show();
            return;
        }
        final int[] values = {0, 300, 900};
        int current = idleTimeout();
        CharSequence[] items = {getString(R.string.reclaim_off),
                getString(R.string.reclaim_normal), getString(R.string.reclaim_light)};
        SegmentedChoiceDialog.show(this, R.string.item_performance_reclaim, items,
                current == 300 ? 1 : current == 900 ? 2 : 0, which -> {
                    if (!FtpService.isFTPServiceFullyStopped()) {
                        Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    settings.edit().putInt(Constants.PreferenceConsts.FTP_IDLE_TIMEOUT, values[which]).apply();
                    refreshContents();
                });
    }

    /**
     * 最大工作线程数。它同时供控制命令与数据传输使用，因此与「并发传输数」存在**双向**约束：
     * 传输槽不得超过「workers − 4」，worker 数也不得低于「slots + 4」，否则满载的传输会把
     * worker 全部占满，新连接连登录命令都排不到队。两端的余量同为
     * {@link Constants.FTPConsts#FTP_TRANSFER_SLOTS_WORKER_RESERVE}。
     */
    private void showWorkerThreadsDialog() {
        if (!FtpService.isFTPServiceFullyStopped()) {
            Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped, Snackbar.LENGTH_LONG).show();
            return;
        }
        int current = settings.getInt(Constants.PreferenceConsts.FTP_WORKER_THREADS,
                Constants.PreferenceConsts.FTP_WORKER_THREADS_DEFAULT);
        final int safeMin = transferSlots() + Constants.FTPConsts.FTP_TRANSFER_SLOTS_WORKER_RESERVE;
        IntSliderDialog.show(this, R.string.item_worker_threads, 0,
                current,
                Constants.PreferenceConsts.FTP_WORKER_THREADS_MIN,
                Constants.PreferenceConsts.FTP_WORKER_THREADS_MAX,
                R.string.item_worker_threads_range,
                value -> value < safeMin, R.string.item_worker_threads_warning,
                value -> {
                    if (!FtpService.isFTPServiceFullyStopped()) {
                        Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    if (value < safeMin) {
                        // 拒绝本次修改：不落盘、不改界面读数，只提示原因。
                        Snackbar.make(root, R.string.item_worker_threads_rejected,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    settings.edit().putInt(Constants.PreferenceConsts.FTP_WORKER_THREADS, value).apply();
                    tvWorkerThreads.setText(getString(R.string.item_worker_threads_value, value));
                });
    }

    private int transferSlots() {
        return Math.max(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MIN, Math.min(
                Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MAX, settings.getInt(
                        Constants.PreferenceConsts.FTP_TRANSFER_SLOTS,
                        Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_DEFAULT)));
    }

    /** 只有 64 KB / 256 KB 两档，存了非法值就退回 64 KB。 */
    private int transferBuffer() {
        int stored = settings.getInt(Constants.PreferenceConsts.FTP_TRANSFER_BUFFER,
                Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_DEFAULT);
        if (stored == Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_TINY) {
            return Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_TINY;
        }
        return stored == Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_LARGE
                ? Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_LARGE
                : Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_SMALL;
    }

    /** 块大小在每次开传前才读取；改档不影响正在传输的文件，也不要求停止服务。 */
    private void showTransferBufferDialog() {
        final int[] values = {Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_TINY,
                Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_SMALL,
                Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_LARGE};
        int current = transferBuffer();
        CharSequence[] items = {
                getString(R.string.item_transfer_buffer_value, values[0] / 1024),
                getString(R.string.item_transfer_buffer_value, values[1] / 1024),
                getString(R.string.item_transfer_buffer_value, values[2] / 1024)};
        SegmentedChoiceDialog.show(this, R.string.item_transfer_buffer, items,
                current == values[0] ? 0 : current == values[1] ? 1 : 2, which -> {
                    int value = values[which];
                    settings.edit().putInt(Constants.PreferenceConsts.FTP_TRANSFER_BUFFER, value).apply();
                    com.github.stjiege.ftpshare.filesystem.TransferBuffer.configure(value);
                    tvTransferBuffer.setText(getString(R.string.item_transfer_buffer_value, value / 1024));
                });
    }

    /**
     * 并发传输数：值域是一段连续区间，用滑块——分块按钮留给「严格 / 正常 / 高」这类离散档位，
     * 把 4–8 拉成一排反而每块只剩二十几像素，点不准。
     *
     * 上限受「工作线程数 − 4」的余量约束（FTP_TRANSFER_SLOTS_WORKER_RESERVE）：
     * 拖动时越过余量在弹窗内亮一行预警；点确定若仍在越界位置则**直接拒绝** —— 不写入配置、
     * 界面读数保持原样，改在页面底部弹一条黑底横幅说明原因，避免把服务推向
     * 「满载时新连接排不到队、登录超时」的状态。
     */
    private void showTransferSlotsDialog() {
        if (!FtpService.isFTPServiceFullyStopped()) {
            Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped, Snackbar.LENGTH_LONG).show();
            return;
        }
        int workers = settings.getInt(Constants.PreferenceConsts.FTP_WORKER_THREADS,
                Constants.PreferenceConsts.FTP_WORKER_THREADS_DEFAULT);
        final int safeMax = workers - Constants.FTPConsts.FTP_TRANSFER_SLOTS_WORKER_RESERVE;
        IntSliderDialog.show(this, R.string.item_transfer_slots, R.string.item_transfer_slots_att,
                transferSlots(), Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MIN,
                Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MAX, R.string.item_transfer_slots_range,
                value -> value > safeMax, R.string.item_transfer_slots_warning, value -> {
                    if (!FtpService.isFTPServiceFullyStopped()) {
                        Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    if (value > safeMax) {
                        // 拒绝本次修改：不落盘、不改界面读数，只提示原因。
                        Snackbar.make(root, R.string.item_transfer_slots_rejected,
                                Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    settings.edit().putInt(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS, value).apply();
                    tvTransferSlots.setText(getString(R.string.item_transfer_slots_value, value));
                });
    }

    /**
     * 编码方式：自适应 / UTF-8 / GBK。会话的字符集是在连接建立时读的，运行中切换会让已连接的
     * 客户端与半截命令行对不上号，因此要求服务完全停止——与原先在「实验功能」里的校验一致。
     */
    private void showCharsetDialog() {
        if (!FtpService.isFTPServiceFullyStopped()) {
            Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped, Snackbar.LENGTH_LONG).show();
            return;
        }
        String[] values = {Constants.Charset.CHARSET_AUTO, Constants.Charset.CHAR_UTF,
                Constants.Charset.CHAR_GBK};
        CharSequence[] items = {getString(R.string.item_charset_auto),
                getString(R.string.item_charset_utf), getString(R.string.item_charset_gbk)};
        String current = settings.getString(Constants.PreferenceConsts.CHARSET_TYPE,
                Constants.PreferenceConsts.CHARSET_TYPE_DEFAULT);
        int checked = values[0].equals(current) ? 0 : values[1].equals(current) ? 1
                : values[2].equals(current) ? 2 : -1;
        SegmentedChoiceDialog.show(this, R.string.item_charset, items, checked, which -> {
            String value = values[which];
            // 弹窗打开期间服务可能又被启动，落盘前再查一次。
            if (!FtpService.isFTPServiceFullyStopped()) {
                Snackbar.make(root, R.string.attention_ftp_must_be_fully_stopped,
                        Snackbar.LENGTH_LONG).show();
                return;
            }
            settings.edit().putString(Constants.PreferenceConsts.CHARSET_TYPE, value).apply();
            tvCharset.setText(CommonUtils.getDisplayCharsetValue(ServerSettingsActivity.this));
        });
    }
}
