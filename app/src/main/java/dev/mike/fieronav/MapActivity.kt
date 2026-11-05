package dev.mike.fieronav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import dev.mike.fieronav.ui.KeyAction
import dev.mike.fieronav.ui.KeyActionHandler
import dev.mike.fieronav.ui.KeyRouter
import dev.mike.fieronav.ui.RoutedKey
import dev.mike.fieronav.ui.RouteMapView

/**
 * Route overview: the whole route on a map, secondary to the guidance screen.
 * D-pad pans, 1 / 3 zoom out / in, OK switches between "follow me" and "whole route",
 * left soft key = whole route, Back (or right soft key) returns.
 *
 * Shows the guided route, or (from the route preview) a route that hasn't started yet.
 */
class MapActivity : Activity(), KeyActionHandler {

    private lateinit var map: RouteMapView
    private lateinit var title: TextView
    private lateinit var softCenter: TextView
    private var preview: Route? = null
    private var following = false
    private var fitted = false
    private var shownRoute: Route? = null

    private val listener: (Nav.Snapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        Nav.init(this)
        map = findViewById(R.id.map)
        title = findViewById(R.id.title)
        softCenter = findViewById(R.id.soft_center)
        findViewById<TextView>(R.id.soft_left).text = "Whole route"
        findViewById<TextView>(R.id.soft_right).text = "Back"

        preview = if (intent.getBooleanExtra(EXTRA_PREVIEW, false)) pendingPreview else null
        val route = preview ?: Nav.currentRoute
        if (route == null) {
            finish()
            return
        }
        map.setRoute(route)
        shownRoute = route
        if (preview != null) {
            Here.best(this)?.let { map.setMe(it, null, 0) }
            title.text = "1 out · 3 in · D-pad moves the map"
        }
        map.fitRoute()
        fitted = true
        updateCenterLabel()
    }

    override fun onResume() {
        super.onResume()
        map.retryTiles()
        if (preview == null) Nav.addListener(listener)
    }

    override fun onPause() {
        super.onPause()
        Nav.removeListener(listener)
    }

    private fun render(s: Nav.Snapshot) {
        when (s.status) {
            Nav.Status.IDLE -> { finish(); return }
            Nav.Status.GUIDING -> {
                // A reroute replaces the route: redraw it.
                Nav.currentRoute?.takeIf { it !== shownRoute }?.let { r -> shownRoute = r; map.setRoute(r) }
                title.text = s.next?.let { "${Units.distance(s.distToNext)} · ${it.text}" } ?: ""
            }
            Nav.Status.ARRIVED -> title.text = "Arrived"
            else -> title.text = s.message
        }
        map.setMe(s.position, s.bearing, s.routeIndex)
        if (following) s.position?.let { map.center(it) }
    }

    private fun updateCenterLabel() {
        softCenter.text = if (following) "Route" else "Me"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.dispatch(event, SCREEN, this) || super.dispatchKeyEvent(event)

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val third = 1f / 3
        when (key.action) {
            KeyAction.UP -> { stopFollowing(); map.pan(0f, -third) }
            KeyAction.DOWN -> { stopFollowing(); map.pan(0f, third) }
            KeyAction.LEFT -> { stopFollowing(); map.pan(-third, 0f) }
            KeyAction.RIGHT -> { stopFollowing(); map.pan(third, 0f) }
            KeyAction.DIGIT -> if (event.repeatCount == 0) when (key.digit) {
                1 -> map.zoom(-1)
                3 -> map.zoom(+1)
                else -> Unit // other digits swallowed (unhandled digits open the dialer)
            }
            KeyAction.OK -> if (event.repeatCount == 0) {
                if (following) {
                    following = false
                    map.fitRoute()
                } else {
                    val me = Nav.snapshot.position ?: Here.best(this)
                    if (me != null) {
                        following = preview == null // a preview has no live position to follow
                        if (map.zoom < 15) map.zoom(15 - map.zoom)
                        map.center(me)
                    }
                }
                updateCenterLabel()
            }
            KeyAction.SOFT_LEFT -> if (event.repeatCount == 0) {
                following = false
                map.fitRoute()
                updateCenterLabel()
            }
            KeyAction.SOFT_RIGHT -> if (event.repeatCount == 0) finish()
            KeyAction.POUND, KeyAction.STAR -> Unit
            KeyAction.BACK -> return false
            else -> return false
        }
        return true
    }

    private fun stopFollowing() {
        if (following) {
            following = false
            updateCenterLabel()
        }
    }

    companion object {
        private const val SCREEN = "Map"
        private const val EXTRA_PREVIEW = "preview"

        /** The route preview's selected route (not started, so Nav doesn't have it). */
        private var pendingPreview: Route? = null

        fun showGuided(context: Context) = context.startActivity(Intent(context, MapActivity::class.java))

        fun showPreview(context: Context, route: Route) {
            pendingPreview = route
            context.startActivity(Intent(context, MapActivity::class.java).putExtra(EXTRA_PREVIEW, true))
        }
    }
}
