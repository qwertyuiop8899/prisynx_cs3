package prisynx.sync

import android.content.Context
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import java.io.File

/**
 * After a restore the plugin list (PLUGINS_KEY) points to .cs3 files that may not
 * exist on this device. For those, the path is rebased onto this device's files dir
 * and the version is set to "not set", so CloudStream's auto-update downloads them
 * again from their repository on the next start.
 */
object PluginRestorer {
    private const val PLUGINS_KEY = "PLUGINS_KEY"
    private const val VERSION_NOT_SET = Int.MIN_VALUE
    private const val FOLDER = "/Extensions/"
    private val mapper = jacksonObjectMapper()

    /** Returns the number of plugins that will be downloaded at the next start. */
    fun markMissing(context: Context): Int {
        val prefs = context.getSharedPrefs()
        val json = prefs.getString(PLUGINS_KEY, null) ?: return 0
        val list = runCatching { mapper.readTree(json) as? ArrayNode }.getOrNull() ?: return 0
        val filesDir = context.filesDir.absolutePath
        var missing = 0
        for (node in list) {
            val obj = node as? ObjectNode ?: continue
            val path = obj.get("filePath")?.asText() ?: continue
            val idx = path.indexOf(FOLDER)
            val local = if (idx >= 0) filesDir + path.substring(idx) else path
            if (local != path) obj.put("filePath", local)
            if (!File(local).exists()) {
                obj.put("version", VERSION_NOT_SET)
                missing++
            }
        }
        prefs.edit().putString(PLUGINS_KEY, mapper.writeValueAsString(list)).commit()
        return missing
    }
}
