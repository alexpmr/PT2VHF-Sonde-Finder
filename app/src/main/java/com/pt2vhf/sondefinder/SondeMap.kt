package com.pt2vhf.sondefinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

/**
 * Vector map (MapLibre + OpenFreeMap). Street names are rendered by the app, so they stay
 * upright whatever the map rotation. Everything the app draws (sondes, track, prediction,
 * user, heading beam) lives in GeoJSON sources + layers on top of the base style.
 * State set before the style finishes loading is kept and applied when it is ready.
 */
class SondeMap(private val ctx: Context, private val view: MapView, saved: Bundle?) {

    enum class Kind { LIVE, OLD, RECOVERED }
    data class Other(val serial: String, val lat: Double, val lon: Double, val kind: Kind)

    var onOtherClick: ((String) -> Unit)? = null
    var onSelectedClick: (() -> Unit)? = null
    var onLandingClick: (() -> Unit)? = null
    /** Map bearing changed (degrees clockwise from north at the top of the screen). */
    var onBearing: ((Float) -> Unit)? = null

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var triedFallback = false

    // last requested state (re-applied after style load)
    private var others: List<Other> = emptyList()
    private var user: LatLng? = null
    private var selected: LatLng? = null
    private var track: List<LatLng> = emptyList()
    private var landing: LatLng? = null
    private var userLine = false
    private var coneHeading: Float? = null
    private var coneNarrow = false

    private val d = ctx.resources.displayMetrics.density

