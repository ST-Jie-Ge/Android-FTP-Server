package com.github.stjiege.ftpshare.fragments

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.Build
import android.view.*
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.activities.LogDirectoryActivity
import com.github.stjiege.ftpshare.activities.LogSettingsActivity
import com.github.stjiege.ftpshare.ui.AppTypeface
import com.github.stjiege.ftpshare.utils.LogManager

class LogFragment : androidx.fragment.app.Fragment() {
    private var login: TextView? = null
    private var runtime: TextView? = null
    private var loginScroll: ScrollView? = null
    private var runtimeScroll: ScrollView? = null
    private var firstResumeForView = false
    private var scrollToEndPending = false
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Avoid rebuilding up to 4,000 lines while another tab is active.
            // onResume performs one complete refresh when the user returns here.
            if (isVisible) {
                refresh(preserveScroll = false)
                view?.post {
                    loginScroll?.scrollTo(0, 0)
                    runtimeScroll?.scrollTo(0, 0)
                }
            }
        }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(LogManager.ACTION_UPDATED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                requireContext().registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (error: Exception) {
            LogManager.warning("注册日志刷新监听失败: ${error.javaClass.simpleName}")
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_logs, container, false)
    override fun onViewCreated(view: View, state: Bundle?) {
        login = view.findViewById(R.id.login_log_text)
        runtime = view.findViewById(R.id.runtime_log_text)
        loginScroll = view.findViewById(R.id.login_log_scroll)
        runtimeScroll = view.findViewById(R.id.runtime_log_scroll)
        // 字体统一由 ui/AppTypeface 提供（进程内只解析一次，asset 缺失时退回系统字体）。
        val logTypeface = AppTypeface.get(requireContext())
        login?.typeface = logTypeface
        runtime?.typeface = logTypeface
        view.findViewById<TextView>(R.id.save_logs).typeface = logTypeface
        view.findViewById<TextView>(R.id.open_log_directory).typeface = logTypeface
        view.findViewById<TextView>(R.id.open_log_settings).typeface = logTypeface
        view.findViewById<View>(R.id.save_logs).setOnClickListener {
            LogManager.saveAllAsync(requireContext()) { saved ->
                if (isAdded) Toast.makeText(requireContext(), if (saved == null) "保存失败" else "日志已保存：${saved.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
        view.findViewById<View>(R.id.open_log_directory).setOnClickListener {
            startActivity(Intent(requireContext(), LogDirectoryActivity::class.java))
        }
        view.findViewById<View>(R.id.open_log_settings).setOnClickListener {
            startActivity(Intent(requireContext(), LogSettingsActivity::class.java))
        }
        firstResumeForView = true
        refresh(preserveScroll = false)
        scrollToLatestEntries(view)
    }

    private fun refresh(preserveScroll: Boolean = true) {
        val context = context ?: return
        val loginY = if (preserveScroll) loginScroll?.scrollY ?: 0 else 0
        val runtimeY = if (preserveScroll) runtimeScroll?.scrollY ?: 0 else 0
        val enabled = LogManager.isLoggingEnabled(context)
        view?.findViewById<View>(R.id.save_logs)?.isEnabled = enabled
        if (!enabled) {
            login?.setText(R.string.logging_paused_message)
            runtime?.setText(R.string.logging_paused_message)
        } else {
            login?.text = LogManager.getLoginText().ifEmpty { "暂无登录记录" }
            runtime?.text = LogManager.getRuntimeText().ifEmpty { "暂无运行记录" }
        }
        if (preserveScroll && !scrollToEndPending) {
            view?.post {
                restoreScroll(loginScroll, loginY)
                restoreScroll(runtimeScroll, runtimeY)
            }
        }
    }

    private fun scrollToLatestEntries(root: View) {
        scrollToEndPending = true
        root.post {
            if (view === root) {
                loginScroll?.fullScroll(View.FOCUS_DOWN)
                runtimeScroll?.fullScroll(View.FOCUS_DOWN)
            }
            scrollToEndPending = false
        }
    }

    private fun restoreScroll(scrollView: ScrollView?, previousY: Int) {
        val scroll = scrollView ?: return
        val child = scroll.getChildAt(0) ?: return
        val maximumY = maxOf(0, child.height - scroll.height)
        scroll.scrollTo(0, minOf(previousY, maximumY))
    }

    override fun onResume() {
        super.onResume()
        if (firstResumeForView) {
            firstResumeForView = false
        } else {
            refresh(preserveScroll = true)
        }
    }
    fun refreshFromHost() {
        scrollToEndPending = true
        refresh(preserveScroll = false)
        view?.let { scrollToLatestEntries(it) }
    }
    override fun onDestroy() {
        if (receiverRegistered) {
            receiverRegistered = false
            // requireContext() throws IllegalStateException once the fragment is detached, and the
            // platform can also throw IllegalArgumentException here: both must be survivable.
            val attachedContext = context
            if (attachedContext != null) {
                try {
                    attachedContext.unregisterReceiver(receiver)
                } catch (_: Exception) {
                    // Teardown must never crash the app.
                }
            }
        }
        super.onDestroy()
    }
}
