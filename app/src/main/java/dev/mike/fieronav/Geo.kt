package dev.mike.fieronav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6_371_000.0

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing a -> b in degrees, 0..360. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Point [fraction] of the way from a to b (fine for the short segments of a route). */
    fun lerp(a: LatLng, b: LatLng, fraction: Double) =
        LatLng(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)

    data class Projection(val fraction: Double, val distance: Double)

    /** Closest point to [p] on segment a-b: how far along (0..1) and how far away (m). Local planar approx. */
    fun project(p: LatLng, a: LatLng, b: LatLng): Projection {
        val kx = cos(Math.toRadians(p.lat)) * R * PI / 180
        val ky = R * PI / 180
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return Projection(t, sqrt(cx * cx + cy * cy))
    }

    /** Google encoded polyline; OSRM `geometries=polyline6` uses precision 1e6. */
    fun decodePolyline(encoded: String, precision: Double = 1e6): List<LatLng> {
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = encoded[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            out += LatLng(lat / precision, lon / precision)
        }
        return out
    }
}

/** US units: feet below 0.1 mi, then tenths of a mile (whole miles from 10 mi). */
object Units {
    private const val M_PER_MI = 1609.344
    private const val FT_PER_M = 3.28084

    fun distance(m: Double): String {
        val mi = m / M_PER_MI
        return when {
            mi < 0.1 -> "${roundFeet(m)} ft"
            mi < 10 -> "${"%.1f".format(mi)} mi"
            else -> "${mi.roundToInt()} mi"
        }
    }

    /** Spoken form: "500 feet", "a quarter mile", "1.5 miles". */
    fun spokenDistance(m: Double): String {
        val mi = m / M_PER_MI
        return when {
            mi < 0.1 -> "${roundFeet(m)} feet"
            mi < 0.35 -> "a quarter mile"
            mi < 0.65 -> "half a mile"
            mi < 0.9 -> "three quarters of a mile"
            mi < 1.15 -> "1 mile"
            mi < 10 -> "${"%.1f".format(mi).removeSuffix(".0")} miles"
            else -> "${mi.roundToInt()} miles"
        }
    }

    private fun roundFeet(m: Double): Int {
        val ft = m * FT_PER_M
        return when {
            ft < 100 -> ((ft / 10).roundToInt() * 10).coerceAtLeast(10)
            else -> (ft / 50).roundToInt() * 50
        }
    }

    fun mph(mps: Double): Int = (mps * 2.236936).roundToInt()

    /** Posted limits are whole mph signs stored as km/h; round back to the nearest 5 mph. */
    fun limitMph(kmh: Int): Int = ((kmh / 1.609344) / 5).roundToInt() * 5

    fun duration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }
}