    init {
        view.onCreate(saved)
        view.addOnDidFailLoadingMapListener {
            // dark style unavailable: fall back to the maintained light style
            if (!triedFallback) { triedFallback = true; map?.setStyle(Style.Builder().fromUri(STYLE_FALLBACK)) { onStyle(it) } }
        }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isTiltGesturesEnabled = false
                setAttributionTintColor(Color.parseColor("#9AA7B2"))
                setAttributionMargins((8 * d).toInt(), 0, 0, (30 * d).toInt())
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(-15.79, -47.88)).zoom(4.0).build()
            m.addOnCameraMoveListener { onBearing?.invoke(m.cameraPosition.bearing.toFloat()) }
            m.addOnMapClickListener { p -> click(m, p) }
            m.setStyle(Style.Builder().fromUri(STYLE_DARK)) { onStyle(it) }
        }
    }

    companion object {
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val STYLE_FALLBACK = "https://tiles.openfreemap.org/styles/liberty"
        private const val ACCENT = "#FF8A3D"
        private const val BLUE = "#4DA3FF"
    }

    // ------------------------------------------------------------------ lifecycle
    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onDestroy() = view.onDestroy()
    fun onLowMemory() = view.onLowMemory()
    fun onSaveInstanceState(b: Bundle) = view.onSaveInstanceState(b)

    // ------------------------------------------------------------------ public state
    fun setOthers(list: List<Other>) { others = list; apply() }
    fun setUser(lat: Double, lon: Double) { user = LatLng(lat, lon); apply() }
    fun setSelected(lat: Double?, lon: Double?, trackPts: List<LatLng>, land: LatLng?, lineToUser: Boolean) {
        selected = if (lat != null && lon != null) LatLng(lat, lon) else null
        track = trackPts; landing = land; userLine = lineToUser
        apply()
    }
    fun setCone(heading: Float?, narrow: Boolean) {
        coneHeading = heading; coneNarrow = narrow
        val st = style ?: return
        st.getSourceAs<GeoJsonSource>("cone")?.setGeoJson(coneFc())
    }

    val bearing: Float get() = map?.cameraPosition?.bearing?.toFloat() ?: 0f

    fun setBearing(deg: Float, animate: Boolean) {
        val m = map ?: return
        val u = CameraUpdateFactory.bearingTo(deg.toDouble())
        if (animate) m.animateCamera(u, 400) else m.moveCamera(u)
    }

    fun follow(lat: Double, lon: Double) {
        map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)), 500)
    }

    fun fit(points: List<LatLng>, paddingDp: Int) {
        val m = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) { m.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 14.0), 600); return }
        val b = LatLngBounds.Builder().includes(points).build()
        val pad = (paddingDp * d).toInt()
        view.post {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, m.cameraPosition.bearing, 0.0, pad, pad, pad, pad), 600)
        }
    }

    // ------------------------------------------------------------------ internals
    private fun onStyle(st: Style) {
        style = st
        st.addImage("live", dot(Color.parseColor(ACCENT), 24, 2f))
        st.addImage("old", dot(Color.parseColor("#8C99A5"), 22, 2f))
        st.addImage("rec", dot(Color.parseColor("#2E8F5A"), 20, 2f))
        st.addImage("sel", dot(Color.parseColor(ACCENT), 40, 3f))
        st.addImage("user", dot(Color.parseColor(BLUE), 40, 3f, Color.WHITE))
        st.addImage("land", cross())
        st.addImage("cone70", cone(70f))
        st.addImage("cone50", cone(50f))
        for (id in listOf("track", "pred", "uline", "others", "land", "sel", "cone", "user")) st.addSource(GeoJsonSource(id))
        st.addLayer(LineLayer("track-l", "track").withProperties(
            lineColor(ACCENT), lineWidth(3f), lineCap(Property.LINE_CAP_ROUND)))
        st.addLayer(LineLayer("pred-l", "pred").withProperties(
            lineColor("#E8EDF1"), lineOpacity(0.75f), lineWidth(2f), lineDasharray(arrayOf(3f, 3f))))
        st.addLayer(LineLayer("uline-l", "uline").withProperties(
            lineColor(BLUE), lineOpacity(0.8f), lineWidth(2f), lineDasharray(arrayOf(4f, 3f))))
        st.addLayer(symbols("others-l", "others", iconImage(Expression.get("icon"))))
        st.addLayer(symbols("land-l", "land", iconImage("land")))
        st.addLayer(symbols("sel-l", "sel", iconImage("sel")))
        st.addLayer(symbols("cone-l", "cone", iconImage(Expression.get("img")),
            iconRotate(Expression.get("rot")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)))
        st.addLayer(symbols("user-l", "user", iconImage("user")))
        apply()
        setCone(coneHeading, coneNarrow)
    }

    private fun symbols(id: String, src: String, vararg extra: org.maplibre.android.style.layers.PropertyValue<*>): Layer =
        SymbolLayer(id, src).withProperties(iconAllowOverlap(true), iconIgnorePlacement(true), *extra)

    private fun pt(l: LatLng) = Point.fromLngLat(l.longitude, l.latitude)
    private fun single(l: LatLng?) = FeatureCollection.fromFeatures(if (l == null) emptyList() else listOf(Feature.fromGeometry(pt(l))))
    private fun line(pts: List<LatLng>) = FeatureCollection.fromFeatures(
        if (pts.size < 2) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(pts.map { pt(it) }))))

    private fun coneFc(): FeatureCollection {
        val u = user; val h = coneHeading
        if (u == null || h == null) return FeatureCollection.fromFeatures(emptyList<Feature>())
        val f = Feature.fromGeometry(pt(u))
        f.addStringProperty("img", if (coneNarrow) "cone50" else "cone70")
        f.addNumberProperty("rot", h)
        return FeatureCollection.fromFeatures(listOf(f))
    }

    private fun apply() {
        val st = style ?: return
        val sel = selected
        st.getSourceAs<GeoJsonSource>("others")?.setGeoJson(FeatureCollection.fromFeatures(others.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
                addStringProperty("serial", it.serial)
                addStringProperty("icon", when (it.kind) { Kind.LIVE -> "live"; Kind.OLD -> "old"; Kind.RECOVERED -> "rec" })
            }
        }))
        st.getSourceAs<GeoJsonSource>("user")?.setGeoJson(single(user))
        st.getSourceAs<GeoJsonSource>("sel")?.setGeoJson(single(sel))
        st.getSourceAs<GeoJsonSource>("land")?.setGeoJson(single(if (sel != null) landing else null))
        st.getSourceAs<GeoJsonSource>("track")?.setGeoJson(line(if (sel != null && track.isNotEmpty()) track + sel else emptyList()))
        st.getSourceAs<GeoJsonSource>("pred")?.setGeoJson(line(if (sel != null && landing != null) listOf(sel, landing!!) else emptyList()))
        val u = user
        st.getSourceAs<GeoJsonSource>("uline")?.setGeoJson(line(if (userLine && u != null && sel != null) listOf(u, sel) else emptyList()))
        st.getSourceAs<GeoJsonSource>("cone")?.setGeoJson(coneFc())
    }

    private fun click(m: MapLibreMap, p: LatLng): Boolean {
        val s = m.projection.toScreenLocation(p)
        val r = 22 * d
        val box = RectF(s.x - r, s.y - r, s.x + r, s.y + r)
        if (m.queryRenderedFeatures(box, "sel-l").isNotEmpty()) { onSelectedClick?.invoke(); return true }
        if (m.queryRenderedFeatures(box, "land-l").isNotEmpty()) { onLandingClick?.invoke(); return true }
        val o = m.queryRenderedFeatures(box, "others-l").firstOrNull()?.getStringProperty("serial")
        if (o != null) { onOtherClick?.invoke(o); return true }
        return false
    }

    // ------------------------------------------------------------------ bitmaps
    private fun dot(fill: Int, sizeDp: Int, ringDp: Float, ring: Int = Color.parseColor("#0E1419")): Bitmap {
        val s = (sizeDp * d).roundToInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(46, Color.red(fill), Color.green(fill), Color.blue(fill))
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = ring; c.drawCircle(s / 2f, s / 2f, s * 0.3f + ringDp * d, p)
        p.color = fill; c.drawCircle(s / 2f, s / 2f, s * 0.3f, p)
        return b
    }

    private fun cross(): Bitmap {
        val s = (34 * d).roundToInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(40, 232, 237, 241); c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = Color.parseColor("#E8EDF1"); p.strokeWidth = 3 * d; p.strokeCap = Paint.Cap.ROUND
        val a = s * 0.3f; val z = s * 0.7f
        c.drawLine(a, a, z, z, p); c.drawLine(z, a, a, z, p)
        return b
    }

    /** Flashlight beam pointing up from the bitmap centre; rotated by the layer. */
    private fun cone(spread: Float): Bitmap {
        val s = (160 * d).roundToInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val x = s / 2f; val r = s / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, x, r, intArrayOf(0x994DA3FF.toInt(), 0x334DA3FF, 0x004DA3FF),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawArc(RectF(0f, 0f, s.toFloat(), s.toFloat()), -90f - spread / 2f, spread, true, p)
        return b
    }
}
