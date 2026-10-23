package com.github.stjiege.ftpshare.fragments;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;
import androidx.fragment.app.Fragment;

import com.github.stjiege.ftpshare.BuildConfig;
import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.MyApplication;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.activities.ServerSettingsActivity;
import com.github.stjiege.ftpshare.activities.ServiceAccountActivity;
import com.github.stjiege.ftpshare.filesystem.UploadLeftoverSweep;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.ui.FtpAddressesDialog;
import com.github.stjiege.ftpshare.utils.AccessibilityKeepAlive;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.StorageUtil;
import com.google.android.material.snackbar.Snackbar;

public class MainFragment extends Fragment implements View.OnClickListener, FtpService.OnFTPServiceStatusChangedListener {

    /**
     * 编译期常量，不能走 {@code MyApplication.getGlobalBaseContext()}：静态字段在类加载时
     * 求值，而 Application 可能还没 onCreate（ContentProvider 或外部组件提前触达本类时），
     * 那时 {@code myApplication} 仍是 null，类初始化直接 NPE。
     */
    public static final String ACTION_FLASH_ACCOUNT_ITEM = BuildConfig.APPLICATION_ID + ":action_flash_account_item";
    final SharedPreferences settings = CommonUtils.getSettingSharedPreferences(MyApplication.getGlobalBaseContext());
    ViewGroup viewGroup_main, viewGroup_more, viewGroup_server_settings;
    CompoundButton switchCompat;
    TextView tvMainTitle, tv_main, tv_login_mode, tvStoragePermissionWarning, tvBatteryOptimizationWarning, tvAccessibilityWarning;
    ScrollView scrollView;
    private com.github.stjiege.ftpshare.views.ResourcePanel resourcePanel;
    /** Keeps the keep-alive warning fresh while the user stays on this screen. */
    private final Runnable accessibilityRefreshTask = new Runnable() {
        @Override public void run() {
            MyApplication.handler.removeCallbacks(this);
            refreshAccessibilityKeepAliveStatus();
            MyApplication.handler.postDelayed(this, 30_000L);
        }
    };
    private final BroadcastReceiver flashItemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (viewGroup_more != null && getActivity() != null) {
                viewGroup_more.clearAnimation();
                scrollView.smoothScrollTo(0, scrollView.getHeight());
                AlphaAnimation alphaAnimation1 = new AlphaAnimation(1.0f, 0.3f);
                alphaAnimation1.setDuration(800);
                alphaAnimation1.setRepeatCount(2);
                alphaAnimation1.setRepeatMode(Animation.RESTART);
                viewGroup_more.setAnimation(alphaAnimation1);
                alphaAnimation1.start();
            }
        }
    };
    private final Runnable lockUiRefresh = this::updateSwitchLockedUi;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FtpService.addOnFtpServiceStatusChangedListener(this);
        try {
            // ContextCompat 的版本兼容写法：API 33+ 走带 flag 的重载，以下由 androidx 补齐，
            // 顺带让 lint 的 UnspecifiedRegisterReceiverFlag 看得到导出状态。
            ContextCompat.registerReceiver(getContext(), flashItemReceiver,
                    new IntentFilter(ACTION_FLASH_ACCOUNT_ITEM), ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            com.github.stjiege.ftpshare.utils.LogManager.warning("注册主界面刷新广播失败: " + e.getClass().getSimpleName());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        scrollView = view.findViewById(R.id.main_frame_sr);
        resourcePanel = view.findViewById(R.id.main_resources);
        viewGroup_main = view.findViewById(R.id.main_area);
        viewGroup_more = view.findViewById(R.id.setting_area);
        viewGroup_server_settings = view.findViewById(R.id.server_settings_area);
        switchCompat = view.findViewById(R.id.main_switch);
        tvMainTitle = view.findViewById(R.id.main_title);
        tv_main = view.findViewById(R.id.main_att);
        tv_login_mode = view.findViewById(R.id.setting_area_value);
        tvStoragePermissionWarning = view.findViewById(R.id.main_storage_permission_warning);
        tvStoragePermissionWarning.setOnClickListener(v -> requestStoragePermission());
        tvBatteryOptimizationWarning = view.findViewById(R.id.main_battery_optimization_warning);
        tvBatteryOptimizationWarning.setOnClickListener(v -> requestBatteryOptimizationExemption());
        tvAccessibilityWarning = view.findViewById(R.id.main_accessibility_warning);
        tvAccessibilityWarning.setOnClickListener(v -> openAccessibilitySettings());
        tvAccessibilityWarning.setOnLongClickListener(v -> {
            // Muting is remembered and only re-armed once heartbeats resume, so "ignore" sticks
            // instead of being overwritten the next time the service reconnects.
            AccessibilityKeepAlive.setWarningMuted(requireContext(), true);
            refreshAccessibilityKeepAliveStatus();
            Toast.makeText(getContext(), R.string.toast_accessibility_warning_ignored, Toast.LENGTH_SHORT).show();
            return true;
        });

        viewGroup_main.setOnClickListener(this);
        // The visible switch stays compact, while this 104dp-wide right-side hit target owns
        // the action.  Taps everywhere else on the row continue to show available FTP IPs.
        view.findViewById(R.id.main_switch_hit_area).setOnClickListener(v -> toggleFtpService());
        viewGroup_more.setOnClickListener(this);
        viewGroup_server_settings.setOnClickListener(this);
        //refreshContents();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (resourcePanel != null) resourcePanel.start();
        //refreshBatteryIgnoreStatus();
        refreshContents();
        MyApplication.handler.removeCallbacks(accessibilityRefreshTask);
        MyApplication.handler.postDelayed(accessibilityRefreshTask, 30_000L);
    }

    @Override
    public void onPause() {
        if (resourcePanel != null) resourcePanel.stop();
        super.onPause();
        MyApplication.handler.removeCallbacks(accessibilityRefreshTask);
    }

    @Override
    public void onDestroyView() {
        MyApplication.handler.removeCallbacks(lockUiRefresh);
        if (resourcePanel != null) resourcePanel.stop();
        resourcePanel = null;
        super.onDestroyView();
    }

    @Override
    public void onClick(View v) {
        if (getContext() == null || getActivity() == null || getView() == null) return;
        switch (v.getId()) {
            default:
                break;
            case R.id.main_area: {
                new FtpAddressesDialog(getActivity()).show();
            }
            break;
            case R.id.setting_area: {
                if (FtpService.isFTPServiceRunning()) {
                    CommonUtils.showSnackBarOfFtpServiceIsRunning(getActivity());
                    return;
                }
                getActivity().startActivityForResult(new Intent(getActivity(), ServiceAccountActivity.class), 0);
            }
            break;
            case R.id.server_settings_area: {
                getActivity().startActivity(new Intent(getActivity(), ServerSettingsActivity.class));
            }
            break;
        }
    }

    /** Only the switch itself starts or stops FTP; the larger row opens all available addresses. */
    private void toggleFtpService() {
        if (UploadLeftoverSweep.isStartLocked()) {
            // 停止后正在清理残留，开关临时锁定。用户若在这段时间内误触，直接切回来并刷新。
            refreshMainStatus();
            return;
        }
        if (!FtpService.isFTPServiceRunning()) {
            if (FtpService.startService(requireActivity())) {
                switchCompat.setEnabled(false);
                tv_main.setText(getResources().getString(R.string.attention_opening_ftp));
            } else {
                switchCompat.setChecked(false);
            }
        } else {
            switchCompat.setEnabled(false);
            tv_main.setText(getResources().getString(R.string.attention_closing_ftp));
            FtpService.stopService();
        }
    }

    private void updateSwitchLockedUi() {
        if (getActivity() == null || getContext() == null || getView() == null) return;
        if (UploadLeftoverSweep.isStartLocked()) {
            switchCompat.setEnabled(false);
            switchCompat.setChecked(false);
            tv_main.setText(getString(R.string.attention_cleaning_ftp_leftovers));
            tvMainTitle.setText(R.string.ftp_status_not_running);
            MyApplication.handler.postDelayed(lockUiRefresh, 1_000L);
        } else {
            switchCompat.setEnabled(true);
            refreshMainStatus();
        }
    }

    private void refreshMainStatus() {
        if (getActivity() == null || getContext() == null || getView() == null) return;
        if (UploadLeftoverSweep.isStartLocked()) {
            updateSwitchLockedUi();
            return;
        }
        boolean running = FtpService.isFTPServiceRunning();
        switchCompat.setChecked(running);
        tvMainTitle.setText(running ? R.string.ftp_status_running : R.string.ftp_status_not_running);
        if (running) {
            java.util.ArrayList<String> addresses = com.github.stjiege.ftpshare.utils.NetworkEnvironmentUtil.getLocalIpv4Addresses();
            String wifi = com.github.stjiege.ftpshare.utils.NetworkEnvironmentUtil.getWifiIp(requireContext());
            if (wifi != null && addresses.remove(wifi)) addresses.add(0, wifi);
            StringBuilder links = new StringBuilder();
            for (String ip : addresses) {
                if (links.length() > 0) links.append("\n");
                links.append(CommonUtils.getFtpServiceAddress(requireContext(), ip));
            }
            tv_main.setText(links.length() > 0 ? links.toString() : getString(R.string.ftp_no_ipv4));
        } else {
            tv_main.setText(R.string.ftp_links_unavailable);
        }
    }

    @Override
    public void onFTPServiceStarted() {
        if (getActivity() == null || getContext() == null || getView() == null) return;
        switchCompat.setEnabled(true);
        switchCompat.setChecked(true);
        refreshMainStatus();
    }

    @Override
    public void onFTPServiceStartError(Exception e) {
        if (getActivity() == null || getContext() == null || getView() == null) return;
        switchCompat.setEnabled(true);
        switchCompat.setChecked(false);
        refreshMainStatus();
        Snackbar.make(getActivity().findViewById(android.R.id.content), e.toString(), Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void onFTPServiceDestroyed() {
        if (getActivity() == null || getContext() == null || getView() == null) return;
        if (UploadLeftoverSweep.isStartLocked()) {
            updateSwitchLockedUi();
            return;
        }
        switchCompat.setChecked(false);
        switchCompat.setEnabled(true);
        refreshMainStatus();
    }


    @Override
    public void onDestroy() {
        MyApplication.handler.removeCallbacks(accessibilityRefreshTask);
        super.onDestroy();
        FtpService.removeOnFtpServiceStatusChangedListener(this);
        try {
            getContext().unregisterReceiver(flashItemReceiver);
        } catch (Exception ignored) {
        }
    }

    public void processingActivityResult(int requestCode, int resultCode, Intent data) {
        /*if(requestCode==0&&resultCode== Activity.RESULT_OK&&getActivity()!=null){
            getActivity().recreate();
        }*/
        refreshContents();
        //refreshBatteryIgnoreStatus();
    }

    private void refreshContents() {
        if (getContext() == null || getActivity() == null || getView() == null) return;
        //switchCompat.post(() -> switchCompat.setChecked(FtpService.isFTPServiceRunning()));
        refreshMainStatus();
        tv_login_mode.setText(getContext().getResources().getString(R.string.item_account_settings_subtitle));
        tvStoragePermissionWarning.setVisibility(hasStoragePermission() ? View.GONE : View.VISIBLE);
        refreshBatteryIgnoreStatus();
        refreshAccessibilityKeepAliveStatus();
    }

    /**
     * Shows a banner when the keep-alive grant the user enabled is gone. Red is the hard fact: the
     * system no longer lists the service. Yellow is only a soft signal: the grant is still there but
     * heartbeats stopped, which Doze or an aggressive ROM can also cause.
     */
    private void refreshAccessibilityKeepAliveStatus() {
        if (tvAccessibilityWarning == null || getContext() == null) return;
        try {
            AccessibilityKeepAlive.State state = AccessibilityKeepAlive.evaluate(getContext());
            // Only the two actionable states raise a banner. NO_BASELINE (no heartbeat in this boot
            // yet) and MUTED (user dismissed) must stay silent, otherwise a fresh start right after
            // a reboot would claim the service had been dead for "0 seconds".
            if (state != AccessibilityKeepAlive.State.GRANT_REVOKED
                    && state != AccessibilityKeepAlive.State.NO_HEARTBEAT) {
                tvAccessibilityWarning.setVisibility(View.GONE);
                return;
            }
            boolean revoked = state == AccessibilityKeepAlive.State.GRANT_REVOKED;
            long silent = AccessibilityKeepAlive.millisSinceLastHeartbeat(getContext());
            tvAccessibilityWarning.setText(revoked
                    ? getString(R.string.main_accessibility_warning_revoked)
                    : getString(R.string.main_accessibility_warning_no_heartbeat,
                            AccessibilityKeepAlive.formatDuration(getContext(), silent)));
            // Swap the whole drawable rather than a flat colour so the rounded banner with its
            // accent bar is preserved in both severities.
            tvAccessibilityWarning.setBackgroundResource(revoked
                    ? R.drawable.bg_banner_error
                    : R.drawable.bg_banner_warning);
            tvAccessibilityWarning.setTextColor(ContextCompat.getColor(requireContext(), revoked
                    ? R.color.color_banner_error_text
                    : R.color.color_banner_warning_text));
            tvAccessibilityWarning.setVisibility(View.VISIBLE);
        } catch (Exception error) {
            tvAccessibilityWarning.setVisibility(View.GONE);
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception error) {
            Toast.makeText(getContext(), "无法打开系统无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) return StorageUtil.hasAllFilesAccess();
        // minSdk 23：SDK_INT < 23 恒假，"低版本默认有权限"的短路分支不再存在。
        return PermissionChecker.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            StorageUtil.openAllFilesAccessSettings(requireContext());
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
    }

    private void refreshBatteryIgnoreStatus() {
        if (tvBatteryOptimizationWarning == null || getContext() == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                boolean ignored = FtpService.isBatteryOptimizationIgnored(getContext());
                boolean shizukuDoze = settings.getBoolean(Constants.PreferenceConsts.SHIZUKU_DOZE_WHITELISTED, Constants.PreferenceConsts.SHIZUKU_DOZE_WHITELISTED_DEFAULT);
                tvBatteryOptimizationWarning.setVisibility(ignored || shizukuDoze ? View.GONE : View.VISIBLE);
            } else {
                tvBatteryOptimizationWarning.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            tvBatteryOptimizationWarning.setVisibility(View.GONE);
        }
    }

    private void requestBatteryOptimizationExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(getContext(), "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
        }
    }

}
