package com.github.stjiege.ftpshare.views

import android.net.TrafficStats
import android.os.Process
import com.github.stjiege.ftpshare.utils.TrafficRates
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.filesystem.TransferSlots
import com.github.stjiege.ftpshare.ui.AppTypeface
import com.github.stjiege.ftpshare.R
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 一格读数。pending=true 表示还在等基线，界面显示"采样中…"。 */
internal data class ResourceReading(val percent: Double?, val detail: String, val pending:Boolean=false)

/**
 * 采样基线跨页面保留。
 *
 * 切走再回来时视图会被重建，如果基线跟着一起丢掉，CPU 与上下行都得重新凑两次采样才能出数，
 * 于是每次进页面都会先闪一次"采样中…"。这里只存数据与采样器实例，不持有线程：
 * 离开页面时 executor 照旧停掉，不会有后台耗电。
 */
internal object ResourceSamples {
    /** 上一次渲染出来的六格读数，重新进页面时直接回填，避免闪一次"采样中…"。 */
    @Volatile var last: List<ResourceReading>? = null
    /** Keep disk readings across pause/resume; reopening the screen does not trigger extra scans. */
    @Volatile var disks: Pair<ResourceReading,ResourceReading>?=null
    @Volatile var disksAt=0L
    @Volatile var cpu: ResourceReading = ResourceReading(null,"",true)
    @Volatile var memory: ResourceReading = ResourceReading(null,"",true)
    @Volatile var nextCpu=0L
    @Volatile var batteryTemperaturePercent: Double? = null
    @Volatile var nextBatteryTemperature=0L
    @Volatile var batteryTemperatureFailures=0
    @Volatile var batteryTemperatureDisabled=false
    @Volatile var sampler: PeakCoreSampler? = null
    val traffic=TrafficRates()
}

