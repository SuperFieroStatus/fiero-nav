package dev.mike.fieronav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import dev.mike.fieronav.ui.KeyRouter
import java.io.File
import java.util.concurrent.Executors

/**
 * Map tiles for the route overview: TomTom's dark "night" raster tiles when a TomTom key is set (and
 * allowed for map display), otherwise OpenStreetMap's standard tiles. Tiles are kept in memory (small LRU)
 * and on disk for a week, as the OSM tile policy asks; requests send an identifying User-Agent.
 */
class Tiles(context: Context, private val onLoaded: () -> Unit) {

    enum class Source(val id: String, val attribution: String) {
        TOMTOM("tomtom", "© TomTom, © OpenStreetMap"),
        OSM("osm", "© OpenStreetMap contributors"),
    }

    var source: Source = if (Keys.tomtom != null) Source.TOMTOM else Source.OSM
        private set

    private val dir = File(context.cacheDir, "tiles")
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()

    /** RGB_565 256×256 = 128 KB each; 40 tiles ≈ 5 MB (the screen shows about 4–6). */
    private val memory = LruCache<String, Bitmap>(40)

    init {
        pool.execute { prune() }
    }

    /** The tile if ready; otherwise starts loading it (once) and returns null. Main thread. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        val wx = ((x % n) + n) % n // wrap around the antimeridian
        val key = "${source.id}/$z/$wx/$y"
        memory.get(key)?.let { return it }
        if (key in pending || key in failed) return null
        pending += key
        val src = source
        pool.execute { load(src, key, z, wx, y) }
        return null
    }

    fun shutdown() = pool.shutdownNow()

    private fun load(src: Source, key: String, z: Int, x: Int, y: Int) {
        val file = File(dir, "$key.png")
        var bmp: Bitmap? = null
        try {
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
                bmp = decode(file.readBytes())
            }
            if (bmp == null) {
                val bytes = Net.getBytes(url(src, z, x, y))
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                bmp = decode(bytes)
            }
        } catch (e: Net.HttpError) {
            Log.w(KeyRouter.TAG, "tile $key: HTTP ${e.code}")
            if (src == Source.TOMTOM && (e.code == 401 || e.code == 403)) {
                // Key not allowed for map display: use OSM tiles from now on.
                main.post { if (source == Source.TOMTOM) { source = Source.OSM; failed.clear(); onLoaded() } }
            }
        } catch (e: Exception) {
            Log.w(KeyRouter.TAG, "tile $key: $e")
        }
        main.post {
            pending -= key
            if (bmp != null) memory.put(key, bmp) else failed += key
            onLoaded()
        }
    }

    /** Lets a failed tile be tried again (e.g. when the view is reopened with signal back). */
    fun retryFailed() = failed.clear()

    private fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

    private fun url(src: Source, z: Int, x: Int, y: Int): String = when (src) {
        Source.TOMTOM -> "https://api.tomtom.com/map/1/tile/basic/night/$z/$x/$y.png?key=${Keys.tomtom}&tileSize=256"
        Source.OSM -> "https://tile.openstreetmap.org/$z/$x/$y.png"
    }

    /** Drop tiles older than a month so the cache can't grow forever. */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        dir.walkBottomUp().forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }
    }

    companion object {
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
