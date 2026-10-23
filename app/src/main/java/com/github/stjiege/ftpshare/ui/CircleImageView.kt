package com.github.stjiege.ftpshare.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 圆形头像：在 onDraw 里用 canvas 自己裁圆，**不走 View.setClipToOutline()**。
 *
 * 之前的写法是普通 ImageView + `android:clipToOutline="true"` + `outlineProvider="background"`
 * （轮廓由一个 oval 的 background drawable 提供，那个 drawable 已随本次改动删除）。
 * 那条路径依赖渲染管线的 outline 裁剪，在 Android 10 及更老的设备上不生效 ——
 * 头像显示成方的，而新版系统是圆的。
 *
 * 这里改成自己在 onDraw 里 clipPath：椭圆是凸路径，API 18+ 的硬件加速 canvas 都支持
 * clipPath，各系统版本表现一致，也不再需要 background 提供轮廓。
 */
class CircleImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val clipPath = Path()

    override fun onDraw(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        if (diameter <= 0f) {
            super.onDraw(canvas)
            return
        }
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        clipPath.reset()
        clipPath.addOval(left, top, left + diameter, top + diameter, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }
}
