package com.pt2vhf.sondefinder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener, LocationListener {

    companion object {
        const val MAX_RADIUS_KM = 25
        val WINDOWS_H = intArrayOf(1, 3, 6, 12, 24, 48, 168, 360, 720, 2160, 4320, 8760)
        const val POLL_MS = 5_000L        // nearby poll interval (one request at a time, never queued)
        const val DETAIL_MS = 30_000L     // track / prediction refresh
        const val STALE_MS = 30_000L      // no fresh list for this long: say so instead of "live"
        const val ALARM_MS = 60_000L      // landing alarm check interval
        const val ALARM_SEARCH_M = 400_000 // flying sondes this far away may still land near the user
        val ALARM_KM = intArrayOf(0, 2, 5, 10, 20, 50)
        const val DETECT_MAX_M = 5000f    // detector starts clicking at 5 km
        val ACCENT = Color.parseColor("#FF8A3D")
        val BLUE = Color.parseColor("#4DA3FF")
        val WHITE = Color.parseColor("#E8EDF1")
        val GREEN = Color.parseColor("#3DDC84")
        val AMBER = Color.parseColor("#FFC43D")
        val RED = Color.parseColor("#FF5C7A")
        val MUTED = Color.parseColor("#9AA7B2")
        val BR: Locale get() = Locale.getDefault()
    }

    private lateinit var map: SondeMap
    private lateinit var compass: CompassView

    private val ui = Handler(Looper.getMainLooper())
    // One job in flight per executor: a new poll is skipped while the previous one runs, so a slow
    // network can never build a queue of old requests (that once left the map 25 min behind).
    private val io = Executors.newSingleThreadExecutor()        // sonde list
    private val detailIo = Executors.newSingleThreadExecutor()  // track + prediction of the selected sonde
    private val alarmIo = Executors.newSingleThreadExecutor()   // landing alarm
    private var listBusy = false
    private var detailBusy = false
    private var alarmBusy = false
    private var lastOkAt = 0L
    private var alarmKm = 0
    private var lastAlarmAt = 0L
    private val alarmed = HashSet<String>()

    private var me: Location? = null
    private var sonde: Sonde? = null
    private var track: List<Point> = emptyList()
    private var pred: Prediction? = null
    // history (/sondes/summary), cached: it only changes when the filter or place changes
    private var histFlights: Map<String, SondeHub.Flight> = emptyMap()
    private var histKey = ""
    private var histAt = 0L
    private var sortByAge = false
    private var detailSerial: String? = null
    private var lastDetailAt = 0L
    private var netError: String? = null
    private var emptyResult = false
    private var targetLanding = false
    private var compassMode = false
    private var heading = 0f
    private var haveHeading = false
    private var magHeading = 0f
    private var haveMag = false
    private var useBackAxis = false
    private var gpsCourse = 0f
    private var gpsCourseAt = 0L
    private var headingFromGps = false
    private var mapHeadingUp = false
    private var lastMapRot = 0f
    private var lastConeHeading = -999f // map beam last drawn heading
    private var autoFitDone = false
    private var all: List<Sonde> = emptyList()
    private var manualSerial: String? = null
    private var radiusKm = 100
    private var windowH = 6
    private var recoveries: Map<String, Recovery> = emptyMap()
    private var lastRecAt = 0L
    private var hideRecovered = false
    private var detectorOn = false
    private var nextBeepAt = 0L
    private var tone: ToneGenerator? = null

    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager

        getSharedPreferences("cfg", MODE_PRIVATE).let {
            radiusKm = it.getInt("radiusKm", 10).coerceIn(1, MAX_RADIUS_KM); windowH = it.getInt("windowH", 6)
            hideRecovered = it.getBoolean("hideRecovered", false)
            mapHeadingUp = it.getBoolean("mapHeadingUp", false)
            compassOffset = it.getFloat("compassOffset", 0f)
            gpsWhenMoving = it.getBoolean("gpsWhenMoving", false)
            mapStyle = it.getString("mapStyle", "dark") ?: "dark"
            mapBrightness = it.getInt("mapBrightness", 0)
            sortByAge = it.getBoolean("sortByAge", false)
            alarmKm = it.getInt("alarmKm", 0).takeIf { k -> k in ALARM_KM } ?: 0
        }
        setupMap(savedInstanceState)
        if (mapHeadingUp) ui.postDelayed({ setMapHeadingUp(true, false) }, 1000)
        compass = findViewById(R.id.compass)
        compass.setOnLongClickListener { showAlignDialog(); true }
        findViewById<TextView>(R.id.btnHeadingMode).setOnClickListener {
            gpsWhenMoving = !gpsWhenMoving
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("gpsWhenMoving", gpsWhenMoving).apply()
            renderHeadingMode(); updateHeading()
        }
        renderHeadingMode()
        findViewById<View>(R.id.btnSound).setOnClickListener {
            setDetector(!detectorOn)
            Toast.makeText(this, if (detectorOn) R.string.detector_on_toast else R.string.detector_off, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnFilter).setOnClickListener { showFilterDialog() }
        findViewById<View>(R.id.btnList).setOnClickListener { showListDialog() }
        findViewById<View>(R.id.btnAuto).setOnClickListener { select(null) }
        findViewById<View>(R.id.btnDetector).setOnClickListener { setDetector(!detectorOn) }

        findViewById<View>(R.id.navMap).setOnClickListener { showCompass(false) }
        findViewById<View>(R.id.navCompass).setOnClickListener { showCompass(true) }
        findViewById<View>(R.id.segLast).setOnClickListener { targetLanding = false; render() }
        findViewById<View>(R.id.segLand).setOnClickListener { targetLanding = true; render() }
        findViewById<View>(R.id.btnCenter).setOnClickListener { fitAll() }
        findViewById<View>(R.id.btnOrient).setOnClickListener { setMapHeadingUp(!mapHeadingUp, true) }
        findViewById<View>(R.id.btnAbout).setOnClickListener { showAbout() }
        if (BuildConfig.OTA) UpdateManager.check(this, false)
        UsagePing.maybeSend(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+ (and predictive back on 16): onBackPressed is no longer called
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        findViewById<View>(R.id.btnCenter).setOnLongClickListener { fitEverything(); true }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copyCoords() }
        findViewById<View>(R.id.btnNav).setOnClickListener { navigate() }
        showCompass(false)
        render()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startLocation()
        registerCompass()
        ui.post(poll)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        sensors.unregisterListener(this)
        try { locations.removeUpdates(this) } catch (_: SecurityException) {}
        ui.removeCallbacks(poll)
        ui.removeCallbacks(tick)
        setDetector(false)
    }

    override fun onStart() { super.onStart(); map.onStart() }
    override fun onStop() { super.onStop(); map.onStop() }
    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); map.onSaveInstanceState(outState) }

    override fun onDestroy() {
        super.onDestroy()
        map.onDestroy()
        io.shutdownNow(); detailIo.shutdownNow(); alarmIo.shutdownNow()
    }

    // ---------------------------------------------------------------- map

    private fun setupMap(saved: Bundle?) {
        map = SondeMap(this, findViewById<MapView>(R.id.map), saved, mapStyle, mapBrightness)
        findViewById<View>(R.id.btnMapStyle).setOnClickListener { showMapSettings() }
        map.onOtherClick = { serial -> select(serial) }
        map.onSelectedClick = { showCompass(true) }
        map.onLandingClick = { targetLanding = true; showCompass(true) }
        map.onBearing = { b ->
            findViewById<View>(R.id.btnOrient).rotation = -b
            updateChipArrow()
        }
    }

    private fun ll(lat: Double, lon: Double) = LatLng(lat, lon)

    private fun fitEverything() {
        val pts = ArrayList<LatLng>()
        me?.let { pts.add(ll(it.latitude, it.longitude)) }
        for (o in all) pts.add(ll(o.lat, o.lon))
        if (pts.size < 2) { fitAll(); return }
        map.fit(pts, 50)
    }

    private fun fitAll() {
        val pts = ArrayList<LatLng>()
        me?.let { pts.add(ll(it.latitude, it.longitude)) }
        sonde?.let { pts.add(ll(it.lat, it.lon)) }
        pred?.landing?.let { pts.add(ll(it.lat, it.lon)) }
        map.fit(pts, 70)
    }

    private fun syncOthers() {
        val now = System.currentTimeMillis()
        map.setOthers(all.filter { it.serial != sonde?.serial }.map { o ->
            val kind = when {
                recoveries[o.serial]?.recovered == true -> SondeMap.Kind.RECOVERED
                now - o.timeMs < 10 * 60_000L -> SondeMap.Kind.LIVE
                else -> SondeMap.Kind.OLD
            }
            SondeMap.Other(o.serial, o.lat, o.lon, kind, FlightLogic.phase(o.velV, now - o.timeMs))
        })
        findViewById<TextView>(R.id.btnList).text = resources.getQuantityString(R.plurals.sondes_count, all.size, all.size)
    }

    private fun updateMap() {
        syncOthers()
        val s = sonde
        val u = me
        if (u != null) map.setUser(u.latitude, u.longitude)
        val land = pred?.landing
        map.setSelected(s?.lat, s?.lon, track.map { ll(it.lat, it.lon) },
            if (s != null && land != null && s.velV < 0) ll(land.lat, land.lon) else null,
            lineToUser = u != null && s != null,
            phase = if (s == null || recoveries[s.serial]?.recovered == true) Phase.NONE
                else FlightLogic.phase(s.velV, System.currentTimeMillis() - s.timeMs))
        if (!autoFitDone && (s != null || u != null)) {
            fitAll(); if (s != null && u != null) autoFitDone = true
        }
    }

    // ---------------------------------------------------------------- data

    private val poll = object : Runnable {
        override fun run() {
            fetch()
            checkAlarm()
            ui.postDelayed(this, pollInterval())
        }
    }

    /** History windows change slowly: poll every 2 min, unless a sonde is flying. */
    private fun pollInterval(): Long {
        val now = System.currentTimeMillis()
        val flying = all.any { now - it.timeMs < FlightLogic.LIVE_MS }
        return if (windowH > 24 && !flying) 120_000L else POLL_MS
    }

    private fun fetch() {
        val loc = me ?: return
        if (listBusy) return // previous request still running: skip, never queue
        val lat = loc.latitude; val lon = loc.longitude
        val manual = manualSerial
        val radiusM = radiusKm * 1000
        val lastSec = windowH * 3600
        val wantRec = System.currentTimeMillis() - lastRecAt > 120_000L
        val hide = hideRecovered
        val knownRec = recoveries
        val hKey = "$radiusM/$lastSec/${"%.2f".format(Locale.US, lat)}/${"%.2f".format(Locale.US, lon)}"
        val wantHist = windowH > 72 && (hKey != histKey || System.currentTimeMillis() - histAt > 30 * 60_000L)
        val knownHist = if (windowH > 72 && hKey == histKey) histFlights else emptyMap()
        listBusy = true
        io.execute {
            try {
                val rec = if (wantRec) try {
                    // reports arrive after the landing, so look a month past the window
                    SondeHub.recovered(lat, lon, radiusM, lastSec + 30 * 86400)
                } catch (e: Exception) { null } else null
                val recMap = rec ?: knownRec
                // live data covers only the last few days; older flights come from the summaries
                val live = SondeHub.nearby(lat, lon, radiusM, minOf(lastSec, 3 * 86400))
                val hist = if (wantHist) SondeHub.history(lat, lon, radiusM, lastSec.toLong()).associateBy { it.sonde.serial }
                    else knownHist
                val merged = HashMap<String, Sonde>()
                for (f in hist.values) merged[f.sonde.serial] = f.sonde
                for (o in live) { val p = merged[o.serial]; if (p == null || o.timeMs >= p.timeMs) merged[o.serial] = o }
                val raw = merged.values.toList()
                val list = if (hide) raw.filter { recMap[it.serial]?.recovered != true || it.serial == manual } else raw
                val res = FloatArray(1)
                val dist = { o: Sonde -> Location.distanceBetween(lat, lon, o.lat, o.lon, res); res[0] }
                // manual choice if still present, else the most recently heard (ties → nearest)
                val nearest = list.firstOrNull { it.serial == manual }
                    ?: list.sortedWith(compareByDescending<Sonde> { it.timeMs / 30_000 }.thenBy { dist(it) }).firstOrNull()
                ui.post {
                    listBusy = false
                    if (radiusM != radiusKm * 1000 || lastSec != windowH * 3600 || manual != manualSerial) return@post // stale
                    netError = null
                    lastOkAt = System.currentTimeMillis()
                    all = list
                    if (wantHist) { histFlights = hist; histKey = hKey; histAt = System.currentTimeMillis() }
                    if (rec != null) { recoveries = rec; lastRecAt = System.currentTimeMillis() }
                    if (manual != null && list.none { it.serial == manual }) {
                        manualSerial = null
                        Toast.makeText(this, getString(R.string.chosen_left_filter), Toast.LENGTH_LONG).show()
                    }
                    emptyResult = nearest == null
                    if (nearest == null) { sonde = null; track = emptyList(); pred = null }
                    else {
                        if (nearest.serial != sonde?.serial) {
                            autoFitDone = false
                            if (nearest.serial != detailSerial) { track = emptyList(); pred = null }
                        }
                        sonde = nearest
                        if (nearest.serial != detailSerial || System.currentTimeMillis() - lastDetailAt > DETAIL_MS)
                            fetchDetail(nearest.serial, hist[nearest.serial])
                    }
                    render(); updateMap()
                }
            } catch (e: Exception) {
                ui.post { listBusy = false; netError = e.message ?: getString(R.string.network_error); render() }
            }
        }
    }

    /** Track and prediction of the selected sonde, on their own thread so the list keeps updating. */
    private fun fetchDetail(serial: String, archived: SondeHub.Flight?) {
        if (detailBusy) return
        detailBusy = true
        detailIo.execute {
            var tr = try { SondeHub.track(serial) } catch (e: Exception) { null }
            var pr = try { SondeHub.prediction(serial) } catch (e: Exception) { null }
            // old flights: no live track/prediction any more, use the archived summary
            if (archived != null) {
                if (tr.isNullOrEmpty()) tr = archived.frames
                if (pr == null && archived.landing != null) pr = Prediction(listOf(archived.landing))
            }
            ui.post {
                detailBusy = false
                if (sonde?.serial != serial) return@post // selection changed meanwhile
                val same = detailSerial == serial
                // a failed refresh keeps what was already shown for this sonde
                track = tr ?: if (same) track else emptyList()
                pred = pr ?: if (same) pred else null
                detailSerial = serial; lastDetailAt = System.currentTimeMillis()
                render(); updateMap()
            }
        }
    }

    // ---------------------------------------------------------------- landing alarm

    /** Every minute while enabled: flying sondes up to 400 km away whose predicted landing is near. */
    private fun checkAlarm() {
        if (alarmKm <= 0 || alarmBusy) return
        val loc = me ?: return
        val now = System.currentTimeMillis()
        if (now - lastAlarmAt < ALARM_MS) return
        lastAlarmAt = now; alarmBusy = true
        val lat = loc.latitude; val lon = loc.longitude
        val maxM = alarmKm * 1000.0
        val done = HashSet(alarmed)
        alarmIo.execute {
            val hits = try {
                val flying = SondeHub.nearby(lat, lon, ALARM_SEARCH_M, 15 * 60)
                    .filter { FlightLogic.phase(it.velV, now - it.timeMs) != Phase.NONE }
                    .map { it.serial }.filter { it !in done }
                FlightLogic.alarmHits(lat, lon, maxM, SondeHub.predictions(flying), now, done)
            } catch (e: Exception) { emptyList() }
            ui.post {
                alarmBusy = false
                if (hits.isNotEmpty() && alarmKm > 0) fireAlarm(hits)
            }
        }
    }

    private fun fireAlarm(hits: List<FlightLogic.AlarmHit>) {
        hits.forEach { alarmed.add(it.serial) }
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).let { t ->
                t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2500)
                ui.postDelayed({ t.release() }, 3000)
            }
        } catch (_: Exception) {}
        try {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 600), -1))
        } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        val lines = hits.joinToString("\n") { h ->
            val d = fmtDist(h.distM).let { "${it.first} ${it.second}" }
            val eta = if (h.landing.timeMs > now) " " + getString(R.string.alarm_eta, ((h.landing.timeMs - now) / 60_000).toInt()) else ""
            getString(R.string.alarm_line, h.serial, d, eta)
        }
        val first = hits.first().serial
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.alarm_title)
            .setMessage(lines)
            .apply { if (all.any { it.serial == first }) setPositiveButton(R.string.alarm_view) { _, _ -> select(first) } }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------------------------------------------------------------- location & heading

    private fun startLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locations.isProviderEnabled(p)) {
                    locations.requestLocationUpdates(p, 1000L, 0f, this)
                    locations.getLastKnownLocation(p)?.let { if (me == null) onLocationChanged(it) }
                }
            }
        } catch (_: SecurityException) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startLocation()
        else Toast.makeText(this, getString(R.string.no_location_perm), Toast.LENGTH_LONG).show()
    }

    override fun onLocationChanged(location: Location) {
        val firstFix = me == null
        val cur = me
        // prefer GPS; accept network fix only if no recent better one
        if (cur != null && location.provider == LocationManager.NETWORK_PROVIDER &&
            cur.provider == LocationManager.GPS_PROVIDER && location.time - cur.time < 15000) return
        me = location
        if (firstFix) ui.post { fetch() }
        // Moving (>= ~7 km/h): the GPS course is the travel direction and is immune to
        // magnetic interference (car body, mounts, electronics).
        if (location.hasBearing() && location.hasSpeed() && location.speed >= 1.2f &&
            (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 30f)) {
            gpsCourse = location.bearing; gpsCourseAt = System.currentTimeMillis()
            updateHeading()
        }
        if (mapHeadingUp && !compassMode) map.follow(location.latitude, location.longitude)
        render(); updateMap()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(rot, e.values)
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD -> {
                val buf = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) { haveGrav = true; grav } else { haveGeo = true; geo }
                for (i in 0..2) buf[i] += (e.values[i] - buf[i]) * 0.2f   // low-pass
                if (!haveGrav || !haveGeo) return
                if (!SensorManager.getRotationMatrix(rot, null, grav, geo)) return
            }
            else -> return
        }
        // rot maps device axes to world (x = East, y = North, z = Up).
        // Lying flat (screen up/down) the heading is where the TOP of the phone points;
        // standing up (hand or car mount) it is where the BACK of the phone points.
        // getOrientation() always uses the top, which flips 180° when the phone is upright
        // and leaning back, as in a car mount.
        val zUp = abs(rot[8])
        if (useBackAxis && zUp > 0.6f) useBackAxis = false
        else if (!useBackAxis && zUp < 0.45f) useBackAxis = true
        val vx = if (useBackAxis) -rot[2] else rot[1]
        val vy = if (useBackAxis) -rot[5] else rot[4]
        if (vx * vx + vy * vy < 1e-4f) return
        var az = Math.toDegrees(atan2(vx.toDouble(), vy.toDouble())).toFloat()
        me?.let {
            az += GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
        }
        rawMag = (az + 360f) % 360f
        magHeading = (rawMag + compassOffset + 720f) % 360f
        haveMag = true
        updateHeading()
    }

    /** Effective heading: GPS course while moving, electronic compass when stopped. */
    private fun updateHeading() {
        // GPS course only when chosen, or when the phone has no compass at all
        val gpsFresh = (gpsWhenMoving || !compassSensor) && System.currentTimeMillis() - gpsCourseAt < 4000L
        headingFromGps = gpsFresh
        val target = if (gpsFresh) gpsCourse else if (haveMag) magHeading else return
        if (!haveHeading) { heading = target; haveHeading = true }
        else {
            var diff = target - heading
            if (diff > 180) diff -= 360f
            if (diff < -180) diff += 360f
            heading = (heading + diff * (if (gpsFresh) 0.35f else 0.15f) + 360f) % 360f
        }
        if (compassMode) renderCompass() else { rotateMap(); updateCone() }
    }

    private fun renderHeadingMode() {
        findViewById<TextView>(R.id.btnHeadingMode).text =
            getString(if (gpsWhenMoving) R.string.hmode_gps else R.string.hmode_compass)
    }

    private fun updateCone() {
        var d = heading - lastConeHeading
        if (d > 180) d -= 360f
        if (d < -180) d += 360f
        if (abs(d) < 2f) return
        lastConeHeading = heading
        map.setCone(heading, headingFromGps)
    }

    private fun rotateMap() {
        if (!mapHeadingUp) return
        var d = heading - lastMapRot
        if (d > 180) d -= 360f
        if (d < -180) d += 360f
        if (abs(d) < 1f) return
        lastMapRot = heading
        map.setBearing(heading, false)
    }

    private fun setMapHeadingUp(on: Boolean, announce: Boolean) {
        mapHeadingUp = on
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("mapHeadingUp", on).apply()
        if (on) {
            lastMapRot = heading + 10f // force an update
            map.setBearing(heading, true)
            me?.let { map.follow(it.latitude, it.longitude) }
        } else {
            map.setBearing(0f, true)
        }
        if (announce) Toast.makeText(this, if (on) R.string.map_heading_up else R.string.map_north_up, Toast.LENGTH_SHORT).show()
    }

    private var chipBearing = 0f
    private fun updateChipArrow() {
        // icon points NE by default; on a rotated map the arrow must follow the map
        // symmetric arrow pointing up; on a rotated map it must follow the map
        findViewById<ImageView>(R.id.chipArrow).rotation = chipBearing - map.bearing
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val t = sensor?.type ?: return
        if ((t == Sensor.TYPE_MAGNETIC_FIELD || t == Sensor.TYPE_ROTATION_VECTOR) &&
            accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW && !calibrationHintShown) {
            calibrationHintShown = true
            Toast.makeText(this, R.string.calibrate_hint, Toast.LENGTH_LONG).show()
        }
    }

    private val grav = FloatArray(3)
    private val geo = FloatArray(3)
    private var haveGrav = false
    private var haveGeo = false
    private var calibrationHintShown = false
    private var compassSensor = true
    private var rawMag = 0f
    private var compassOffset = 0f
    /** false: always the phone's compass (needle follows where the phone points).
     *  true: GPS course while moving (phone fixed in a car mount, avoids magnetic interference). */
    private var gpsWhenMoving = false
    private var mapStyle = "dark"
    private var mapBrightness = 0

    /**
     * Rotation vector only when the phone has a gyroscope; many phones without one expose no
     * (or an unreliable) rotation vector. Otherwise the classic accelerometer + magnetometer.
     */
    private fun registerCompass() {
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        compassSensor = true
        if (gyro != null && rv != null) {
            sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI)
        } else if (acc != null && mag != null) {
            sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
            sensors.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
        } else if (rv != null) {
            sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI)
        } else {
            compassSensor = false
        }
    }

    // ---------------------------------------------------------------- rendering

    private val tick = object : Runnable {
        override fun run() { renderAge(); ui.postDelayed(this, 1000L) }
    }

    private fun showCompass(on: Boolean) {
        compassMode = on
        findViewById<View>(R.id.mapScreen).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.compassScreen).visibility = if (on) View.VISIBLE else View.GONE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val navOn = findViewById<View>(if (on) R.id.navCompass else R.id.navMap)
        val navOff = findViewById<View>(if (on) R.id.navMap else R.id.navCompass)
        navOn.setBackgroundResource(R.drawable.bg_nav_on)
        navOff.background = null
        findViewById<ImageView>(R.id.navMapIcon).setColorFilter(if (on) MUTED else WHITE)
        findViewById<TextView>(R.id.navMapText).setTextColor(if (on) MUTED else WHITE)
        findViewById<ImageView>(R.id.navCompassIcon).setColorFilter(if (on) WHITE else MUTED)
        findViewById<TextView>(R.id.navCompassText).setTextColor(if (on) WHITE else MUTED)
        render()
    }

    private fun txt(id: Int, s: CharSequence) { findViewById<TextView>(id).text = s }

    private fun cardinal(d: Double): String {
        val n = resources.getStringArray(R.array.cardinals8)
        return n[(((d % 360 + 360) % 360) / 45.0).roundToInt() % 8]
    }

    // no-break space between number and unit: "1.075 m" never splits across lines
    private fun fmtAlt(a: Double) = if (a.isNaN()) "—" else String.format(BR, "%,.0f\u00A0m", a)

    private fun ageColor(sec: Long) = when { sec < 30 -> GREEN; sec < 120 -> AMBER; else -> RED }

    private fun ageText(sec: Long) = when {
        sec < 90 -> getString(R.string.ago_s, sec)
        sec < 90 * 60 -> getString(R.string.ago_min, sec / 60)
        sec < 48 * 3600 -> getString(R.string.ago_h, sec / 3600.0)
        sec < 365L * 86400 -> getString(R.string.ago_d, sec / 86400)
        else -> getString(R.string.ago_y, sec / (365.0 * 86400))
    }

    private fun etaSeconds(s: Sonde): Long? {
        val now = System.currentTimeMillis()
        val land = pred?.landing
        if (land != null && land.timeMs > now) return (land.timeMs - now) / 1000
        if (!s.velV.isNaN() && s.velV < -0.5 && !s.alt.isNaN()) {
            val ground = land?.alt?.takeIf { !it.isNaN() } ?: me?.altitude ?: 0.0
            val t = ((s.alt - ground) / -s.velV).toLong() - (now - s.timeMs) / 1000
            return if (t > 0) t else 0
        }
        return null
    }

    private fun etaText(s: Sonde?): String {
        if (s == null) return "—"
        if (s.velV.isNaN() || s.velV >= -0.5) return "—"
        // a sonde that stopped transmitting long ago has already landed: no countdown
        if (System.currentTimeMillis() - s.timeMs > 10 * 60_000L) return "—"
        val t = etaSeconds(s) ?: return "—"
        return if (t < 60) getString(R.string.lt_1min) else getString(R.string.approx_min, (t / 60.0).roundToInt())
    }

    private fun setDot(id: Int, color: Int) {
        (findViewById<View>(id).background.mutate() as GradientDrawable).setColor(color)
    }

    private fun renderAge() {
        val s = sonde
        val live = findViewById<TextView>(R.id.liveText)
        when {
            me == null -> { live.text = getString(R.string.waiting_gps); setDot(R.id.liveDot, AMBER) }
            netError != null -> { live.text = getString(R.string.no_connection); setDot(R.id.liveDot, RED) }
            // requests still running but nothing new for a while: never claim "live" on old data
            lastOkAt > 0 && System.currentTimeMillis() - lastOkAt > pollInterval() + STALE_MS -> {
                live.text = getString(R.string.stale_data, ageText((System.currentTimeMillis() - lastOkAt) / 1000))
                setDot(R.id.liveDot, AMBER)
            }
            else -> { live.text = getString(R.string.live); setDot(R.id.liveDot, GREEN) }
        }
        if (s == null || s.timeMs == 0L) {
            txt(R.id.age, "—"); txt(R.id.cAge, "—"); return
        }
        val sec = ((System.currentTimeMillis() - s.timeMs) / 1000).coerceAtLeast(0)
        val c = ageColor(sec)
        for (id in listOf(R.id.age, R.id.cAge)) {
            findViewById<TextView>(id).apply { text = ageText(sec); setTextColor(c) }
        }
        setDot(R.id.freshDot, c); setDot(R.id.cFreshDot, c)
        txt(R.id.vEta, etaText(s)); txt(R.id.cEta, etaText(s))
    }

    private fun render() {
        txt(R.id.btnFilter, "$radiusKm km · ${windowLabel(windowH)}" + if (alarmKm > 0) " · 🔔 $alarmKm km" else "")
        txt(R.id.modeLabel, getString(if (manualSerial != null) R.string.mode_manual else R.string.mode_auto))
        findViewById<View>(R.id.btnAuto).visibility = if (manualSerial != null) View.VISIBLE else View.GONE
        val s = sonde
        if (s == null) {
            txt(R.id.serial, "—"); txt(R.id.cSerial, "—")
            txt(R.id.stype, when {
                me == null -> getString(R.string.waiting_gps_lc)
                netError != null -> getString(R.string.no_connection_sondehub)
                emptyResult -> getString(R.string.no_sonde_in, radiusKm, windowLabel(windowH))
                else -> getString(R.string.searching)
            })
            txt(R.id.cType, findViewById<TextView>(R.id.stype).text)
            txt(R.id.badge, "—")
            for (id in listOf(R.id.vAlt, R.id.vVv, R.id.vVh, R.id.vHead, R.id.vFreq, R.id.vEta, R.id.coords,
                R.id.cAlt, R.id.cElev, R.id.cEta, R.id.chipDist, R.id.cDist)) txt(id, "—")
            txt(R.id.via, ""); txt(R.id.cVv, "")
            findViewById<View>(R.id.warn).visibility = View.GONE
            findViewById<View>(R.id.cWarn).visibility = View.GONE
            txt(R.id.chipBearing, getString(if (me == null) R.string.waiting_gps_lc else R.string.no_sonde))
            txt(R.id.cBearing, "")
            compass.bearing = null
            renderSegment(); renderAge(); renderCompass()
            return
        }

        txt(R.id.serial, s.serial)
        txt(R.id.stype, s.type)
        txt(R.id.cSerial, s.serial)
        txt(R.id.cType, listOfNotNull(s.type.ifBlank { null },
            if (s.freq.isNaN()) null else String.format(BR, "%.3f MHz", s.freq)).joinToString(" · "))
        val ageSec = (System.currentTimeMillis() - s.timeMs) / 1000
        val recv = recoveries[s.serial]
        txt(R.id.badge, when {
            recv?.recovered == true -> getString(R.string.st_recovered)
            ageSec > 600 -> getString(R.string.st_no_signal)
            s.velV.isNaN() -> "—"
            s.velV < -1.0 -> getString(R.string.st_descending)
            s.velV > 1.0 -> getString(R.string.st_ascending)
            ageSec > 300 -> getString(R.string.st_landed)
            else -> getString(R.string.st_stable)
        })
        findViewById<TextView>(R.id.badge).setTextColor(if (recv?.recovered == true) GREEN else Color.parseColor("#FFB07A"))
        txt(R.id.via, if (s.uploader.isBlank()) "" else getString(R.string.via, s.uploader))
        // warnings: already recovered / signal lost high above ground
        val warns = ArrayList<String>()
        if (recv != null) {
            val head = if (recv.recovered) {
                if (recv.by.isNotBlank()) getString(R.string.recovered_by, recv.by) else getString(R.string.recovered)
            } else {
                if (recv.by.isNotBlank()) getString(R.string.failed_by, recv.by) else getString(R.string.failed)
            }
            warns.add(head + if (recv.description.isNotBlank()) ": ${recv.description}" else ".")
        }
        val myAlt = me?.takeIf { it.hasAltitude() }?.altitude
        if (ageSec > 600 && !s.alt.isNaN() && myAlt != null && s.alt - myAlt > 300) {
            warns.add(getString(R.string.high_last, fmtAlt(s.alt - myAlt),
                if (s.heading.isNaN()) "" else getString(R.string.along_heading, s.heading, cardinal(s.heading))))
        }
        for (id in listOf(R.id.warn, R.id.cWarn)) findViewById<TextView>(id).apply {
            text = warns.joinToString("\n"); visibility = if (warns.isEmpty()) View.GONE else View.VISIBLE
        }
        txt(R.id.vAlt, fmtAlt(s.alt))
        txt(R.id.vVv, if (s.velV.isNaN()) "—" else String.format(BR, "%+.1f m/s", s.velV).replace("-", "−"))
        findViewById<TextView>(R.id.vVv).setTextColor(if (!s.velV.isNaN() && s.velV < 0) Color.parseColor("#FFB07A") else WHITE)
        txt(R.id.vVh, if (s.velH.isNaN()) "—" else String.format(BR, "%.0f km/h", s.velH * 3.6))
        txt(R.id.vHead, if (s.heading.isNaN()) "—" else String.format(BR, "%.0f° %s", s.heading, cardinal(s.heading)))
        txt(R.id.vFreq, if (s.freq.isNaN()) "—" else String.format(BR, "%.3f", s.freq))
        txt(R.id.coords, String.format(Locale.US, "%.5f, %.5f", s.lat, s.lon))
        txt(R.id.cAlt, fmtAlt(s.alt))
        txt(R.id.cVv, if (s.velV.isNaN()) "" else String.format(BR, "%s %.1f m/s", if (s.velV < 0) "↓" else "↑", abs(s.velV)))

        val u = me
        if (u != null) {
            val r = FloatArray(2)
            Location.distanceBetween(u.latitude, u.longitude, s.lat, s.lon, r)
            var b = r[1].toDouble(); if (b < 0) b += 360
            txt(R.id.chipDist, fmtDist(r[0].toDouble()).let { "${it.first}\u00A0${it.second}" })
            txt(R.id.chipBearing, getString(R.string.chip_bearing, b, cardinal(b)))
            chipBearing = b.toFloat(); updateChipArrow()
            if (!s.alt.isNaN()) {
                val myAlt = if (u.hasAltitude()) u.altitude else 0.0
                val el = Math.toDegrees(atan2(s.alt - myAlt, r[0].toDouble()))
                txt(R.id.cElev, String.format(BR, "%.0f°", el))
            } else txt(R.id.cElev, "—")
        }
        renderSegment(); renderAge(); renderCompass()
    }

    private fun fmtDist(m: Double): Pair<String, String> =
        if (m < 1000) Pair(String.format(BR, "%.0f", m), "m")
        else if (m < 100_000) Pair(String.format(BR, "%.2f", m / 1000), "km")
        else Pair(String.format(BR, "%.0f", m / 1000), "km")

    private fun renderSegment() {
        val last = findViewById<TextView>(R.id.segLast)
        val land = findViewById<TextView>(R.id.segLand)
        val hasLand = pred?.landing != null
        if (!hasLand && targetLanding) targetLanding = false
        last.setBackgroundResource(if (!targetLanding) R.drawable.bg_seg_on else 0)
        land.setBackgroundResource(if (targetLanding) R.drawable.bg_seg_on else 0)
        last.setTextColor(if (!targetLanding) WHITE else MUTED)
        land.setTextColor(if (targetLanding) WHITE else if (hasLand) MUTED else Color.parseColor("#55626D"))
        land.isEnabled = hasLand
    }

    private fun renderCompass() {
        if (!compassMode) return
        compass.heading = heading
        val s = sonde
        val u = me
        val tgt: Pair<Double, Double>? = if (s == null) null
            else if (targetLanding) pred?.landing?.let { Pair(it.lat, it.lon) }
            else Pair(s.lat, s.lon)
        val color = if (targetLanding) WHITE else ACCENT
        compass.needleColor = color
        findViewById<TextView>(R.id.cCaption).apply {
            text = getString(if (targetLanding) R.string.to_landing else R.string.to_last_position)
            setTextColor(color)
        }
        if (tgt == null || u == null) {
            compass.bearing = null
            txt(R.id.cDist, "—"); txt(R.id.cBearing, if (u == null) getString(R.string.waiting_gps_lc) else "")
            return
        }
        val r = FloatArray(2)
        Location.distanceBetween(u.latitude, u.longitude, tgt.first, tgt.second, r)
        var b = r[1].toDouble(); if (b < 0) b += 360
        compass.bearing = b.toFloat()
        val dist = fmtDist(r[0].toDouble())
        txt(R.id.cDist, dist.first); txt(R.id.cUnit, dist.second)
        var rel = (b - heading).toFloat()
        if (rel > 180) rel -= 360f
        if (rel < -180) rel += 360f
        val turn = when {
            !haveHeading -> getString(if (compassSensor) R.string.no_compass else R.string.no_compass_sensor)
            abs(rel) <= 5 -> getString(R.string.straight_ahead)
            else -> getString(if (rel > 0) R.string.turn_right else R.string.turn_left, abs(rel))
        }
        txt(R.id.cBearing, getString(R.string.bearing_line, b, cardinal(b), turn) +
            if (!haveHeading) "" else getString(if (headingFromGps) R.string.heading_src_gps else R.string.heading_src_mag, heading) +
                if (headingFromGps || compassOffset == 0f) "" else getString(R.string.offset_suffix, compassOffset))
    }

    // ---------------------------------------------------------------- selection & filter

    private fun windowLabel(h: Int) = when {
        h == 8760 -> getString(R.string.one_year)
        h % 24 == 0 && h >= 48 -> getString(R.string.days_short, h / 24)
        else -> getString(R.string.hours_short, h)
    }

    /** Pick a sonde by serial (manual), or null to go back to automatic (most recent). */
    private fun select(serial: String?) {
        manualSerial = serial
        val chosen = serial?.let { k -> all.firstOrNull { it.serial == k } }
        if (chosen != null && chosen.serial != sonde?.serial) {
            sonde = chosen; track = emptyList(); pred = null; targetLanding = false
            autoFitDone = false
        }
        lastDetailAt = 0
        render(); updateMap()
        ui.removeCallbacks(poll); ui.post(poll)
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (on) Color.parseColor("#0E1419") else WHITE)
            setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            gravity = Gravity.CENTER
            minWidth = (56 * d).toInt()
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * d).toInt()).apply {
                marginEnd = (8 * d).toInt()
            }
            setOnClickListener { click() }
        }
    }

    private fun showMapSettings() {
        val d = resources.displayMetrics.density
        var st = mapStyle
        val startBrightness = mapBrightness
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(MUTED); letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (14 * d).toInt(), 0, (8 * d).toInt())
        }
        val names = mapOf("dark" to R.string.style_dark, "fiord" to R.string.style_fiord,
            "positron" to R.string.style_positron, "liberty" to R.string.style_liberty)
        val row = LinearLayout(this)
        fun fill() {
            row.removeAllViews()
            for ((k, r) in names) row.addView(chip(getString(r), k == st) { st = k; map.setStyleName(k); fill() })
        }
        fill()
        val value = TextView(this).apply { setTextColor(WHITE); textSize = 13f }
        fun showValue(v: Int) { value.text = if (v == 0) "0" else String.format(BR, "%+d", v) }
        showValue(mapBrightness)
        val seek = android.widget.SeekBar(this).apply {
            max = 100; progress = mapBrightness + 50
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    mapBrightness = p - 50; showValue(mapBrightness); map.setBrightness(mapBrightness)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        box.addView(label(getString(R.string.map_style_label)))
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        box.addView(label(getString(R.string.map_brightness_label)))
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f))
            addView(value)
        })
        val original = mapStyle
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.map_settings_title)
            .setView(box)
            .setNegativeButton(R.string.cancel) { _, _ ->
                mapBrightness = startBrightness; map.setBrightness(startBrightness); map.setStyleName(original)
            }
            .setNeutralButton(R.string.align_reset) { _, _ ->
                mapBrightness = 0; map.setBrightness(0); saveMapSettings(st)
            }
            .setPositiveButton(R.string.apply) { _, _ -> saveMapSettings(st) }
            .show()
    }

    private fun saveMapSettings(style: String) {
        mapStyle = style
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("mapStyle", style).putInt("mapBrightness", mapBrightness).apply()
    }

    private fun showFilterDialog() {
        val d = resources.displayMetrics.density
        var r = radiusKm
        var w = windowH
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(MUTED); letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (14 * d).toInt(), 0, (8 * d).toInt())
        }
        var hide = hideRecovered
        var alarm = alarmKm
        val wRow = LinearLayout(this)
        val hRow = LinearLayout(this)
        val aRow = LinearLayout(this)
        fun fill() {
            wRow.removeAllViews(); hRow.removeAllViews(); aRow.removeAllViews()
            hRow.addView(chip(getString(R.string.show_all), !hide) { hide = false; fill() })
            hRow.addView(chip(getString(R.string.hide_recovered), hide) { hide = true; fill() })
            for (h in WINDOWS_H) wRow.addView(chip(windowLabel(h), h == w) { w = h; fill() })
            for (k in ALARM_KM) aRow.addView(chip(if (k == 0) getString(R.string.alarm_off) else "$k km", k == alarm) { alarm = k; fill() })
        }
        fill()
        box.addView(label(getString(R.string.radius_label)))
        // radius: slider 1–25 km
        val rValue = TextView(this).apply { setTextColor(WHITE); textSize = 14f; text = "$r km"; minWidth = (56 * d).toInt(); gravity = Gravity.END }
        val rSeek = android.widget.SeekBar(this).apply {
            max = MAX_RADIUS_KM - 1; progress = r - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { r = p + 1; rValue.text = "$r km" }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(rSeek, LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f))
            addView(rValue)
        })
        box.addView(label(getString(R.string.window_label)))
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wRow) })
        box.addView(label(getString(R.string.recovered_label)))
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(hRow) })
        box.addView(TextView(this).apply {
            text = getString(R.string.long_window_hint)
            textSize = 12f; setTextColor(MUTED); setPadding(0, (14 * d).toInt(), 0, 0)
        })
        box.addView(label(getString(R.string.alarm_label)))
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(aRow) })
        box.addView(TextView(this).apply {
            text = getString(R.string.alarm_hint)
            textSize = 12f; setTextColor(MUTED); setPadding(0, (10 * d).toInt(), 0, 0)
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.filter_title)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                radiusKm = r; windowH = w; hideRecovered = hide; lastRecAt = 0
                if (alarm != alarmKm) { alarmKm = alarm; alarmed.clear(); lastAlarmAt = 0 }
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("radiusKm", r).putInt("windowH", w)
                    .putBoolean("hideRecovered", hide).putInt("alarmKm", alarm).apply()
                all = emptyList(); sonde = null; track = emptyList(); pred = null; histKey = ""
                manualSerial = null; autoFitDone = false; lastDetailAt = 0; emptyResult = false
                render(); updateMap()
                ui.removeCallbacks(poll); ui.post(poll)
                ui.postDelayed({ fitEverything() }, 2500)
            }
            .show()
    }

    private fun showListDialog() {
        val u = me
        if (all.isEmpty()) { Toast.makeText(this, getString(R.string.no_sonde_filter), Toast.LENGTH_SHORT).show(); return }
        val res = FloatArray(1)
        val now = System.currentTimeMillis()
        val sorted = if (sortByAge) all.sortedByDescending { it.timeMs } else all.sortedBy {
            if (u == null) 0f else { Location.distanceBetween(u.latitude, u.longitude, it.lat, it.lon, res); res[0] }
        }
        val items = ArrayList<String>()
        items.add((if (manualSerial == null) "● " else "") + getString(R.string.auto_most_recent))
        for (o in sorted) {
            val dist = if (u == null) "" else {
                Location.distanceBetween(u.latitude, u.longitude, o.lat, o.lon, res)
                fmtDist(res[0].toDouble()).let { "${it.first}\u00A0${it.second}" }
            }
            val mark = if (manualSerial == o.serial) "● " else ""
            val rv = recoveries[o.serial]
            val tag = if (rv?.recovered == true) "  ·  " + getString(R.string.tag_recovered) else if (rv != null) "  ·  " + getString(R.string.tag_not_found) else ""
            items.add("$mark${o.serial}  ·  $dist  ·  ${ageText((now - o.timeMs) / 1000).replace(' ', '\u00A0')}  ·  ${fmtAlt(o.alt)}$tag")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.choose_title)
            .setItems(items.toTypedArray()) { _, i -> select(if (i == 0) null else sorted[i - 1].serial) }
            .setNegativeButton(R.string.close, null)
            // ⇅ toggles the order (nearest first / most recent first) and reopens the list
            .setNeutralButton(getString(if (sortByAge) R.string.sort_by_distance else R.string.sort_by_age)) { _, _ ->
                sortByAge = !sortByAge
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("sortByAge", sortByAge).apply()
                showListDialog()
            }
            .show()
    }

    // ---------------------------------------------------------------- proximity detector

    /** Distance (m) from the user to the compass target, or null. */
    private fun targetDistance(): Float? {
        val u = me ?: return null
        val s = sonde ?: return null
        val t = if (targetLanding) pred?.landing?.let { Pair(it.lat, it.lon) } ?: return null else Pair(s.lat, s.lon)
        val r = FloatArray(1)
        Location.distanceBetween(u.latitude, u.longitude, t.first, t.second, r)
        return r[0]
    }

    private fun setDetector(on: Boolean) {
        detectorOn = on
        ui.removeCallbacks(detector)
        if (on) {
            if (tone == null) tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }
            nextBeepAt = 0; ui.post(detector)
        } else {
            tone?.release(); tone = null
        }
        updateDetectorLabel(targetDistance())
    }

    private fun updateDetectorLabel(d: Float?) {
        findViewById<ImageView>(R.id.btnSound).setColorFilter(if (detectorOn) ACCENT else WHITE)
        val b = findViewById<TextView>(R.id.btnDetector)
        if (!detectorOn) {
            b.text = getString(R.string.detector_off); b.setTextColor(WHITE); return
        }
        val acc = me?.takeIf { it.hasAccuracy() }?.let { getString(R.string.gps_acc, it.accuracy) } ?: ""
        b.text = when {
            d == null -> getString(R.string.detector_no_target)
            d > DETECT_MAX_M -> getString(R.string.detector_far, acc)
            else -> getString(R.string.detector_dist, d, acc)
        }
        b.setTextColor(when { d == null || d > DETECT_MAX_M -> MUTED; d < 50 -> GREEN; d < 300 -> AMBER; else -> ACCENT })
    }

    /** "Metal detector": beeps and vibrates faster as you get closer (starts at 3 km). */
    /**
     * Geiger-counter style: short clicks whose rate rises on a log scale as you get closer,
     * from one click every ~2.5 s at 5 km to ~16 per second within 5 m (slightly irregular,
     * like a real counter). Vibration joins in under 500 m; a different tone under 15 m.
     */
    private val detector = object : Runnable {
        override fun run() {
            if (!detectorOn) return
            val d = targetDistance()
            val now = System.currentTimeMillis()
            if (now - lastDetectorLabel > 500) { updateDetectorLabel(d); lastDetectorLabel = now }
            if (d != null && d <= DETECT_MAX_M && now >= nextBeepAt) {
                val x = (d.coerceIn(5f, DETECT_MAX_M) / 5f).toDouble()
                val base = 60.0 * Math.pow(x, Math.log(2500.0 / 60.0) / Math.log(DETECT_MAX_M / 5.0))
                val jitter = 0.75 + Math.random() * 0.5
                nextBeepAt = now + (base * jitter).toLong().coerceAtLeast(40L)
                tone?.startTone(if (d < 15) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, if (d < 15) 60 else 18)
                if (d < 500) try {
                    val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                    v.vibrate(VibrationEffect.createOneShot(if (d < 15) 80 else 20, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {}
            }
            ui.postDelayed(this, 20)
        }
    }

    private var lastDetectorLabel = 0L

    // ---------------------------------------------------------------- compass alignment

    /** Manual alignment: point the phone (top, or back if upright) at a known direction. */
    private fun showAlignDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.align_title)
            .setMessage(getString(R.string.align_msg, rawMag, compassOffset))
            .setPositiveButton(R.string.align_now) { _, _ -> setOffset(-rawMag) }
            .setNeutralButton(R.string.align_flip) { _, _ -> setOffset(compassOffset + 180f) }
            .setNegativeButton(R.string.align_reset) { _, _ -> setOffset(0f) }
            .show()
    }

    private fun setOffset(o: Float) {
        var v = ((o % 360f) + 360f) % 360f
        if (v > 180f) v -= 360f
        compassOffset = v
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putFloat("compassOffset", v).apply()
        haveHeading = false
        Toast.makeText(this, getString(R.string.align_done, v), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- actions

    // ---------------------------------------------------------------- back / exit

    /** Back: compass screen returns to the map; on the map, confirm before closing. */
    private fun handleBack() {
        if (compassMode) { showCompass(false); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.exit_yes) { _, _ -> finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Android 8–12 (and 13+ when the callback above is not used)
        handleBack()
    }

    private fun showAbout() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.about_msg, UpdateManager.installedVersion(this@MainActivity))
            setTextColor(WHITE); textSize = 15f
        })
        box.addView(android.widget.CheckBox(this).apply {
            text = getString(R.string.usage_label); setTextColor(WHITE); textSize = 14f
            isChecked = UsagePing.enabled(this@MainActivity)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setOnCheckedChangeListener { _, on -> UsagePing.setEnabled(this@MainActivity, on) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * d).toInt()
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.app_name)
            .setView(ScrollView(this).apply { addView(box) })
            .apply { if (BuildConfig.OTA) setPositiveButton(R.string.check_updates) { _, _ -> UpdateManager.check(this@MainActivity, true) } }
            .setNeutralButton("www.PT2VHF.com") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pt2vhf.com/sonde"))) } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun copyCoords() {
        val s = sonde ?: return
        val text = String.format(Locale.US, "%.6f, %.6f", s.lat, s.lon)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(s.serial, text))
        Toast.makeText(this, getString(R.string.coords_copied), Toast.LENGTH_SHORT).show()
    }

    private fun navigate() {
        val s = sonde ?: return
        val (lat, lon) = if (targetLanding) pred?.landing?.let { Pair(it.lat, it.lon) } ?: Pair(s.lat, s.lon) else Pair(s.lat, s.lon)
        val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(s.serial)))
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.no_nav_app), Toast.LENGTH_SHORT).show() }
    }
}
