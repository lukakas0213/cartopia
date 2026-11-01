package com.cartopia.app.data

import android.content.Context
import com.cartopia.app.geo.GeoPoint
import org.json.JSONArray

/**
 * Everything the player keeps between drives, stored on the device only.
 * Driven routes never leave the car; the 3D village reads 마일 through the WebView bridge.
 */
class VillageStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("cartopia", Context.MODE_PRIVATE)

    val isSeeded: Boolean get() = prefs.getBoolean(KEY_SEEDED, false)

    var miles: Int
        get() = prefs.getInt(KEY_MILES, 0)
        set(value) = prefs.edit().putInt(KEY_MILES, value).apply()

    var revealedBlocks: Set<Int>
        get() = prefs.getStringSet(KEY_BLOCKS, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKS, value.map { it.toString() }.toSet()).apply()

    var routes: List<List<GeoPoint>>
        get() {
            val raw = prefs.getString(KEY_ROUTES, null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { r ->
                val pts = arr.getJSONArray(r)
                (0 until pts.length()).map { i ->
                    val p = pts.getJSONArray(i)
                    GeoPoint(p.getDouble(0), p.getDouble(1))
                }
            }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { r -> arr.put(JSONArray().apply { r.forEach { put(JSONArray().put(it.lat).put(it.lon)) } }) }
            prefs.edit().putString(KEY_ROUTES, arr.toString()).apply()
        }

    /** Village state owned by the 3D page (yard items, wool, fish dex) as an opaque JSON string. */
    var villageState: String
        get() = prefs.getString(KEY_VILLAGE, "{}")!!
        set(value) = prefs.edit().putString(KEY_VILLAGE, value).apply()

    fun seed(history: List<List<GeoPoint>>, blocks: Set<Int>, startMiles: Int) {
        routes = history
        revealedBlocks = blocks
        miles = startMiles
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    fun reset() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_MILES = "miles"
        const val KEY_BLOCKS = "revealed_blocks"
        const val KEY_ROUTES = "routes"
        const val KEY_VILLAGE = "village_state"
    }
}
