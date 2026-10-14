package com.ranzxm.browserak

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Shared 24-unit line icons; no font glyphs or bundled icon library. */
internal class BrowserIcon(private val name: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) {
            val path = Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]) }
            canvas.drawPath(path, paint)
        }
        when (name) {
            "back" -> line(15f, 5f, 8f, 12f, 15f, 19f)
            "forward", "go" -> { line(9f, 5f, 16f, 12f, 9f, 19f); if (name == "go") line(4f, 12f, 16f, 12f) }
            "home" -> { line(3f, 10f, 12f, 3f, 21f, 10f); line(6f, 9f, 6f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 18f, 21f, 18f, 9f) }
            "search" -> { canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); line(15f, 15f, 21f, 21f) }
            "tabs" -> { canvas.drawRoundRect(5f, 6f, 20f, 21f, 3f, 3f, paint); line(3f, 16f, 3f, 4f, 16f, 4f) }
            "grab" -> { line(3f, 8f, 3f, 3f, 8f, 3f); line(16f, 3f, 21f, 3f, 21f, 8f); line(3f, 16f, 3f, 21f, 8f, 21f); line(16f, 21f, 21f, 21f, 21f, 16f); line(10f, 8f, 16f, 12f, 10f, 16f, 10f, 8f) }
            "menu" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f) }
            "bookmark" -> line(6f, 3f, 18f, 3f, 18f, 21f, 12f, 17f, 6f, 21f, 6f, 3f)
            "download" -> { line(12f, 3f, 12f, 15f); line(7f, 10f, 12f, 15f, 17f, 10f); line(4f, 16f, 4f, 21f, 20f, 21f, 20f, 16f) }
            "script" -> { line(7f, 6f, 2f, 12f, 7f, 18f); line(17f, 6f, 22f, 12f, 17f, 18f); line(14f, 4f, 10f, 20f) }
            "history" -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 6f, 12f, 12f, 16f, 14f) }
            "shield" -> { line(12f, 2f, 20f, 5f, 19f, 15f, 16f, 19f, 12f, 22f, 8f, 19f, 5f, 15f, 4f, 5f, 12f, 2f); line(8f, 12f, 11f, 15f, 16f, 9f) }
            "refresh" -> { canvas.drawArc(4f, 4f, 20f, 20f, 45f, 290f, false, paint); line(20f, 3f, 20f, 9f, 14f, 9f) }
            "private" -> { canvas.drawRoundRect(2f, 7f, 22f, 17f, 5f, 5f, paint); canvas.drawCircle(7f, 12f, 1.5f, paint); canvas.drawCircle(17f, 12f, 1.5f, paint) }
            "theme" -> { canvas.drawCircle(12f, 12f, 8f, paint); line(12f, 4f, 12f, 20f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
