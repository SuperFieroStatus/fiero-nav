package dev.mike.fieronav

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import dev.mike.fieronav.ui.KeyAction
import dev.mike.fieronav.ui.KeyActionHandler
import dev.mike.fieronav.ui.KeyRouter
import dev.mike.fieronav.ui.ListItem
import dev.mike.fieronav.ui.ListScreen
import dev.mike.fieronav.ui.MultiTap
import dev.mike.fieronav.ui.RoutedKey
import dev.mike.fieronav.ui.say

/**
 * "Where to?": resume, search, Home, Work, saved places, recent destinations — and search itself.
 *
 * Search lives in this same window on purpose: on the Flip 2 the system sends any digit that arrives
 * while no window has focus (i.e. during an activity switch) to the dialer, so typing fast right after
 * opening a separate search screen popped up the dialer.
 */
class MainActivity : Activity(), KeyActionHandler {

    private sealed class Row(val item: ListItem) {
        class Resume(item: ListItem) : Row(item)
        class Search(item: ListItem) : Row(item)
        class Slot(item: ListItem, val place: Place?, val which: String) : Row(item)
        class Saved(item: ListItem, val place: Place) : Row(item)
        class Recent(item: ListItem, val place: Place) : Row(item)
        class About(item: ListItem) : Row(item)
    }

    private lateinit var list: ListScreen
    private lateinit var title: TextView
    private lateinit var entry: TextView
    private lateinit var modeBadge: TextView
    private lateinit var softLeft: TextView
    private lateinit var softCenter: TextView
    private lateinit var softRight: TextView
    private var rows: List<Row> = emptyList()
    private var homeSelection = 0

    // ---- search state ----
    private var searching = false
    private var results: List<Place> = emptyList()
    private var searched = ""
    private var busy = false
    private val literal = StringBuilder() // debug `--es query` text, bypassing multi-tap
    private val multiTap = MultiTap { text, pending -> renderEntry(text, pending) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        Nav.init(this)
        list = findViewById(R.id.list)
        title = findViewById(R.id.title)
        entry = findViewById(R.id.info)
        modeBadge = findViewById(R.id.mode)
        entry.setBackgroundColor(getColor(R.color.divider))
        softLeft = findViewById(R.id.soft_left)
        softCenter = findViewById(R.id.soft_center)
        softRight = findViewById(R.id.soft_right)
        list.requestFocus()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
        Keys.fromIntent(this, intent)?.let { say(it) }
        handleDebugQuery(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Keys.fromIntent(this, intent)?.let { say(it) }
        handleDebugQuery(intent)
    }

    /** Debug: `am start -n dev.mike.fieronav/.MainActivity --es query "coffee"`. */
    private fun handleDebugQuery(intent: Intent) {
        val q = intent.getStringExtra(EXTRA_QUERY) ?: return
        intent.removeExtra(EXTRA_QUERY)
        enterSearch()
        multiTap.clear()
        literal.setLength(0)
        literal.append(q)
        renderEntry(q, null)
        search()
    }

    override fun onResume() {
        super.onResume()
        Here.acquire(this)
        if (searching) renderSearchChrome() else showHome()
    }

    override fun onPause() {
        super.onPause()
        Here.release()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Here.release()
            Here.acquire(this)
        } else {
            say("Location is needed to navigate")
        }
    }

    // ---- Where to? ----

    private fun showHome() {
        searching = false
        entry.visibility = View.GONE
        modeBadge.visibility = View.GONE
        title.text = "Where to?"
        softLeft.text = "Options"
        softCenter.text = "Go"
        softRight.text = if (Nav.active) "Guide" else ""
        list.setEmptyText("")
        val here = Here.best(this)
        fun detail(p: Place): String {
            val d = here?.let { " · ${Units.distance(Geo.distance(it, p.pos))}" } ?: ""
            return p.address.ifEmpty { p.name } + d
        }
        val out = ArrayList<Row>()
        Nav.snapshot.destination?.takeIf { Nav.active }?.let {
            out += Row.Resume(ListItem("▶ Resume: ${it.name}", "Back to guidance"))
        }
        out += Row.Search(ListItem("Search", "Or just start typing"))
        PlaceStore.home(this).let {
            out += Row.Slot(ListItem(if (it != null) "Home" else "Home (not set)", it?.let(::detail) ?: "Options > Set as Home on any place", dim = it == null), it, "home")
        }
        PlaceStore.work(this).let {
            out += Row.Slot(ListItem(if (it != null) "Work" else "Work (not set)", it?.let(::detail) ?: "Options > Set as Work on any place", dim = it == null), it, "work")
        }
        PlaceStore.saved(this).forEach { out += Row.Saved(ListItem("★ ${it.name}", detail(it)), it) }
        PlaceStore.recent(this).forEach { out += Row.Recent(ListItem(it.name, "Recent · ${detail(it)}"), it) }
        out += Row.About(ListItem("About", "Map data © OpenStreetMap contributors", dim = true))
        rows = out
        list.setListItems(out.map { it.item }, homeSelection)
    }

