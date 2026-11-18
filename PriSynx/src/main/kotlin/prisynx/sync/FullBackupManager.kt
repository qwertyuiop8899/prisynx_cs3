package prisynx.sync

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/**
 * Handles full extraction and restoration of all settings:
 * 1. CloudStream core settings and DataStore (bookmarks, watch history, repos, playback state).
 * 2. PriSynx private preferences (debrid API keys, hub categories, kisskh, etc.).
 * 3. Any additional SharedPreferences XML files present in the app's shared_prefs directory.
 */
object FullBackupManager {
    private val mapper = jacksonObjectMapper()

    data class SharedPreferencesSnapshot(
        val name: String,
        val bools: Map<String, Boolean> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val longs: Map<String, Long> = emptyMap(),
        val floats: Map<String, Float> = emptyMap(),
        val strings: Map<String, String> = emptyMap(),
        val stringSets: Map<String, Set<String>> = emptyMap()
    )

    data class FullBackupPayload(
        val version: Int = 1,
        val timestamp: Long = System.currentTimeMillis(),
        val prefs: List<SharedPreferencesSnapshot> = emptyList()
    )

    // Keys that must NOT be restored (device-specific hardware or local instance keys)
    private val skipKeys = setOf(
        "data_store_helper/account_key_index",
        "FILES_TO_DELETE_KEY"
    )

    private fun extractSnapshot(name: String, prefs: SharedPreferences): SharedPreferencesSnapshot {
        val all = prefs.all
        val bools = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val floats = mutableMapOf<String, Float>()
        val strings = mutableMapOf<String, String>()
        val stringSets = mutableMapOf<String, Set<String>>()

        for ((key, value) in all) {
            if (key in skipKeys) continue
            when (value) {
                is Boolean -> bools[key] = value
                is Int -> ints[key] = value
                is Long -> longs[key] = value
                is Float -> floats[key] = value
                is String -> strings[key] = value
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val set = value.filterIsInstance<String>().toSet()
                    if (set.isNotEmpty()) stringSets[key] = set
                }
            }
        }

        return SharedPreferencesSnapshot(name, bools, ints, longs, floats, strings, stringSets)
    }

    private fun restoreSnapshot(prefs: SharedPreferences, snapshot: SharedPreferencesSnapshot) {
        val editor = prefs.edit()
        snapshot.bools.forEach { (k, v) -> if (k !in skipKeys) editor.putBoolean(k, v) }
        snapshot.ints.forEach { (k, v) -> if (k !in skipKeys) editor.putInt(k, v) }
        snapshot.longs.forEach { (k, v) -> if (k !in skipKeys) editor.putLong(k, v) }
        snapshot.floats.forEach { (k, v) -> if (k !in skipKeys) editor.putFloat(k, v) }
        snapshot.strings.forEach { (k, v) -> if (k !in skipKeys) editor.putString(k, v) }
        snapshot.stringSets.forEach { (k, v) -> if (k !in skipKeys) editor.putStringSet(k, v) }
        editor.commit()
    }

    /**
     * Extracts ALL app and plugin settings into a single JSON string.
     */
    fun createFullBackupJson(context: Context): String {
        val snapshots = mutableListOf<SharedPreferencesSnapshot>()

        // 1. CloudStream standard datastore & default settings
        snapshots.add(extractSnapshot("__cs_datastore__", context.getSharedPrefs()))
        snapshots.add(extractSnapshot("__cs_default__", context.getDefaultSharedPrefs()))

        // 2. Scan all XML files in shared_prefs directory to capture all plugins (Debrid, Hub, etc.)
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (prefsDir.exists() && prefsDir.isDirectory) {
            prefsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".xml")) {
                    val name = file.name.removeSuffix(".xml")
                    if (name.isNotEmpty() && name != context.packageName + "_preferences") {
                        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                        snapshots.add(extractSnapshot(name, sp))
                    }
                }
            }
        } else {
            // Fallback for known PriSynx custom prefs
            listOf("prisynx_debrid", "prisynx_hub", "prisynx_kisskh").forEach { name ->
                val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                snapshots.add(extractSnapshot(name, sp))
            }
        }

        val payload = FullBackupPayload(
            timestamp = System.currentTimeMillis(),
            prefs = snapshots
        )
        return mapper.writeValueAsString(payload)
    }

    /**
     * Restores all settings from JSON string into SharedPreferences.
     */
    /** What the last restore wrote on this device. */
    var lastReport: PluginRestorer.Report? = null
        private set
    var lastPrefsRestored = 0
        private set

    fun restoreFullBackupJson(context: Context, json: String): Boolean {
        return try {
            val payload = mapper.readValue<FullBackupPayload>(json)
            for (snapshot in payload.prefs) {
                val sp: SharedPreferences = when (snapshot.name) {
                    "__cs_datastore__" -> context.getSharedPrefs()
                    "__cs_default__" -> context.getDefaultSharedPrefs()
                    else -> context.getSharedPreferences(snapshot.name, Context.MODE_PRIVATE)
                }
                restoreSnapshot(sp, snapshot)
            }
            lastPrefsRestored = payload.prefs.size
            lastReport = runCatching { PluginRestorer.restore(context) }.getOrNull()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
