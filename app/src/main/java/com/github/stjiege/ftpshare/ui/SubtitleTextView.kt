package com.github.stjiege.ftpshare.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 副标题专用 TextView —— 只是把字体换成 [AppTypeface] 里那套，
 * 其余行为完全等同 AppCompatTextView（布局里原有的 style/属性照旧生效，
 * `style="@style/SettingsSubtitle"` 会由主题的 defStyleRes 机制正常套用）。
 *
 * 为什么用自定义控件而不是在 SettingsSubtitle 样式里写 android:fontFamily：
 * 用 @font/xxx 走样式在 API 26 以下要靠 AppCompat 接管，而本工程主题是
 * MaterialComponents 的 **Bridge** 版本，属性解析路径容易静默退回默认字体
 * （同一类坑本项目已经踩过：materialXxxStyle 解析到 0 后静默退回）。
 * 直接在构造函数里赋 typeface 与主题无关，23–34 全版本表现一致。
 */
class SubtitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        typeface = AppTypeface.get(context)
    }
}
