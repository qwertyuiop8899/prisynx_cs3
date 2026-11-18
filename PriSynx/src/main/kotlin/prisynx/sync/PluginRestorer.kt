package prisynx.sync

import android.content.Context
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * After a restore the plugin list (PLUGINS_KEY) points to .cs3 files that may not exist
 * on this device. Their paths are rebased onto this device's files dir and the missing
 * files are downloaded right away from the url stored in the list, so CloudStream loads
 * them at the next start. Anything that cannot be downloaded is marked outdated as a
 * fallback, so CloudStream's own auto-update fetches it from its repository.
 */
object PluginRestorer {
    private const val PLUGINS_KEY = "PLUGINS_KEY"
    private const val REPOSITORIES_KEY = "REPOSITORIES_KEY"
    private const val VERSION_NOT_SET = Int.MIN_VALUE
    private const val FOLDER = "/Extensions/"
    private val mapper = jacksonObjectMapper()

    data class Report(
        val repos: Int = 0,
        val plugins: Int = 0,
        val downloaded: List<String> = emptyList(),
        val failed: List<String> = emptyList()
    )

    private fun download(url: String, dest: File): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn = URL(url).openConnection() as HttpURLConnection
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(URL(url), next).openConnection() as HttpURLConnection
        }
        if (conn.responseCode !in 200..299) return@runCatching false
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (tmp.length() < 1024) { tmp.delete(); return@runCatching false }
        if (dest.exists()) { dest.setWritable(true); dest.delete() }
        tmp.renameTo(dest)
    }.getOrDefault(false)

    /** Must run off the main thread (downloads). */
    fun restore(context: Context): Report {
        val prefs = context.getSharedPrefs()
        val repos = runCatching {
            (mapper.readTree(prefs.getString(REPOSITORIES_KEY, null) ?: "[]") as ArrayNode).size()
        }.getOrDefault(0)
        val json = prefs.getString(PLUGINS_KEY, null) ?: return Report(repos)
        val list = runCatching { mapper.readTree(json) as? ArrayNode }.getOrNull() ?: return Report(repos)
        val filesDir = context.filesDir.absolutePath
        val downloaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (node in list) {
            val obj = node as? ObjectNode ?: continue
            val name = obj.get("internalName")?.asText() ?: "?"
            val path = obj.get("filePath")?.asText() ?: continue
            val idx = path.indexOf(FOLDER)
            val local = if (idx >= 0) filesDir + path.substring(idx) else path
            if (local != path) obj.put("filePath", local)
            if (File(local).exists()) continue
            val url = obj.get("url")?.asText()
            if (!url.isNullOrBlank() && download(url, File(local))) {
                downloaded += name
            } else {
                obj.put("version", VERSION_NOT_SET)
                failed += name
            }
        }
        prefs.edit().putString(PLUGINS_KEY, mapper.writeValueAsString(list)).commit()
        return Report(repos, list.size(), downloaded, failed)
    }
}
