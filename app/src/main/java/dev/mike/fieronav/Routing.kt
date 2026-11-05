package dev.mike.fieronav

import org.json.JSONObject
import java.io.IOException

enum class Arrow { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, ROUNDABOUT, ARRIVE }

/** One maneuver, as OSRM describes it, with the text we show and say for it. */
data class Step(
    val type: String,
    val modifier: String,
    val road: String,
    val text: String,
    val arrow: Arrow,
    /** Index into [Route.points] where this maneuver happens. */
    val startIndex: Int,
    /** OSRM's travel time for the stretch after this maneuver, seconds. */
    val duration: Double,
)

class Route(
    val points: List<LatLng>,
    val steps: List<Step>,
    val distance: Double,
    val duration: Double,
    /** "via" roads, e.g. "I 35, US 290". */
    val summary: String,
) {
    /** Distance along the route at each point, metres. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    fun along(step: Step): Double = cumulative[step.startIndex]
}

class RouteError(msg: String) : IOException(msg)

/** Driving routes from the public OSRM server (router.project-osrm.org): no key, no traffic. */
object Routing {
    fun routes(from: LatLng, to: LatLng, headingDeg: Double? = null, alternatives: Boolean = true): List<Route> {
        // A heading on the start point stops a reroute from telling you to turn around on a highway.
        val bearings = headingDeg?.let { "&bearings=${it.toInt()},60;" } ?: ""
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?steps=true&overview=false&geometries=polyline6&alternatives=$alternatives$bearings"
        val json = Net.getJson(url, timeoutMs = 10_000)
        val code = json.optString("code")
        if (code != "Ok") {
            throw RouteError(if (code == "NoRoute") "No driving route found" else json.optString("message", code))
        }
        val arr = json.getJSONArray("routes")
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private fun parse(r: JSONObject): Route {
        val leg = r.getJSONArray("legs").getJSONObject(0)
        val stepsJson = leg.getJSONArray("steps")
        val points = ArrayList<LatLng>()
        val steps = ArrayList<Step>()
        for (i in 0 until stepsJson.length()) {
            val s = stepsJson.getJSONObject(i)
            val geom = Geo.decodePolyline(s.getString("geometry"))
            val start = if (points.isEmpty()) 0 else points.size - 1
            // Consecutive step geometries share their joining point.
            if (points.isEmpty()) points += geom else points += geom.drop(1)
            val m = s.getJSONObject("maneuver")
            val road = roadName(s.optString("name"), s.optString("ref"))
            val type = m.optString("type")
            val modifier = m.optString("modifier")
            val text = Instructions.text(
                type = type,
                modifier = modifier,
                road = road,
                exitNumber = m.optInt("exit", 0),
                exits = s.optString("exits"),
                destinations = s.optString("destinations"),
                bearingAfter = m.optDouble("bearing_after", 0.0),
            )
            steps += Step(type, modifier, road, text, Instructions.arrow(type, modifier), start, s.optDouble("duration"))
        }
        if (points.size == 1) points += points[0] // degenerate route: keep the geometry a line
        return Route(points, steps, r.optDouble("distance"), r.optDouble("duration"), leg.optString("summary"))
    }

    /** "Main Street (FM 973)"-style data comes as name + ref; show the name, fall back to the ref. */
    internal fun roadName(name: String, ref: String): String = when {
        name.isNotBlank() -> name
        ref.isNotBlank() -> ref.substringBefore(';').trim()
        else -> ""
    }
}

/** Turns OSRM maneuvers into short English instructions. Pure functions: unit-tested. */
object Instructions {

    fun text(
        type: String,
        modifier: String,
        road: String,
        exitNumber: Int = 0,
        exits: String = "",
        destinations: String = "",
        bearingAfter: Double = 0.0,
    ): String {
        val onto = if (road.isNotEmpty()) " onto $road" else ""
        val toward = destinations.substringAfter(':', destinations).trim().takeIf { it.isNotEmpty() }
            ?.let { " toward ${it.split(',').first().trim()}" } ?: ""
        val side = when {
            modifier.contains("left") -> " on the left"
            modifier.contains("right") -> " on the right"
            else -> ""
        }
        val dir = when {
            modifier == "uturn" -> "U-turn"
            modifier.isEmpty() || modifier == "straight" -> "straight"
            else -> modifier
        }
        return when (type) {
            "depart" -> "Head ${compass(bearingAfter)}${if (road.isNotEmpty()) " on $road" else ""}"
            "arrive" -> "Arrive at your destination$side"
            "turn", "end of road" -> when {
                dir == "U-turn" -> "Make a U-turn$onto"
                dir == "straight" -> "Continue straight$onto"
                dir.startsWith("slight ") -> "Slight ${dir.removePrefix("slight ")}$onto"
                dir.startsWith("sharp ") -> "Sharp ${dir.removePrefix("sharp ")}$onto"
                else -> "Turn $dir$onto"
            }
            "new name", "continue" -> when {
                dir == "straight" -> "Continue$onto"
                dir == "U-turn" -> "Make a U-turn$onto"
                dir.startsWith("slight ") -> "Bear ${dir.removePrefix("slight ")}$onto"
                dir.startsWith("sharp ") -> "Sharp ${dir.removePrefix("sharp ")}$onto"
                else -> "Turn $dir$onto"
            }
            "merge" -> "Merge${if (dir == "straight") "" else " " + dir.removePrefix("slight ")}$onto"
            "on ramp" -> "Take the ramp$side${if (road.isNotEmpty()) " to $road" else ""}$toward"
            "off ramp" -> {
                val exit = exits.split(';').firstOrNull { it.isNotBlank() }?.let { "exit $it" } ?: "the exit"
                "Take $exit$side$toward"
            }
            "fork" -> "Keep ${if (dir.contains("left")) "left" else if (dir.contains("right")) "right" else "straight"} at the fork$toward"
            "roundabout", "rotary" ->
                if (exitNumber > 0) "At the roundabout, take the ${ordinal(exitNumber)} exit$onto" else "Enter the roundabout$onto"
            "roundabout turn" -> "At the roundabout, turn ${dir.removePrefix("slight ").removePrefix("sharp ")}$onto"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            else -> if (road.isNotEmpty()) "Continue on $road" else "Continue"
        }
    }

    fun arrow(type: String, modifier: String): Arrow = when {
        type == "arrive" -> Arrow.ARRIVE
        type == "roundabout" || type == "rotary" || type == "roundabout turn" -> Arrow.ROUNDABOUT
        modifier == "uturn" -> Arrow.UTURN
        modifier == "sharp left" -> Arrow.SHARP_LEFT
        modifier == "left" -> Arrow.LEFT
        modifier == "slight left" -> Arrow.SLIGHT_LEFT
        modifier == "sharp right" -> Arrow.SHARP_RIGHT
        modifier == "right" -> Arrow.RIGHT
        modifier == "slight right" -> Arrow.SLIGHT_RIGHT
        else -> Arrow.STRAIGHT
    }

    /** Maneuvers that don't need a spoken heads-up (the road just changes name). */
    fun isQuiet(type: String, modifier: String) =
        (type == "new name" || type == "continue" || type == "notification" || type == "use lane") &&
            (modifier.isEmpty() || modifier == "straight")

    fun compass(deg: Double): String =
        arrayOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")[
            (((deg % 360) + 360 + 22.5) / 45).toInt() % 8,
        ]

    fun ordinal(n: Int): String = when (n) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; 6 -> "sixth"
        else -> "${n}th"
    }
}
