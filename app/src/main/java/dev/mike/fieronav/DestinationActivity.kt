package dev.mike.fieronav

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import dev.mike.fieronav.ui.KeyAction
import dev.mike.fieronav.ui.KeyActionHandler
import dev.mike.fieronav.ui.KeyRouter
import dev.mike.fieronav.ui.ListItem
import dev.mike.fieronav.ui.ListScreen
import dev.mike.fieronav.ui.RoutedKey
import dev.mike.fieronav.ui.say
import java.text.DateFormat
import java.util.Date

/** Route preview: the destination, its driving routes (time · distance · via), Start. */
class DestinationActivity : Activity(), KeyActionHandler {

    private lateinit var place: Place
    private lateinit var list: ListScreen
    private lateinit var title: TextView
    private var routes: List<Route> = emptyList()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        place = Place(
            intent.getStringExtra(EXTRA_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_ADDRESS).orEmpty(),
            LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0)),
        )
        list = findViewById(R.id.list)
        title = findViewById(R.id.title)
        title.text = place.name
        findViewById<TextView>(R.id.info).apply {
            visibility = if (place.address.isNotEmpty()) View.VISIBLE else View.GONE
            text = place.address
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.text_dim))
            textSize = 14f
        }
        findViewById<TextView>(R.id.soft_left).text = "Options"
        findViewById<TextView>(R.id.soft_center).text = "Start"
        findViewById<TextView>(R.id.soft_right).text = "Start"
        list.requestFocus()
        load()
    }

    override fun onResume() {
        super.onResume()
        Here.acquire(this)
    }

    override fun onPause() {
        super.onPause()
        Here.release()
    }

    private fun load() {
        val from = Here.best(this)
        if (from == null) {
            list.setEmptyText("Waiting for GPS… (OK to retry)")
            list.setListItems(emptyList())
            return
        }
        loading = true
        list.setEmptyText("Finding routes…")
        list.setListItems(emptyList())
        Net.io.execute {
            val res = runCatching { Routing.routes(from, place.pos) }
            runOnUiThread {
                loading = false
                if (isDestroyed) return@runOnUiThread
                res.onSuccess { found ->
                    routes = found
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
                    val items = found.mapIndexed { i, r ->
                        val arrive = time.format(Date(System.currentTimeMillis() + (r.duration * 1000).toLong()))
                        val traffic = if (r.trafficDelay >= 60) " · +${Units.duration(r.trafficDelay)} traffic" else ""
                        ListItem(
                            "${Units.duration(r.duration)} · ${Units.distance(r.distance)} · $arrive${if (i == 0) "" else " (alt)"}",
                            "via ${r.summary.ifEmpty { "local roads" }}$traffic",
                        )
                    } + ListItem("Open in OsmAnd", "Offline navigation", dim = true)
                    list.setListItems(items)
                }.onFailure {
                    list.setEmptyText((it.message ?: "No route") + " (OK to retry)")
                    list.setListItems(listOf(ListItem("Open in OsmAnd", "Offline navigation", dim = true)))
                }
            }
        }
    }

    private fun start(index: Int) {
        if (index == routes.size) return openInOsmAnd() // last row
        val route = routes.getOrNull(index) ?: return
        Nav.start(this, place, route, simulated = intent.getBooleanExtra(EXTRA_SIMULATE, false))
        startActivity(Intent(this, GuideActivity::class.java))
        finish()
    }

    /** OsmAnd's URL API starts its own navigation straight away (uses its downloaded maps). */
    private fun openInOsmAnd() {
        val uri = Uri.parse(
            "osmand.api://navigate?dest_lat=${place.pos.lat}&dest_lon=${place.pos.lon}" +
                "&dest_name=${Uri.encode(place.name)}&profile=car&force=true",
        )
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("net.osmand.plus"))
            PlaceStore.addRecent(this, place)
        } catch (e: ActivityNotFoundException) {
            say("OsmAnd isn't installed")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.dispatch(event, SCREEN, this) || super.dispatchKeyEvent(event)

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (key.action) {
            KeyAction.UP -> list.move(-1)
            KeyAction.DOWN -> list.move(+1)
            KeyAction.OK, KeyAction.SOFT_RIGHT -> if (!repeat) {
                if (routes.isEmpty() && !loading && list.size == 0) load()
                else if (!loading) start(list.selectedIndex)
            }
            KeyAction.SOFT_LEFT -> if (!repeat) PlaceOptions.show(this, place)
            // Swallow stray digits: unhandled ones open the system dialer on this phone.
            KeyAction.DIGIT, KeyAction.POUND -> Unit
            else -> return false
        }
        return true
    }

    companion object {
        private const val SCREEN = "Destination"
        const val EXTRA_NAME = "name"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_SIMULATE = "simulate"

        fun open(context: Context, p: Place) {
            context.startActivity(
                Intent(context, DestinationActivity::class.java)
                    .putExtra(EXTRA_NAME, p.name)
                    .putExtra(EXTRA_ADDRESS, p.address)
                    .putExtra(EXTRA_LAT, p.pos.lat)
                    .putExtra(EXTRA_LON, p.pos.lon),
            )
        }
    }
}
