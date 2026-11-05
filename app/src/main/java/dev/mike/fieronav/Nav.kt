package dev.mike.fieronav

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.mike.fieronav.ui.KeyRouter

/**
 * The navigation session: destination, route, progress along it, spoken prompts, rerouting.
 * Lives as long as the process; [NavService] keeps the process (and GPS) alive while guiding.
 * Main thread only.
 */
object Nav {
    enum class Status { IDLE, ROUTING, GUIDING, ARRIVED, ERROR }

    data class Snapshot(
        val status: Status,
        val message: String = "",
        val destination: Place? = null,
        /** Next maneuver worth showing (quiet name changes skipped). */
        val next: Step? = null,
        val distToNext: Double = 0.0,
        /** The maneuver after [next], when it comes soon after (for "then …"). */
        val then: Step? = null,
        val currentRoad: String = "",
        val remainingDist: Double = 0.0,
        val remainingTime: Double = 0.0,
        val etaMillis: Long = 0,
        val muted: Boolean = false,
        val gpsOk: Boolean = false,
        val simulating: Boolean = false,
    )

    data class Upcoming(val step: Step, val distance: Double)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()

    var snapshot = Snapshot(Status.IDLE)
        private set

    private var destination: Place? = null
    private var route: Route? = null
    private var segIndex = 0
    private var along = 0.0
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var offRouteCount = 0
    private var lastRerouteAt = 0L
    private var rerouting = false
    private var muted = false
    private var simulate = false

    /** Debug simulator settings (GuideActivity extras `sim_speed`, `sim_detour`). */
    var simSpeed = 17.0
    var simDetour = false
    private var announcedStep = -1
    private var announcedLevel = 0 // 1 = early heads-up, 2 = final prompt
    private var spokeStart = false

