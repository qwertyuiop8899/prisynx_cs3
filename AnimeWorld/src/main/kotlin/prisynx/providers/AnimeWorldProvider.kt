package prisynx.providers

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
 * AnimeWorld: native catalogue/search/details plus the original TMDB mapping
 * and SweetPixel playback implementation.
 */
class AnimeWorldProvider : TmdbProvider("AnimeWorld", "https://www.animeworld.ac") {
    private val awBase = "https://www.animeworld.ac"
    private val pageCache = ConcurrentHashMap<String, Pair<String, Long>>()

    companion object {
        /** Home sections of the site. Ids are stable: they are saved in the settings. */
        val catalogs: List<CatalogRow> = listOf(
            CatalogRow("latest", "Ultimi episodi"),
            CatalogRow("airing", "Anime in corso"),
            CatalogRow("upcoming", "In uscita"),
            CatalogRow("new", "Nuove aggiunte"),
            CatalogRow("top_day", "Top anime del giorno"),
            CatalogRow("top_week", "Top anime della settimana"),
            CatalogRow("top_month", "Top anime del mese")
        )
    }

    @Volatile
    private var homeCache: Pair<Map<String, List<CatalogCard>>, Long>? = null
    private val blocked = listOf(
        "jujutsukaisenanime.com",
        "onepunchman.it",
        "dragonballhd.it",
        "narutolegend.it"
    )

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
        if (!host.equals("animeworld.ac", ignoreCase = true)) return null
        val match = Regex("^/play/[^/]+", RegexOption.IGNORE_CASE)
            .find(uri.path.orEmpty()) ?: return null
        return NativeRef(match.value, queryValue(uri.rawQuery, "ep")?.toIntOrNull())
    }

    private fun queryValue(query: String?, name: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').firstOrNull {
            it.substringBefore('=').equals(name, ignoreCase = true)
        }?.substringAfter('=', "")
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

    private suspend fun fetchText(url: String): String? = try {
        net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
                "Referer" to "$awBase/"
            ),
            timeout = 30
        ).text
    } catch (e: Exception) {
        null
    }

    private fun parseCards(html: String): List<CatalogCard> {
        val cards = LinkedHashMap<String, CatalogCard>()
        val anchorRegex = Regex(
            "<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']*/play/[^\"']+)[\"'][^>]*>\\s*<img\\b[^>]*>",
            RegexOption.IGNORE_CASE
        )
        for (match in anchorRegex.findAll(html)) {
            val anchor = match.value
            val href = decodeHtmlEntities(match.groupValues[1])
            val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
                .find(anchor)?.value ?: continue
            val path = try {
                Regex("^/play/[^/]+", RegexOption.IGNORE_CASE)
                    .find(URI(href).path.orEmpty())?.value
            } catch (e: Exception) {
                Regex("^/play/[^/]+", RegexOption.IGNORE_CASE).find(href)?.value
            } ?: continue
            val title = attribute(imgTag, "alt").orEmpty()
                .ifBlank { attribute(anchor, "title").orEmpty() }
                .ifBlank {
                    href.substringAfter("/play/").substringBefore('.')
                        .replace('-', ' ')
                }
            val poster = absUrl(attribute(imgTag, "src").orEmpty(), awBase)
            if (title.isBlank() || cards.containsKey(path)) continue
            val url = "$awBase$path"
            val card = newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }
            }
            cards[path] = CatalogCard(card = card, dub = isDubTitle(title))
        }
        return cards.values.toList()
    }

    /** AnimeWorld marks the dubbed editions with an "(ITA)" suffix in the title. */
    private fun isDubTitle(title: String): Boolean =
        title.contains("(ITA)", ignoreCase = true)

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
        val html = fetchText("$awBase/") ?: return emptyMap()
        val rows = LinkedHashMap<String, List<CatalogCard>>()

        fun add(id: String, source: String) {
            val cards = parseCards(source).take(40)
            if (cards.isNotEmpty()) rows[id] = cards
        }

        add(
            "latest",
            block(
                html,
                "<h2 class=\"title\">Ultimi Episodi</h2>",
                listOf("<div class=\"title\">Anime in corso</div>")
            )
        )
        add(
            "airing",
            block(
                html,
                "<div class=\"title\">Anime in corso</div>",
                listOf("<div class=\"title\">Uscite ")
            )
        )
        add(
            "upcoming",
            block(
                html,
                "<div class=\"title\">Uscite ",
                listOf("<div class=\"widget quickfilter\">")
            )
        )
        add(
            "new",
            block(
                html,
                "<div class=\"title\">Nuove aggiunte</div>",
                listOf("<div id=\"footer\"", "</body>")
            )
        )
        // Top Anime is client-side tabbed: the three lists are already in the page.
        add(
            "top_day",
            block(
                html,
                "<div class=\"content\" data-name=\"day\">",
                listOf("<div class=\"content hidden\" data-name=\"week\">")
            )
        )
        add(
            "top_week",
            block(
                html,
                "<div class=\"content hidden\" data-name=\"week\">",
                listOf("<div class=\"content hidden\" data-name=\"month\">")
            )
        )
        add(
            "top_month",
            block(
                html,
                "<div class=\"content hidden\" data-name=\"month\">",
                listOf("<div class=\"title\">Nuove aggiunte</div>", "<div id=\"footer\"")
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
        val html = fetchText("$awBase/search?keyword=${enc(query)}")
            ?: return super.search(query)
        val cards = parseCards(html)
            .filter { it.matches(CatalogSettings.edition, CatalogSettings.type) }
            .map { it.card }
        return cards.ifEmpty { super.search(query) }
    }

    // ------------------------------------------------------------ native load

    private suspend fun fetchPage(path: String): String? {
        val now = System.currentTimeMillis()
        pageCache[path]?.let { (html, at) ->
            if (now - at < 180_000L) return html
        }
        val animeUrl = "$awBase$path"
        val headers = mutableMapOf(
            "User-Agent" to UA_PC,
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8"
        )
        val cookies = LinkedHashMap<String, String>()
        var page = try {
            val response = net.get(animeUrl, headers = headers, timeout = 25)
            cookies.putAll(response.cookies)
            response.text
        } catch (e: Exception) {
            return null
        }
        val challenge = Regex("(SecurityAW[\\w-]*)=([a-f0-9]+)", RegexOption.IGNORE_CASE)
            .find(page)
        if (challenge != null && !page.contains("data-episode-num")) {
            cookies[challenge.groupValues[1]] = challenge.groupValues[2]
            val retryUrl = animeUrl + if (animeUrl.contains("?")) "&d=1" else "?d=1"
            page = try {
                net.get(
                    retryUrl,
                    headers = headersWithCookies(headers, cookies),
                    timeout = 25
                ).text
            } catch (e: Exception) {
                return null
            }
        }
        pageCache[path] = page to now
        return page
    }

    private fun parseEpisodeNumbers(html: String): List<Int> {
        val numbers = LinkedHashSet<Int>()
        for (match in Regex(
            "data-episode-num\\s*=\\s*[\"']([^\"']+)",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val number = match.groupValues[1].replace(',', '.')
                .toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
            if (number != null && number > 0) numbers.add(number)
        }
        if (numbers.isEmpty()) {
            for (match in Regex(
                "data-num\\s*=\\s*[\"']([0-9]+)",
                RegexOption.IGNORE_CASE
            ).findAll(html)) {
                match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { numbers.add(it) }
            }
        }
        return numbers.sorted()
    }

    private suspend fun loadNative(ref: NativeRef, url: String): LoadResponse {
        val page = fetchPage(ref.path)
            ?: throw ErrorLoadingException("AnimeWorld non raggiungibile")
        val title = cleanText(
            Regex(
                "<h1[^>]*id=[\"']anime-title[\"'][^>]*>([\\s\\S]*?)</h1>",
                RegexOption.IGNORE_CASE
            ).find(page)?.groupValues?.get(1)
        ).ifBlank {
            cleanText(
                Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)
                    .find(page)?.groupValues?.get(1)
            )
        }.ifBlank {
            metaContent(page, "og:title").orEmpty()
                .substringBefore(" Streaming")
                .substringBefore(" Episodio")
        }

        val thumbnailBlock = Regex(
            "<div[^>]*id=[\"']thumbnail-watch[\"'][^>]*>([\\s\\S]*?)</div>",
            RegexOption.IGNORE_CASE
        ).find(page)?.groupValues?.get(1)
        val thumbnailTag = thumbnailBlock?.let {
            Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).find(it)?.value
        }
        val poster = attribute(thumbnailTag.orEmpty(), "src").orEmpty()
            .ifBlank { metaContent(page, "og:image").orEmpty() }
        val background = metaContent(page, "og:image").orEmpty().ifBlank { poster }
        val plot = metaContent(page, "description").orEmpty()
            .ifBlank { metaContent(page, "og:description").orEmpty() }
            .replace(
                Regex("^Trama di\\s+.*?\\s+SUB ITA:\\s*", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("^Trama di\\s+.*?:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        val episodes = parseEpisodeNumbers(page).map { number ->
            newEpisode(
                url = "$awBase${ref.path}?ep=$number",
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
            val result = resolvePath(native.path, episode, false) ?: return false
            emit(
                callback = callback,
                url = result.first,
                label = "AnimeWorld ${result.second} " +
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
        if (mappingPaths(map, "animeworld", "/play/").isEmpty() && !meta.imdbId.isNullOrBlank()) {
            map = fetchAnimeMapping("imdb", meta.imdbId!!, season, episode)
        }
        if (map == null) return false
        var paths = mappingPaths(map, "animeworld", "/play/")
        if (paths.isEmpty()) {
            val kitsu = map.path("requested").path("resolvedKitsuId").asText("")
                .ifBlank { map.path("kitsu").path("id").asText("") }
            if (kitsu.isNotBlank()) {
                val kMap = fetchAnimeMapping("kitsu", kitsu, 1, episode)
                paths = mappingPaths(kMap, "animeworld", "/play/")
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
        val paths = mappingPaths(map, "animeworld", "/play/")
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
            val result = resolvePath(path, episodeNumber, isMovie) ?: continue
            if (!seen.add(result.first)) continue
            emit(
                callback = callback,
                url = result.first,
                label = "AnimeWorld ${result.second} " +
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

    private data class Episode(val num: Int, val token: String)

    private suspend fun resolvePath(
        path: String,
        episode: Int,
        isMovie: Boolean
    ): Quadruple<String, String, Int, Map<String, String>>? {
        val animeUrl = "$awBase$path"
        val pageHeaders = mutableMapOf(
            "User-Agent" to UA_PC,
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8"
        )
        val cookies = LinkedHashMap<String, String>()
        var page = try {
            val response = net.get(animeUrl, headers = pageHeaders, timeout = 25)
            cookies.putAll(response.cookies)
            response.text
        } catch (e: Exception) {
            return null
        }
        val challenge = Regex("(SecurityAW[\\w-]*)=([a-f0-9]+)", RegexOption.IGNORE_CASE).find(page)
        if (challenge != null && !page.contains("data-episode-num")) {
            cookies[challenge.groupValues[1]] = challenge.groupValues[2]
            val retryUrl = animeUrl + if (animeUrl.contains("?")) "&d=1" else "?d=1"
            page = try {
                val response = net.get(retryUrl, headers = headersWithCookies(pageHeaders, cookies), timeout = 25)
                cookies.putAll(response.cookies)
                response.text
            } catch (e: Exception) {
                return null
            }
        }

        val episodes = parseEpisodes(page)
        val entry = pickEpisode(episodes, episode, isMovie) ?: return null
        val csrf = Regex("name=[\"']csrf-token[\"'][^>]*content=[\"']([^\"']+)")
            .find(page)?.groupValues?.get(1)
            ?: Regex("content=[\"']([^\"']+)[\"'][^>]*name=[\"']csrf-token[\"']")
                .find(page)?.groupValues?.get(1)
            ?: ""

        val apiHeaders = mutableMapOf(
            "User-Agent" to UA_PC,
            "Referer" to animeUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )
        if (csrf.isNotBlank()) apiHeaders["csrf-token"] = csrf
        val info = try {
            json.readTree(
                net.get(
                    "$awBase/api/episode/info?id=${prisynx.providers.core.enc(entry.token)}",
                    headers = headersWithCookies(apiHeaders, cookies),
                    timeout = 20
                ).text
            )
        } catch (e: Exception) {
            return null
        }

        var media = info.path("grabber").asText("")
        if (!Regex("^https?://[^\\s\"'<>]+\\.(?:mp4|m3u8)(?:[?#][^\\s\"'<>]*)?$", RegexOption.IGNORE_CASE).matches(media)) {
            media = ""
        }
        val target = info.path("target").asText("")
        if (media.isBlank() && target.isNotBlank()) {
            val targetUrl = awBase + target.replace(Regex("^https?://[^/]+"), "")
            media = try {
                val html = net.get(targetUrl, headers = headersWithCookies(apiHeaders, cookies), timeout = 20).text
                    .replace("\\/", "/")
                Regex("https?://[^\\s\"'<>\\\\]+\\.(?:mp4|m3u8)[^\\s\"'<>\\\\]*", RegexOption.IGNORE_CASE)
                    .find(html)?.value ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        if (media.isBlank()) return null
        val low = media.lowercase()
        if (low.endsWith(".mkv.mp4") || blocked.any { low.contains(it) }) return null

        val headers = mapOf("User-Agent" to UA_PC, "Referer" to animeUrl)
        var quality = 0
        if (low.contains(".m3u8")) {
            val master = try {
                net.get(media, headers = headers, timeout = 15).text
            } catch (e: Exception) {
                ""
            }
            if (master.contains("#EXTM3U")) quality = qualityFromHeight(bestHeight(master))
        } else {
            quality = Regex("(2160|1080|720|480)p").find(low)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        val label = when {
            quality > 0 -> "${quality}p"
            low.contains(".m3u8") -> "HLS"
            else -> "MP4"
        }
        return Quadruple(media, label, quality, headers)
    }

    private fun headersWithCookies(
        base: Map<String, String>,
        cookies: Map<String, String>
    ): Map<String, String> {
        if (cookies.isEmpty()) return base
        return base + ("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    private fun parseEpisodes(html: String): List<Episode> {
        val out = mutableListOf<Episode>()
        for (tag in Regex("<a\\b[^>]*data-episode-num=[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val value = Regex("data-episode-num=[\"']([^\"']+)").find(tag.value)?.groupValues?.get(1)
                ?: continue
            val num = value.replace(',', '.').toDoubleOrNull()?.let { kotlin.math.round(it).toInt() } ?: continue
            val token = Regex("data-id=[\"']([^\"']+)").find(tag.value)?.groupValues?.get(1) ?: continue
            out.add(Episode(num, token))
        }
        return out
    }

    private fun pickEpisode(list: List<Episode>, episode: Int, isMovie: Boolean): Episode? {
        if (list.isEmpty()) return null
        if (isMovie) return list.first()
        return list.firstOrNull { it.num == episode } ?: if (list.size == 1 && episode == 1) list.first() else null
    }
}
