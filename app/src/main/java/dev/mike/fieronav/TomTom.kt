package dev.mike.fieronav

import org.json.JSONObject
import kotlin.math.abs

/**
 * Traffic-aware car routes from TomTom's Routing API (v1 calculateRoute), used when a TomTom API key is
 * set (see [Keys]). Free tier: 20,000 requests a month. Returns the same [Route]/[Step] model as OSRM,
 * plus per-segment speed limits and the traffic delay.
 */
object TomTom {

    fun routes(key: String, from: LatLng, to: LatLng, headingDeg: Double?, alternatives: Boolean): List<Route> {
        val heading = headingDeg?.let { "&vehicleHeading=${(it.toInt() % 360 + 360) % 360}" } ?: ""
        val url = "https://api.tomtom.com/routing/1/calculateRoute/" +
            "${from.lat},${from.lon}:${to.lat},${to.lon}/json" +
            "?key=$key&travelMode=car&routeType=fastest&traffic=true" +
            "&maxAlternatives=${if (alternatives) 2 else 0}" +
            "&instructionsType=text&language=en-US&sectionType=speedLimit$heading"
        return parse(Net.getJson(url, timeoutMs = 12_000))
    }

    /** Pure: response JSON -> routes. Unit-tested with a canned response. */
    fun parse(json: JSONObject): List<Route> {
        json.optJSONObject("error")?.let { throw RouteError(it.optString("description", "TomTom error")) }
        val arr = json.optJSONArray("routes") ?: throw RouteError(
            // Errors come as {"code","message"} (auth) or {"detailedError":{"message"}} (bad request).
            json.optJSONObject("detailedError")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").ifBlank { "No route" },
        )
        return (0 until arr.length()).map { parseRoute(arr.getJSONObject(it)) }
    }

    private fun parseRoute(r: JSONObject): Route {
        val points = ArrayList<LatLng>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val pts = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until pts.length()) {
                val p = pts.getJSONObject(i)
                val ll = LatLng(p.getDouble("latitude"), p.getDouble("longitude"))
                if (points.isEmpty() || points.last() != ll) points += ll
            }
        }
        if (points.size == 1) points += points[0]
        val last = points.size - 1

        val summary = r.getJSONObject("summary")
        val total = summary.optDouble("travelTimeInSeconds")

        val instructions = r.optJSONObject("guidance")?.optJSONArray("instructions")
        val raw = ArrayList<Triple<Step, Double, Int>>() // step, travel time to it, index
        if (instructions != null) {
            for (i in 0 until instructions.length()) {
                val ins = instructions.getJSONObject(i)
                val idx = ins.optInt("pointIndex", 0).coerceIn(0, last)
                raw += Triple(step(ins, idx, points), ins.optDouble("travelTimeInSeconds"), idx)
            }
        }
        // Each step's duration is the time from it to the next instruction.
        val steps = raw.mapIndexed { i, (s, t, _) ->
            val next = raw.getOrNull(i + 1)?.second ?: total
            s.copy(duration = (next - t).coerceAtLeast(0.0))
        }.ifEmpty {
            listOf(Step("arrive", "", "", "Arrive at your destination", Arrow.ARRIVE, last, 0.0))
        }

        // SPEED_LIMIT sections: maxSpeedLimitInKmh over [startPointIndex, endPointIndex] of the points.
        var limits: IntArray? = null
        r.optJSONArray("sections")?.let { sections ->
            for (i in 0 until sections.length()) {
                val s = sections.getJSONObject(i)
                if (s.optString("sectionType") != "SPEED_LIMIT") continue
                val kmh = s.optInt("maxSpeedLimitInKmh", 0)
                if (kmh <= 0) continue
                val arr = limits ?: IntArray(points.size).also { limits = it }
                val a = s.optInt("startPointIndex", 0).coerceIn(0, last)
                val b = s.optInt("endPointIndex", a).coerceIn(a, last)
                for (k in a..b) arr[k] = kmh
            }
        }

        return Route(
            points = points,
            steps = steps,
            distance = summary.optDouble("lengthInMeters"),
            duration = total,
            summary = "",
            speedLimitsKmh = limits,
            trafficDelay = summary.optDouble("trafficDelayInSeconds", 0.0),
            source = "TomTom",
        )
    }

    private fun step(ins: JSONObject, idx: Int, points: List<LatLng>): Step {
        val maneuver = ins.optString("maneuver")
        val angle = ins.optDouble("turnAngleInDecimalDegrees", 0.0)
        val numbers = ins.optJSONArray("roadNumbers")
        val road = ins.optString("street").ifBlank { numbers?.optString(0).orEmpty() }
        val (type, modifier) = osrmLike(maneuver, angle)
        val bearing = if (idx < points.size - 1) Geo.bearing(points[idx], points[idx + 1]) else 0.0
        val known = type != "unknown"
        val text = if (known) {
            Instructions.text(
                type = type,
                modifier = modifier,
                road = road,
                exitNumber = ins.optInt("roundaboutExitNumber", 0),
                exits = ins.optString("exitNumber"),
                destinations = ins.optString("signpostText"),
                bearingAfter = bearing,
            )
        } else {
            // A maneuver this app doesn't know yet: TomTom's own sentence.
            ins.optString("message").ifBlank { if (road.isNotEmpty()) "Continue on $road" else "Continue" }
        }
        return Step(if (known) type else "other", modifier, road, text, Instructions.arrow(type, modifier), idx, 0.0)
    }

    /** TomTom maneuver code -> (OSRM type, modifier), so wording and arrows are shared. */
    internal fun osrmLike(maneuver: String, angle: Double): Pair<String, String> {
        val side = if (angle < 0) "left" else "right"
        return when (maneuver) {
            "DEPART" -> "depart" to ""
            "ARRIVE", "WAYPOINT_REACHED" -> "arrive" to ""
            "ARRIVE_LEFT", "WAYPOINT_LEFT" -> "arrive" to "left"
            "ARRIVE_RIGHT", "WAYPOINT_RIGHT" -> "arrive" to "right"
            "STRAIGHT" -> "turn" to "straight"
            "FOLLOW" -> "new name" to "straight"
            "KEEP_LEFT" -> "fork" to "slight left"
            "KEEP_RIGHT" -> "fork" to "slight right"
            "BEAR_LEFT" -> "turn" to "slight left"
            "BEAR_RIGHT" -> "turn" to "slight right"
            "TURN_LEFT" -> "turn" to "left"
            "TURN_RIGHT" -> "turn" to "right"
            "SHARP_LEFT" -> "turn" to "sharp left"
            "SHARP_RIGHT" -> "turn" to "sharp right"
            "MAKE_UTURN", "TRY_MAKE_UTURN" -> "turn" to "uturn"
            "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" -> "on ramp" to "slight $side"
            "TAKE_EXIT" -> "off ramp" to "slight $side"
            "MOTORWAY_EXIT_LEFT" -> "off ramp" to "slight left"
            "MOTORWAY_EXIT_RIGHT" -> "off ramp" to "slight right"
            "SWITCH_PARALLEL_ROAD", "SWITCH_MAIN_ROAD" ->
                "continue" to if (abs(angle) < 5) "straight" else "slight $side"
            "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> "roundabout" to side
            else -> "unknown" to ""
        }
    }
}
