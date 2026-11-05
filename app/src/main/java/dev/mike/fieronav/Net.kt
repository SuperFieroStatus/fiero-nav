package dev.mike.fieronav

import android.util.Log
import dev.mike.fieronav.ui.KeyRouter
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Plain HttpURLConnection + org.json: no dependencies. Call off the UI thread. */
object Net {
    /** Photon, OSRM and the OSM tile servers ask clients to identify themselves. */
    const val USER_AGENT = "FieroNav/0.4 (keypad navigation; https://github.com/SuperFieroStatus/fiero-nav)"

    val io: ExecutorService = Executors.newFixedThreadPool(2)

    class HttpError(val code: Int, msg: String) : IOException(msg)

    /** Raw GET (map tiles). Throws [HttpError] on non-2xx so callers can tell "forbidden" from "offline". */
    fun getBytes(url: String, timeoutMs: Int = 8000): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun getJson(url: String, timeoutMs: Int = 8000): JSONObject {
        var lastError: IOException? = null
        // One retry: weak signal and stale keep-alive sockets are common on this phone (FlipSpot M4).
        repeat(2) { attempt ->
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (attempt > 0) conn.setRequestProperty("Connection", "close")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    // OSRM reports "NoRoute" etc. as JSON with a 400; let the caller read it.
                    if (body.startsWith("{")) return JSONObject(body)
                    throw HttpError(code, "HTTP $code")
                }
                return JSONObject(body)
            } catch (e: HttpError) {
                throw e
            } catch (e: IOException) {
                Log.w(KeyRouter.TAG, "GET failed (attempt ${attempt + 1}): ${url.substringBefore('?')} $e")
                lastError = e
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: IOException("request failed")
    }
}
