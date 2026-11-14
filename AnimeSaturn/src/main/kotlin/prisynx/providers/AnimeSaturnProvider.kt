package prisynx.providers

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.CatalogCard
import prisynx.providers.core.CatalogRow
import prisynx.providers.core.CatalogSettings
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.matches
import prisynx.providers.core.UA_PC
import prisynx.providers.core.absUrl
import prisynx.providers.core.bestHeight
import prisynx.providers.core.decodeHtmlEntities
import prisynx.providers.core.enc
import prisynx.providers.core.KitsuRef
import prisynx.providers.core.fetchAnimeMapping
import prisynx.providers.core.isItalianPath
import prisynx.providers.core.json
import prisynx.providers.core.mappingAbsoluteEpisode
import prisynx.providers.core.mappingPaths
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromHeight
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * AnimeSaturn: native catalogue/search/details plus the original SaturnCDN
 * playback implementation.
 */
class AnimeSaturnProvider : TmdbProvider("AnimeSaturn", "https://www.animesaturn.net") {
    private val asBase = "https://www.animesaturn.net"
    private val pageCache = ConcurrentHashMap<String, Pair<String, Long>>()

    companion object {
        /** Home sections of the site. Ids are stable: they are saved in the settings. */
        val catalogs: List<CatalogRow> = listOf(
            CatalogRow("featured", "In evidenza", horizontal = true),
            CatalogRow("latest", "Ultime uscite"),
            CatalogRow("top", "Più visti questa settimana"),
            CatalogRow("new", "Nuove aggiunte")
        )
    }

