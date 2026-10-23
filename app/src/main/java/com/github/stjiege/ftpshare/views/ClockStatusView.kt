package com.github.stjiege.ftpshare.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Matching 1.6dp strokes; no WebView/SVG support required for system indicators. */
class ClockStatusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    var percent = -1
        private set
    var charging = false
        private set
    private var network = 0
    private var dim = false
    // 预分配：桌钟每秒重绘一次，在 onDraw 里 new Path() 会持续制造短命对象喂给 GC。
    private val boltPath = Path()
    fun update(percent: Int, charging: Boolean, network: Int, dim: Boolean) {
        if (this.percent == percent && this.charging == charging && this.network == network && this.dim == dim) return
        this.percent = percent; this.charging = charging; this.network = network; this.dim = dim
        contentDescription = "电池 " + (if (percent < 0) "未知" else "$percent%") + (if (charging) "，充电中" else "") + when(network) { 1 -> "，Wi-Fi"; 2 -> "，蜂窝数据"; else -> "，网络未连接" }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(resources.displayMetrics.density, resources.displayMetrics.density)
        paint.color = if (percent in 0..19) Color.rgb(255,82,82) else Color.rgb(204,204,204)
        paint.alpha = if (percent in 0..19 && dim) 65 else 255
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(2f,5f,18f,31f,2f,2f,paint)
        canvas.drawLine(7f,2f,13f,2f,paint)
        paint.style = Paint.Style.FILL
        if (percent > 0) canvas.drawRect(5f,28f-20f*percent.coerceIn(0,100)/100f,15f,28f,paint)
        if (charging) {
            // Lightning alongside the battery remains legible even at zero charge.
            paint.style = Paint.Style.STROKE
            boltPath.rewind()
            boltPath.moveTo(29f,9f);boltPath.lineTo(23f,18f);boltPath.lineTo(29f,18f);boltPath.lineTo(24f,26f)
            canvas.drawPath(boltPath,paint)
        }
        paint.color=Color.rgb(204,204,204);paint.alpha=255;paint.style=Paint.Style.STROKE
        if(network==1){
            canvas.drawArc(39f,7f,67f,35f,225f,90f,false,paint)
            canvas.drawArc(44f,12f,62f,30f,225f,90f,false,paint)
            canvas.drawArc(49f,17f,57f,25f,225f,90f,false,paint)
            paint.style=Paint.Style.FILL;canvas.drawCircle(53f,24f,1.4f,paint)
        } else if(network==2){
            for(i in 0..3)canvas.drawRoundRect(41f+i*6,23f-i*5,44f+i*6,28f,1f,1f,paint)
        }
        canvas.restore()
    }
}
