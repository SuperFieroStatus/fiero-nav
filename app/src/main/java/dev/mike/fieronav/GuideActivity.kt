package dev.mike.fieronav

import android.app.Activity
import android.app.AlertDialog
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
import dev.mike.fieronav.ui.TurnArrowView
import dev.mike.fieronav.ui.say
import java.text.DateFormat
import java.util.Date

/**
 * Turn-by-turn screen. OK repeats the instruction, * mutes, Down shows upcoming turns, right soft
 * stops. Back leaves the screen; guidance carries on (notification, voice).
 */
class GuideActivity : Activity(), KeyActionHandler {

    private lateinit var eta: TextView
    private lateinit var arrow: TurnArrowView
    private lateinit var dist: TextView
    private lateinit var instruction: TextView
    private lateinit var then: TextView
    private lateinit var road: TextView
    private lateinit var guide: View
    private lateinit var steps: ListScreen
    private lateinit var softCenter: TextView
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private var showingSteps = false

    private val listener: (Nav.Snapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        Nav.init(this)
        eta = findViewById(R.id.eta)
        arrow = findViewById(R.id.arrow)
        dist = findViewById(R.id.dist)
        instruction = findViewById(R.id.instruction)
        then = findViewById(R.id.then)
        road = findViewById(R.id.road)
        guide = findViewById(R.id.guide)
        steps = findViewById(R.id.steps)
        softCenter = findViewById(R.id.soft_center)
        findViewById<TextView>(R.id.soft_left).text = "Options"
        findViewById<TextView>(R.id.soft_right).text = "Stop"
        steps.requestFocus()
        startFromDebugExtras()
    }

    /** Debug: `am start -n dev.mike.fieronav/.GuideActivity --es name X --ef lat .. --ef lon .. --ez simulate true` */
    private fun startFromDebugExtras() {
        if (!intent.hasExtra("lat")) return
        val p = Place(
            intent.getStringExtra("name") ?: "Test destination", "",
            LatLng(intent.getFloatExtra("lat", 0f).toDouble(), intent.getFloatExtra("lon", 0f).toDouble()),
        )
        Nav.simSpeed = intent.getFloatExtra("sim_speed", 17f).toDouble()
        Nav.simDetour = intent.getBooleanExtra("sim_detour", false)
        // Optional start point (`--ef from_lat .. --ef from_lon ..`), for demos away from your real location.
        val origin = if (intent.hasExtra("from_lat")) {
            LatLng(intent.getFloatExtra("from_lat", 0f).toDouble(), intent.getFloatExtra("from_lon", 0f).toDouble())
        } else {
            null
        }
        Nav.start(this, p, null, simulated = intent.getBooleanExtra("simulate", false), origin = origin)
    }

    override fun onResume() {
        super.onResume()
        Nav.addListener(listener)
    }

    override fun onPause() {
        super.onPause()
        Nav.removeListener(listener)
    }

    private fun render(s: Nav.Snapshot) {
        softCenter.text = if (s.muted) "Muted" else "Repeat"
        when (s.status) {
            Nav.Status.IDLE -> {
                finish()
                return
            }
            Nav.Status.ROUTING, Nav.Status.ERROR -> {
                eta.text = s.destination?.name.orEmpty()
                arrow.arrow = null
                dist.text = ""
                instruction.text = s.message
                then.text = ""
                road.text = ""
            }
            Nav.Status.ARRIVED -> {
                eta.text = "Arrived · ${timeFormat.format(Date())}"
                arrow.arrow = Arrow.ARRIVE
                dist.text = ""
                instruction.text = s.destination?.name.orEmpty()
                then.text = ""
                road.text = s.destination?.address.orEmpty()
            }
            Nav.Status.GUIDING -> {
                eta.text = "${timeFormat.format(Date(s.etaMillis))} · ${Units.duration(s.remainingTime)} · " +
                    Units.distance(s.remainingDist) + if (s.simulating) " · SIM" else ""
                arrow.arrow = s.next?.arrow
                dist.text = Units.distance(s.distToNext)
                instruction.text = s.next?.text.orEmpty()
                then.text = s.then?.let { "Then: ${it.text}" }.orEmpty()
                road.text = when {
                    !s.gpsOk && !s.simulating -> "Weak GPS signal"
                    s.currentRoad.isNotEmpty() -> "On ${s.currentRoad}"
                    else -> ""
                }
                if (showingSteps) fillSteps()
            }
        }
    }

    private fun fillSteps() {
        val items = Nav.upcoming().map { ListItem("${Units.distance(it.distance)} · ${it.step.text}", it.step.text) }
        steps.setListItems(items, steps.selectedIndex)
    }

    private fun showSteps(show: Boolean) {
        showingSteps = show
        guide.visibility = if (show) View.GONE else View.VISIBLE
        steps.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            steps.setEmptyText("No turns left")
            steps.setListItems(emptyList())
            fillSteps()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.dispatch(event, SCREEN, this) || super.dispatchKeyEvent(event)

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (key.action) {
            KeyAction.DOWN -> if (showingSteps) steps.move(+1) else showSteps(true)
            KeyAction.UP -> if (showingSteps) {
                if (steps.selectedIndex == 0) showSteps(false) else steps.move(-1)
            }
            KeyAction.OK -> if (!repeat) Nav.repeat()
            KeyAction.STAR -> if (!repeat) say(if (Nav.toggleMute()) "Voice off" else "Voice on")
            KeyAction.SOFT_RIGHT -> if (!repeat) confirmStop()
            KeyAction.SOFT_LEFT -> if (!repeat) options()
            KeyAction.BACK -> if (showingSteps) showSteps(false) else return false
            // Swallow stray digits: unhandled ones open the system dialer on this phone.
            KeyAction.DIGIT, KeyAction.POUND -> Unit
            else -> return false
        }
        return true
    }

    private fun options() {
        val actions = listOf<Pair<String, () -> Unit>>(
            (if (Nav.snapshot.muted) "Voice on" else "Voice off") to { Nav.toggleMute() },
            "Upcoming turns" to { showSteps(true) },
            "Reroute now" to { Nav.reroute() },
            "Stop navigation" to { Nav.stop() },
        )
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun confirmStop() {
        if (!Nav.active) return Nav.stop()
        AlertDialog.Builder(this)
            .setTitle("Stop navigation?")
            .setPositiveButton("Stop") { _, _ -> Nav.stop() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    companion object {
        private const val SCREEN = "Guide"
    }
}