    @Volatile
    private var homeCache: Pair<Map<String, List<CatalogCard>>, Long>? = null

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.TvSeries,
        TvType.Movie
    )

    private data class NativeRef(val path: String, val episode: Int? = null)

    private fun parseNative(value: String): NativeRef? {
        val uri = try {
            URI(value.trim())
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.removePrefix("www.") ?: return null
        if (!host.equals("animesaturn.net", ignoreCase = true)) return null
        val path = uri.path.orEmpty()
        val episode = Regex("^/episode/([^/]+)/ep-(\\d+)", RegexOption.IGNORE_CASE)
            .find(path)
        if (episode != null) {
            return NativeRef(
                "/anime/${episode.groupValues[1]}",
                episode.groupValues[2].toIntOrNull()
            )
        }
        val anime = Regex("^/anime/[^/]+", RegexOption.IGNORE_CASE).find(path)
            ?: return null
        return NativeRef(anime.value, null)
    }

    // ------------------------------------------------------------ native cards

    private fun cleanText(value: String?): String = decodeHtmlEntities(
        value.orEmpty()
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    private fun attribute(tag: String, name: String): String? {
        val value = Regex(
            "(?:^|\\s)$name\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(tag)?.groupValues?.get(1) ?: return null
        return decodeHtmlEntities(value)
    }

    private fun metaContent(html: String, name: String): String? {
        for (match in Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = match.value
            val key = attribute(tag, "name") ?: attribute(tag, "property") ?: continue
            if (key.equals(name, ignoreCase = true)) return attribute(tag, "content")
        }
        return null
    }

    private suspend fun fetchText(url: String): String? {
        val now = System.currentTimeMillis()
        pageCache[url]?.let { (html, at) ->
            if (now - at < 180_000L) return html
        }
        return try {
            val text = net.get(
                url,
                headers = mapOf(
                    "User-Agent" to UA_PC,
                    "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
                    "Referer" to "$asBase/"
                ),
                timeout = 30
            ).text
            if (text.isNotBlank()) pageCache[url] = text to now
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun pathOf(href: String): String {
        return try {
            URI(href).path.orEmpty()
        } catch (e: Exception) {
            href.substringBefore('?').substringBefore('#')
        }
    }

    private fun parseCards(html: String): List<CatalogCard> {
        val cards = LinkedHashMap<String, CatalogCard>()
        val anchorRegex = Regex(
            "<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE
        )
        for (match in anchorRegex.findAll(html)) {
            val href = decodeHtmlEntities(match.groupValues[1])
            val body = match.groupValues[2]
            val path = when {
                href.contains("/anime/", ignoreCase = true) -> {
                    Regex("^/anime/[^/]+", RegexOption.IGNORE_CASE)
                        .find(pathOf(href))?.value
                }
                href.contains("/episode/", ignoreCase = true) -> {
                    Regex("^/episode/([^/]+)/ep-\\d+", RegexOption.IGNORE_CASE)
                        .find(pathOf(href))?.let { "/anime/${it.groupValues[1]}" }
                }
                else -> null
            } ?: continue
            val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
                .find(body)?.value ?: continue
            val title = cleanText(
                Regex(
                    "<h3[^>]*class=[\"'][^\"']*ac__title[^\"']*[\"'][^>]*>([\\s\\S]*?)</h3>",
                    RegexOption.IGNORE_CASE
                ).find(body)?.groupValues?.get(1)
            ).ifBlank {
                attribute(imgTag, "alt").orEmpty()
            }.ifBlank {
                path.substringAfterLast('/').substringBeforeLast('-').replace('-', ' ')
            }
            val poster = absUrl(attribute(imgTag, "src").orEmpty(), asBase)
            if (title.isBlank() || cards.containsKey(path)) continue
            val card = newAnimeSearchResponse(title, asBase + path, TvType.Anime, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }
            }
            val type = Regex("ac__type-badge[^>]*>([^<]+)<", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
            cards[path] = CatalogCard(
                card = card,
                dub = body.contains("ac__dub-badge") || isDubTitle(title),
                type = type
            )
        }
        return cards.values.toList()
    }

    /** AnimeSaturn marks the dubbed editions with an "(ITA)" suffix in the title. */
    private fun isDubTitle(title: String): Boolean =
        title.contains("(ITA)", ignoreCase = true)

    /** The "In evidenza" hero carousel: one landscape card per slide. */
    private fun parseHero(html: String): List<CatalogCard> {
        val cards = LinkedHashMap<String, CatalogCard>()
        val titleRegex = Regex("hero-title[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE)
        for (match in titleRegex.findAll(html)) {
            val title = cleanText(match.groupValues[1])
            if (title.isBlank()) continue
            val tailStart = match.range.last + 1
            val tail = html.substring(tailStart, minOf(html.length, tailStart + 3000))
            val href = Regex("href=\"(/anime/[^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(tail)?.groupValues?.get(1) ?: continue
            val path = Regex("^/anime/[^/]+", RegexOption.IGNORE_CASE)
                .find(pathOf(href))?.value ?: continue
            if (cards.containsKey(path)) continue
            val head = html.substring(maxOf(0, match.range.first - 3000), match.range.first)
            val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
                .findAll(head).lastOrNull()?.value
            val poster = imgTag?.let { absUrl(attribute(it, "src").orEmpty(), asBase) }.orEmpty()
            val type = Regex(
                "hero-pill[^>]*>\\s*([A-Za-z]+)\\s*<",
                RegexOption.IGNORE_CASE
            ).find(tail)?.groupValues?.get(1)
            val card = newAnimeSearchResponse(title, asBase + path, TvType.Anime, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }
            }
            cards[path] = CatalogCard(card = card, dub = isDubTitle(title), type = type)
        }
        return cards.values.toList()
    }

    private fun block(html: String, startMarker: String, stopMarkers: List<String>): String {
        val start = html.indexOf(startMarker, ignoreCase = true)
        if (start < 0) return ""
        var end = html.length
        for (marker in stopMarkers) {
            val index = html.indexOf(marker, start + startMarker.length, ignoreCase = true)
            if (index > start && index < end) end = index
        }
        return html.substring(start, end)
    }

    /** Raw cards per section; the row filters are applied by [getMainPage]. */
    private suspend fun nativeCards(): Map<String, List<CatalogCard>> {
        val now = System.currentTimeMillis()
        homeCache?.let { (rows, at) ->
            if (now - at < 120_000L) return rows
        }
        val html = fetchText("$asBase/") ?: return emptyMap()
        val rows = LinkedHashMap<String, List<CatalogCard>>()

        fun add(id: String, source: String) {
            val cards = parseCards(source).take(40)
            if (cards.isNotEmpty()) rows[id] = cards
        }

        val featured = parseHero(html).take(40)
        if (featured.isNotEmpty()) rows["featured"] = featured

        add(
            "latest",
            block(
                html,
                "<h2 class=\"section-title\">Ultime uscite</h2>",
                listOf("Pi&ugrave; visti questa settimana")
            )
        )
        add(
            "top",
            block(
                html,
                "Pi&ugrave; visti questa settimana",
                listOf("Calendario uscite")
            )
        )
        add(
            "new",
            block(
                html,
                "<h2 class=\"section-title\">Nuove aggiunte</h2>",
                listOf("<h2 class=\"section-title\">News</h2>", "News")
            )
        )
        if (rows.isNotEmpty()) homeCache = rows to now
        return rows
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cards = try {
            nativeCards()
        } catch (e: Exception) {
            emptyMap()
        }
        val edition = CatalogSettings.edition
        val type = CatalogSettings.type
        val rows = CatalogSettings.visible(catalogs).mapNotNull { row ->
            val visible = cards[row.id].orEmpty().filter { it.matches(edition, type) }
            if (visible.isEmpty()) null
            else HomePageList(row.label, visible.map { it.card }, isHorizontalImages = row.horizontal)
        }
        return if (rows.isNotEmpty()) newHomePageResponse(rows, false)
        else super.getMainPage(page, request)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val html = fetchText("$asBase/filter?key=${enc(query)}")
            ?: return super.search(query)
        val resultBlock = Regex(
            "<section>\\s*<div class=\"rail\">([\\s\\S]*?)</section>",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return super.search(query)
        val cards = parseCards(resultBlock)
            .filter { it.matches(CatalogSettings.edition, CatalogSettings.type) }
            .map { it.card }
        return cards.ifEmpty { super.search(query) }
    }

    // ------------------------------------------------------------ native load

    private suspend fun loadNative(ref: NativeRef, url: String): LoadResponse {
        val page = fetchText("$asBase${ref.path}")
            ?: throw ErrorLoadingException("AnimeSaturn non raggiungibile")
        val slug = ref.path.substringAfterLast('/')

        val title = cleanText(
            Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)
                .find(page)?.groupValues?.get(1)
        ).ifBlank {
            metaContent(page, "og:title").orEmpty()
                .substringAfter(" - ")
                .substringBefore(" Streaming")
        }

        val posterBlock = Regex(
            "<div[^>]*class=[\"'][^\"']*ag-poster[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
            RegexOption.IGNORE_CASE
        ).find(page)?.groupValues?.get(1)
        val posterTag = posterBlock?.let {
            Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).find(it)?.value
        }
        val poster = attribute(posterTag.orEmpty(), "src").orEmpty()
            .ifBlank { metaContent(page, "og:image").orEmpty() }
        val background = metaContent(page, "og:image").orEmpty().ifBlank { poster }
        val plot = metaContent(page, "description").orEmpty()
            .ifBlank { metaContent(page, "og:description").orEmpty() }
            .trim()
        val genreBlock = Regex(
            "<div[^>]*class=[\"'][^\"']*ag-genres[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
            RegexOption.IGNORE_CASE
        ).find(page)?.groupValues?.get(1)
        val tags = genreBlock?.let {
            Regex("<a\\b[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
                .findAll(it)
                .mapNotNull { match -> cleanText(match.groupValues[1]).ifBlank { null } }
                .toList()
        } ?: emptyList()

        val numbers = LinkedHashSet<Int>()
        for (match in Regex(
            "href\\s*=\\s*[\"']/episode/([^\"']+)/ep-(\\d+)",
            RegexOption.IGNORE_CASE
        ).findAll(page)) {
            if (!match.groupValues[1].equals(slug, ignoreCase = true)) continue
            match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }?.let { numbers.add(it) }
        }

        val episodes = numbers.sorted().map { number ->
            newEpisode(
                url = "$asBase/episode/$slug/ep-$number",
                initializer = {
                    name = "Episodio $number"
                    season = 1
                    episode = number
                    posterUrl = poster.takeIf { it.isNotBlank() }
                },
                fix = false
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster.takeIf { it.isNotBlank() }
            backgroundPosterUrl = background.takeIf { it.isNotBlank() }
            this.plot = plot.takeIf { it.isNotBlank() }
            this.tags = tags
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val native = parseNative(url) ?: return super.load(url)
        return loadNative(native, url)
    }

    // ------------------------------------------------------------ playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val native = parseNative(data)
        val episode = native?.episode
        if (native != null && episode != null) {
            val result = resolvePath(native.path, episode) ?: return false
            emit(
                callback = callback,
                url = result.first,
                label = "AnimeSaturn ${result.second} " +
                    (if (isItalianPath(native.path)) "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDDEF\uD83C\uDDF5 SUB"),
                quality = result.third,
                type = if (result.first.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = result.fourth["Referer"],
                headers = result.fourth
            )
            return true
        }
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        var map = fetchAnimeMapping("tmdb", ref.id.toString(), season, episode)
        if (mappingPaths(map, "animesaturn", "/anime/").isEmpty() && !meta.imdbId.isNullOrBlank()) {
            map = fetchAnimeMapping("imdb", meta.imdbId!!, season, episode)
        }
        if (map == null) return false
        var paths = mappingPaths(map, "animesaturn", "/anime/")
        if (paths.isEmpty()) {
            val kitsu = map.path("requested").path("resolvedKitsuId").asText("")
                .ifBlank { map.path("kitsu").path("id").asText("") }
            if (kitsu.isNotBlank()) {
                val kMap = fetchAnimeMapping("kitsu", kitsu, 1, episode)
                paths = mappingPaths(kMap, "animesaturn", "/anime/")
                if (paths.isNotEmpty()) map = kMap
            }
        }
        if (paths.isEmpty()) return false

        return emitPaths(paths, mappingAbsoluteEpisode(map, episode), episode, !ref.isTv, callback)
    }

    override val supportsKitsu = true

    override suspend fun resolveKitsu(
        ref: KitsuRef,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = ref.episode ?: 1
        val map = fetchAnimeMapping("kitsu", ref.id, 0, episode) ?: return false
        val paths = mappingPaths(map, "animesaturn", "/anime/")
        if (paths.isEmpty()) return false
        return emitPaths(paths, mappingAbsoluteEpisode(map, episode), episode, ref.isMovie, callback)
    }

    /** Tries the mapped episode number, then the requested one (TMDB episode groups can be off). */
    private suspend fun emitPaths(
        paths: List<String>,
        target: Int,
        fallback: Int,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (emitEpisode(paths, target, isMovie, callback)) return true
        return fallback != target && emitEpisode(paths, fallback, isMovie, callback)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun emitEpisode(
        paths: List<String>,
        episodeNumber: Int,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = 0
        val seen = HashSet<String>()
        for (path in paths) {
            val result = resolvePath(path, episodeNumber) ?: continue
            if (!seen.add(result.first.substringBefore('?'))) continue
            emit(
                callback = callback,
                url = result.first,
                label = "AnimeSaturn ${result.second} " +
                    (if (isItalianPath(path)) "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDDEF\uD83C\uDDF5 SUB"),
                quality = result.third,
                type = if (result.first.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = result.fourth["Referer"],
                headers = result.fourth
            )
            emitted++
        }
        return emitted > 0
    }

    private suspend fun resolvePath(
        path: String,
        episode: Int
    ): Quadruple<String, String, Int, Map<String, String>>? {
        val pageUrl = "$asBase$path/ep-$episode"
        val page = try {
            net.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to UA_PC,
                    "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
                    "Referer" to "$asBase$path"
                ),
                timeout = 25
            ).text
        } catch (e: Exception) {
            return null
        }
        val embed = decodeHtmlEntities(
            Regex("<iframe\\b[^>]*src=[\"']((?:https?:)?//[^\"']*saturncdn[^\"']*/embed/[^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(page)?.groupValues?.get(1) ?: return null
        )
        val embedUrl = if (embed.startsWith("//")) "https:$embed" else embed
        val origin = Regex("^https?://[^/]+").find(embedUrl)?.value ?: return null
        val vid = Regex("/embed/([^/?#]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val token = Regex("[?&]token=([^&#]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val expires = Regex("[?&]expires=([^&#]+)").find(embedUrl)?.groupValues?.get(1) ?: ""

        val playlistUrl = "$origin/embed/$vid/playlist?token=$token&expires=$expires"
        val playlist = try {
            net.get(
                playlistUrl,
                headers = mapOf(
                    "User-Agent" to UA_PC,
                    "Accept" to "*/*",
                    "Origin" to origin,
                    "Referer" to embedUrl
                ),
                timeout = 20
            ).text
        } catch (e: Exception) {
            return null
        }
        val payload = runCatching { json.readTree(playlist) }.getOrNull() ?: return null
        val data = payload.path("d").asText("")
        if (data.isBlank()) return null
        val media = xorToken(data, token)?.trim().orEmpty()
        if (!Regex("^https?://[^\\s\"'<>]+$", RegexOption.IGNORE_CASE).matches(media)) return null

        val headers = mapOf("User-Agent" to UA_PC, "Referer" to embedUrl, "Origin" to origin)
        var quality = 0
        if (media.contains(".m3u8")) {
            val master = try {
                net.get(media, headers = headers, timeout = 15).text
            } catch (e: Exception) {
                ""
            }
            if (master.contains("#EXTM3U")) quality = qualityFromHeight(bestHeight(master))
        } else {
            quality = Regex("(2160|1080|720|480)p").find(media)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        val label = when {
            quality > 0 -> "${quality}p"
            media.contains(".m3u8") -> "HLS"
            else -> "MP4"
        }
        return Quadruple(media, label, quality, headers)
    }

    private fun xorToken(data: String, token: String): String? {
        val bytes = try {
            Base64.decode(data.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
        return buildString(bytes.size) {
            for (i in bytes.indices) {
                append(((bytes[i].toInt() and 0xff) xor token[i % token.length].code).toChar())
            }
        }
    }
}
