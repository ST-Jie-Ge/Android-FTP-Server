package com.github.stjiege.ftpshare.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 全应用唯一的字体入口 —— 目前只服务「日志正文 / 性能监视器 / 副标题」三处。
 *
 * 字体文件是 assets 里的**子集字库**（numGlyphs 4186，CJK 常用区 3758/20992 ≈ GB2312 一级字），
 * 覆盖常用汉字、ASCII 与常用标点；生僻字（翀喆犇垚玥…）、符号（✓ ✗ ← • ─ ° ※）、带圈数字
 * ④–⑩、℃ ℉ ㎡、emoji、CJK 扩展 A/B 都不在其中。
 *
 * 缺字**由 Android 平台逐字回退**（Minikin 按系统 fallback 链取字形），这里不做任何字形判断；
 * 代价是缺字处会画成系统字体，同一行出现两种字形风格 —— 这是已知取舍，不是 bug。
 *
 * **只对中文生效**：HarmonyOS Sans SC 是中文定制字库，英文下套用它反而让拉丁字形走子集里的
 * 那套（与系统 Noto Sans 风格打架，且子集对西文覆盖不完整）。因此只有当应用语言是「简体中文」
 * （显式选择，或跟随系统且系统为中文）时才返回这套字体，其余一律返回系统默认 —— 英文环境
 * 与 WebView 的字体都不受影响（WebView 走自己的 CSS/WebView 渲染，根本不经过这里）。
 *
 * 这里唯一自己实现的降级是「资源级」的：asset 缺失或损坏时退回 Typeface.DEFAULT，
 * 保证任何一张界面都不会因为字体加载失败而崩。
 */
object AppTypeface {

    private const val ASSET = "fonts/HarmonyOS_Sans_SC_Regular.ttf"

    @Volatile
    private var cached: Typeface? = null

    /** 缓存时生效的语言判定；语言切换会重建所有 Activity，缓存必须跟着失效。 */
    @Volatile
    private var cachedForChinese: Boolean? = null

    /**
     * 当前应用语言是否为中文。
     *
     * 优先看 AppCompat 的应用内语言（AppCompatDelegate.getApplicationLocales）：
     * 显式选了中文 → 是；显式选了英文 → 否；跟随系统（空列表）→ 回落到系统默认语言判断。
     * 这里刻意**不**读 SharedPreferences 里的旧 LANGUAGE_SETTING 档位 —— 它已被迁移到
     * AppCompat 机制，读档位会造成两份状态不同步（见 BaseActivity 的迁移注释）。
     */
    private fun isChineseLocale(): Boolean {
        val appLocales: LocaleListCompat = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            return appLocales.get(0)?.language == Locale.SIMPLIFIED_CHINESE.language
        }
        // 跟随系统：空列表。按系统当前语言判断。
        return Locale.getDefault().language == Locale.SIMPLIFIED_CHINESE.language
    }

    /**
     * 进程内只解析一次；Typeface 本身可跨界面复用。
     * 语言一旦切换（中文 ↔ 非中文），缓存失效重取 —— 因为切换语言会 recreate 全部 Activity，
     * 而本类是单例、字段跨重建存活，不失效的话英文界面会残留中文字体。
     */
    fun get(context: Context): Typeface {
        val chinese = isChineseLocale()
        cached?.let { if (cachedForChinese == chinese) return it }
        synchronized(this) {
            cached?.let { if (cachedForChinese == chinese) return it }
            val loaded = if (chinese) {
                try {
                    Typeface.createFromAsset(context.applicationContext.assets, ASSET)
                } catch (error: RuntimeException) {
                    // 字体资源缺失/损坏：退回系统默认字体，绝不向上抛。
                    null
                }
            } else {
                null
            }
            val resolved = loaded ?: Typeface.DEFAULT
            cached = resolved
            cachedForChinese = chinese
            return resolved
        }
    }

    /** 便于代码里创建的 TextView 使用（布局里的用 [SubtitleTextView]）。 */
    fun applyTo(view: TextView?) {
        if (view == null) return
        view.typeface = get(view.context)
    }
}
