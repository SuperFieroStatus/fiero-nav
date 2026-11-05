package dev.mike.fieronav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import dev.mike.fieronav.LatLng
import dev.mike.fieronav.R
import dev.mike.fieronav.Route
import dev.mike.fieronav.Tiles
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Web Mercator in "world pixels" at zoom z (256-px tiles), the tile scheme OSM and TomTom share. */
object Mercator {
    fun size(z: Int): Double = 256.0 * (1 shl z)

    fun x(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * size(z)

    fun y(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * size(z)
    }

    fun lon(x: Double, z: Int): Double = x / size(z) * 360.0 - 180.0

    fun lat(y: Double, z: Int): Double = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * y / size(z)))))

    /** Largest zoom (≤ [maxZ]) at which all [points] fit in w×h with [pad] pixels to spare on each side. */
    fun fitZoom(points: List<LatLng>, w: Int, h: Int, pad: Int, maxZ: Int = 17, minZ: Int = 3): Int {
        if (points.isEmpty()) return maxZ
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        for (z in maxZ downTo minZ) {
            val dx = x(maxLon, z) - x(minLon, z)
            val dy = y(minLat, z) - y(maxLat, z)
            if (dx <= w - 2 * pad && dy <= h - 2 * pad) return z
        }
        return minZ
    }
}

/**
 * Keypad map: raster tiles, the route line, turn points, destination and your position. No touch:
 * the screen calls [pan], [zoom], [fitRoute] and [follow].
 */
class RouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val tiles = Tiles(context) { invalidate() }

    var zoom = 14
        private set
    private var centerLat = 0.0
    private var centerLon = 0.0

    private var route: Route? = null
    private var turns: List<LatLng> = emptyList()
    private var destination: LatLng? = null
    private var me: LatLng? = null
    private var heading: Float? = null
    /** Route point you're at; the stretch before it is drawn dimmer. */
    private var progressIndex = 0

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(7f); color = 0xFF000000.toInt()
    }
    private val routeLine = Paint(routeCasing).apply { strokeWidth = dp(4.5f); color = context.getColor(R.color.highlight) }
    private val passedLine = Paint(routeLine).apply { color = 0xFF8A5A33.toInt() }
    private val turnDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.highlight) }
    private val tileBg = Paint().apply { color = 0xFF1C1C1C.toInt() }
    private val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = dp(9f)
    }
    private val attributionBg = Paint().apply { color = 0x99000000.toInt() }
    private val arrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val arrow = Path()

    fun setRoute(r: Route?) {
        route = r
        turns = r?.steps?.filter { it.type != "depart" && it.type != "arrive" }?.map { r.points[it.startIndex] }.orEmpty()
        destination = r?.points?.lastOrNull()
        invalidate()
    }

    fun setMe(pos: LatLng?, bearing: Float?, routeIndex: Int) {
        me = pos
        heading = bearing
        progressIndex = routeIndex
        invalidate()
    }

    fun center(on: LatLng) {
        centerLat = on.lat
        centerLon = on.lon
        invalidate()
    }

    /** Zoom and center so the whole route (and you) are on screen. */
    fun fitRoute() {
        val r = route ?: return
        val pts = r.points + listOfNotNull(me)
        if (width == 0) {
            post { fitRoute() }
            return
        }
        zoom = Mercator.fitZoom(pts, width, height, dp(18f).toInt())
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val cy = (Mercator.y(minLat, zoom) + Mercator.y(maxLat, zoom)) / 2
        centerLat = Mercator.lat(cy, zoom)
        centerLon = (minLon + maxLon) / 2
        invalidate()
    }

    /** Moves the view by a fraction of its size (D-pad: a third of the screen per press). */
    fun pan(fx: Float, fy: Float) {
        val cx = Mercator.x(centerLon, zoom) + fx * width
        val cy = Mercator.y(centerLat, zoom) + fy * height
        centerLon = Mercator.lon(cx, zoom)
        centerLat = Mercator.lat(cy, zoom)
        invalidate()
    }

    fun zoom(delta: Int) {
        zoom = (zoom + delta).coerceIn(3, 18)
        invalidate()
    }

    fun retryTiles() {
        tiles.retryFailed()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tiles.shutdown()
    }

    override fun onDraw(canvas: Canvas) {
        val z = zoom
        val left = Mercator.x(centerLon, z) - width / 2.0
        val top = Mercator.y(centerLat, z) - height / 2.0

        // Tiles
        val tx0 = floor(left / 256).toInt()
        val ty0 = floor(top / 256).toInt()
        val tx1 = floor((left + width) / 256).toInt()
        val ty1 = floor((top + height) / 256).toInt()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val sx = (tx * 256 - left).toFloat()
            val sy = (ty * 256 - top).toFloat()
            val bmp = tiles.get(z, tx, ty)
            if (bmp != null) canvas.drawBitmap(bmp, sx, sy, null)
            else canvas.drawRect(sx, sy, sx + 256, sy + 256, tileBg)
        }

        // Route: passed part dim, the rest orange, both with a dark casing so they read on any tile.
        route?.let { r ->
            fun line(from: Int, to: Int, paint: Paint) {
                if (to - from < 1) return
                path.reset()
                for (i in from..to) {
                    val p = r.points[i]
                    val x = (Mercator.x(p.lon, z) - left).toFloat()
                    val y = (Mercator.y(p.lat, z) - top).toFloat()
                    if (i == from) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, routeCasing)
                canvas.drawPath(path, paint)
            }
            val split = progressIndex.coerceIn(0, r.points.size - 1)
            line(0, split, passedLine)
            line(split, r.points.size - 1, routeLine)
        }

        // Turn points (only when zoomed in enough to tell them apart)
        if (z >= 12) for (t in turns) {
            val x = (Mercator.x(t.lon, z) - left).toFloat()
            val y = (Mercator.y(t.lat, z) - top).toFloat()
            canvas.drawCircle(x, y, dp(3.5f), dark)
            canvas.drawCircle(x, y, dp(2.5f), turnDot)
        }

        // Destination
        destination?.let { d ->
            val x = (Mercator.x(d.lon, z) - left).toFloat()
            val y = (Mercator.y(d.lat, z) - top).toFloat()
            canvas.drawCircle(x, y, dp(7f), turnDot)
            canvas.drawCircle(x, y, dp(5f), dark)
            canvas.drawCircle(x, y, dp(3.5f), accent)
        }

        // You: an arrow along your heading, or a dot without one.
        me?.let { m ->
            val x = (Mercator.x(m.lon, z) - left).toFloat()
            val y = (Mercator.y(m.lat, z) - top).toFloat()
            val h = heading
            if (h == null) {
                canvas.drawCircle(x, y, dp(7f), dark)
                canvas.drawCircle(x, y, dp(5.5f), turnDot)
            } else {
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(h)
                val s = dp(9f)
                arrow.reset()
                arrow.moveTo(0f, -s); arrow.lineTo(s * 0.75f, s * 0.8f); arrow.lineTo(0f, s * 0.35f); arrow.lineTo(-s * 0.75f, s * 0.8f); arrow.close()
                canvas.drawPath(arrow, arrowOutline)
                canvas.drawPath(arrow, turnDot)
                canvas.restore()
            }
        }

        // Attribution (required by both tile providers)
        val text = tiles.source.attribution
        val tw = attribution.measureText(text)
        val pad = dp(2f)
        canvas.drawRect(width - tw - 3 * pad, height - attribution.textSize - 2 * pad, width.toFloat(), height.toFloat(), attributionBg)
        canvas.drawText(text, width - tw - pad * 1.5f, height - pad * 1.6f, attribution)
    }
}
