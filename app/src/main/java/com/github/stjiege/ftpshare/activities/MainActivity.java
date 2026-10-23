package com.github.stjiege.ftpshare.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.ButtonBarLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.fragments.LogFragment;
import com.github.stjiege.ftpshare.fragments.MainFragment;
import com.github.stjiege.ftpshare.fragments.SettingFragment;
import com.github.stjiege.ftpshare.ui.NavItemMotion;
import com.github.stjiege.ftpshare.ui.RadioSelectionDialog;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.utils.AppAnalytics;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends BaseActivity {

    public static final String STATE_CURRENT_TAB = "current_tab";

    /** Two presses inside this window are required before the app goes to the home screen. */
    private static final long BACK_TO_EXIT_INTERVAL_MS = 2000L;

    /** POST_NOTIFICATIONS 的请求码，只用于区分回调；见 requestNotificationPermissionIfNeeded()。 */
    private static final int REQUEST_POST_NOTIFICATIONS = 210;

    /** 旧版存储权限（READ/WRITE_EXTERNAL_STORAGE）的请求码，只用于区分回调。 */
    private static final int REQUEST_LEGACY_STORAGE = 211;

    /**
     * 同意弹窗正文的分段，成对出现：「小标题, 正文, 小标题, 正文 …」。
     *
     * **第一段必须是「会收集什么信息」**（产品的明确要求：用户最先要看到的是"会收集我什么"）。
     * 境外接收方 / 保存期限 / 行使权利的方式和程序**不在应用内出现**，只由末尾的
     * analytics_prompt_more_hint 指向「了解更多」——那个按钮打开的是 Google 的隐私说明网页，
     * 属应用外部内容。**不要在应用里再建一个二级说明弹窗。**
     */
    private static final int[] ANALYTICS_PROMPT_SECTIONS = {
            R.string.analytics_prompt_section_categories,
            R.string.analytics_prompt_section_categories_body,
            R.string.analytics_prompt_section_purpose,
            R.string.analytics_prompt_section_purpose_body,
            R.string.analytics_prompt_section_excluded,
            R.string.analytics_prompt_section_excluded_body,
            R.string.analytics_prompt_section_choice,
            R.string.analytics_prompt_section_choice_body
    };

    private final MainFragment mainFragment = new MainFragment();
    private final LogFragment logFragment = new LogFragment();
    private final SettingFragment settingFragment = new SettingFragment();
    private Menu menu;
    private int current_tab = 0;
    private BottomNavigationView navigation;
    /** 选中项图标放大、文字淡出的动效，见 ui/NavItemMotion。 */
    private NavItemMotion navMotion;
    private long lastBackPressedAt;
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    /** Raises the desk clock once the app has been left alone on the FTP page. */
    private final Runnable deskClockLauncher = new Runnable() {
        @Override
        public void run() {
            if (!canLaunchDeskClock()) return;
            try {
                LogManager.debug("FTP 页面无操作，拉起桌面时钟");
                startActivity(new Intent(MainActivity.this, ClockActivity.class));
            } catch (Exception e) {
                LogManager.warning("拉起桌面时钟失败: " + e.getClass().getSimpleName());
            }
        }
    };

    /**
     * The clock only makes sense while the user is actually looking at the app: in front, on the
     * FTP page and with the Laboratory switch on. Any other page or a backgrounded app cancels it.
     */
    private boolean canLaunchDeskClock() {
        if (isFinishing() || isDestroyed()) return false;
        if (current_tab != 0) return false;
        return CommonUtils.getSettingSharedPreferences(this)
                .getBoolean(Constants.PreferenceConsts.DESKTOP_CLOCK_ENABLED,
                        Constants.PreferenceConsts.DESKTOP_CLOCK_ENABLED_DEFAULT);
    }

    /** Rearms the idle countdown. Called on every interaction and whenever the page changes. */
    private void scheduleDeskClock() {
        idleHandler.removeCallbacks(deskClockLauncher);
        if (canLaunchDeskClock()) {
            idleHandler.postDelayed(deskClockLauncher, Constants.PreferenceConsts.DESKTOP_CLOCK_IDLE_MS);
        }
    }

    private void cancelDeskClock() {
        idleHandler.removeCallbacks(deskClockLauncher);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        navigation = findViewById(R.id.navigation);
        navMotion = NavItemMotion.attach(navigation);
        navigation.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (R.id.navigation_main == item.getItemId()) {
                    setBottomNavAndTab(0);
                    return true;
                } else if (R.id.navigation_device == item.getItemId()) {
                    setBottomNavAndTab(1);
                    return true;
                } else if (R.id.navigation_settings == item.getItemId()) {
                    setBottomNavAndTab(2);
                    return true;
                }
                return false;
            }
        });

        // 进入界面时直接落到终态，不播动效——此时用户还没做任何操作，动一下等于噪音。
        if (savedInstanceState == null) {
            setBottomNavAndTab(0, false);
        } else {
            setBottomNavAndTab(savedInstanceState.getInt(STATE_CURRENT_TAB), false);
        }
        final int selectedTab = getIntent().getIntExtra(STATE_CURRENT_TAB, -1);
        if (selectedTab >= 0) {
            setBottomNavAndTab(selectedTab, false);
        }

        checkAndShowUploadIntent(getIntent());
        showAnalyticsPromptThenRequestNotifications();
    }

    private void showAnalyticsPromptThenRequestNotifications() {
        if (!AppAnalytics.claimFirstLaunchPrompt(this)) {
            requestNotificationPermissionIfNeeded();
            return;
        }
        showAnalyticsConsentDialog(true, null);
    }

    /** Called from Settings whenever a user turns analytics back on after declining or disabling it. */
    public void requestAnalyticsConsent(Runnable onAccepted) {
        if (AppAnalytics.isEnabled(this)) {
            if (onAccepted != null) onAccepted.run();
            return;
        }
        showAnalyticsConsentDialog(false, onAccepted);
    }

    /** 「了解更多」：打开 Google 的隐私说明网页（应用外部内容，不在应用内另设说明页）。 */
    private void openFirebasePrivacyPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://firebase.google.com/support/privacy/")));
        } catch (Exception error) {
            LogManager.warning("无法打开 Firebase 隐私说明: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 生成同意弹窗的内容区：顶部图标与标题固定，下面按
     * {@link #ANALYTICS_PROMPT_SECTIONS} 逐对生成浅灰分区卡。
     *
     * 文案的唯一来源仍是 ANALYTICS_PROMPT_SECTIONS —— 加一段说明只需要在那里加一对，
     * 不需要改布局。分段之间用 margin 拉开，不用 divider（divider 需要额外 drawable）。
     */
    private View buildConsentContentView() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View root = inflater.inflate(R.layout.dialog_analytics_consent, null);
        ViewGroup sections = root.findViewById(R.id.consent_sections);
        for (int i = 0; i < ANALYTICS_PROMPT_SECTIONS.length; i += 2) {
            View card = inflater.inflate(R.layout.item_consent_section, sections, false);
            TextView label = card.findViewById(R.id.section_label);
            TextView body = card.findViewById(R.id.section_body);
            label.setText(ANALYTICS_PROMPT_SECTIONS[i]);
            body.setText(ANALYTICS_PROMPT_SECTIONS[i + 1]);
            if (i + 2 < ANALYTICS_PROMPT_SECTIONS.length) {
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) card.getLayoutParams();
                params.bottomMargin = dp(8);
                card.setLayoutParams(params);
            }
            sections.addView(card);
        }
        return root;
    }

    private void showAnalyticsConsentDialog(final boolean requestNotificationsAfterward,
                                            final Runnable onAccepted) {
        final View content = buildConsentContentView();
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppAnalyticsConsentDialog)
                .setView(content)
                .setPositiveButton(R.string.analytics_prompt_accept, (clickedDialog, which) -> {
                    AppAnalytics.setEnabled(this, true);
                    if (onAccepted != null) onAccepted.run();
                    if (requestNotificationsAfterward) runPostConsentPermissionSteps();
                })
                .setNegativeButton(R.string.analytics_prompt_decline, (clickedDialog, which) ->
                        { if (requestNotificationsAfterward) runPostConsentPermissionSteps(); })
                .setNeutralButton(R.string.analytics_prompt_learn_more, (clickedDialog, which) -> {
                    openFirebasePrivacyPage();
                    if (requestNotificationsAfterward) runPostConsentPermissionSteps();
                })
                .setOnCancelListener(cancelledDialog -> {
                    if (requestNotificationsAfterward) runPostConsentPermissionSteps();
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            styleConsentButtons(dialog);
            constrainConsentDialogBody(dialog, content);
        });
        dialog.show();
    }

    /**
     * 三个按钮改成一强两弱的层次：了解更多（纯文字）/ 拒绝收集（描边）/ 同意并开启（主色实心）。
     *
     * 原来是灰 / 红 / 绿三个实心色块并排，视觉噪音大且看不出主次。现在只留「同意并开启」
     * 用主色实心，另两个分别降为描边和纯文字。
     *
     * ButtonBarLayout 默认给每个按钮 64dp 最小宽 + 48dp 最小高，三个中文按钮并排时
     * 会把整行顶满、彼此紧贴，窄屏上还会触发垂直堆叠变回"三个大条"。
     * 这里只收按钮本身（最小宽清零、最小高压到 36dp），**不动字号**。
     *
     * 间距分两种，和改版前保持一致：
     * - 了解更多 | 拒绝收集：中间隔着系统布局里那个 weight=1 的 Space（弹性空隙），
     *   由它把后两个按钮推向右侧。按钮收窄后这段空隙会变大，但窄屏上它也最容易被
     *   压成 0，所以给它留 24dp 下限，保证「了解更多」永远孤悬在左、和右边两个分开。
     * - 拒绝收集 | 同意并开启：没有 spacer，只靠各自的 6dp margin，挨得近。
     */
    private void styleConsentButtons(AlertDialog dialog) {
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

        // 了解更多：纯文字，背景换成系统的点击波纹（透明底 + 按下反馈），不再是色块。
        if (neutral != null) {
            TypedValue ripple = new TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                    ripple, true)) {
                neutral.setBackgroundResource(ripple.resourceId);
            }
            neutral.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            neutral.setAllCaps(false);
        }
        // 拒绝收集：白底描边。
        if (negative != null) {
            negative.setBackgroundResource(R.drawable.bg_consent_button_outline);
            negative.setTextColor(Color.parseColor("#455A64"));
            negative.setAllCaps(false);
        }
        // 同意并开启：主色实心，唯一的强调用按钮。
        if (positive != null) {
            positive.setBackgroundResource(R.drawable.bg_consent_button_solid);
            positive.setTextColor(Color.WHITE);
            positive.setAllCaps(false);
        }
        tightenConsentButton(neutral);
        tightenConsentButton(negative);
        tightenConsentButton(positive);

        if (dialog.getWindow() != null) {
            ButtonBarLayout bar = findButtonBar(dialog.getWindow().getDecorView());
            if (bar != null) {
                // 左右 14dp + 按钮自身 6dp margin = 按钮离窗口边缘 20dp，
                // 与正文内容区的 20dp padding 对齐（用户反馈按钮太贴边）。
                bar.setPadding(dp(14), dp(4), dp(14), dp(4));
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    // 那个不可见的 Space 就是「了解更多」与「拒绝收集」之间的弹性空隙。
                    if (child instanceof Space) child.setMinimumWidth(dp(24));
                }
            }
        }
    }

    /**
     * 只压按钮本身：清掉系统给的 64dp 最小宽、48dp 最小高，改成 36dp 高、左右各留 6dp。
     * 字号不动（沿用系统的按钮字号），否则字会跟着变小——那不是要的效果。
     * 另外锁死单行：按钮文字一旦折成两行，按钮就会重新变高变胖。
     * 背景换成 drawable 后 padding 会被清掉，这里统一补回来。
     */
    private void tightenConsentButton(Button button) {
        if (button == null) return;
        button.setMinWidth(0);
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setSingleLine(true);
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams row = (LinearLayout.LayoutParams) params;
            row.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            row.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            row.leftMargin = dp(6);
            row.rightMargin = dp(6);
            button.setLayoutParams(row);
        }
    }

    /**
     * 递归找出按钮栏。ButtonBarLayout 是 appcompat 的内部类（@RestrictTo LIBRARY_GROUP），
     * 但布局 XML 里没有可依赖的公开 id 能定位它，只能按类型找 —— 见 findScrollableAncestor 同理。
     */
    @SuppressLint("RestrictedApi")
    private ButtonBarLayout findButtonBar(View root) {
        if (root instanceof ButtonBarLayout) return (ButtonBarLayout) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ButtonBarLayout found = findButtonBar(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 把弹窗可滚动区的高度封顶到屏幕的 50%，别让它顶满整屏。
     *
     * AlertDialog 的 parentPanel 是 wrap_content：正文一长，弹窗就会几乎占到整屏。
     * 现在正文区是自建的 ScrollView（R.id.consent_scroll），直接给它钉高度即可——
     * 内容照旧可以滚，弹窗整体只占屏幕约 55%（再外加固定的标题与按钮区）。
     */
    private void constrainConsentDialogBody(AlertDialog dialog, View content) {
        final View scroll = content.findViewById(R.id.consent_scroll);
        if (scroll == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int maxBody = Math.max(dp(120), (int) (metrics.heightPixels * 0.5f));
        scroll.post(() -> {
            if (scroll.getHeight() > maxBody) {
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                params.height = maxBody;
                scroll.setLayoutParams(params);
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Android 13（API 33）起 POST_NOTIFICATIONS 是运行时权限。未授予时前台服务照常运行，
     * 但那条「FTP服务正在运行」的常驻通知不会出现在通知栏——服务在跑、界面一切正常，
     * 用户完全看不出问题出在哪，这也是本项目 targetSdk 34 之后最容易踩的静默失败。
     *
     * <p>此前整个工程只有设置页的「通知权限」那一行会请求它，用户没点过就永远拿不到授权。
     * 这里每次进主界面补一次：已授权时直接返回；被拒绝过时系统自己决定还弹不弹，
     * 我们不需要也不应该重复骚扰。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
    }

    /**
     * 同意弹窗收尾：先补通知权限（API 33+），再补旧版存储权限（API 23–29）。
     * 两者落在不同系统版本上，不会连着弹两个系统框。
     */
    private void runPostConsentPermissionSteps() {
        requestNotificationPermissionIfNeeded();
        requestLegacyStoragePermissionIfNeeded();
    }

    /**
     * Android 6–10（API 23–29）在安装后第一次启动时主动申请存储权限。
     *
     * 此前这条链路上完全没有主动申请：存储权限只在首页黄色横幅被点、
     * 或进入账户 / 匿名账户页时才触发，新装的用户不知道要点那根横幅，
     * 结果就是服务能起来、但列不出文件。
     *
     * <p>只覆盖 API 23–29：API 30+ 的「所有文件访问」（MANAGE_EXTERNAL_STORAGE）
     * 不是运行时权限，**无法用 requestPermissions 申请**，只能跳系统设置页让用户手动开。
     * 那边维持现状（首页横幅 + 点击跳转 StorageUtil.openAllFilesAccessSettings），
     * 不在用户毫无预期时把他扔进设置页。
     */
    private void requestLegacyStoragePermissionIfNeeded() {
        // minSdk 23：SDK_INT < 23 恒假，只剩"Android 11+ 走所有文件访问权限、不再申请旧权限"。
        if (Build.VERSION.SDK_INT >= 30) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_LEGACY_STORAGE);
    }

    private void setBottomNavAndTab(int current_tab) {
        setBottomNavAndTab(current_tab, true);
    }

    /**
     * @param animate 是否播「图标放大 / 文字淡出」的切换动效；界面重建、外部跳转进入时传 false。
     */
    private void setBottomNavAndTab(int current_tab, boolean animate) {
        if (current_tab == 0) {
            this.current_tab = 0;
            onNavigationMainSelected();
            navigation.getMenu().getItem(0).setChecked(true);
        } else if (current_tab == 1) {
            this.current_tab = 1;
            onClientDeviceSelected();
            navigation.getMenu().getItem(1).setChecked(true);
        } else if (current_tab == 2) {
            this.current_tab = 2;
            onNavigationAccountSelected();
            navigation.getMenu().getItem(2).setChecked(true);
        }
        // 选中项图标放大、其余项复原与文字淡入淡出，都交给 NavItemMotion；此处只报目标页。
        if (navMotion != null) {
            navMotion.select(this.current_tab, animate);
        }
        // Leaving the FTP page cancels a pending clock; returning to it starts the countdown again.
        scheduleDeskClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleDeskClock();
    }

    @Override
    protected void onPause() {
        cancelDeskClock();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelDeskClock();
        if (navMotion != null) {
            navMotion.release();
        }
        super.onDestroy();
    }

    /** Any touch or key press counts as activity, so the clock keeps its distance. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        scheduleDeskClock();
    }

    /**
     * Back always returns to the FTP page first, so a stray press never leaves the app while the
     * user is reading logs or changing settings. Only a second press from the main page exits.
     */
    @Override
    public void onBackPressed() {
        if (current_tab != 0) {
            setBottomNavAndTab(0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt > BACK_TO_EXIT_INTERVAL_MS) {
            lastBackPressedAt = now;
            Toast.makeText(this, R.string.main_press_back_again_to_exit, Toast.LENGTH_SHORT).show();
            return;
        }
        lastBackPressedAt = 0L;
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_TAB, current_tab);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAndShowUploadIntent(intent);
    }

    private void checkAndShowUploadIntent(Intent intent) {
        if (Intent.ACTION_SEND.equalsIgnoreCase(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equalsIgnoreCase(intent.getAction())) {
            Toast.makeText(this, "外部FTP访问功能已取消", Toast.LENGTH_SHORT).show();
        }
    }

    private void onNavigationMainSelected() {
        try {
            getSupportFragmentManager().beginTransaction().replace(R.id.main_frame, mainFragment).commit();
            getSupportActionBar().setTitle(getResources().getString(R.string.app_name));
            refreshMenuVisibilitiesForCurrentTab();
        } catch (Exception e) {
            LogManager.warning("切换FTP页面失败: " + e.getClass().getSimpleName());
        }

    }

    private void onClientDeviceSelected() {
        try {
            getSupportFragmentManager().beginTransaction().replace(R.id.main_frame, logFragment).commit();
            getSupportActionBar().setTitle(getResources().getString(R.string.title_logs));
            refreshMenuVisibilitiesForCurrentTab();
        } catch (Exception e) {
            LogManager.warning("切换日志页面失败: " + e.getClass().getSimpleName());
        }
    }

    private void onNavigationAccountSelected() {
        try {
            getSupportFragmentManager().beginTransaction().replace(R.id.main_frame, settingFragment).commit();
            getSupportActionBar().setTitle(getResources().getString(R.string.title_settings));
            refreshMenuVisibilitiesForCurrentTab();
        } catch (Exception e) {
            LogManager.warning("切换设置页面失败: " + e.getClass().getSimpleName());
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mainFragment.processingActivityResult(requestCode, resultCode, data);
        settingFragment.processActivityResult(requestCode, resultCode, data);
    }

    private void refreshMenuVisibilitiesForCurrentTab() {
        if (menu == null) return;
        MenuItem logToggle = menu.findItem(R.id.action_log_toggle);
        MenuItem language = menu.findItem(R.id.action_language);
        boolean onLogs = current_tab == 1;
        boolean onMain = current_tab == 0;
        logToggle.setVisible(onLogs);
        language.setVisible(onMain);
        if (onLogs) {
            boolean enabled = LogManager.isLoggingEnabled(this);
            logToggle.setIcon(enabled ? R.drawable.ic_log_pause : R.drawable.ic_log_play);
            logToggle.setTitle(enabled ? R.string.logging_pause : R.string.logging_start);
            logToggle.setEnabled(!LogManager.isLoggingLocked(this));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        this.menu = menu;
        refreshMenuVisibilitiesForCurrentTab();
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_log_toggle) {
            if (!LogManager.setLoggingEnabled(this, !LogManager.isLoggingEnabled(this))) {
                Toast.makeText(this, R.string.logging_locked_message, Toast.LENGTH_SHORT).show();
            }
            logFragment.refreshFromHost();
            refreshMenuVisibilitiesForCurrentTab();
            return true;
        } else if (item.getItemId() == R.id.action_language) {
            chooseLanguage();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void chooseLanguage() {
        new RadioSelectionDialog<Integer>(this, getString(R.string.setting_language),
                new String[]{getString(R.string.setting_language_follow_system),
                        getString(R.string.setting_language_chinese), getString(R.string.setting_language_english)},
                new Integer[]{Constants.PreferenceConsts.LANGUAGE_FOLLOW_SYSTEM,
                        Constants.PreferenceConsts.LANGUAGE_SIMPLIFIED_CHINESE,
                        Constants.PreferenceConsts.LANGUAGE_ENGLISH},
                CommonUtils.getSettingSharedPreferences(this).getInt(Constants.PreferenceConsts.LANGUAGE_SETTING,
                        Constants.PreferenceConsts.LANGUAGE_SETTING_DEFAULT),
                new RadioSelectionDialog.ConfirmedCallback<Integer>() {
                    @Override public void onConfirmed(String selection, Integer value) {
                        // Switching language necessarily recreates every Activity — that is how
                        // AppCompat applies a per-app locale, and the recreate is what shows up as
                        // a flash. Picking the language already in effect would flash for nothing.
                        LocaleListCompat requested = CommonUtils.localeListForSetting(value);
                        if (requested.equals(AppCompatDelegate.getApplicationLocales())) return;
                        // Keep the legacy setting for the FTP service notification, then let the
                        // official AppCompat per-app locale API recreate every Activity.
                        CommonUtils.getSettingSharedPreferences(MainActivity.this).edit()
                                .putInt(Constants.PreferenceConsts.LANGUAGE_SETTING, value).commit();
                        AppCompatDelegate.setApplicationLocales(requested);
                    }
                }).show();
    }

}
