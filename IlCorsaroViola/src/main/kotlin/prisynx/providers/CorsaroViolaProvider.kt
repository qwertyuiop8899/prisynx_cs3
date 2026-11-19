package prisynx.providers

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.Debrid
import prisynx.providers.core.DebridSettings
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.ProviderOptions
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.enc
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * IlCorsaroViola: P2P index. CloudStream supports MAGNET links, so the plugin
 * returns the same magnets/file indexes as the Nuvio provider.
 */
class CorsaroViolaProvider : TmdbProvider("IlCorsaroViola", "https://corsaroviola.example") {
    private val key = "Corsaro_Viola_Nuvio_Key_2026"

    private fun decode(value: String): String {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        return buildString(bytes.size) {
            for (i in bytes.indices) {
                append(((bytes[i].toInt() and 0xff) xor key[i % key.length].code).toChar())
            }
        }
    }

    private val apiBase: String by lazy { decode("KxsGA1tdQG5lWUFdUG9gQ09HXWpxXUptAw==") }
    private val token: String by lazy { decode("MAsHGQkUXCg/DAwIGSkRKhIAAC8kFxow") }

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = if (ref.isTv) {
            "${ref.id}:${ref.season ?: 1}:${ref.episode ?: 1}"
        } else {
            ref.id.toString()
        }
        val endpoint = if (ref.isTv) "/tv" else "/movie"
        val url = "$apiBase$endpoint?tmdb_id=${enc(id)}&token=${enc(token)}"
        val root = try {
            json.readTree(
                net.get(url, headers = mapOf("User-Agent" to UA_PC, "Accept" to "application/json"), timeout = 25).text
            )
        } catch (e: Exception) {
            return false
        }

        val results = root.path("results")
        if (!results.isArray) return false
        class Torrent(val magnet: String, val title: String, val quality: Int, val seeders: Int, val size: Long)
        val seen = HashSet<String>()
        val all = results.mapNotNull { item ->
            val magnet = item.path("magnet").asText("")
            if (!magnet.startsWith("magnet:") || !seen.add(magnet)) return@mapNotNull null
            val rawTitle = item.path("title").asText("")
            Torrent(magnet, rawTitle, qualityFromText(rawTitle), item.path("seeders").asInt(0), item.path("file").path("size").asLong(0))
        }.sortedByDescending { it.seeders }

        // Best seeded 4K and Full HD first (when present), then fill up with the most seeded.
        val max = ProviderOptions.torrentMaxResults.coerceAtLeast(1)
        val picked = LinkedHashSet<Torrent>()
        all.firstOrNull { it.quality >= 2160 }?.let(picked::add)
        all.firstOrNull { it.quality in 1080 until 2160 }?.let(picked::add)
        all.forEach { if (picked.size < max) picked.add(it) }
        val chosen = picked.take(max).sortedByDescending { it.seeders }

        // With a debrid service configured, cached torrents become direct links.
        // Resolved in parallel so three magnets do not triple the wait.
        val direct = if (DebridSettings.active()) {
            coroutineScope {
                chosen.map { async { Debrid.resolve(it.magnet) } }.awaitAll()
            }
        } else {
            List(chosen.size) { null }
        }
        val tag = DebridSettings.shortLabel(DebridSettings.service)

        var emitted = 0
        for ((index, t) in chosen.withIndex()) {
            val italian = Regex("ita|italian|italiano", RegexOption.IGNORE_CASE).containsMatchIn(t.title)
            val label = "IlCorsaroViola ${if (t.quality > 0) "${t.quality}p" else "HD"} " +
                (if (italian) "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDDEC\uD83C\uDDE7") +
                " \u00B7 ${t.seeders} seed" + (if (t.size > 0) " \u00B7 ${t.size / (1024 * 1024)} MB" else "")
            val link = direct.getOrNull(index)
            if (link != null) {
                emit(
                    callback = callback,
                    url = link.url,
                    label = "\u26A1 $label \u00B7 $tag",
                    quality = t.quality,
                    type = ExtractorLinkType.VIDEO,
                    referer = "",
                    headers = emptyMap()
                )
            } else if (DebridSettings.active() && DebridSettings.cacheOnly) {
                // Not cached and P2P disabled: listed but not playable.
                val hash = Regex("btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
                    .find(t.magnet)?.groupValues?.get(1)?.lowercase() ?: "x"
                emit(
                    callback = callback,
                    url = "https://non-in-cache.invalid/$hash.mkv",
                    label = "\u274C $label \u00B7 non in cache $tag",
                    quality = t.quality,
                    type = ExtractorLinkType.VIDEO,
                    referer = "",
                    headers = emptyMap()
                )
            } else {
                emit(
                    callback = callback,
                    url = t.magnet,
                    label = label + if (DebridSettings.active()) " \u00B7 P2P (non in cache $tag)" else "",
                    quality = t.quality,
                    type = ExtractorLinkType.MAGNET,
                    referer = "",
                    headers = emptyMap()
                )
            }
            emitted++
        }
        return emitted > 0
    }
}
