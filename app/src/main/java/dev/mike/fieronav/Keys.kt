package dev.mike.fieronav

import android.content.Context
import android.content.Intent

/**
 * Optional API keys, stored in the app's own prefs on the phone: never in the APK or the repo.
 * Set from a computer (scripts/deploy.ps1 does this from local.properties):
 *   adb shell am start -n dev.mike.fieronav/.MainActivity --es tomtom_key YOUR_KEY
 * An empty value removes the key.
 */
object Keys {
    private const val EXTRA_TOMTOM = "tomtom_key"

    @Volatile
    var tomtom: String? = null
        private set

    fun load(context: Context) {
        tomtom = prefs(context).getString("tomtom", null)?.takeIf { it.isNotBlank() }
    }

    /** Applies a key passed as an intent extra; returns a message for the user, or null if none was passed. */
    fun fromIntent(context: Context, intent: Intent): String? {
        if (!intent.hasExtra(EXTRA_TOMTOM)) return null
        val v = intent.getStringExtra(EXTRA_TOMTOM).orEmpty().trim()
        intent.removeExtra(EXTRA_TOMTOM)
        prefs(context).edit().putString("tomtom", v.ifEmpty { null }).apply()
        tomtom = v.ifEmpty { null }
        return if (v.isEmpty()) "TomTom key removed: routes without traffic" else "TomTom key saved: live traffic on"
    }

    private fun prefs(c: Context) = c.getSharedPreferences("keys", Context.MODE_PRIVATE)
}
