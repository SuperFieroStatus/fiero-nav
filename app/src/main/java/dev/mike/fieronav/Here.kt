package dev.mike.fieronav

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import dev.mike.fieronav.ui.say

/**
 * Keeps a fresh GPS fix while the Where to? / Search / preview screens are open, so search is biased
 * to where you are and the route starts from the right place. Ref-counted by screens.
 */
object Here {
    private var users = 0
    private var lm: LocationManager? = null

    var fix: LatLng? = null
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            fix = LatLng(location.latitude, location.longitude)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Best guess right now: live fix, else the system's last known location. */
    fun best(context: Context): LatLng? = fix ?: Nav.lastKnown(context)

    @SuppressLint("MissingPermission")
    fun acquire(context: Context) {
        if (users++ > 0) return
        val m = context.applicationContext.getSystemService(LocationManager::class.java)
        lm = m
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 10f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // No permission yet; MainActivity asks for it.
        } catch (e: IllegalArgumentException) {
        }
    }

    fun release() {
        if (users == 0 || --users > 0) return
        lm?.removeUpdates(listener)
    }
}

/** Left-soft-key menu for a place, shared by every list that shows places. */
object PlaceOptions {
    fun show(activity: Activity, place: Place, extra: List<Pair<String, () -> Unit>> = emptyList(), onChanged: () -> Unit = {}) {
        val c = activity
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Set as Home" to { PlaceStore.setHome(c, place); c.say("Home set"); onChanged() }
        actions += "Set as Work" to { PlaceStore.setWork(c, place); c.say("Work set"); onChanged() }
        if (PlaceStore.saved(c).none { it.samePlace(place) }) {
            actions += "Save place" to { PlaceStore.addSaved(c, place); c.say("Saved"); onChanged() }
        }
        actions += extra
        AlertDialog.Builder(activity)
            .setTitle(place.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }
}
