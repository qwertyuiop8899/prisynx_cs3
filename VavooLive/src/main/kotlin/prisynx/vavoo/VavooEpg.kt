package prisynx.vavoo

import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Italian EPG (now / next) read straight from the XMLTV file used by TvVoo.
 * The file is ~18 MB but GitHub serves it gzipped (~2 MB); it is parsed as a
 * stream and only programmes of the next 24 hours are kept.
 */
object VavooEpg {
    private const val URL_EPG = "https://raw.githubusercontent.com/qwertyuiop8899/TV/refs/heads/main/epg.xml"
    private const val TTL = 60 * 60_000L
    private const val RETRY = 5 * 60_000L
    private const val WINDOW = 24 * 60 * 60_000L

    data class Programme(val start: Long, val stop: Long, val title: String, val desc: String?)

    private val lock = Mutex()
    private var loadedAt = 0L
    private var failedAt = 0L
    private var keyToIds: Map<String, Set<String>> = emptyMap()
    private var programmes: Map<String, List<Programme>> = emptyMap()

    private val TIME = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val CLOCK = SimpleDateFormat("HH:mm", Locale.ITALY).apply {
        timeZone = TimeZone.getTimeZone("Europe/Rome")
    }

    /** Same idea as TvVoo, tolerant to "Rai1.it" / "Rai 1 HD.it" / "RAI 1 .s". */
    fun key(raw: String): String = raw.lowercase(Locale.ROOT).trim()
        .replace(Regex("\\.it$"), "")
        .replace(Regex("\\s*(\\.[a-z0-9]{1,3})+$"), "")
        .replace(Regex("\\s*\\((?:\\d+|[a-z]{1,3}|backup)\\)\\s*$"), "")
        .replace(Regex("\\b(hd|fhd|uhd|4k|sd)\\b"), " ")
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "")

    /** Starts the download in background (home load), without waiting. */
    fun warmUp() {
        if (fresh()) return
        CoroutineScope(Dispatchers.IO).launch { ensure() }
    }

    private fun fresh() = programmes.isNotEmpty() && System.currentTimeMillis() - loadedAt < TTL

    suspend fun ensure(): Boolean = lock.withLock {
        val now = System.currentTimeMillis()
        if (fresh()) return@withLock true
        if (now - failedAt < RETRY) return@withLock programmes.isNotEmpty()
        val ok = withTimeoutOrNull(60_000L) { withContext(Dispatchers.IO) { download(now) } } ?: false
        if (ok) loadedAt = now else failedAt = now
        programmes.isNotEmpty()
    }

    private fun download(now: Long): Boolean = try {
        val conn = URL(URL_EPG).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 40_000
        conn.setRequestProperty("Accept-Encoding", "gzip")
        val raw = conn.inputStream
        val input = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
        input.use { parse(it, now) }
    } catch (e: Exception) {
        false
    }

    private fun parse(input: java.io.InputStream, now: Long): Boolean {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val keys = HashMap<String, MutableSet<String>>()
        val progs = HashMap<String, MutableList<Programme>>()
        var channelId: String? = null
        var progChannel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        channelId?.let { keys.getOrPut(key(it)) { HashSet() }.add(it) }
                    }
                    "display-name" -> {
                        val id = channelId
                        val text = parser.nextText()
                        if (id != null && text.isNotBlank()) keys.getOrPut(key(text)) { HashSet() }.add(id)
                    }
                    "programme" -> {
                        progChannel = parser.getAttributeValue(null, "channel")
                        start = time(parser.getAttributeValue(null, "start"))
                        stop = time(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> if (progChannel != null && title == null) title = parser.nextText().trim()
                    "desc" -> if (progChannel != null && desc == null) desc = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        val ch = progChannel
                        val t = title
                        if (ch != null && !t.isNullOrBlank() && stop > now && start < now + WINDOW) {
                            progs.getOrPut(ch) { ArrayList() }.add(Programme(start, stop, t, desc?.ifBlank { null }))
                        }
                        progChannel = null
                    }
                }
            }
            event = parser.next()
        }
        if (progs.isEmpty()) return false
        progs.values.forEach { it.sortBy { p -> p.start } }
        keyToIds = keys
        programmes = progs
        return true
    }

    private fun time(value: String?): Long = try {
        if (value.isNullOrBlank()) 0L else synchronized(TIME) { TIME.parse(value)?.time ?: 0L }
    } catch (e: Exception) {
        0L
    }

    private fun clock(ms: Long) = synchronized(CLOCK) { CLOCK.format(ms) }

    /** Current and next programme of a channel (null when not in the EPG). */
    fun nowNext(channelName: String): Pair<Programme?, Programme?>? {
        val now = System.currentTimeMillis()
        val ids = keyToIds[key(channelName)] ?: return null
        for (id in ids) {
            val list = programmes[id] ?: continue
            val current = list.firstOrNull { it.start <= now && it.stop > now }
            val next = list.firstOrNull { it.start >= (current?.stop ?: now) }
            if (current != null || next != null) return current to next
        }
        return null
    }

    /** "Ora in onda / A seguire" text for the channel page. */
    fun describe(channelName: String): String? {
        val (current, next) = nowNext(channelName) ?: return null
        val sb = StringBuilder()
        if (current != null) {
            sb.append("🔴 Ora in onda (${clock(current.start)}–${clock(current.stop)}): ${current.title}")
            current.desc?.let { sb.append("\n").append(it.take(300)) }
        }
        if (next != null) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("⏭ A seguire (${clock(next.start)}): ${next.title}")
        }
        return sb.toString().ifBlank { null }
    }
}
