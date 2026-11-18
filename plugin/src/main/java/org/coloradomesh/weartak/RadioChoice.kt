package org.coloradomesh.weartak

import android.content.Context

/** The radio chosen on the radio screen. One radio, used by this app alone. */
data class ChosenRadio(val address: String, val name: String)

/** Keeps the chosen radio across restarts. */
object RadioChoice {
    private const val FILE = "radio"
    private const val ADDRESS = "address"
    private const val NAME = "name"

    fun get(context: Context): ChosenRadio? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val address = prefs.getString(ADDRESS, null) ?: return null
        return ChosenRadio(address, prefs.getString(NAME, null) ?: address)
    }

    fun set(context: Context, radio: ChosenRadio) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(ADDRESS, radio.address)
            .putString(NAME, radio.name)
            .apply()
    }

    fun forget(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