/** Visible-screen-only sampling. No shell, root, broad storage access or FTP-thread work. */
class ResourcePanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private data class Cell(val value: TextView, val bar: ProgressBar, val fill:GradientDrawable)
    private val main=Handler(Looper.getMainLooper())
    private var worker: ScheduledExecutorService?=null
    private var generation=0
    private val cells=ArrayList<Cell>()
    init {
        orientation=VERTICAL
        setPadding(dp(16),dp(4),dp(16),dp(8))
        val titles=intArrayOf(R.string.resource_app_memory,R.string.resource_internal,R.string.resource_app_cpu,R.string.resource_removable,R.string.resource_network,R.string.resource_connections)
        for(row in 0 until (titles.size+1)/2){
            val line=LinearLayout(context).apply {orientation=HORIZONTAL;isBaselineAligned=false}
            addView(line,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            for(col in 0..1){
                if(row*2+col>=titles.size)break
                val tile=LinearLayout(context).apply {orientation=VERTICAL;setPadding(if(col==0)0 else dp(10),dp(6),if(col==0)dp(10) else 0,dp(6))}
                line.addView(tile,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
                // 标题与读数都换成与副标题同源的字体（ui/AppTypeface）。字体是子集字库，
                // 生僻字/符号由系统逐字回退，读数里的数字与单位（MB/s、%）不受影响。
                tile.addView(TextView(context).apply {setText(titles[row*2+col]);textSize=13f;setTextColor(Color.rgb(75,85,99));typeface=AppTypeface.get(context)})
                val value=TextView(context).apply {text=context.getString(R.string.resource_sampling);typeface=AppTypeface.get(context);textSize=12f;setTextColor(Color.rgb(107,114,128));includeFontPadding=false}
                tile.addView(value,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply {topMargin=dp(2)})
                val fill=GradientDrawable().apply {setColor(Color.rgb(76,137,190));cornerRadius=dp(3).toFloat()}
                val bar=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=1000;isIndeterminate=false
                    val track=GradientDrawable().apply {setColor(Color.rgb(235,238,242));cornerRadius=dp(3).toFloat()}
                    progressDrawable=if(row*2+col==2 || row*2+col==5) {
                        // The orange temperature/slot fill shares the bar bounds and sits below the blue fill.
                        val overlay=GradientDrawable().apply {setColor(Color.rgb(239,140,0));cornerRadius=dp(3).toFloat()}
                        LayerDrawable(arrayOf(track,ClipDrawable(overlay,Gravity.LEFT,ClipDrawable.HORIZONTAL),ClipDrawable(fill,Gravity.LEFT,ClipDrawable.HORIZONTAL))).apply {
                            setId(0,android.R.id.background)
                            setId(1,android.R.id.secondaryProgress)
                            setId(2,android.R.id.progress)
                        }
                    } else LayerDrawable(arrayOf(track,ClipDrawable(fill,Gravity.LEFT,ClipDrawable.HORIZONTAL))).apply {setId(0,android.R.id.background);setId(1,android.R.id.progress)}
                }
                tile.addView(bar,LayoutParams(LayoutParams.MATCH_PARENT,dp(5)).apply {topMargin=dp(3)})
                cells.add(Cell(value,bar,fill))
            }
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    fun start() {
        if(worker!=null)return
        val token=++generation
        val app=context.applicationContext
        // 采样器跨页面复用：它内部保存着上次的核心忙时计数 / /proc/self/stat 快照，
        // 复用后回到页面的第一拍就能算出真实百分比，不必再等一个"采样中"周期。
        val cpuSampler=ResourceSamples.sampler ?: PeakCoreSampler(app).also { ResourceSamples.sampler=it }
        // 失败计数按"本次停留"计：换一次页面重新给普通权限采样机会。
        cpuSampler.resetFailures()
        val preferences=app.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE)
        ResourceSamples.batteryTemperatureFailures=preferences.getInt(
            Constants.PreferenceConsts.BATTERY_TEMPERATURE_FAILURES,0).coerceIn(0,3)
        ResourceSamples.batteryTemperatureDisabled=preferences.getBoolean(
            Constants.PreferenceConsts.BATTERY_TEMPERATURE_UNAVAILABLE,
            Constants.PreferenceConsts.BATTERY_TEMPERATURE_UNAVAILABLE_DEFAULT)
                || ResourceSamples.batteryTemperatureFailures>=3
        val traffic=ResourceSamples.traffic
        // 立刻回填上一次的读数，切回本页面不会先闪一次"采样中…"。
        val cached=ResourceSamples.last
        if(cached!=null) main.post { if(token==generation) render(cached) }
        worker=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"resource-panel").apply {isDaemon=true}}.also { executor->
            executor.scheduleWithFixedDelay({
                val now=SystemClock.elapsedRealtime()
                if(now>=ResourceSamples.nextCpu) {
                    ResourceSamples.cpu=safely {cpuSampler.sample(app)}
                    ResourceSamples.memory=safely {val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory);val bytes=memory.totalPss.toLong()*1024;ResourceReading((bytes.toDouble()/(500L*1024*1024)*100).coerceIn(0.0,100.0),Formatter.formatShortFileSize(app,bytes))}
                    ResourceSamples.nextCpu=SystemClock.elapsedRealtime()+3000L
                }
                if(now>=ResourceSamples.nextBatteryTemperature && !ResourceSamples.batteryTemperatureDisabled) {
                    sampleBatteryTemperature(app)
                    ResourceSamples.nextBatteryTemperature=SystemClock.elapsedRealtime()+60_000L
                }
                val rates=try {traffic.sample(TrafficStats.getUidTxBytes(Process.myUid()),TrafficStats.getUidRxBytes(Process.myUid()),SystemClock.elapsedRealtime())}catch(e:Exception){traffic.sample(-1,-1,SystemClock.elapsedRealtime())}
                var diskReadings=ResourceSamples.disks
                if(diskReadings==null || now-ResourceSamples.disksAt>=60000L) {
                    val internal=safely {disk(app,Environment.getDataDirectory())}
                    val removable=safely {val card=app.getExternalFilesDirs(null).filterNotNull().firstOrNull {Environment.isExternalStorageRemovable(it)&&Environment.getExternalStorageState(it) in arrayOf(Environment.MEDIA_MOUNTED,Environment.MEDIA_MOUNTED_READ_ONLY)};if(card==null)ResourceReading(null,app.getString(R.string.resource_no_card)) else disk(app,card)}
                    diskReadings=Pair(internal,removable)
                    ResourceSamples.disks=diskReadings;ResourceSamples.disksAt=SystemClock.elapsedRealtime()
                }
                val data=listOf(ResourceSamples.memory,diskReadings.first,ResourceSamples.cpu,diskReadings.second,networkReading(rates[0],rates[1]),connectionReading(app),transferReading(app))
                ResourceSamples.last=data
                main.post {if(token==generation&&worker!=null)render(data)}
            },0,1,TimeUnit.SECONDS)
        }
    }
    /** 上行 + 下行**合并**成一条「网络」读数；条形 100% 对应 600 Mbps（见 TrafficRates.progress）。 */
    private fun networkReading(tx:Double,rx:Double):ResourceReading {
        if(tx==TrafficRates.UNKNOWN || rx==TrafficRates.UNKNOWN)
            return ResourceReading(null,context.getString(R.string.resource_cpu_restricted))
        if(tx==TrafficRates.PENDING || rx==TrafficRates.PENDING)
            return ResourceReading(null,context.getString(R.string.resource_sampling),true)
        val total=tx+rx
        return ResourceReading(TrafficRates.progress(total),String.format(Locale.getDefault(),"%.2f MB/s",total/1_000_000.0))
    }
    /**
     * 连接数：条形进度 = 已登录会话 / 设置里的「最大连接数」。
     * 分母每次采样重新读一次配置，所以在服务端设置里改完上限回来立刻就变，不用重启界面。
     * 服务未运行时读数为 -1，按 0 显示。
     */
    private fun connectionReading(context:Context):ResourceReading {
        val max=context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE)
                .getInt(Constants.PreferenceConsts.MAX_LOGIN_NUM,Constants.PreferenceConsts.MAX_LOGIN_NUM_DEFAULT)
                .coerceAtLeast(1)
        val active=FtpService.getActiveConnectionCount().coerceAtLeast(0)
        return ResourceReading(active.toDouble()/max*100,"$active / $max")
    }
    private fun transferReading(context:Context):ResourceReading {
        val slots=TransferSlots.snapshot()
        // The setting is the bar's denominator.  The semaphore capacity can temporarily
        // differ while a service is being restarted (or while an old transfer is draining),
        // which previously made 100% correspond to the stale runtime capacity instead of the
        // value shown in settings.
        val max=context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE)
            .getInt(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS,Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_DEFAULT)
            .coerceIn(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MIN,Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MAX)
        val used=slots.inUse.coerceIn(0,max)
        return ResourceReading(used.toDouble()/max*100,"$used / $max")
    }
    private fun render(data:List<ResourceReading>) {
        for(i in cells.indices) {
            val item=data[i];val cell=cells[i];val percent=item.percent
            val unknown=percent==null && !item.pending
            cell.value.text=when {
                item.pending -> context.getString(R.string.resource_sampling)
                unknown -> context.getString(R.string.resource_cpu_restricted)
                i==2 -> String.format(Locale.getDefault(),"%.1f%%",percent)
                else -> item.detail
            }
            cell.fill.setColor(when {
                unknown -> Color.rgb(211,47,47)
                // 磁盘(1/3) 用量过 90% 转橙，沿用原有逻辑。
                (i==1 || i==3) && percent!=null && percent>=90.0 -> Color.rgb(239,140,0)
                // 网络(4) 跑满量程（600 Mbps，progress 已封顶 100%）也转橙：
                // 条长由下面的 coerceIn 钉在 100%，永远不溢出，只用颜色表达"顶格"。
                i==4 && percent!=null && percent>=100.0 -> Color.rgb(239,140,0)
                else -> Color.rgb(76,137,190)
            })
            val barPercent = if (i == 2 || i == 4) gentleProgress(percent) else percent
            cell.bar.progress=if(unknown)1000 else if(barPercent==null)0 else (barPercent.coerceIn(0.0,100.0)*10).toInt()
            if(i==2) {
                cell.bar.secondaryProgress=((ResourceSamples.batteryTemperaturePercent?:0.0).coerceIn(0.0,100.0)*10).toInt()
            }
            cell.bar.alpha=1f
            cell.bar.contentDescription=cell.value.text
            if(i==5) {
                val slots=data.getOrNull(6)
                cell.bar.secondaryProgress=((slots?.percent?:0.0).coerceIn(0.0,100.0)*10).toInt()
                cell.bar.contentDescription="${context.getString(R.string.resource_connections)} ${item.detail}; ${context.getString(R.string.item_transfer_slots)} ${slots?.detail?:"0"}"
            }
        }
    }
    /** Reads the public sticky battery status intent off the UI/FTP threads, at most once per minute. */
    private fun sampleBatteryTemperature(app:Context) {
        try {
            val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if(battery==null || !battery.hasExtra(android.os.BatteryManager.EXTRA_TEMPERATURE)) {
                batteryTemperatureReadFailed(app)
                return
            }
            val raw=battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE)
            if(raw==Int.MIN_VALUE) {
                batteryTemperatureReadFailed(app)
                return
            }
            val celsius=raw/10.0
            ResourceSamples.batteryTemperaturePercent=((celsius-27.0)/15.0*100.0).coerceIn(0.0,100.0)
            ResourceSamples.batteryTemperatureFailures=0
            app.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE).edit()
                .remove(Constants.PreferenceConsts.BATTERY_TEMPERATURE_FAILURES).apply()
        } catch(_:Exception) {
            batteryTemperatureReadFailed(app)
        }
    }
    private fun batteryTemperatureReadFailed(app:Context) {
        ResourceSamples.batteryTemperatureFailures++
        val preferences=app.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE)
        preferences.edit().putInt(Constants.PreferenceConsts.BATTERY_TEMPERATURE_FAILURES,
            ResourceSamples.batteryTemperatureFailures.coerceAtMost(3)).apply()
        if(ResourceSamples.batteryTemperatureFailures>=3) {
            ResourceSamples.batteryTemperatureDisabled=true
            ResourceSamples.batteryTemperaturePercent=null
            preferences.edit().putBoolean(Constants.PreferenceConsts.BATTERY_TEMPERATURE_UNAVAILABLE,true).apply()
        }
    }
    /**
     * A mild perceptual curve for CPU and network bars only. The displayed number remains the
     * measured value; this makes small non-zero activity visible without exaggerating mid/high use.
     */
    private fun gentleProgress(percent: Double?): Double? = percent?.let {
        100.0 * Math.pow((it.coerceIn(0.0, 100.0) / 100.0), 0.8)
    }
    fun stop(){generation++;worker?.shutdownNow();worker=null;main.removeCallbacksAndMessages(null)}
    override fun onDetachedFromWindow(){stop();super.onDetachedFromWindow()}
    private fun safely(action:()->ResourceReading):ResourceReading=try{action()}catch(e:Exception){ResourceReading(null,context.getString(R.string.resource_unavailable))}
    private fun disk(context:Context,path:File):ResourceReading {val stat=StatFs(path.absolutePath);return capacity(context,stat.totalBytes-stat.availableBytes,stat.totalBytes)}
    private fun capacity(context:Context,used:Long,total:Long):ResourceReading {
        if(total<=0)return ResourceReading(null,context.getString(R.string.resource_unavailable))
        val amount=used.coerceIn(0,total)
        return ResourceReading(amount.toDouble()/total*100,Formatter.formatShortFileSize(context,amount)+" / "+Formatter.formatShortFileSize(context,total))
    }
}

