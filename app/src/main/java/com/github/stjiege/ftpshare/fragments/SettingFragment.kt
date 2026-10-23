package com.github.stjiege.ftpshare.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.activities.AccessControlActivity
import com.github.stjiege.ftpshare.activities.LaboratoryActivity
import com.github.stjiege.ftpshare.activities.MainActivity
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.utils.CommonUtils
import com.github.stjiege.ftpshare.utils.LogManager
import com.github.stjiege.ftpshare.utils.AppAnalytics
import com.github.stjiege.ftpshare.utils.StorageUtil
import com.github.stjiege.ftpshare.utils.UpdateChecker

/** Compact settings landing page; detailed configuration is deliberately isolated. */
class SettingFragment : Fragment() {
    private val settings by lazy { CommonUtils.getSettingSharedPreferences(requireContext()) }
    private lateinit var autoStart: CheckBox
    private lateinit var mdns: CheckBox
    private lateinit var analytics: CheckBox

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.activity_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        autoStart = view.findViewById(R.id.setting_auto_start_cb)
        mdns = view.findViewById(R.id.setting_mdns_cb)
        analytics = view.findViewById(R.id.setting_analytics_cb)
        intArrayOf(
            R.id.setting_storage_access, R.id.setting_legacy_storage_access,
            R.id.setting_accessibility_control,
            R.id.setting_notification_access, R.id.setting_app_permissions, R.id.setting_access_control,
            R.id.setting_auto_start, R.id.setting_mdns, R.id.setting_analytics,
            R.id.setting_laboratory, R.id.setting_check_update, R.id.setting_feedback
        ).forEach { id -> view.findViewById<View>(id).setOnClickListener(::onClick) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun onClick(view: View) {
        when (view.id) {
            R.id.setting_storage_access -> if (Build.VERSION.SDK_INT >= 30) StorageUtil.openAllFilesAccessSettings(requireContext())
            R.id.setting_legacy_storage_access -> if (Build.VERSION.SDK_INT in 23..29) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            R.id.setting_accessibility_control -> openAccessibilitySettings()
            R.id.setting_notification_access -> openNotificationSettings()
            R.id.setting_app_permissions -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
            R.id.setting_access_control -> startActivity(Intent(requireContext(), AccessControlActivity::class.java))
            R.id.setting_auto_start -> toggle(autoStart, Constants.PreferenceConsts.START_AFTER_BOOT)
            R.id.setting_mdns -> {
                toggle(mdns, Constants.PreferenceConsts.MDNS_ENABLED)
                FtpService.sendEmptyMessage(FtpService.MESSAGE_MDNS_RELOAD)
            }
            R.id.setting_analytics -> {
                if (analytics.isChecked) {
                    AppAnalytics.setEnabled(requireContext(), false)
                    analytics.isChecked = false
                } else {
                    (activity as? MainActivity)?.requestAnalyticsConsent {
                        analytics.isChecked = true
                    }
                }
            }
            R.id.setting_laboratory -> startActivity(Intent(requireContext(), LaboratoryActivity::class.java))
            R.id.setting_check_update -> try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ST-Jie-Ge/Android-FTP-Server")))
            } catch (error: Exception) {
                Toast.makeText(requireContext(), error.toString(), Toast.LENGTH_SHORT).show()
            }
            R.id.setting_feedback -> try {
                val qqUri = Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=983086628")
                startActivity(Intent(Intent.ACTION_VIEW, qqUri))
            } catch (error: Exception) {
                Toast.makeText(requireContext(), R.string.feedback_qq_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggle(box: CheckBox, key: String) {
        box.toggle()
        settings.edit().putBoolean(key, box.isChecked).apply()
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
        } catch (error: Exception) {
            Toast.makeText(requireContext(), "无法打开系统通知设置", Toast.LENGTH_SHORT).show()
            LogManager.error("无法打开系统通知设置", error)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (error: Exception) {
            Toast.makeText(requireContext(), "无法打开系统无障碍设置", Toast.LENGTH_SHORT).show()
            LogManager.error("无法打开系统无障碍设置", error)
        }
    }

    fun processActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = refresh()

    private fun refresh() {
        if (!isAdded || view == null) return
        autoStart.isChecked = settings.getBoolean(Constants.PreferenceConsts.START_AFTER_BOOT, Constants.PreferenceConsts.START_AFTER_BOOT_DEFAULT)
        mdns.isChecked = settings.getBoolean(Constants.PreferenceConsts.MDNS_ENABLED, Constants.PreferenceConsts.MDNS_ENABLED_DEFAULT)
        analytics.isChecked = AppAnalytics.isEnabled(requireContext())
        // 权限入口只在“还没授权”时才有意义；已经拿到权限就整行收起来，避免用户点了没反应。
        // “所有文件访问权限”还依赖 MANAGE_EXTERNAL_STORAGE（Android 11 / API 30 才有的接口），
        // 低版本上根本不存在这一项。
        val allFilesVisibility = if (Build.VERSION.SDK_INT >= 30 && !StorageUtil.hasAllFilesAccess()) View.VISIBLE else View.GONE
        view?.findViewById<View>(R.id.setting_storage_access)?.visibility = allFilesVisibility
        view?.findViewById<View>(R.id.setting_storage_divider)?.visibility = allFilesVisibility
        // “Android 6+ 存储读写权限”是运行时权限，只在 API 23–29 存在：
        // Android 11+ 已被所有文件访问权限接管；Android 5 及以下安装即授予，无需申请。
        val legacyVisibility = if (Build.VERSION.SDK_INT in 23..29
                && requireContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) View.VISIBLE else View.GONE
        view?.findViewById<View>(R.id.setting_legacy_storage_access)?.visibility = legacyVisibility
        view?.findViewById<View>(R.id.setting_legacy_storage_divider)?.visibility = legacyVisibility
        // 「检查更新」行：启动时的 Gitee 检查发现新版本后，标题与副标题会替换为
        // 「发现新版本！」+「最新版本: x.x」（副标题用警示橙 #EF8C00，与资源监视器一致）；
        // 跳转行为不变（浏览器打开项目主页）。
        val latestVersion = UpdateChecker.getLatestVersionName(requireContext())
        val checkUpdateSubtitle = view?.findViewById<TextView>(R.id.setting_check_update_subtitle)
        view?.findViewById<TextView>(R.id.setting_check_update_title)?.text =
            if (latestVersion != null) getString(R.string.action_update_found)
            else getString(R.string.action_check_update)
        if (checkUpdateSubtitle != null) {
            if (latestVersion != null) {
                checkUpdateSubtitle.text = getString(R.string.update_latest_version, latestVersion)
                // 警示橙 = #EF8C00（Color.rgb(239,140,0)），工程既定取值
                checkUpdateSubtitle.setTextColor(android.graphics.Color.rgb(239, 140, 0))
            } else {
                checkUpdateSubtitle.text = getString(R.string.item_check_update_att)
                // 恢复 SettingsSubtitle 的默认次级文字色
                checkUpdateSubtitle.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.color_text_secondary))
            }
        }
    }
}
