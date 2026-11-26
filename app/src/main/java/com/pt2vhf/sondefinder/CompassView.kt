package com.pt2vhf.sondefinder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Compass card that rotates with the phone heading and an ADF-style needle pointing at the target. */
class CompassView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Phone heading (degrees, true north). */
    var heading = 0f
        set(v) { field = v; invalidate() }

    /** Absolute bearing to the target, or null when unknown (needle hidden). */
    var bearing: Float? = null
        set(v) { field = v; invalidate() }

    var needleColor = Color.parseColor("#FF8A3D")
        set(v) { field = v; invalidate() }

    private val cards: Array<String> = resources.getStringArray(R.array.cardinals4)
    private var shownHeading = 0f
    private var shownRel = 0f
    private var first = true

    private val d = resources.displayMetrics.density
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#121A21") }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F161C"); style = Paint.Style.STROKE
    }
    private val rimLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26323C"); style = Paint.Style.STROKE; strokeWidth = 1 * d
    }
    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5CED6"); strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E4C57"); strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND
    }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; color = Color.parseColor("#7F8E9B")
    }
    private val lubber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DA3FF") }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1419"); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val hubFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E1419") }
    private val hubRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EDF1"); style = Paint.Style.STROKE; strokeWidth = 3 * d
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the needle shadow
    }

    private fun norm(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val size = min(width.toFloat(), height.toFloat() - 18 * d)
        val r = size / 2f - 4 * d
        val cy = 18 * d + size / 2f

        // smooth animation toward the targets
        val targetRel = bearing?.let { norm(it - heading) } ?: 0f
        if (first) { shownHeading = heading; shownRel = targetRel; first = false }
        shownHeading += norm(heading - shownHeading) * 0.18f
        shownRel += norm(targetRel - shownRel) * 0.18f
        val animating = kotlin.math.abs(norm(heading - shownHeading)) > 0.2f ||
            kotlin.math.abs(norm(targetRel - shownRel)) > 0.2f

        // lubber mark (phone heading)
        val lp = Path().apply {
            moveTo(cx - 10 * d, 0f); lineTo(cx + 10 * d, 0f); lineTo(cx, 14 * d); close()
        }
        c.drawPath(lp, lubber)

        // face
        c.drawCircle(cx, cy, r, face)
        rim.strokeWidth = 10 * d
        c.drawCircle(cx, cy, r - 5 * d, rim)
        c.drawCircle(cx, cy, r, rimLine)

        // rotating card
        c.save()
        c.rotate(-shownHeading, cx, cy)
        for (i in 0 until 72) {
            val major = i % 6 == 0
            c.save()
            c.rotate(i * 5f, cx, cy)
            val top = cy - r + 8 * d
            c.drawLine(cx, top, cx, top + (if (major) 14 else 8) * d, if (major) tickMajor else tickMinor)
            c.restore()
        }
        card.textSize = r * 0.15f
        num.textSize = r * 0.075f
        for (i in 0 until 12) {
            val deg = i * 30
            c.save()
            c.rotate(deg.toFloat(), cx, cy)
            val label = when (deg) { 0 -> cards[0]; 90 -> cards[1]; 180 -> cards[2]; 270 -> cards[3]; else -> null }
            if (label != null) {
                card.color = if (deg == 0) Color.parseColor("#FF5C5C") else Color.parseColor("#E8EDF1")
                c.drawText(label, cx, cy - r + 30 * d + card.textSize * 0.8f, card)
            } else {
                c.drawText(deg.toString(), cx, cy - r + 30 * d + num.textSize, num)
            }
            c.restore()
        }
        c.restore()

        // ADF needle
        if (bearing != null) {
            c.save()
            c.rotate(shownRel, cx, cy)
            needle.color = needleColor
            needle.setShadowLayer(3 * d, 0f, 2 * d, Color.argb(140, 0, 0, 0))
            val k = r / 150f
            val tipY = cy - 132 * k
            val tailY = cy + 128 * k
            val shaft = Path().apply {
                addRect(cx - 3 * k, tipY + 26 * k, cx + 3 * k, tailY - 20 * k, Path.Direction.CW)
            }
            val head = Path().apply {
                moveTo(cx, tipY); lineTo(cx + 15 * k, tipY + 38 * k); lineTo(cx, tipY + 30 * k)
                lineTo(cx - 15 * k, tipY + 38 * k); close()
            }
            val tail = Path().apply {
                moveTo(cx, tailY - 32 * k); lineTo(cx + 15 * k, tailY); lineTo(cx, tailY - 10 * k)
                lineTo(cx - 15 * k, tailY); close()
            }
            for (p in listOf(shaft, head, tail)) { c.drawPath(p, needle); c.drawPath(p, outline) }
            needle.clearShadowLayer()
            c.restore()
        }

        // hub
        c.drawCircle(cx, cy, 12 * d, hubFill)
        c.drawCircle(cx, cy, 12 * d, hubRing)

        if (animating) postInvalidateOnAnimation()
    }
}
