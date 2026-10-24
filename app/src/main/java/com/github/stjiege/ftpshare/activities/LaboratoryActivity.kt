package com.github.stjiege.ftpshare.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.ftpserver.data.TcpSocketBuffer
import com.github.stjiege.ftpshare.ui.SegmentedChoiceDialog
import com.github.stjiege.ftpshare.shizuku.IShizukuCommandService
import com.github.stjiege.ftpshare.shizuku.ShizukuCommandService
import com.github.stjiege.ftpshare.shizuku.ShizukuWatchdogController
import com.github.stjiege.ftpshare.utils.CommonUtils
import com.github.stjiege.ftpshare.utils.LogManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/** Experimental Shizuku functions are isolated so a missing Shizuku service never affects settings. */
class LaboratoryActivity : BaseActivity(), View.OnClickListener {
    private val settings by lazy { CommonUtils.getSettingSharedPreferences(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Shizuku-Laboratory").apply { isDaemon = true }
    }
    private lateinit var accessValue: TextView
    private lateinit var doze: CheckBox
    private lateinit var watchdog: CheckBox
    private lateinit var externalControl: CheckBox
    private lateinit var performanceOptimizationDisabled: CheckBox
    private lateinit var desktopClock: CheckBox
    private lateinit var chacha20: CheckBox
    private lateinit var newFilesystem: CheckBox
    private lateinit var watchdogValue: TextView
    private lateinit var socketBufferValue: TextView
    private var userService: IShizukuCommandService? = null
    private var bound = false
    private var connecting = false
    @Volatile private var destroyed = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, ShizukuCommandService::class.java))
            .tag("ftpserverg2-deviceidle")
            .processNameSuffix("ftpserverg2-deviceidle")
            .version(22)
            .daemon(false)
    }
    private val timeout = Runnable {
        if (!destroyed && !bound && connecting && !isFinishing) {
            connecting = false
            accessValue.text = "Shizuku 用户服务连接超时"
        }
    }
    /** The watchdog state drifts while the screen is open, so the row is re-read on a timer. */
    private val accessibilityTicker = object : Runnable {
        override fun run() {
            uiHandler.removeCallbacks(this)
            if (!destroyed && !isFinishing) {
                if (::watchdogValue.isInitialized) watchdogValue.text = ShizukuWatchdogController.getStatus()
            }
            uiHandler.postDelayed(this, 10_000L)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (destroyed) {
                try { Shizuku.unbindUserService(userServiceArgs, this, true) } catch (_: Exception) { }
                return
            }
            userService = IShizukuCommandService.Stub.asInterface(service)
            bound = userService != null
            connecting = false
            uiHandler.removeCallbacks(timeout)
            uiHandler.post {
                if (!destroyed && !isFinishing) {
                    refresh()
                    refreshWhitelist()
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            bound = false
            connecting = false
            uiHandler.post { if (!destroyed && !isFinishing) refresh() }
        }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_SHIZUKU) uiHandler.post {
            try {
                refresh()
            } catch (error: Exception) {
                LogManager.warning("刷新 Shizuku 授权状态失败: ${error.javaClass.simpleName}")
            }
            try {
                bindUserService()
            } catch (error: Exception) {
                LogManager.warning("授权后连接 Shizuku 失败: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_laboratory)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        accessValue = findViewById(R.id.lab_shizuku_access_value)
        doze = findViewById(R.id.lab_shizuku_doze_cb)
        watchdog = findViewById(R.id.lab_shizuku_watchdog_cb)
        externalControl = findViewById(R.id.lab_external_control_cb)
        performanceOptimizationDisabled = findViewById(R.id.lab_performance_optimization_cb)
        desktopClock = findViewById(R.id.lab_desktop_clock_cb)
        chacha20 = findViewById(R.id.lab_chacha20_cb)
        newFilesystem = findViewById(R.id.lab_legacy_filesystem_cb)
        watchdogValue = findViewById(R.id.lab_shizuku_watchdog_value)
        socketBufferValue = findViewById(R.id.lab_socket_buffer_request_value)
        findViewById<View>(R.id.lab_shizuku_access).setOnClickListener(this)
        findViewById<View>(R.id.lab_shizuku_doze).setOnClickListener(this)
        findViewById<View>(R.id.lab_shizuku_watchdog).setOnClickListener(this)
        findViewById<View>(R.id.lab_external_control).setOnClickListener(this)
        findViewById<View>(R.id.lab_performance_optimization).setOnClickListener(this)
        findViewById<View>(R.id.lab_desktop_clock).setOnClickListener(this)
        findViewById<View>(R.id.lab_chacha20).setOnClickListener(this)
        findViewById<View>(R.id.lab_legacy_filesystem).setOnClickListener(this)
        findViewById<View>(R.id.lab_socket_buffer_request).setOnClickListener(this)
        findViewById<View>(R.id.lab_open_clock).setOnClickListener(this)
        try { Shizuku.addRequestPermissionResultListener(permissionListener) } catch (error: Exception) { LogManager.warning("无法注册 Shizuku 授权回调: ${error.javaClass.simpleName}") }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        uiHandler.removeCallbacks(accessibilityTicker)
        uiHandler.postDelayed(accessibilityTicker, 10_000L)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(accessibilityTicker)
    }

    override fun onDestroy() {
        destroyed = true
        uiHandler.removeCallbacks(accessibilityTicker)
        uiHandler.removeCallbacks(timeout)
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (error: Exception) {
            LogManager.debug("移除 Shizuku 授权回调失败: ${error.javaClass.simpleName}")
        }
        if (bound) try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (error: Exception) {
            LogManager.debug("解绑 Shizuku 用户服务失败: ${error.javaClass.simpleName}")
        }
        worker.shutdownNow()
        super.onDestroy()
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lab_shizuku_access -> requestOrConnect()
            R.id.lab_shizuku_doze -> toggleDoze()
            R.id.lab_shizuku_watchdog -> toggleWatchdog()
            R.id.lab_external_control -> toggleExternalControl()
            R.id.lab_performance_optimization -> togglePerformanceOptimization()
            R.id.lab_desktop_clock -> toggleDesktopClock()
            R.id.lab_chacha20 -> toggleChaCha20()
            R.id.lab_legacy_filesystem -> togglePerformanceMode()
            R.id.lab_socket_buffer_request -> showSocketBufferDialog()
            R.id.lab_open_clock -> startActivity(Intent(this, ClockActivity::class.java))
        }
    }

    private fun requestOrConnect() {
        val permission = try { Shizuku.checkSelfPermission() } catch (error: Exception) {
            Toast.makeText(this, "未检测到可用的 Shizuku 服务", Toast.LENGTH_SHORT).show()
            LogManager.warning("Shizuku 不可用: ${error.javaClass.simpleName}")
            refresh()
            return
        }
        if (permission == PackageManager.PERMISSION_GRANTED) bindUserService()
        else try { Shizuku.requestPermission(REQUEST_SHIZUKU) } catch (error: Exception) {
            Toast.makeText(this, "无法请求 Shizuku 授权", Toast.LENGTH_SHORT).show()
            LogManager.error("请求 Shizuku 授权失败", error)
        }
    }

    private fun bindUserService() {
        if (destroyed || bound || connecting || !isAuthorized()) return
        try {
            connecting = true
            Shizuku.bindUserService(userServiceArgs, connection)
            uiHandler.postDelayed(timeout, 8_000L)
        } catch (error: Exception) {
            connecting = false
            accessValue.text = "Shizuku 用户服务连接失败：${error.javaClass.simpleName}"
            LogManager.error("连接 Shizuku 用户服务失败", error)
        }
    }

    private fun refresh() {
        if (destroyed || !::accessValue.isInitialized) return
        val authorized = isAuthorized()
        val dozeRow = findViewById<View>(R.id.lab_shizuku_doze)
        val dozeDivider = findViewById<View>(R.id.lab_shizuku_doze_divider)
        val watchdogRow = findViewById<View>(R.id.lab_shizuku_watchdog)
        val watchdogDivider = findViewById<View>(R.id.lab_shizuku_watchdog_divider)
        externalControl.isChecked = settings.getBoolean(Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED, Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED_DEFAULT)
        performanceOptimizationDisabled.isChecked = settings.getBoolean(Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED, Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED_DEFAULT)
        desktopClock.isChecked = settings.getBoolean(Constants.PreferenceConsts.DESKTOP_CLOCK_ENABLED, Constants.PreferenceConsts.DESKTOP_CLOCK_ENABLED_DEFAULT)
        chacha20.isChecked = settings.getBoolean(Constants.PreferenceConsts.FTPS_CHACHA20_PREFERRED, Constants.PreferenceConsts.FTPS_CHACHA20_PREFERRED_DEFAULT)
        newFilesystem.isChecked = settings.getBoolean(Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED, Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED_DEFAULT)
        newFilesystem.isChecked = settings.getBoolean(Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED, Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED_DEFAULT)
                && settings.getBoolean(Constants.PreferenceConsts.NEW_COMMAND_ROUTER_ENABLED, Constants.PreferenceConsts.NEW_COMMAND_ROUTER_ENABLED_DEFAULT)
                && !settings.getBoolean(Constants.PreferenceConsts.USE_LEGACY_DATA_CONNECTION, Constants.PreferenceConsts.USE_LEGACY_DATA_CONNECTION_DEFAULT)
        val socketBuffer = socketBufferRequest()
        socketBufferValue.text = socketBufferLabel(socketBuffer)
        TcpSocketBuffer.configure(socketBuffer)
        // 时钟调试入口只在开启时钟样式后才出现：没开时钟时它没有意义。
        val clockVisibility = if (desktopClock.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.lab_open_clock).visibility = clockVisibility
        findViewById<View>(R.id.lab_open_clock_divider).visibility = clockVisibility
        watchdogValue.text = ShizukuWatchdogController.getStatus()
        if (!authorized) {
            val installed = try { Shizuku.checkSelfPermission(); true } catch (_: Exception) { false }
            accessValue.text = getString(if (installed) R.string.setting_shizuku_permission_required else R.string.setting_shizuku_not_running)
            dozeRow.visibility = View.GONE
            dozeDivider.visibility = View.GONE
            watchdogRow.visibility = View.GONE
            watchdogDivider.visibility = View.GONE
            return
        }
        val identity = try { if (Shizuku.getUid() == 0) "root" else "ADB" } catch (_: Exception) { "Shizuku" }
        accessValue.text = getString(R.string.setting_shizuku_ready, identity)
        dozeRow.visibility = View.VISIBLE
        dozeDivider.visibility = View.VISIBLE
        watchdogRow.visibility = View.VISIBLE
        watchdogDivider.visibility = View.VISIBLE
        watchdog.isChecked = settings.getBoolean(Constants.PreferenceConsts.SHIZUKU_WATCHDOG, Constants.PreferenceConsts.SHIZUKU_WATCHDOG_DEFAULT)
        bindUserService()
    }

    private fun isAuthorized(): Boolean = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }

    private fun toggleDoze() {
        val service = userService
        if (service == null) {
            requestOrConnect()
            Toast.makeText(this, "正在连接 Shizuku，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val enable = !doze.isChecked
        doze.isEnabled = false
        worker.execute {
            try {
                val result = service.setDozeWhitelist(enable)
                uiHandler.post {
                    if (destroyed || isFinishing) return@post
                    if (result) {
                        doze.isChecked = enable
                        settings.edit().putBoolean(Constants.PreferenceConsts.SHIZUKU_DOZE_WHITELISTED, enable).apply()
                        LogManager.info("Shizuku 已${if (enable) "加入" else "移出"} Doze 白名单")
                    } else Toast.makeText(this, getString(R.string.setting_shizuku_operation_failed, "命令未成功执行"), Toast.LENGTH_LONG).show()
                    doze.isEnabled = true
                    refreshWhitelist()
                }
            } catch (error: Exception) {
                uiHandler.post {
                    if (!destroyed && !isFinishing) {
                        Toast.makeText(this, getString(R.string.setting_shizuku_operation_failed, error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        doze.isEnabled = true
                    }
                }
            }
        }
    }

    private fun toggleWatchdog() {
        // The guard binds its own Shizuku user service; it must not depend on the separate
        // device-idle service this screen happens to hold.
        if (!isAuthorized()) {
            requestOrConnect()
            Toast.makeText(this, "请先完成 Shizuku 授权", Toast.LENGTH_SHORT).show()
            return
        }
        val enable = !watchdog.isChecked
        watchdog.isEnabled = false
        val appContext = applicationContext
        try {
            worker.execute {
                try {
                    if (!enable) ShizukuWatchdogController.stop(appContext)
                    settings.edit().putBoolean(Constants.PreferenceConsts.SHIZUKU_WATCHDOG, enable).apply()
                    if (enable && FtpService.isFTPServiceRunning()) ShizukuWatchdogController.startIfEnabled(appContext)
                    val status = ShizukuWatchdogController.getStatus()
                    uiHandler.post {
                        if (destroyed || isFinishing) return@post
                        watchdog.isChecked = enable
                        watchdog.isEnabled = true
                        watchdogValue.text = status
                        // The switch alone would lie: say it out loud when Shizuku refused.
                        if (enable && (status.contains("未授权") || status.contains("不可用"))) {
                            Toast.makeText(this, status, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (error: Exception) {
                    LogManager.error("切换看门狗状态失败", error)
                    uiHandler.post {
                        if (!destroyed && !isFinishing) {
                            watchdog.isEnabled = true
                            Toast.makeText(this, "看门狗操作失败：${error.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            watchdog.isEnabled = true
            LogManager.debug("看门狗状态任务已取消")
        }
    }

    private fun toggleExternalControl() {
        externalControl.toggle()
        settings.edit().putBoolean(Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED, externalControl.isChecked).apply()
        LogManager.info("外部 FTP 控制接口已${if (externalControl.isChecked) "启用" else "关闭"}")
    }

    private fun togglePerformanceOptimization() {
        performanceOptimizationDisabled.toggle()
        val disabled = performanceOptimizationDisabled.isChecked
        settings.edit().putBoolean(Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED, disabled).apply()
        FtpService.sendEmptyMessage(if (disabled) FtpService.MESSAGE_WAKELOCK_RELEASE else FtpService.MESSAGE_WAKELOCK_ACQUIRE)
        FtpService.sendEmptyMessage(FtpService.MESSAGE_WIFI_LOCK_RELOAD)
        LogManager.info("性能优化已${if (disabled) "关闭" else "启用"}")
    }

    private fun toggleChaCha20() {
        chacha20.toggle()
        val enabled = chacha20.isChecked
        settings.edit().putBoolean(Constants.PreferenceConsts.FTPS_CHACHA20_PREFERRED, enabled).apply()
        if (enabled) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.attention_chacha20_restart)
                .setPositiveButton(R.string.dialog_button_confirm, null)
                .show()
        }
        LogManager.info("FTPS ChaCha20 优先已${if (enabled) "开启" else "关闭"}（下次启动生效）")
    }

    private fun togglePerformanceMode() {
        val enabled = !newFilesystem.isChecked
        if (!FtpService.updatePerformanceMode(this, enabled)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.attention_ftp_must_be_fully_stopped)
                .setPositiveButton(R.string.dialog_button_confirm, null)
                .show()
            return
        }
        newFilesystem.isChecked = enabled
        LogManager.info("性能优先模式已${if (enabled) "启用" else "关闭"}（重启后新的Apache调度才会生效）")
    }

    private fun socketBufferRequest(): Int = settings.getInt(
        Constants.PreferenceConsts.FTP_SOCKET_BUFFER_REQUEST,
        Constants.PreferenceConsts.FTP_SOCKET_BUFFER_SYSTEM_DEFAULT
    ).takeIf {
        it == Constants.PreferenceConsts.FTP_SOCKET_BUFFER_4M ||
            it == Constants.PreferenceConsts.FTP_SOCKET_BUFFER_8M ||
            it == Constants.PreferenceConsts.FTP_SOCKET_BUFFER_16M
    } ?: Constants.PreferenceConsts.FTP_SOCKET_BUFFER_SYSTEM_DEFAULT

    private fun socketBufferLabel(bytes: Int): String = when (bytes) {
        Constants.PreferenceConsts.FTP_SOCKET_BUFFER_4M -> "4 MB"
        Constants.PreferenceConsts.FTP_SOCKET_BUFFER_8M -> "8 MB"
        Constants.PreferenceConsts.FTP_SOCKET_BUFFER_16M -> "16 MB"
        else -> getString(R.string.setting_socket_buffer_system_default)
    }

    private fun showSocketBufferDialog() {
        val values = intArrayOf(
            Constants.PreferenceConsts.FTP_SOCKET_BUFFER_SYSTEM_DEFAULT,
            Constants.PreferenceConsts.FTP_SOCKET_BUFFER_4M,
            Constants.PreferenceConsts.FTP_SOCKET_BUFFER_8M,
            Constants.PreferenceConsts.FTP_SOCKET_BUFFER_16M
        )
        val labels = arrayOf(
            getString(R.string.setting_socket_buffer_system_default), "4 MB", "8 MB", "16 MB"
        )
        val current = socketBufferRequest()
        val checked = values.indexOf(current)
        SegmentedChoiceDialog.show(this, R.string.setting_socket_buffer_choice_title, labels, checked) { which ->
            val selected = values[which]
            settings.edit().putInt(Constants.PreferenceConsts.FTP_SOCKET_BUFFER_REQUEST, selected).apply()
            TcpSocketBuffer.configure(selected)
            socketBufferValue.text = labels[which]
            LogManager.info(getString(R.string.setting_socket_buffer_request_applied, labels[which]))
        }
    }

    private fun toggleDesktopClock() {
        desktopClock.toggle()
        settings.edit().putBoolean(Constants.PreferenceConsts.DESKTOP_CLOCK_ENABLED, desktopClock.isChecked).apply()
        LogManager.info("时钟样式已${if (desktopClock.isChecked) "启用" else "关闭"}")
        refresh()
    }

    private fun refreshWhitelist() {
        if (destroyed) return
        val service = userService ?: return
        try {
            worker.execute {
                try {
                    val enabled = service.isDozeWhitelisted()
                    settings.edit().putBoolean(Constants.PreferenceConsts.SHIZUKU_DOZE_WHITELISTED, enabled).apply()
                    uiHandler.post { if (!destroyed && !isFinishing) doze.isChecked = enabled }
                } catch (error: Exception) {
                    LogManager.debug("读取 Shizuku Doze 状态失败: ${error.javaClass.simpleName}")
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            LogManager.debug("Shizuku 状态任务已取消")
        }
    }

    private companion object { const val REQUEST_SHIZUKU = 62015 }
}