    private lateinit var voice: Voice

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        voice = Voice(app)
    }

    fun addListener(l: (Snapshot) -> Unit) {
        listeners += l
        l(snapshot)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners -= l
    }

    val active: Boolean get() = snapshot.status == Status.ROUTING || snapshot.status == Status.GUIDING

    // ---- session ----

    /** Starts guidance to [place]; uses [initial] if a route was already fetched for the preview. */
    fun start(context: Context, place: Place, initial: Route?, simulated: Boolean = false) {
        init(context)
        stopLocation()
        destination = place
        route = null
        simulate = simulated
        resetProgress()
        spokeStart = false
        PlaceStore.addRecent(app, place)
        app.startForegroundService(Intent(app, NavService::class.java))
        startLocation()
        if (initial != null) {
            useRoute(initial)
        } else {
            publish(Status.ROUTING, "Finding route…")
            fetchRoute(lastKnown(app), heading = null)
        }
    }

    fun stop() {
        stopLocation()
        voice.stop()
        destination = null
        route = null
        publish(Status.IDLE)
        app.stopService(Intent(app, NavService::class.java))
    }

    fun toggleMute(): Boolean {
        muted = !muted
        if (muted) voice.stop()
        publish(snapshot.status, snapshot.message)
        return muted
    }

    /** Speaks the current instruction again (OK on the guidance screen). */
    fun repeat() {
        val s = snapshot
        val next = s.next ?: return
        voice.say(prompt(next, s.distToNext), force = true)
    }

    fun upcoming(): List<Upcoming> {
        val r = route ?: return emptyList()
        return r.steps.filter { r.along(it) > along + 1 }.map { Upcoming(it, r.along(it) - along) }
    }

    fun reroute() {
        val fix = lastFix ?: return
        lastRerouteAt = 0
        startReroute(fix)
    }

    // ---- routing ----

    private fun fetchRoute(from: LatLng?, heading: Double?) {
        val to = destination?.pos ?: return
        if (from == null) {
            publish(Status.ROUTING, "Waiting for GPS…")
            return // the first fix triggers routing (see onFix)
        }
        rerouting = true
        Net.io.execute {
            val result = runCatching { Routing.routes(from, to, heading, alternatives = false).first() }
            main.post {
                rerouting = false
                if (destination?.pos != to) return@post // stopped or changed meanwhile
                result.onSuccess { useRoute(it) }.onFailure {
                    Log.w(KeyRouter.TAG, "route failed: $it")
                    if (route == null) publish(Status.ERROR, it.message ?: "No route")
                    else say("Can't reach the route server", force = false)
                }
            }
        }
    }

    private fun useRoute(r: Route) {
        route = r
        resetProgress()
        lastFix?.let { onFix(it) } ?: run { recompute() }
        if (simulate) Simulator.start(r)
    }

    private fun resetProgress() {
        segIndex = 0
        along = 0.0
        offRouteCount = 0
        announcedStep = -1
        announcedLevel = 0
    }

    private fun startReroute(fix: Location) {
        if (rerouting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRerouteAt < 8_000) return
        lastRerouteAt = now
        simDetour = false // the simulator restarts on the new route; don't detour again
        say("Rerouting", force = false)
        val heading = if (fix.hasBearing() && fix.speed > 3) fix.bearing.toDouble() else null
        fetchRoute(LatLng(fix.latitude, fix.longitude), heading)
    }

    // ---- location ----

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!simulate) onFix(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (simulate) return
        val lm = app.getSystemService(LocationManager::class.java)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            publish(Status.ERROR, "Location permission missing")
        } catch (e: IllegalArgumentException) {
            publish(Status.ERROR, "No GPS")
        }
    }

    private fun stopLocation() {
        Simulator.stop()
        if (::app.isInitialized) app.getSystemService(LocationManager::class.java).removeUpdates(gpsListener)
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): LatLng? {
        val lm = context.getSystemService(LocationManager::class.java)
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { LatLng(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        }
    }

    internal fun onFix(fix: Location) {
        lastFix = fix
        lastFixAt = SystemClock.elapsedRealtime()
        if (destination == null) return
        val r = route
        if (r == null) {
            if (!rerouting) fetchRoute(LatLng(fix.latitude, fix.longitude), null)
            return
        }
        val p = LatLng(fix.latitude, fix.longitude)
        // Search a window ahead of the last position (routes can pass near themselves).
        var best = segIndex
        var bestProj = Geo.Projection(0.0, Double.MAX_VALUE)
        val from = (segIndex - 2).coerceAtLeast(0)
        val to = (segIndex + 80).coerceAtMost(r.points.size - 2)
        for (i in from..to) {
            val proj = Geo.project(p, r.points[i], r.points[i + 1])
            if (proj.distance < bestProj.distance) {
                best = i
                bestProj = proj
            }
        }
        val tolerance = maxOf(45.0, if (fix.hasAccuracy()) fix.accuracy * 1.5 else 45.0)
        if (bestProj.distance > tolerance) {
            offRouteCount++
            if (offRouteCount >= 3) startReroute(fix)
        } else {
            offRouteCount = 0
            segIndex = best
            val segLen = r.cumulative[best + 1] - r.cumulative[best]
            along = r.cumulative[best] + bestProj.fraction * segLen
        }
        recompute()
    }

    // ---- progress + prompts ----

    private fun recompute() {
        val r = route ?: return
        val dest = destination ?: return
        val remaining = (r.length - along).coerceAtLeast(0.0)
        val fix = lastFix
        val toDest = fix?.let { Geo.distance(LatLng(it.latitude, it.longitude), dest.pos) } ?: Double.MAX_VALUE
        if (snapshot.status != Status.ARRIVED && (remaining < 25 || toDest < 25)) {
            say("You have arrived", force = false)
            stopLocation()
            publish(Status.ARRIVED, "Arrived")
            // End the session (and the foreground notification) a minute after arriving.
            val session = destination
            main.postDelayed({ if (snapshot.status == Status.ARRIVED && destination === session) stop() }, 60_000)
            return
        }
        if (snapshot.status == Status.ARRIVED) return

        val stepIdx = r.steps.indexOfFirst { r.along(it) > along + 3 && !Instructions.isQuiet(it.type, it.modifier) }
        val next = r.steps.getOrNull(stepIdx)
        val distToNext = next?.let { r.along(it) - along } ?: remaining
        val current = r.steps.lastOrNull { r.along(it) <= along + 3 }
        val after = if (stepIdx >= 0) r.steps.drop(stepIdx + 1).firstOrNull { !Instructions.isQuiet(it.type, it.modifier) } else null
        val then = after?.takeIf { r.along(it) - r.along(next!!) < 250 }

        // Remaining time: OSRM's time for the rest of the current stretch (pro rata) plus all later steps.
        var time = 0.0
        val curIdx = current?.let { r.steps.indexOf(it) } ?: 0
        for (i in curIdx until r.steps.size) {
            val s = r.steps[i]
            val start = r.along(s)
            val end = r.steps.getOrNull(i + 1)?.let { r.along(it) } ?: r.length
            val len = (end - start).coerceAtLeast(1.0)
            val left = (end - maxOf(start, along)).coerceIn(0.0, len)
            time += s.duration * left / len
        }

        publishGuiding(next, distToNext, then, current?.road.orEmpty(), remaining, time)
        if (next != null) announce(stepIdx, next, distToNext, then, current)
    }

    private fun announce(stepIdx: Int, next: Step, dist: Double, then: Step?, current: Step?) {
        val speed = lastFix?.takeIf { it.hasSpeed() }?.speed?.toDouble() ?: 0.0
        if (!spokeStart) {
            spokeStart = true
            val road = current?.road?.takeIf { it.isNotEmpty() }
            say(if (road != null && dist > 1500) "Continue on $road for ${Units.spokenDistance(dist)}" else prompt(next, dist), false)
            if (dist > 1500) announcedStep = stepIdx.also { announcedLevel = 0 } else announcedStep = stepIdx.also { announcedLevel = 1 }
            return
        }
        if (stepIdx != announcedStep) {
            announcedStep = stepIdx
            announcedLevel = 0
            // Long stretch ahead: say so once, right after the previous maneuver.
            if (dist > 3000) {
                val road = current?.road?.takeIf { it.isNotEmpty() }
                if (road != null) say("Continue on $road for ${Units.spokenDistance(dist)}", false)
            }
        }
        val early = when {
            speed > 22 -> 1600.0 // highway: a mile out
            speed > 13 -> 800.0
            else -> 300.0
        }
        val final = maxOf(60.0, speed * 7)
        if (announcedLevel < 2 && dist <= final) {
            announcedLevel = 2
            val thenText = then?.let { ", then ${lower(it.text)}" } ?: ""
            say(next.text + thenText, false)
        } else if (announcedLevel < 1 && dist <= early && dist > final + 80) {
            announcedLevel = 1
            say(prompt(next, dist), false)
        }
    }

    private fun prompt(step: Step, dist: Double) = "In ${Units.spokenDistance(dist)}, ${lower(step.text)}"

    private fun lower(s: String) = s.replaceFirstChar { it.lowercase() }

    private fun say(text: String, force: Boolean) {
        Log.i(KeyRouter.TAG, "say: $text")
        if (!muted || force) voice.say(text, force)
    }

    // ---- publishing ----

    private fun publishGuiding(next: Step?, dist: Double, then: Step?, road: String, remaining: Double, time: Double) {
        snapshot = Snapshot(
            status = Status.GUIDING,
            destination = destination,
            next = next,
            distToNext = dist,
            then = then,
            currentRoad = road,
            remainingDist = remaining,
            remainingTime = time,
            etaMillis = System.currentTimeMillis() + (time * 1000).toLong(),
            muted = muted,
            gpsOk = lastFix != null && SystemClock.elapsedRealtime() - lastFixAt < 5000,
            simulating = simulate,
        )
        listeners.toList().forEach { it(snapshot) }
    }

    private fun publish(status: Status, message: String = "") {
        snapshot = if (status == Status.GUIDING) snapshot.copy(muted = muted)
        else Snapshot(status, message, destination, muted = muted, simulating = simulate)
        listeners.toList().forEach { it(snapshot) }
    }

    /** Debug: drives the route at a fixed speed, one fix per second (`--ez simulate true`). */
    private object Simulator {
        private var running: Runnable? = null

        fun start(r: Route) {
            stop()
            var d = 0.0
            var ticks = 0
            val speed = simSpeed
            val tick = object : Runnable {
                override fun run() {
                    ticks++
                    d = (d + speed).coerceAtMost(r.length)
                    var i = r.cumulative.indexOfFirst { it >= d }.coerceAtLeast(1)
                    if (i >= r.points.size) i = r.points.size - 1
                    val segLen = (r.cumulative[i] - r.cumulative[i - 1]).coerceAtLeast(0.01)
                    var p = Geo.lerp(r.points[i - 1], r.points[i], (d - r.cumulative[i - 1]) / segLen)
                    // Detour: drift ~150 m off the route for ticks 8-14 to trigger a reroute (once).
                    if (simDetour && ticks in 8..14) p = LatLng(p.lat + 0.00135, p.lon)
                    if (simDetour && ticks == 15) simDetour = false
                    val loc = Location("sim").apply {
                        latitude = p.lat
                        longitude = p.lon
                        accuracy = 5f
                        this.speed = speed.toFloat()
                        bearing = Geo.bearing(r.points[i - 1], r.points[i]).toFloat()
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    onFix(loc)
                    if (d < r.length && running === this) main.postDelayed(this, 1000)
                }
            }
            running = tick
            main.postDelayed(tick, 1000)
        }

        fun stop() {
            running?.let { main.removeCallbacks(it) }
            running = null
        }
    }
}
