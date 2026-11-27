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
class SondeMap(private val ctx: Context, private val view: MapView, saved: Bundle?,
               private var styleName: String, private var brightness: Int) {

    enum class Kind { LIVE, OLD, RECOVERED }
    data class Other(val serial: String, val lat: Double, val lon: Double, val kind: Kind, val phase: Phase = Phase.NONE)

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
    private var selectedPhase = Phase.NONE
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
            m.setStyle(builder(styleName)) { onStyle(it) }
            applyBrightness()
        }
    }

    companion object {
        val STYLES = listOf("dark", "fiord", "positron", "liberty", SATELLITE)
        const val SATELLITE = "satellite"
        fun styleUrl(name: String) = "https://tiles.openfreemap.org/styles/" + (if (name in STYLES && name != SATELLITE) name else "dark")

        /** Satellite: Esri World Imagery (raster) with the Esri boundaries/place-names overlay on top. */
        private val SATELLITE_JSON = """{"version":8,"sources":{
            "sat":{"type":"raster","tileSize":256,"maxzoom":19,
              "tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
              "attribution":"Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community"},
            "names":{"type":"raster","tileSize":256,"maxzoom":19,
              "tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"]}},
            "layers":[{"id":"sat","type":"raster","source":"sat"},{"id":"names","type":"raster","source":"names"}]}"""

        fun builder(name: String): Style.Builder =
            if (name == SATELLITE) Style.Builder().fromJson(SATELLITE_JSON) else Style.Builder().fromUri(styleUrl(name))
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
    fun setSelected(lat: Double?, lon: Double?, trackPts: List<LatLng>, land: LatLng?, lineToUser: Boolean,
                    phase: Phase = Phase.NONE) {
        selected = if (lat != null && lon != null) LatLng(lat, lon) else null
        selectedPhase = phase
        track = trackPts; landing = land; userLine = lineToUser
        apply()
    }
    fun setCone(heading: Float?, narrow: Boolean) {
        coneHeading = heading; coneNarrow = narrow
        val st = style ?: return
        st.getSourceAs<GeoJsonSource>("cone")?.setGeoJson(coneFc())
    }

    fun setStyleName(name: String) {
        if (name == styleName) return
        styleName = name; style = null; triedFallback = false
        map?.setStyle(builder(name)) { onStyle(it) }
    }

    /**
     * Brightness -50..+50 applied to the rendered map image (TextureView layer paint):
     * positive lifts dark tones, negative dims. Needs texture render mode (set in the layout).
     */
    fun setBrightness(v: Int) { brightness = v; applyBrightness() }

    private fun applyBrightness() {
        val tv = findTexture(view) ?: run { view.postDelayed({ findTexture(view)?.let { applyTo(it) } }, 500); return }
        applyTo(tv)
    }

    private fun applyTo(tv: android.view.TextureView) {
        val v = brightness.coerceIn(-50, 50)
        if (v == 0) { tv.setLayerPaint(null); return }
        val scale = 1f + v * 0.006f
        val offset = if (v > 0) v * 1.4f else 0f
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f))
        tv.setLayerPaint(Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) })
    }

    private fun findTexture(v: android.view.View): android.view.TextureView? {
        if (v is android.view.TextureView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
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
        // flying sondes: balloon while going up, parachute while coming down
        st.addImage("up", balloon(30)); st.addImage("down", parachute(30))
        st.addImage("sel-up", balloon(46)); st.addImage("sel-down", parachute(46))
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
        st.addLayer(symbols("sel-l", "sel", iconImage(Expression.get("icon"))))
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
                addStringProperty("icon", when (it.kind) {
                    Kind.LIVE -> when (it.phase) { Phase.UP -> "up"; Phase.DOWN -> "down"; Phase.NONE -> "live" }
                    Kind.OLD -> "old"; Kind.RECOVERED -> "rec"
                })
            }
        }))
        st.getSourceAs<GeoJsonSource>("user")?.setGeoJson(single(user))
        st.getSourceAs<GeoJsonSource>("sel")?.setGeoJson(FeatureCollection.fromFeatures(if (sel == null) emptyList() else listOf(
            Feature.fromGeometry(pt(sel)).apply {
                addStringProperty("icon", when (selectedPhase) { Phase.UP -> "sel-up"; Phase.DOWN -> "sel-down"; Phase.NONE -> "sel" })
            })))
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

    private fun canvas(sizeDp: Int): Pair<Bitmap, Canvas> {
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
    private fun balloon(sizeDp: Int): Bitmap {
        val (b, c) = canvas(sizeDp)
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
    private fun parachute(sizeDp: Int): Bitmap {
        val (b, c) = canvas(sizeDp)
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