/** Real CPU usage from ordinary-app interfaces only — no Shizuku, no shell, no root.
 *
 * 两个来源**按 Android 版本分工**，不是互为冗余：
 * 1. cgroup v1 cpuacct（cpuacct.usage_percpu）：逐核忙时计数，**旧安卓**可读；
 * 2. /proc/self/stat 的 utime+stime（字段 14/15）：任务组累计时钟，**Android 10-14** 仍可读，
 *    覆盖本进程全部线程，按逻辑核数归一——跑满所有核 = 100%，而不是 N×100%。
 *
 * 回退规则：
 * - 持久化只记「结论」：哪个可用就落盘哪个（cpuacct / procstat），下次启动直接按它采样，
 *   不再重复探测；
 * - 当前方法连续失败 [MAX_SOURCE_FAILURES] 次 → 本次运行内切到另一种接着试；
 *   另一种可用就落盘它（"1 不可用 2 可用 → 下次直接按 2"）；
 * - **两种都不可用是设备的稳定属性**（系统接口层面被关掉，不是瞬时故障）→ 落盘 none，
 *   本次运行显示"未知"，下次启动也不再判断、不再探测；
 * - 两套基线（cpuacct 计数 / self 时钟）互相独立：切换来源后的第一拍天然是
 *   "采样中"，绝不会拿 A 源的基线去减 B 源的读数。
 *
 * 判定（配置直接命中 or 探测）放在进程级单例里做一次；结论跨进程持久化。 */