    private fun open(row: Row?) {
        when (row) {
            is Row.Resume -> startActivity(Intent(this, GuideActivity::class.java))
            is Row.Search -> enterSearch()
            is Row.Slot -> row.place?.let { DestinationActivity.open(this, it) }
                ?: say("Pick a place, then Options > Set as ${row.which.replaceFirstChar { it.uppercase() }}")
            is Row.Saved -> DestinationActivity.open(this, row.place)
            is Row.Recent -> DestinationActivity.open(this, row.place)
            is Row.About -> about()
            null -> Unit
        }
    }

    /** Credits required by the data licenses (OpenStreetMap is ODbL: attribution must be visible). */
    private fun about() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        android.app.AlertDialog.Builder(this)
            .setTitle("Fiero Nav $version")
            .setMessage(
                "Keypad-first driving navigation.\n\n" +
                    "Map data © OpenStreetMap contributors (ODbL).\n" +
                    "Search: Photon by Komoot.\n" +
                    "Routes: " + (if (Keys.tomtom != null) "TomTom (live traffic), OSRM as fallback" else "OSRM (project-osrm.org)") + ".\n\n" +
                    (if (Keys.tomtom != null) "Traffic and speed limits © TomTom." else "No live traffic: times assume free-flowing roads."),
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun homeOptions(row: Row?) {
        val refresh = { showHome() }
        when (row) {
            is Row.Slot -> row.place?.let { p ->
                PlaceOptions.show(this, p, listOf("Clear ${row.which}" to {
                    if (row.which == "home") PlaceStore.setHome(this, null) else PlaceStore.setWork(this, null)
                    refresh()
                }), refresh)
            } ?: say("Not set yet")
            is Row.Saved -> PlaceOptions.show(this, row.place, listOf("Remove from saved" to { PlaceStore.removeSaved(this, row.place); refresh() }), refresh)
            is Row.Recent -> PlaceOptions.show(this, row.place, listOf("Remove from recent" to { PlaceStore.removeRecent(this, row.place); refresh() }), refresh)
            else -> say("No options here")
        }
    }

    // ---- search ----

    private fun enterSearch() {
        if (!searching) homeSelection = list.selectedIndex
        searching = true
        results = emptyList()
        searched = ""
        renderSearchChrome()
        list.setEmptyText("Type a place or address, then OK")
        list.setListItems(emptyList())
        renderEntry(multiTap.text, null)
    }

    /** Search title; the input mode (`#` changes it) shows in the badge on the entry line. */
    private var titleBase = "Search"

    private fun setSearchTitle(base: String) {
        titleBase = base
        if (searching) {
            title.text = base
            modeBadge.text = multiTap.mode.label
        }
    }

    private fun renderSearchChrome() {
        entry.visibility = View.VISIBLE
        modeBadge.visibility = View.VISIBLE
        // Keep typed text clear of the badge.
        entry.setPadding(entry.paddingLeft, entry.paddingTop, (52 * resources.displayMetrics.density).toInt(), entry.paddingBottom)
        setSearchTitle(if (results.isEmpty()) "Search" else "Results (${results.size})")
        softLeft.text = "Options"
        softCenter.text = "Search"
        softRight.text = "Clear"
    }

    private fun leaveSearch() {
        multiTap.clear()
        literal.setLength(0)
        showHome()
    }

    private fun query() = (if (literal.isNotEmpty()) literal.toString() else multiTap.text).trim()

    private fun renderEntry(text: String, pending: Char?) {
        // MultiTap reports the committed text and the still-cycling letter separately.
        val sb = SpannableStringBuilder(text)
        if (pending != null) {
            // The letter still cycling is shown highlighted at the end.
            val start = sb.length
            sb.append(pending)
            sb.setSpan(BackgroundColorSpan(getColor(R.color.highlight)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(getColor(R.color.highlight_text)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append("▏")
        }
        entry.text = sb
        if (searching) setSearchTitle(titleBase) // the mode may have changed
    }

    private fun search() {
        val q = query()
        if (q.length < 2) return say("Type at least 2 letters")
        if (busy) return
        busy = true
        searched = q
        setSearchTitle("Searching…")
        val near = Here.best(this)
        Net.io.execute {
            val res = runCatching { PlaceSearch.search(q, near) }
            runOnUiThread {
                busy = false
                if (isDestroyed || !searching) return@runOnUiThread
                res.onSuccess { places ->
                    results = places
                    setSearchTitle(if (places.isEmpty()) "No results" else "Results (${places.size})")
                    list.setEmptyText("Nothing found for “$q”")
                    list.setListItems(
                        places.map { p ->
                            // Distance in the row itself: chain stores all have the same name.
                            val d = near?.let { " · ${Units.distance(Geo.distance(it, p.pos))}" } ?: ""
                            ListItem(p.name + d, p.address)
                        },
                    )
                }.onFailure {
                    setSearchTitle("Search")
                    list.setEmptyText("Can't reach search (no data?)")
                    list.setListItems(emptyList())
                }
            }
        }
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyRouter.dispatch(event, SCREEN, this) || super.dispatchKeyEvent(event)

    override fun onKeyAction(key: RoutedKey, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (key.action) {
            KeyAction.UP -> list.move(-1)
            KeyAction.DOWN -> list.move(+1)
            KeyAction.LEFT -> list.page(-1)
            KeyAction.RIGHT -> list.page(+1)
            // Typing anywhere searches; digits never leave this window (see class comment).
            KeyAction.DIGIT -> if (!repeat) {
                if (!searching) enterSearch()
                literal.setLength(0)
                multiTap.press(key.digit)
            } else if (event.isLongPress && searching) {
                multiTap.longPress(key.digit) // hold a key: type its digit in any mode
            }
            KeyAction.OK -> if (!repeat) {
                if (!searching) open(rows.getOrNull(list.selectedIndex))
                else if (query() != searched || results.isEmpty()) search()
                else results.getOrNull(list.selectedIndex)?.let { DestinationActivity.open(this, it) }
            }
            KeyAction.BACK -> if (searching) {
                when {
                    literal.isNotEmpty() -> { literal.setLength(0); renderEntry("", null) }
                    multiTap.backspace() -> Unit
                    !repeat -> leaveSearch()
                }
            } else {
                return false
            }
            KeyAction.SOFT_LEFT -> if (!repeat) {
                if (searching) results.getOrNull(list.selectedIndex)?.let { PlaceOptions.show(this, it) }
                else homeOptions(rows.getOrNull(list.selectedIndex))
            }
            KeyAction.SOFT_RIGHT -> if (!repeat) {
                if (searching) { literal.setLength(0); multiTap.clear() }
                else if (Nav.active) startActivity(Intent(this, GuideActivity::class.java))
            }
            // # changes the input mode (Abc / abc / ABC / 123), or opens search from the list.
            KeyAction.POUND -> if (!repeat) {
                if (!searching) enterSearch() else {
                    multiTap.nextMode()
                    say(MODE_NAMES.getValue(multiTap.mode))
                }
            }
            // * is consumed too, so it can't reach the system dialer.
            KeyAction.STAR -> Unit
            else -> return false
        }
        return true
    }

    companion object {
        private const val SCREEN = "WhereTo"
        const val EXTRA_QUERY = "query"

        private val MODE_NAMES = mapOf(
            MultiTap.Mode.SENTENCE to "Abc: capital first letter",
            MultiTap.Mode.LOWER to "abc: lower case",
            MultiTap.Mode.UPPER to "ABC: capitals",
            MultiTap.Mode.DIGITS to "123: numbers",
        )
    }
}
