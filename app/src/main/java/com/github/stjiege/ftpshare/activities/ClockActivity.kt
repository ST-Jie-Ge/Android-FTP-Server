package com.github.stjiege.ftpshare.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.utils.LogManager
import com.github.stjiege.ftpshare.views.ClockStatusView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Calendar

/** Local-only clock; the close button and Back are the only in-app exit actions. */
class ClockActivity : BaseActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var web: WebView?=null
    private lateinit var indicators: ClockStatusView
    private var resumed=false
    private var pageReady=false
    private var lastPayload=""
    private var error=""
    private var blink=false
    private var targetRunning: Boolean?=null
    private var commandAt=0L
    private var updateCount=0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        installBackHandler()
        requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_clock)
        indicators=findViewById(R.id.clock_status)
        findViewById<View>(R.id.clock_exit).setOnClickListener { finish() }
        web=findViewById<WebView>(R.id.clock_web).also { configure(it);it.loadUrl(CLOCK_URL) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.setBackgroundColor(Color.BLACK)
        view.overScrollMode=View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled=false;view.isHorizontalScrollBarEnabled=false
        view.isLongClickable=false;view.setOnLongClickListener { true }
        view.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=false
            builtInZoomControls=false;displayZoomControls=false;setSupportZoom(false)
            defaultTextEncodingName="utf-8";cacheMode=WebSettings.LOAD_NO_CACHE
            allowFileAccess=false;allowContentAccess=false
            allowFileAccessFromFileURLs=false;allowUniversalAccessFromFileURLs=false
            blockNetworkLoads=true;setSupportMultipleWindows(false)
        }
        view.webViewClient=object: WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?)=true
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?)=true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = filterResource(request?.url.toString())
            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = filterResource(url)
            override fun onPageFinished(view: WebView, url: String) {
                if(url!=CLOCK_URL)return
                pageReady=true;lastPayload=""
                view.evaluateJavascript("window.clockLifecycle && window.clockLifecycle($resumed)",null)
                if(resumed)pushState()
            }
        }
        view.addJavascriptInterface(ClockBridge(),"Android")
    }
    private fun filterResource(url: String?): WebResourceResponse? {
        if(url==CLOCK_URL||url==FONT_URL)return null
        return WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
    }
    override fun onResume() {
        super.onResume();resumed=true;web?.onResume();enterImmersive()
        web?.evaluateJavascript("window.clockLifecycle && window.clockLifecycle(true)",null)
        handler.removeCallbacks(tick);handler.post(tick)
    }
    override fun onPause() {
        resumed=false;handler.removeCallbacks(tick)
        web?.evaluateJavascript("window.clockLifecycle && window.clockLifecycle(false)",null)
        web?.onPause();super.onPause()
    }
    override fun onDestroy() {
        resumed=false;handler.removeCallbacksAndMessages(null)
        web?.let { it.removeJavascriptInterface("Android");(it.parent as? ViewGroup)?.removeView(it);it.destroy() }
        web=null;super.onDestroy()
    }
    /** 返回键改走 AndroidX 的 OnBackPressedDispatcher：原先重写 onBackPressed() 且不调 super，
     *  在 Android 13+ 的预测式返回手势下会被当成"事件没人处理"（预览动画缺失、极端情况不退出）。 */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {super.onWindowFocusChanged(hasFocus);if(hasFocus)enterImmersive()}
    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
    }
    private val tick=object: Runnable {
        override fun run() {
            if(!resumed)return
            updateIndicators()
            if(updateCount++%2==0)pushState()
            if(updateCount%60==1)applyBrightnessPolicy()
            handler.postDelayed(this,500L)
        }
    }
    private fun updateIndicators() {
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1)?:-1
        val percent=if(level>=0&&scale>0)(level*100/scale).coerceIn(0,100) else -1
        val plugged=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0
        val manager=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps=manager.activeNetwork?.let {manager.getNetworkCapabilities(it)}
        val network=when {caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->1;caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->2;else->0}
        blink=!blink;indicators.update(percent,plugged,network,blink && percent in 0..19)
    }
    private fun pushState() {
        if(!pageReady)return
        val running=FtpService.isFTPServiceRunning()
        val stopped=FtpService.isFTPServiceFullyStopped()
        val target=targetRunning
        if(target!=null) {
            if((target&&running)||(!target&&stopped))targetRunning=null
            else if(android.os.SystemClock.elapsedRealtime()-commandAt>15000L){targetRunning=null;error="FTP 切换未完成，请重试"}
        }
        val payload=JSONObject().put("running",running).put("busy",targetRunning!=null||(!running&&!stopped)).put("error",error).put("logs",LogManager.getClockLogText()).toString()
        if(payload!=lastPayload) {
            // JSON is transported as a quoted string. Log data is only assigned to textContent.
            val quoted=JSONObject.quote(payload).replace("\u2028","\\u2028").replace("\u2029","\\u2029")
            web?.evaluateJavascript("window.clockUpdate && window.clockUpdate(JSON.parse($quoted))",null)
            lastPayload=payload
        }
    }
    private inner class ClockBridge {
        @JavascriptInterface fun setFtp(enabled: Boolean) {
            handler.post {
                if(!resumed||!pageReady||isFinishing||targetRunning!=null)return@post
                val running=FtpService.isFTPServiceRunning()
                if(!running&&!FtpService.isFTPServiceFullyStopped())return@post
                error=""
                if(enabled==running){pushState();return@post}
                targetRunning=enabled;commandAt=android.os.SystemClock.elapsedRealtime()
                try {
                    if(enabled) {
                        if(!FtpService.startService(this@ClockActivity)){targetRunning=null;error="FTP 启动被拒绝，请检查权限"}
                    } else FtpService.stopService()
                }catch(e:RuntimeException){targetRunning=null;error="FTP 操作失败";LogManager.warning("时钟 FTP 操作失败: "+e.javaClass.simpleName)}
                pushState()
            }
        }
    }
    private fun applyBrightnessPolicy() {
        val base=try{Settings.System.getInt(contentResolver,Settings.System.SCREEN_BRIGHTNESS)/255f}catch(e:Exception){.5f}
        val hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val target=minOf(base.coerceIn(0f,1f),if(hour in 8..18).7f else .5f)
        if(window.attributes.screenBrightness!=target)window.attributes=window.attributes.apply {screenBrightness=target}
    }
    companion object {
        private const val CLOCK_URL="file:///android_asset/clock.html"
        private const val FONT_URL="file:///android_asset/fonts/WOX-Striped-Triple-Demo-1.otf"
    }
}
