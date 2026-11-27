package com.pt2vhf.sondefinder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/** Balloon (ascending) and parachute (descending) icons, shared by the map and the compass. */
object SondeIcons {
    private const val ACCENT = "#FF8A3D"

    private fun canvas(sizeDp: Int, d: Float): Pair<Bitmap, Canvas> {
        val s = (sizeDp * d).roundToInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val fill = Color.parseColor(ACCENT)
        p.color = Color.argb(46, Color.red(fill), Color.green(fill), Color.blue(fill))
        c.drawCircle(s / 2f, s / 2f, s / 2f, p) // soft halo, as on the plain dots
        return Pair(b, c)
    }

    private fun outline(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1419"); style = Paint.Style.STROKE; strokeWidth = w; strokeJoin = Paint.Join.ROUND
    }

    /** Weather balloon: round envelope, cord and payload box. */
    fun balloon(sizeDp: Int, d: Float): Bitmap {
        val (b, c) = canvas(sizeDp, d)
        val s = b.width.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(ACCENT) }
        val line = outline(1.5f * d)
        val env = RectF(s * 0.27f, s * 0.10f, s * 0.73f, s * 0.58f)
        c.drawOval(env, fill); c.drawOval(env, line)
        c.drawLine(s / 2f, s * 0.58f, s / 2f, s * 0.74f, line)
        val box = RectF(s * 0.42f, s * 0.74f, s * 0.58f, s * 0.88f)
        c.drawRect(box, fill); c.drawRect(box, line)
        return b
    }

    /** Parachute: canopy, suspension lines and payload box. */
    fun parachute(sizeDp: Int, d: Float): Bitmap {
        val (b, c) = canvas(sizeDp, d)
        val s = b.width.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(ACCENT) }
        val line = outline(1.5f * d)
        val canopy = RectF(s * 0.16f, s * 0.14f, s * 0.84f, s * 0.62f)
        c.drawArc(canopy, 180f, 180f, true, fill); c.drawArc(canopy, 180f, 180f, true, line)
        val box = RectF(s * 0.42f, s * 0.72f, s * 0.58f, s * 0.86f)
        c.drawLine(s * 0.16f, s * 0.38f, box.left, box.top, line)
        c.drawLine(s / 2f, s * 0.38f, s / 2f, box.top, line)
        c.drawLine(s * 0.84f, s * 0.38f, box.right, box.top, line)
        c.drawRect(box, fill); c.drawRect(box, line)
        return b
    }
}
