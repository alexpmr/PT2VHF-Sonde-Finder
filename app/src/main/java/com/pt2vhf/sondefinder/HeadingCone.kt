package com.pt2vhf.sondefinder

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * "Flashlight" beam in front of the user's dot showing where the phone is heading
 * (true north reference). osmdroid draws overlays on a canvas already rotated with the map,
 * so the beam is drawn in map (north-up) coordinates.
 */
class HeadingCone(private val density: Float) : Overlay() {
    var position: GeoPoint? = null
    var heading: Float? = null
    /** Beam width in degrees (wider when the heading is less certain). */
    var spread = 70f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = Point()
    private val oval = RectF()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val p = position ?: return
        val h = heading ?: return
        map.projection.toPixels(p, pt)
        val r = 80 * density
        val x = pt.x.toFloat(); val y = pt.y.toFloat()
        paint.shader = RadialGradient(x, y, r, intArrayOf(0x994DA3FF.toInt(), 0x334DA3FF, 0x004DA3FF),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        oval.set(x - r, y - r, x + r, y + r)
        // drawArc angles start at 3 o'clock and grow clockwise; north (up) is -90°
        c.drawArc(oval, h - 90f - spread / 2f, spread, true, paint)
    }
}
