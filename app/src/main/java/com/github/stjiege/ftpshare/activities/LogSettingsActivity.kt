package com.github.stjiege.ftpshare.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.ui.RadioSelectionDialog
import com.github.stjiege.ftpshare.utils.CommonUtils
import com.github.stjiege.ftpshare.utils.LogManager

class LogSettingsActivity : BaseActivity(), View.OnClickListener {
    private val settings by lazy { CommonUtils.getSettingSharedPreferences(this) }
    private lateinit var autoSave: TextView
    private lateinit var level: TextView
    private lateinit var maxLines: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_log_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        autoSave = findViewById(R.id.log_setting_auto_save_value)
        level = findViewById(R.id.log_setting_level_value)
        maxLines = findViewById(R.id.log_setting_max_lines_value)
        findViewById<View>(R.id.log_setting_auto_save).setOnClickListener(this)
        findViewById<View>(R.id.log_setting_level).setOnClickListener(this)
        findViewById<View>(R.id.log_setting_max_lines).setOnClickListener(this)
        if (settings.getInt(Constants.PreferenceConsts.LOG_MAX_LINES, Constants.PreferenceConsts.LOG_MAX_LINES_DEFAULT) == 2000) {
            settings.edit().putInt(Constants.PreferenceConsts.LOG_MAX_LINES, 1500).apply()
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    override fun onClick(view: View) {
        when (view.id) {
            R.id.log_setting_auto_save -> RadioSelectionDialog(this, getString(R.string.log_setting_auto_save), arrayOf("关闭", "每 3 小时", "每 6 小时", "每 12 小时"), arrayOf(0, 3, 6, 12), settings.getInt(Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS, Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS_DEFAULT), object : RadioSelectionDialog.ConfirmedCallback<Int> {
                override fun onConfirmed(selection: String, value: Int) { settings.edit().putInt(Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS, value).apply(); LogManager.updateAutoSaveSchedule(this@LogSettingsActivity); refresh() }
            }).show()
            R.id.log_setting_level -> RadioSelectionDialog(this, getString(R.string.log_setting_level), arrayOf("warning", "info", "debug"), arrayOf(Constants.PreferenceConsts.LOG_LEVEL_WARNING, Constants.PreferenceConsts.LOG_LEVEL_INFO, Constants.PreferenceConsts.LOG_LEVEL_DEBUG), settings.getString(Constants.PreferenceConsts.LOG_LEVEL, Constants.PreferenceConsts.LOG_LEVEL_DEFAULT), object : RadioSelectionDialog.ConfirmedCallback<String> {
                override fun onConfirmed(selection: String, value: String) { settings.edit().putString(Constants.PreferenceConsts.LOG_LEVEL, value).apply(); LogManager.reloadConfiguration(this@LogSettingsActivity); refresh() }
            }).show()
            R.id.log_setting_max_lines -> RadioSelectionDialog(this, getString(R.string.log_setting_max_lines), arrayOf("500", "1000", "1500"), arrayOf(500, 1000, 1500), settings.getInt(Constants.PreferenceConsts.LOG_MAX_LINES, Constants.PreferenceConsts.LOG_MAX_LINES_DEFAULT), object : RadioSelectionDialog.ConfirmedCallback<Int> {
                override fun onConfirmed(selection: String, value: Int) { settings.edit().putInt(Constants.PreferenceConsts.LOG_MAX_LINES, value).apply(); LogManager.reloadConfiguration(this@LogSettingsActivity); refresh() }
            }).show()
        }
    }

    private fun refresh() {
        if (!::autoSave.isInitialized) return
        autoSave.text = when (settings.getInt(Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS, Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS_DEFAULT)) { 3 -> "每 3 小时（日志锁定开启）"; 6 -> "每 6 小时（日志锁定开启）"; 12 -> "每 12 小时（日志锁定开启）"; else -> "关闭" }
        level.text = settings.getString(Constants.PreferenceConsts.LOG_LEVEL, Constants.PreferenceConsts.LOG_LEVEL_DEFAULT)
        maxLines.text = "${settings.getInt(Constants.PreferenceConsts.LOG_MAX_LINES, Constants.PreferenceConsts.LOG_MAX_LINES_DEFAULT)} 行"
    }
}
