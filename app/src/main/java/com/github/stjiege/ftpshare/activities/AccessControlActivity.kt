package com.github.stjiege.ftpshare.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.utils.CommonUtils

/** Security policy for complete-storage virtual roots and IP access. Changes take effect on the next FTP start. */
class AccessControlActivity : BaseActivity() {
    private lateinit var restrictAndroidData: CheckBox
    private lateinit var ipWhitelist: CheckBox
    private lateinit var ftps: CheckBox
    private lateinit var ipv6: CheckBox
    private lateinit var activeMode: CheckBox
    private val settings by lazy { CommonUtils.getSettingSharedPreferences(this) }

    /** 与 FTP-S 同款：改动只在下一次 FTP 启动时生效，运行中一律拒绝。 */
    private fun requireFullyStopped(): Boolean {
        val stopped = FtpService.isFTPServiceFullyStopped()
        if (!stopped) Toast.makeText(this, R.string.attention_ftp_must_be_fully_stopped, Toast.LENGTH_SHORT).show()
        return stopped
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_access_control)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ftps = findViewById(R.id.access_control_ftps_cb)
        findViewById<View>(R.id.access_control_ftps).setOnClickListener {
            if (!requireFullyStopped()) return@setOnClickListener
            ftps.toggle()
            settings.edit().putBoolean(Constants.PreferenceConsts.FTPS_ENABLED, ftps.isChecked).apply()
        }
        ipv6 = findViewById(R.id.access_control_ipv6_cb)
        findViewById<View>(R.id.access_control_ipv6).setOnClickListener {
            val enabled = !ipv6.isChecked
            if (!FtpService.updateIpv6Enabled(this, enabled)) {
                Toast.makeText(this, R.string.attention_ftp_must_be_fully_stopped, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ipv6.isChecked = enabled
            if (enabled) ipWhitelist.isChecked = false
            updateWhitelistListVisibility()
        }
        activeMode = findViewById(R.id.access_control_active_mode_cb)
        findViewById<View>(R.id.access_control_active_mode).setOnClickListener {
            if (!requireFullyStopped()) return@setOnClickListener
            activeMode.toggle()
            settings.edit().putBoolean(Constants.PreferenceConsts.ACTIVE_MODE_ENABLED, activeMode.isChecked).apply()
        }
        restrictAndroidData = findViewById(R.id.access_control_android_data_cb)
        ipWhitelist = findViewById(R.id.access_control_ip_whitelist_cb)
        findViewById<View>(R.id.access_control_android_data).setOnClickListener {
            if (FtpService.isFTPServiceRunning()) {
                Toast.makeText(this, R.string.attention_ftp_must_be_fully_stopped, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            restrictAndroidData.toggle()
            settings.edit().putBoolean(Constants.PreferenceConsts.RESTRICT_ANDROID_DATA, restrictAndroidData.isChecked).apply()
        }
        findViewById<View>(R.id.access_control_ip_whitelist).setOnClickListener {
            val enabled = !ipWhitelist.isChecked
            if (!FtpService.updateIpWhitelistEnabled(this, enabled)) {
                Toast.makeText(this, R.string.attention_ftp_must_be_fully_stopped, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ipWhitelist.isChecked = enabled
            if (enabled) ipv6.isChecked = false
            updateWhitelistListVisibility()
        }
        findViewById<View>(R.id.access_control_ip_whitelist_list).setOnClickListener {
            if (!requireFullyStopped()) return@setOnClickListener
            startActivity(Intent(this, IpWhitelistActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::ftps.isInitialized) ftps.isChecked = settings.getBoolean(Constants.PreferenceConsts.FTPS_ENABLED, false)
        if (::ipv6.isInitialized) ipv6.isChecked = settings.getBoolean(Constants.PreferenceConsts.IPV6_ENABLED, Constants.PreferenceConsts.IPV6_ENABLED_DEFAULT)
        if (::activeMode.isInitialized) activeMode.isChecked = settings.getBoolean(Constants.PreferenceConsts.ACTIVE_MODE_ENABLED, Constants.PreferenceConsts.ACTIVE_MODE_ENABLED_DEFAULT)
        if (::restrictAndroidData.isInitialized) {
            restrictAndroidData.isChecked = settings.getBoolean(Constants.PreferenceConsts.RESTRICT_ANDROID_DATA, Constants.PreferenceConsts.RESTRICT_ANDROID_DATA_DEFAULT)
        }
        if (::ipWhitelist.isInitialized) {
            ipWhitelist.isChecked = settings.getBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED, Constants.PreferenceConsts.IP_WHITELIST_ENABLED_DEFAULT)
            updateWhitelistListVisibility()
        }
    }

    private fun updateWhitelistListVisibility() {
        val visible = ipWhitelist.isChecked
        findViewById<View>(R.id.access_control_ip_whitelist_list).visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.access_control_ip_whitelist_divider).visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)
}
