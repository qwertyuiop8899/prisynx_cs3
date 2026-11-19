package prisynx.providers.core

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Debrid support for the torrent sources (IlCorsaroViola).
 *
 * The index only returns magnets. When the user configures a debrid service the
 * plugin sends the magnet to the service (Real-Debrid, AllDebrid or TorBox),
 * waits for the download according to [DebridSettings.waitSeconds] and emits the
 * direct HTTP link instead of the magnet. When the link is not ready yet the
 * magnet is kept by the list and the torrent keeps downloading on the service,
 * so the next attempt gets the direct link.
 */
class DebridService(val id: String, val label: String, val keyPage: String)

data class DebridLink(val url: String, val name: String, val size: Long)

object DebridSettings {
    val services = listOf(
        DebridService("realdebrid", "Real-Debrid", "https://real-debrid.com/apitoken"),
        DebridService("alldebrid", "AllDebrid", "https://alldebrid.com/apikeys"),
        DebridService("torbox", "TorBox", "https://torbox.app/settings")
    )

    /** Values offered by the "wait" selector, in seconds. 0 = cached only. */
    val waitChoices = listOf(0, 20, 40, 60, 90, 120)

    private const val PREFS = "prisynx_debrid"
    private const val KEY_ON = "enabled"
    private const val KEY_SERVICE = "service"
    private const val KEY_WAIT = "wait_seconds"
    private const val DEFAULT_WAIT = 60
    private const val KEY_CACHE_ONLY = "cache_only"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun attach(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences? = preferences

    fun labelOf(id: String): String =
        services.firstOrNull { it.id == id }?.label ?: "Debrid"

    /** Compact tag used in the stream labels ("RD", "AD", "TB"). */
    fun shortLabel(id: String): String = when (id) {
        "realdebrid" -> "RD"
        "alldebrid" -> "AD"
        "torbox" -> "TB"
        else -> "Debrid"
    }

    var enabled: Boolean
        get() = prefs()?.getBoolean(KEY_ON, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_ON, value)?.apply()
        }

    var service: String
        get() = prefs()?.getString(KEY_SERVICE, services.first().id)
            ?.takeIf { id -> services.any { it.id == id } }
            ?: services.first().id
        set(value) {
            if (services.none { it.id == value }) return
            prefs()?.edit()?.putString(KEY_SERVICE, value)?.apply()
        }

    /** Seconds to wait for a debrid download; 0 means "cached only". */
    var waitSeconds: Int
        get() = prefs()?.getInt(KEY_WAIT, DEFAULT_WAIT)?.coerceIn(0, 300) ?: DEFAULT_WAIT
        set(value) {
            prefs()?.edit()?.putInt(KEY_WAIT, value.coerceIn(0, 300))?.apply()
        }

    fun nextWaitSeconds(): Int {
        val index = waitChoices.indexOf(waitSeconds)
        return waitChoices[(index + 1) % waitChoices.size]
    }

    /**
     * true: only torrents cached on the service are playable, the others are
     * listed as "non in cache" (no P2P). false: not cached torrents fall back
     * to the magnet (CloudStream P2P).
     */
    var cacheOnly: Boolean
        get() = prefs()?.getBoolean(KEY_CACHE_ONLY, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_CACHE_ONLY, value)?.apply()
        }

    /** API key of the selected service (each service keeps its own). */
    val apiKey: String
        get() = prefs()?.getString("key_$service", "").orEmpty().trim()

    fun setApiKey(value: String) {
        prefs()?.edit()?.putString("key_$service", value.trim())?.apply()
    }

    fun keyOf(id: String): String =
        prefs()?.getString("key_$id", "").orEmpty().trim()

    /** A debrid service is configured and enabled. */
    fun active(): Boolean = enabled && apiKey.isNotBlank()

    fun maskedKey(): String {
        val key = apiKey
        if (key.isBlank()) return "chiave non impostata"
        if (key.length <= 8) return "•".repeat(key.length)
        return key.take(4) + "••••" + key.takeLast(4)
    }

    fun reset() {
        prefs()?.edit()?.clear()?.apply()
        Debrid.clearCache()
    }
}

