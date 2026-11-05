package dev.mike.fieronav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Place(val name: String, val address: String, val pos: LatLng) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("address", address).put("lat", pos.lat).put("lon", pos.lon)

    fun samePlace(other: Place) = Geo.distance(pos, other.pos) < 30

    companion object {
        fun fromJson(o: JSONObject) =
            Place(o.optString("name"), o.optString("address"), LatLng(o.getDouble("lat"), o.getDouble("lon")))
    }
}

/**
 * Place search through Photon (photon.komoot.io, OpenStreetMap data, no key), biased toward [near].
 * Searches only on OK, never per keystroke.
 */
object PlaceSearch {
    /**
     * Photon's lat/lon bias alone still ranks a same-named place abroad first ("heb" -> Germany), so
     * search a box ~170 miles around [near] first and only go worldwide if nothing matches there.
     */
    fun search(query: String, near: LatLng?): List<Place> {
        if (near != null) {
            val box = "&bbox=${near.lon - BOX_DEG},${near.lat - BOX_DEG},${near.lon + BOX_DEG},${near.lat + BOX_DEG}"
            val local = fetch(query, near, box)
            if (local.isNotEmpty()) return nearestFirstWithinName(local, near)
        }
        return fetch(query, near, "")
    }

    private const val BOX_DEG = 2.5

    /**
     * Keeps Photon's order between different names but puts the closest first among places that share
     * a name (chains: "H-E-B", "Shell"), so the top hit is the nearest branch.
     */
    fun nearestFirstWithinName(places: List<Place>, near: LatLng): List<Place> {
        val groups = LinkedHashMap<String, MutableList<Place>>()
        for (p in places) groups.getOrPut(p.name.lowercase().trim()) { ArrayList() } += p
        return groups.values.flatMap { g -> g.sortedBy { Geo.distance(near, it.pos) } }
    }

    private fun fetch(query: String, near: LatLng?, box: String): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val json = Net.getJson("https://photon.komoot.io/api/?q=$q&limit=12&lang=en$bias$box")
        val features = json.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            out += Place(name(p), address(p), LatLng(coords.getDouble(1), coords.getDouble(0)))
        }
        // Photon can return the same spot twice (e.g. a building and its address node).
        return out.fold(ArrayList()) { acc, place -> if (acc.none { it.samePlace(place) }) acc += place; acc }
    }

    internal fun name(p: JSONObject): String {
        val street = listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
        return p.optString("name").ifBlank { street.ifBlank { p.optString("city", "Unnamed place") } }
    }

    internal fun address(p: JSONObject): String {
        val street = listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
        val parts = listOf(street, p.optString("city").ifBlank { p.optString("county") }, p.optString("state"))
            .filter { it.isNotBlank() && it != name(p) }
        return parts.joinToString(", ")
    }
}

/** Home, Work, saved places and recent destinations, in the app's own prefs. */
object PlaceStore {
    private const val MAX_RECENT = 12

    private fun prefs(c: Context) = c.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun home(c: Context) = one(c, "home")
    fun work(c: Context) = one(c, "work")
    fun setHome(c: Context, p: Place?) = putOne(c, "home", p)
    fun setWork(c: Context, p: Place?) = putOne(c, "work", p)

    fun saved(c: Context) = list(c, "saved")
    fun recent(c: Context) = list(c, "recent")

    fun addSaved(c: Context, p: Place) = putList(c, "saved", listOf(p) + saved(c).filterNot { it.samePlace(p) })
    fun removeSaved(c: Context, p: Place) = putList(c, "saved", saved(c).filterNot { it.samePlace(p) })

    fun addRecent(c: Context, p: Place) =
        putList(c, "recent", (listOf(p) + recent(c).filterNot { it.samePlace(p) }).take(MAX_RECENT))
    fun removeRecent(c: Context, p: Place) = putList(c, "recent", recent(c).filterNot { it.samePlace(p) })

    private fun one(c: Context, key: String) =
        prefs(c).getString(key, null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    private fun putOne(c: Context, key: String, p: Place?) =
        prefs(c).edit().putString(key, p?.toJson()?.toString()).apply()

    private fun list(c: Context, key: String): List<Place> {
        val raw = prefs(c).getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun putList(c: Context, key: String, places: List<Place>) =
        prefs(c).edit().putString(key, JSONArray(places.map { it.toJson() }).toString()).apply()
}