internal class PeakCoreSampler(context:Context) {
    companion object {
        /** 一种来源连续失败这么多次，本次运行切到另一种。 */
        private const val MAX_SOURCE_FAILURES = 3
    }
    private val prefs=context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE)
    // cpuacct 基线。
    private var accountingFile: File?=null
    private var accountingChecked=false
    private var lastUsage: List<Long>?=null
    private var lastNanos=0L
    // /proc/self/stat 基线。Fields 14/15 are task-group cumulative user+kernel ticks.
    private var lastSelfTicks:Long?=null
    private var lastSelfNanos=0L
    // 回退状态：仅存在于本次运行。active=null 表示还没判定或已放弃。
    private var resolved=false
    private var active:String?=null
    private val failures=HashMap<String,Int>(4)
    private var denied=false

    /** 每次进入页面重新计失败次数：换一次页面，当前方法重新获得机会。 */
    fun resetFailures(){failures.clear();denied=false}

    /** 持久化「结论」。只在真正变化时写盘；none 表示两种接口在本机都不可用。 */
    private fun saveMethod(newMethod:String) {
        val current=prefs.getString(Constants.PreferenceConsts.CPU_SAMPLER_METHOD,
                Constants.PreferenceConsts.CPU_SAMPLER_METHOD_DEFAULT)
        if(current==newMethod)return
        prefs.edit().putString(Constants.PreferenceConsts.CPU_SAMPLER_METHOD,newMethod).apply()
    }

    fun sample(context:Context):ResourceReading {
        if(denied)return ResourceReading(null,context.getString(R.string.resource_cpu_restricted))
        if(!resolved) {
            resolved=true
            val configured=prefs.getString(Constants.PreferenceConsts.CPU_SAMPLER_METHOD,
                    Constants.PreferenceConsts.CPU_SAMPLER_METHOD_DEFAULT)
            if(configured==Constants.PreferenceConsts.CPU_SAMPLER_METHOD_CPUACCT
                    || configured==Constants.PreferenceConsts.CPU_SAMPLER_METHOD_PROCSTAT) {
                active=configured
            } else if(configured==Constants.PreferenceConsts.CPU_SAMPLER_METHOD_NONE) {
                // 结论已是 none：两种接口在本机都不可用（设备的稳定属性）。
                // 直接显示"未知"，本次运行与以后每次启动都不再判断、不再探测。
                denied=true
                return ResourceReading(null,context.getString(R.string.resource_cpu_restricted))
            } else {
                // 自动探测（仅首次运行走到这里）：依次试两种普通接口。探测本身就是第一次
                // 读取，基线随之建立，本拍直接显示"采样中"，下一拍出真实百分比。
                val found=arrayOf(Constants.PreferenceConsts.CPU_SAMPLER_METHOD_CPUACCT,
                        Constants.PreferenceConsts.CPU_SAMPLER_METHOD_PROCSTAT)
                        .firstOrNull { trySource(it,context)!=null }
                if(found==null) {
                    denied=true
                    saveMethod(Constants.PreferenceConsts.CPU_SAMPLER_METHOD_NONE)
                    return ResourceReading(null,context.getString(R.string.resource_cpu_restricted))
                }
                active=found
                return ResourceReading(null,context.getString(R.string.resource_sampling),true)
            }
        }
        val src=active!!
        val reading=trySource(src,context)
        if(reading!=null){saveMethod(src);return reading}
        // 失败计一次；没到上限就继续显示"采样中"，给下几拍机会。
        val tried=(failures[src]?:0)+1
        failures[src]=tried
        if(tried<MAX_SOURCE_FAILURES)
            return ResourceReading(null,context.getString(R.string.resource_sampling),true)
        // 连续失败到上限：本次运行切到另一种普通接口接着试。
        // 另一种可用的话 saveMethod(src) 会在它成功时落盘（"1 不可用 2 可用 → 下次按 2"）。
        val other=if(src==Constants.PreferenceConsts.CPU_SAMPLER_METHOD_CPUACCT)
            Constants.PreferenceConsts.CPU_SAMPLER_METHOD_PROCSTAT
            else Constants.PreferenceConsts.CPU_SAMPLER_METHOD_CPUACCT
        active=other
        if((failures[other]?:0)>=MAX_SOURCE_FAILURES) {
            // 两种都持续失败：本机两个接口都不可用，是设备的稳定属性。落盘 none，
            // 本次运行显示"未知"，下次启动也不再判断、不再探测。
            denied=true
            saveMethod(Constants.PreferenceConsts.CPU_SAMPLER_METHOD_NONE)
            return ResourceReading(null,context.getString(R.string.resource_cpu_restricted))
        }
        return ResourceReading(null,context.getString(R.string.resource_sampling),true)
    }

    private fun trySource(src:String,context:Context):ResourceReading? =
        if(src==Constants.PreferenceConsts.CPU_SAMPLER_METHOD_CPUACCT) sampleAccounting(context)
        else sampleSelfStat(context)

    /** Root cpuacct includes all tasks. Never use a foreground/app subgroup as device usage.
     *  @return 成功时返回 Reading（首次为 null peak 的“采样中”也是成功）；失败返回 null。 */
    private fun sampleAccounting(context:Context):ResourceReading? {
        if(!accountingChecked) {
            accountingChecked=true
            try {
                // Discover v1 cpuacct mount roots; do not assume a vendor-specific mount path.
                val mounts=File("/proc/self/mountinfo").readLines()
                for(line in mounts) {
                    val halves=line.split(" - ",limit=2)
                    if(halves.size!=2)continue
                    val left=halves[0].split(' ');val right=halves[1].split(' ')
                    if(left.size<5 || right.size<3 || left[3]!="/" || right[0]!="cgroup" || !right[2].split(',').contains("cpuacct"))continue
                    val mount=left[4].replace("\\040"," ").replace("\\134","\\")
                    val file=File(mount,"cpuacct.usage_percpu")
                    val counters=readUsage(file)
                    if(counters.isNotEmpty()) {accountingFile=file;break}
                }
            }catch(ignored:Exception){ /* No readable root per-core accounting on this OS. */ }
        }
        val file=accountingFile?:return null
        return try {
            val now=SystemClock.elapsedRealtimeNanos()
            val counters=readUsage(file)
            val old=lastUsage
            val elapsed=now-lastNanos
            lastUsage=counters;lastNanos=now
            if(old==null || counters.size!=old.size || elapsed<=0)ResourceReading(null,context.getString(R.string.resource_sampling),true)
            else {
                var peak:Double?=null
                for(i in counters.indices) {
                    val delta=counters[i]-old[i]
                    if(delta>=0){val value=(delta.toDouble()/elapsed*100).coerceIn(0.0,100.0);if(peak==null||value>peak)peak=value}
                }
                ResourceReading(peak,if(peak==null)context.getString(R.string.resource_sampling) else "",peak==null)
            }
        }catch(ignored:Exception){accountingFile=null;lastUsage=null;null}
    }

    /**
     * Ordinary-app fallback for modern Android, where global /proc/stat is restricted but
     * /proc/self/stat is readable. Fields 14/15 (utime/stime) are task-group counters, so the
     * delta covers every app thread. It is normalized by logical cores: a process using every
     * core is 100%, never N x 100%.
     */
    private fun sampleSelfStat(context:Context):ResourceReading? {
        val ticks=try {
            val line=File("/proc/self/stat").readText().trim()
            // comm is parenthesized and can theoretically contain spaces; parse only after its last ')'.
            val close=line.lastIndexOf(')')
            if(close<0)return null
            val fields=line.substring(close+1).trim().split(Regex("\\s+"))
            // After pid + comm, field index 0 is state (original field 3): utime/stime are 11/12.
            if(fields.size<=12)return null
            fields[11].toLong()+fields[12].toLong()
        }catch(ignored:Exception){return null}
        val now=SystemClock.elapsedRealtimeNanos()
        val old=lastSelfTicks
        val elapsed=now-lastSelfNanos
        lastSelfTicks=ticks
        lastSelfNanos=now
        if(old==null || elapsed<=0 || ticks<old)
            return ResourceReading(null,context.getString(R.string.resource_sampling),true)
        val tickRate=try { Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L) }catch(ignored:Exception){100L}
        val cores=Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val percent=((ticks-old).toDouble()*1_000_000_000.0/elapsed/tickRate/cores*100.0)
            .coerceIn(0.0,100.0)
        return ResourceReading(percent,"")
    }

    private fun readUsage(file:File):List<Long> = file.readText().trim().split(Regex("\\s+")).map {it.toLong().also {value->require(value>=0)}}
}