object Debrid {
    private const val RD_API = "https://api.real-debrid.com/rest/1.0"
    private const val AD_API = "https://api.alldebrid.com/v4"
    private const val TB_API = "https://api.torbox.app/v1/api"

    private const val SUCCESS_TTL = 30 * 60 * 1000L
    private const val MISS_TTL_CACHED = 3 * 60 * 1000L
    private const val MISS_TTL_PENDING = 30 * 1000L
    private const val BASE_TIMEOUT = 25_000L
    private const val PENDING_WAIT = 15_000L

    private class Hit(val link: DebridLink, val at: Long)
    private class Miss(val at: Long, val ttl: Long)

    /** A magnet already sent to the service, waiting for the download. */
    private class Pending(val service: String, val key: String, val id: String)

    private val hits = ConcurrentHashMap<String, Hit>()
    private val misses = ConcurrentHashMap<String, Miss>()
    private val pending = ConcurrentHashMap<String, Pending>()

    fun clearCache() {
        hits.clear()
        misses.clear()
        pending.clear()
    }

    /** Magnet infohash (the debrid APIs all work with the hex hash). */
    private fun hashOf(magnet: String): String? =
        Regex("urn:btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
            .find(magnet)?.groupValues?.get(1)?.lowercase()

    /**
     * Direct link for a magnet, or null when the service cannot provide one
     * (yet). Never throws.
     */
    suspend fun resolve(magnet: String): DebridLink? {
        if (!DebridSettings.active()) return null
        val hash = hashOf(magnet) ?: return null
        val now = System.currentTimeMillis()

        hits[hash]?.let { if (now - it.at < SUCCESS_TTL) return it.link else hits.remove(hash) }
        misses[hash]?.let { if (now - it.at < it.ttl) return null else misses.remove(hash) }

        val service = DebridSettings.service
        val key = DebridSettings.apiKey
        val cacheOnly = DebridSettings.cacheOnly
        val waitMs = if (cacheOnly) 0L else DebridSettings.waitSeconds * 1000L
        val inFlight = if (cacheOnly) null else pending[hash]?.takeIf { it.service == service && it.key == key }

        val link = try {
            withTimeoutOrNull(BASE_TIMEOUT + waitMs + 30_000L) {
                if (inFlight != null) {
                    // Already sent: just check again, no need to wait the full time.
                    fetchPending(inFlight, if (waitMs == 0L) 3_000L else minOf(waitMs, PENDING_WAIT))
                } else {
                    when (service) {
                        "realdebrid" -> realDebrid(magnet, hash, key, waitMs, cacheOnly)
                        "alldebrid" -> allDebrid(magnet, hash, key, waitMs)
                        "torbox" -> torBox(magnet, hash, key, waitMs)
                        else -> null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }

        if (link != null) {
            hits[hash] = Hit(link, System.currentTimeMillis())
            misses.remove(hash)
            pending.remove(hash)
        } else {
            misses[hash] = Miss(
                System.currentTimeMillis(),
                if (waitMs > 0) MISS_TTL_PENDING else MISS_TTL_CACHED
            )
        }
        return link
    }

    /** Validates a key and returns a message for the settings UI. */
    suspend fun check(service: String, key: String): String {
        if (key.isBlank()) return "Inserisci la chiave API"
        return try {
            when (service) {
                "realdebrid" -> {
                    val user = json.readTree(
                        net.get(
                            "$RD_API/user",
                            headers = mapOf("Authorization" to "Bearer $key"),
                            timeout = 20
                        ).text
                    )
                    val name = user.path("username").asText("")
                    if (name.isBlank()) "Chiave non valida" else "Real-Debrid: $name"
                }

                "alldebrid" -> {
                    val res = json.readTree(
                        net.get("$AD_API/user?agent=priSynx&apikey=$key", timeout = 20).text
                    )
                    val name = res.path("data").path("user").path("username").asText("")
                    if (name.isBlank()) {
                        "Chiave non valida (" + res.path("error").path("message").asText("errore") + ")"
                    } else {
                        "AllDebrid: $name"
                    }
                }

                "torbox" -> {
                    val me = json.readTree(
                        net.get(
                            "$TB_API/user/me",
                            headers = mapOf("Authorization" to "Bearer $key"),
                            timeout = 20
                        ).text
                    )
                    val mail = me.path("data").path("email").asText("")
                    if (mail.isBlank() || !me.path("success").asBoolean(false)) {
                        "Chiave non valida"
                    } else {
                        "TorBox: $mail"
                    }
                }

                else -> "Servizio sconosciuto"
            }
        } catch (e: Exception) {
            "Errore di rete: ${e.message ?: "sconosciuto"}"
        }
    }

    // ------------------------------------------------------------ pending

    private suspend fun fetchPending(p: Pending, limitMs: Long): DebridLink? = when (p.service) {
        "realdebrid" -> realDebridReady(p.key, p.id, limitMs)
        "alldebrid" -> allDebridReady(p.key, p.id, limitMs)
        "torbox" -> torBoxReady(p.key, p.id.toLongOrNull() ?: 0L, limitMs)
        else -> null
    }

    // ------------------------------------------------------------ Real-Debrid

    /**
     * Real-Debrid has no working cache check (instantAvailability is disabled),
     * so the magnet is added, the largest video file is selected and the
     * torrent is polled: a cached one is "downloaded" within a few seconds.
     * In cache-only mode a torrent that is not ready is deleted again.
     */
    private suspend fun realDebrid(
        magnet: String,
        hash: String,
        key: String,
        waitMs: Long,
        cacheOnly: Boolean
    ): DebridLink? {
        val headers = mapOf("Authorization" to "Bearer $key")
        val added = try {
            json.readTree(
                net.post(
                    "$RD_API/torrents/addMagnet",
                    headers = headers,
                    data = null,
                    requestBody = FormBody.Builder().add("magnet", magnet).build(),
                    timeout = 25
                ).text
            )
        } catch (e: Exception) {
            return null
        }
        val id = added.path("id").asText("")
        if (id.isBlank()) return null
        val probe = cacheOnly || waitMs == 0L

        if (!rdSelectFiles(headers, id, if (probe) 8_000L else minOf(waitMs, 12_000L))) {
            if (probe) rdDelete(headers, id)
            return null
        }
        if (!probe) pending[hash] = Pending("realdebrid", key, id)
        val link = realDebridReady(key, id, if (probe) 6_000L else waitMs, select = false)
        if (link == null && probe) rdDelete(headers, id)
        return link
    }

    private suspend fun rdDelete(headers: Map<String, String>, id: String) {
        runCatching { net.delete("$RD_API/torrents/delete/$id", headers = headers, timeout = 15) }
    }

    /**
     * Real-Debrid only accepts selectFiles once the magnet conversion is done.
     * Waits for that state and selects the largest video file (not "all", so
     * the only link of the torrent is the video and not an nfo or a sample).
     */
    private suspend fun rdSelectFiles(
        headers: Map<String, String>,
        id: String,
        limitMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + limitMs
        while (true) {
            val info = try {
                json.readTree(
                    net.get("$RD_API/torrents/info/$id", headers = headers, timeout = 20).text
                )
            } catch (e: Exception) {
                null
            }
            when (info?.path("status")?.asText("").orEmpty()) {
                "waiting_files_selection" -> {
                    var best = ""
                    var bestSize = -1L
                    var bestVideo = false
                    info?.path("files")?.forEach { file ->
                        val video = isVideo(file.path("path").asText(""))
                        val size = file.path("bytes").asLong(0)
                        if ((video && !bestVideo) || (video == bestVideo && size > bestSize)) {
                            best = file.path("id").asText("")
                            bestSize = size
                            bestVideo = video
                        }
                    }
                    val ok = runCatching {
                        net.post(
                            "$RD_API/torrents/selectFiles/$id",
                            headers = headers,
                            data = null,
                            requestBody = FormBody.Builder().add("files", best.ifBlank { "all" }).build(),
                            timeout = 25
                        )
                    }.isSuccess
                    if (ok) return true
                }

                "downloading", "downloaded", "queued", "compressing", "uploading" -> return true
                "magnet_error", "error", "dead", "virus" -> return false
            }
            if (System.currentTimeMillis() >= deadline) return false
            delay(700)
        }
    }

    private suspend fun realDebridReady(
        key: String,
        id: String,
        limitMs: Long,
        select: Boolean = true
    ): DebridLink? {
        val headers = mapOf("Authorization" to "Bearer $key")
        // A pending torrent may still be waiting for the file selection.
        if (select) rdSelectFiles(headers, id, minOf(limitMs, 4_000L))
        val link = waitForLink(limitMs) {
            val info = json.readTree(
                net.get("$RD_API/torrents/info/$id", headers = headers, timeout = 20).text
            )
            val links = info.path("links")
            if (info.path("status").asText("") == "downloaded" && links.isArray && links.size() > 0) {
                links.path(0).asText("")
            } else {
                ""
            }
        } ?: return null

        val unlocked = json.readTree(
            net.post(
                "$RD_API/unrestrict/link",
                headers = headers,
                data = null,
                requestBody = FormBody.Builder().add("link", link).build(),
                timeout = 25
            ).text
        )
        val url = unlocked.path("download").asText("")
        if (url.isBlank()) return null
        // The torrent stays on the account: deleting it may kill the link.
        return DebridLink(
            url = url,
            name = unlocked.path("filename").asText(""),
            size = unlocked.path("filesize").asLong(0)
        )
    }

    private val videoExt = Regex("\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm|wmv|mpg|mpeg)$", RegexOption.IGNORE_CASE)

    private fun isVideo(name: String): Boolean =
        videoExt.containsMatchIn(name) && !name.contains("sample", ignoreCase = true)

    // ------------------------------------------------------------ AllDebrid

    private suspend fun allDebrid(magnet: String, hash: String, key: String, waitMs: Long): DebridLink? {
        val query = "agent=priSynx&apikey=$key"
        val uploaded = try {
            json.readTree(
                net.post(
                    "$AD_API/magnet/upload?$query",
                    data = null,
                    requestBody = FormBody.Builder().add("magnets[]", magnet).build(),
                    timeout = 25
                ).text
            )
        } catch (e: Exception) {
            return null
        }
        val entry = uploaded.path("data").path("magnets").path(0)
        val id = entry.path("id").asLong(0)
        if (id == 0L) return null
        val ready = entry.path("ready").asBoolean(false)
        if (!ready && waitMs == 0L) {
            runCatching { net.post("$AD_API/magnet/delete?id=$id&$query", data = null, timeout = 15) }
            return null
        }

        pending[hash] = Pending("alldebrid", key, id.toString())
        return allDebridReady(key, id.toString(), if (waitMs == 0L) 4_000L else waitMs)
    }

    private suspend fun allDebridReady(key: String, id: String, limitMs: Long): DebridLink? {
        val query = "agent=priSynx&apikey=$key"
        var bestLink = ""
        var bestName = ""
        var bestSize = -1L

        val found = waitForLink(limitMs) {
            val status = json.readTree(
                net.get("$AD_API/magnet/status?id=$id&$query", timeout = 20).text
            )
            val files = status.path("data").path("magnets").path("files")
            if (files.isArray) {
                files.forEach { file ->
                    val link = file.path("l").asText("")
                    val size = file.path("s").asLong(0)
                    if (link.isNotBlank() && size > bestSize) {
                        bestLink = link
                        bestName = file.path("n").asText("")
                        bestSize = size
                    }
                }
            }
            bestLink
        } ?: return null

        val unlocked = json.readTree(
            net.post(
                "$AD_API/link/unlock?$query",
                data = null,
                requestBody = FormBody.Builder().add("link", found).build(),
                timeout = 25
            ).text
        )
        val url = unlocked.path("data").path("link").asText("")
        if (url.isBlank()) return null
        return DebridLink(url = url, name = bestName, size = bestSize.coerceAtLeast(0))
    }

    // ------------------------------------------------------------ TorBox

    private suspend fun torBox(magnet: String, hash: String, key: String, waitMs: Long): DebridLink? {
        val headers = mapOf("Authorization" to "Bearer $key")

        // With no wait only cached torrents are created.
        if (waitMs == 0L) {
            val cached = try {
                json.readTree(
                    net.get(
                        "$TB_API/torrents/checkcached?hash=$hash&format=list&list_files=true",
                        headers = headers,
                        timeout = 20
                    ).text
                )
            } catch (e: Exception) {
                return null
            }
            val data = cached.path("data")
            val isCached = when {
                data.isArray -> data.any {
                    (if (it.isTextual) it.asText("") else it.path("hash").asText("")).equals(hash, true)
                }
                data.has(hash) -> true
                else -> false
            }
            if (!isCached) return null
        }

        val created = try {
            json.readTree(
                net.post(
                    "$TB_API/torrents/createtorrent",
                    headers = headers,
                    data = null,
                    requestBody = FormBody.Builder().add("magnet", magnet).build(),
                    timeout = 25
                ).text
            )
        } catch (e: Exception) {
            return null
        }
        val torrentId = created.path("data").path("torrent_id").asLong(0)
        if (torrentId == 0L) return null

        pending[hash] = Pending("torbox", key, torrentId.toString())
        return torBoxReady(key, torrentId, if (waitMs == 0L) 4_000L else waitMs)
    }

    private suspend fun torBoxReady(key: String, torrentId: Long, limitMs: Long): DebridLink? {
        if (torrentId == 0L) return null
        val headers = mapOf("Authorization" to "Bearer $key")
        var fileId = -1L
        var fileName = ""
        var fileSize = -1L
        var fileVideo = false
        val deadline = System.currentTimeMillis() + limitMs

        while (true) {
            val list = json.readTree(
                net.get(
                    "$TB_API/torrents/mylist?id=$torrentId&bypass_cache=true",
                    headers = headers,
                    timeout = 20
                ).text
            )
            val data = list.path("data")
            val ready = data.path("download_finished").asBoolean(false) ||
                data.path("download_present").asBoolean(false)
            val files = data.path("files")
            if (ready && files.isArray) {
                files.forEach { file ->
                    val id = file.path("id").asLong(-1)
                    val name = file.path("name").asText("")
                    val size = file.path("size").asLong(0)
                    val video = isVideo(name)
                    if (id >= 0 && ((video && !fileVideo) || (video == fileVideo && size > fileSize))) {
                        fileId = id
                        fileName = name.substringAfterLast('/')
                        fileSize = size
                        fileVideo = video
                    }
                }
            }
            if (fileId >= 0) break
            if (System.currentTimeMillis() >= deadline) break
            delay(1_000)
        }
        if (fileId < 0) return null

        val download = json.readTree(
            net.get(
                "$TB_API/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$fileId",
                headers = headers,
                timeout = 25
            ).text
        )
        val url = download.path("data").asText("")
        if (url.isBlank()) return null
        return DebridLink(url = url, name = fileName, size = fileSize.coerceAtLeast(0))
    }

    // ------------------------------------------------------------ helpers

    /** Polls [fetch] until it returns a non blank string or [limitMs] expires. */
    private suspend fun waitForLink(limitMs: Long, fetch: suspend () -> String): String? {
        val deadline = System.currentTimeMillis() + limitMs
        while (true) {
            val value = fetch()
            if (value.isNotBlank()) return value
            if (System.currentTimeMillis() >= deadline) return null
            delay(1_000)
        }
    }
}
