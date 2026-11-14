package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Episode
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

/**
 * AnimeUnity: native archive/home/search plus the original mapping fallback.
 *
 * Native pages already contain a JSON payload for the show and its first 120
 * episodes. Long shows are completed through /info_api in batches of 120.
 * Playback still uses the /embed-url -> VixCloud signed master playlist.
 */
class AnimeUnityProvider : TmdbProvider("AnimeUnity", "https://www.animeunity.to") {
    private val auBase = "https://www.animeunity.to"

    companion object {
        /** Home sections of the site. Ids are stable: they are saved in the settings. */
        val catalogs: List<CatalogRow> = listOf(
            CatalogRow("featured", "In evidenza"),
            CatalogRow("latest", "Ultimi episodi"),
            CatalogRow("top", "Top anime"),
            CatalogRow("archive", "Archivio")
        )
    }

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
        if (!host.equals("animeunity.to", ignoreCase = true)) return null
        val match = Regex("^/anime/[^/]+", RegexOption.IGNORE_CASE)
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

    private fun animeTitle(node: JsonNode): String {
        for (field in listOf("title", "title_it", "title_eng")) {
            val value = node.path(field).asText("").trim()
            if (value.isNotBlank()) return decodeHtmlEntities(value)
        }
        return ""
    }

    private fun animePath(node: JsonNode): String {
        val id = node.path("id").asInt(0)
        val slug = node.path("slug").asText("").trim()
        if (id <= 0 || slug.isBlank()) return ""
        return "/anime/$id-$slug"
    }

    private fun animePoster(node: JsonNode): String {
        // imageurl is AniList's portrait cover; "cover" is often landscape.
        for (field in listOf("imageurl", "imageurl_cover", "cover")) {
            val value = node.path(field).asText("").trim()
            if (value.isNotBlank()) return absUrl(value, auBase)
        }
        return ""
    }

    private fun cardFromNode(node: JsonNode): CatalogCard? {
        val title = animeTitle(node)
        val path = animePath(node)
        if (title.isBlank() || path.isBlank()) return null
        val card = newAnimeSearchResponse(title, auBase + path, TvType.Anime, fix = false) {
            posterUrl = animePoster(node).takeIf { it.isNotBlank() }
            year = node.path("date").asText("").take(4).toIntOrNull()
        }
        return CatalogCard(
            card = card,
            dub = node.path("dub").asInt(0) == 1,
            type = node.path("type").asText("").takeIf { it.isNotBlank() }
        )
    }

    private fun elementTag(html: String, name: String): String? =
        Regex("<$name\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)?.value

    private fun attribute(tag: String, name: String): String? {
        val value = Regex(
            "(?:^|\\s)$name\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(tag)?.groupValues?.get(1) ?: return null
        return decodeHtmlEntities(value)
    }

    private fun jsonNodes(raw: String?): List<JsonNode> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { json.readTree(raw) }.getOrNull() ?: return emptyList()
        return when {
            root.isArray -> root.toList()
            root.path("data").isArray -> root.path("data").toList()
            else -> emptyList()
        }
    }

    private fun cleanText(value: String?): String = decodeHtmlEntities(
        value.orEmpty()
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    private suspend fun fetchText(url: String, referer: String = "$auBase/"): String? = try {
        net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30
        ).text
    } catch (e: Exception) {
        null
    }

    @Volatile
    private var homeCache: Pair<Map<String, List<CatalogCard>>, Long>? = null

    /** Raw cards per section; the row filters are applied by [getMainPage]. */
    private suspend fun nativeCards(): Map<String, List<CatalogCard>> {
        val now = System.currentTimeMillis()
        homeCache?.let { (rows, at) ->
            if (now - at < 120_000L) return rows
        }

        // Home, top and archive are three independent pages. Fetching them in
        // parallel cuts the cold home-page load roughly to the slowest request.
        val rows = coroutineScope {
            val homeDeferred = async { fetchText("$auBase/") }
            val topDeferred = async { fetchText("$auBase/top-anime") }
            val archiveDeferred = async { fetchText("$auBase/archivio") }

            val rows = LinkedHashMap<String, List<CatalogCard>>()
            homeDeferred.await()?.let { home ->
                val featured = jsonNodes(
                    elementTag(home, "the-carousel")?.let { attribute(it, "animes") }
                ).mapNotNull(::cardFromNode).distinctBy { it.card.url }.take(40)
                if (featured.isNotEmpty()) rows["featured"] = featured

                val latest = jsonNodes(
                    elementTag(home, "layout-items")?.let { attribute(it, "items-json") }
                ).mapNotNull { node ->
                    val anime = node.path("anime")
                    if (anime.isObject) anime else node.takeIf { it.path("id").asInt(0) > 0 }
                }.mapNotNull(::cardFromNode).distinctBy { it.card.url }.take(40)
                if (latest.isNotEmpty()) rows["latest"] = latest
            }

            topDeferred.await()?.let { top ->
                val cards = jsonNodes(
                    elementTag(top, "top-anime")?.let { attribute(it, "animes") }
                ).mapNotNull(::cardFromNode).distinctBy { it.card.url }.take(40)
                if (cards.isNotEmpty()) rows["top"] = cards
            }

            archiveDeferred.await()?.let { archive ->
                val cards = jsonNodes(
                    elementTag(archive, "archivio")?.let { attribute(it, "records") }
                ).mapNotNull(::cardFromNode).distinctBy { it.card.url }.take(40)
                if (cards.isNotEmpty()) rows["archive"] = cards
            }
            rows
        }

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
        val html = fetchText("$auBase/archivio?title=${enc(query)}", "$auBase/archivio")
            ?: return super.search(query)
        val cards = jsonNodes(
            elementTag(html, "archivio")?.let { attribute(it, "records") }
        ).mapNotNull(::cardFromNode).distinctBy { it.card.url }
            .filter { it.matches(CatalogSettings.edition, CatalogSettings.type) }
            .map { it.card }
        return cards.ifEmpty { super.search(query) }
    }

    // ------------------------------------------------------------ native load

    private suspend fun loadNative(ref: NativeRef, url: String): LoadResponse {
        val page = fetchText(auBase + ref.path)
            ?: throw ErrorLoadingException("AnimeUnity non raggiungibile")
        val player = elementTag(page, "video-player")
            ?: throw ErrorLoadingException("Scheda AnimeUnity non trovata")
        val anime = attribute(player, "anime")
            ?.let { runCatching { json.readTree(it) }.getOrNull() }

        var title = anime?.let(::animeTitle).orEmpty()
        if (title.isBlank()) {
            title = cleanText(
                Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                    .find(page)?.groupValues?.get(1)
            ).ifBlank { ref.path.substringAfterLast('/') }
        }
        val poster = anime?.let(::animePoster).orEmpty()
        val banner = anime?.path("imageurl_cover")?.asText("").orEmpty()
        val background = if (banner.isNotBlank()) absUrl(banner, auBase) else poster
        val plot = anime?.path("plot")?.asText("")?.trim().orEmpty()
        val year = anime?.path("date")?.asText("")?.take(4)?.toIntOrNull()
        val duration = anime?.path("episodes_length")?.asInt(0)?.takeIf { it > 0 }
        val tags = anime?.path("genres")?.mapNotNull {
            it.path("name").asText("").ifBlank { null }
        } ?: emptyList()

        val episodeNodes = LinkedHashMap<Int, Pair<Int, JsonNode>>()
        fun addEpisodes(nodes: JsonNode?) {
            nodes?.forEach { node ->
                val raw = node.path("number").asText("").replace(',', '.')
                val value = raw.toDoubleOrNull() ?: return@forEach
                val number = kotlin.math.round(value).toInt()
                if (number > 0 && !episodeNodes.containsKey(number)) {
                    episodeNodes[number] = number to node
                }
            }
        }

        addEpisodes(
            attribute(player, "episodes")?.let {
                runCatching { json.readTree(it) }.getOrNull()
            }
        )

        val total = attribute(player, "episodes_count")?.toIntOrNull()
            ?: anime?.path("episodes_count")?.asInt(0)
            ?: 0
        val animeId = anime?.path("id")?.asInt(0)?.takeIf { it > 0 }
            ?: Regex("/anime/(\\d+)").find(ref.path)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        if (total > episodeNodes.size && animeId > 0) {
            // Only request ranges that are not already embedded in the page.
            // Long shows (One Piece: 1000+) need several /info_api calls, and
            // doing them sequentially was the main reason load() felt slow.
            val known = episodeNodes.keys.toSet()
            val ranges = (1..total step 120).map { start ->
                start..minOf(start + 119, total)
            }.filter { range -> range.any { it !in known } }

            if (ranges.isNotEmpty()) {
                val headers = mapOf(
                    "User-Agent" to UA_PC,
                    "Referer" to auBase + ref.path,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*"
                )
                val semaphore = Semaphore(4)
                val batches = coroutineScope {
                    ranges.map { range ->
                        async {
                            semaphore.withPermit {
                                val start = range.first
                                val end = range.last
                                val info = try {
                                    net.get(
                                        "$auBase/info_api/$animeId/1?start_range=$start&end_range=$end",
                                        headers = headers,
                                        timeout = 25
                                    ).text
                                } catch (e: Exception) {
                                    null
                                }
                                info?.let { runCatching { json.readTree(it) }.getOrNull() }
                                    ?.path("episodes")
                            }
                        }
                    }.awaitAll()
                }
                batches.forEach(::addEpisodes)
            }
        }

        val episodes = episodeNodes.values.map { (number, node) ->
            newEpisode(
                url = "$auBase${ref.path}?ep=$number",
                initializer = {
                    name = "Episodio ${node.path("number").asText(number.toString())}"
                    season = 1
                    episode = number
                    posterUrl = poster.takeIf { it.isNotBlank() }
                },
                fix = false
            )
        }.sortedBy { it.episode ?: 0 }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster.takeIf { it.isNotBlank() }
            backgroundPosterUrl = background.takeIf { it.isNotBlank() }
            this.plot = plot.takeIf { it.isNotBlank() }
            this.year = year
            this.duration = duration
            this.tags = tags
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val native = parseNative(url) ?: return super.load(url)
        return loadNative(native, url)
    }

    private suspend fun resolveNative(
        ref: NativeRef,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = ref.episode ?: return false
        val embed = resolveEmbed(ref.path, episode) ?: return false
        val streams = resolveVix(embed) ?: return false
        var emitted = 0
        val seen = HashSet<String>()
        for (stream in streams.take(2)) {
            if (!seen.add(stream.first)) continue
            emit(
                callback = callback,
                url = stream.first,
                label = "AnimeUnity ${stream.second} " +
                    (if (isItalianPath(ref.path)) "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDDEF\uD83C\uDDF5 SUB"),
                quality = stream.third,
                type = ExtractorLinkType.M3U8,
                referer = stream.fourth["Referer"],
                headers = stream.fourth
            )
            emitted++
        }
        return emitted > 0
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val native = parseNative(data)
        if (native != null && native.episode != null) {
            return resolveNative(native, subtitleCallback, callback)
        }
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    // ------------------------------------------------------------ TMDB fallback

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        var map = fetchAnimeMapping("tmdb", ref.id.toString(), season, episode)
        if (mappingPaths(map, "animeunity", "/anime/").isEmpty() && !meta.imdbId.isNullOrBlank()) {
            map = fetchAnimeMapping("imdb", meta.imdbId!!, season, episode)
        }
        if (map == null) return false
        var paths = mappingPaths(map, "animeunity", "/anime/")
        if (paths.isEmpty()) {
            val kitsu = map.path("requested").path("resolvedKitsuId").asText("")
                .ifBlank { map.path("kitsu").path("id").asText("") }
            if (kitsu.isNotBlank()) {
                val kMap = fetchAnimeMapping("kitsu", kitsu, 1, episode)
                paths = mappingPaths(kMap, "animeunity", "/anime/")
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
        val paths = mappingPaths(map, "animeunity", "/anime/")
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
            val embed = resolveEmbed(path, episodeNumber) ?: continue
            val streams = resolveVix(embed) ?: continue
            for (stream in streams.take(2)) {
                if (!seen.add(stream.first)) continue
                emit(
                    callback = callback,
                    url = stream.first,
                    label = "AnimeUnity ${stream.second} " +
                        (if (isItalianPath(path)) "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDDEF\uD83C\uDDF5 SUB"),
                    quality = stream.third,
                    type = ExtractorLinkType.M3U8,
                    referer = stream.fourth["Referer"],
                    headers = stream.fourth
                )
                emitted++
            }
        }
        return emitted > 0
    }

    // ------------------------------------------------------------ playback internals

    private data class Session(
        val cookies: MutableMap<String, String> = LinkedHashMap(),
        var csrf: String = ""
    )

    private fun cookieHeader(session: Session): String =
        session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun pageHeaders(session: Session, referer: String): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to UA_PC,
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val cookie = cookieHeader(session)
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }

    private fun xhrHeaders(session: Session, referer: String): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to UA_PC,
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )
        if (session.csrf.isNotBlank()) headers["X-CSRF-TOKEN"] = session.csrf
        session.cookies["XSRF-TOKEN"]?.let { headers["X-XSRF-TOKEN"] = it }
        val cookie = cookieHeader(session)
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }

    private suspend fun getText(
        url: String,
        headers: Map<String, String>,
        session: Session
    ): String? = try {
        val response = net.get(url, headers = headers, timeout = 25)
        session.cookies.putAll(response.cookies)
        response.text
    } catch (e: Exception) {
        null
    }

    private suspend fun resolveEmbed(animePath: String, episode: Int): String? {
        val session = Session()
        val pageUrl = auBase + animePath
        val page = getText(pageUrl, pageHeaders(session, "$auBase/"), session) ?: return null
        Regex("<meta[^>]+name=[\"']csrf-token[\"'][^>]+content=[\"']([^\"']+)")
            .find(page)?.groupValues?.get(1)?.let { session.csrf = it }
        val tag = Regex("<video-player\\b[^>]*>", RegexOption.IGNORE_CASE).find(page)?.value
            ?: return null

        val episodesJson = attribute(tag, "episodes")
        val episodes = episodesJson?.let { runCatching { json.readTree(it) }.getOrNull() }
        var target: JsonNode? = null
        episodes?.forEach { node ->
            val number = node.path("number").asText("").replace(',', '.').toDoubleOrNull()
            if (number != null && kotlin.math.round(number).toInt() == episode) target = node
        }

        if (target == null && episodes != null && episodes.size() > 0) {
            val animeId = attribute(tag, "anime")?.let {
                runCatching { json.readTree(it).path("id").asText("") }.getOrDefault("")
            }.orEmpty().ifBlank {
                Regex("/anime/(\\d+)").find(animePath)?.groupValues?.get(1) ?: ""
            }
            if (animeId.isNotBlank()) {
                val start = ((episode - 1) / 120) * 120 + 1
                val end = start + 119
                val infoUrl = "$auBase/info_api/$animeId/1?start_range=$start&end_range=$end"
                val infoText = getText(infoUrl, xhrHeaders(session, pageUrl), session)
                val info = infoText?.let { runCatching { json.readTree(it) }.getOrNull() }
                info?.path("episodes")?.forEach { node ->
                    val number = node.path("number").asText("").replace(',', '.').toDoubleOrNull()
                    if (number != null && kotlin.math.round(number).toInt() == episode) target = node
                }
            }
        }

        var embed = target?.path("embed_url")?.asText("").orEmpty()
        val targetId = target?.path("id")?.asText("").orEmpty()
        if (embed.isBlank() && targetId.isNotBlank()) {
            val response = getText("$auBase/embed-url/$targetId", xhrHeaders(session, pageUrl), session)
            if (!response.isNullOrBlank() && response.trim().startsWith("http")) {
                embed = response.trim()
            }
        }
        if (embed.isBlank()) {
            val total = attribute(tag, "episodes_count")?.toIntOrNull() ?: 0
            if ((episodes?.size() ?: 0) <= 1 && total <= 1) {
                attribute(tag, "embed_url")?.takeIf { it.startsWith("http") }?.let { embed = it }
            }
        }
        return embed.ifBlank { null }
    }

    private suspend fun resolveVix(originalEmbed: String): List<Quadruple<String, String, Int, Map<String, String>>>? {
        val hosts = listOf("vixcloud.co", "vixsrc.to")
        for (host in hosts) {
            val embed = originalEmbed.replace(
                Regex("^(https?://)(?:www\\.)?(?:vixcloud\\.(?:co|cc)|vixsrc\\.to)", RegexOption.IGNORE_CASE),
                "$1$host"
            )
            val headers = mapOf("User-Agent" to UA_PC, "Referer" to "https://$host/")
            val html = try {
                net.get(embed, headers = headers, timeout = 20).text
            } catch (e: Exception) {
                continue
            }
            val token = Regex("[\"']token[\"']\\s*:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
                ?: continue
            val expires = Regex("[\"']expires[\"']\\s*:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
                ?: continue
            val asn = Regex("[\"']asn[\"']\\s*:\\s*[\"']([^\"']*)[\"']").find(html)?.groupValues?.get(1).orEmpty()
            val playlist = Regex("url\\s*:\\s*[\"']([^\"']+/playlist/\\d+[^\"']*)[\"']")
                .find(html)?.groupValues?.get(1)
                ?: (Regex("/embed/(\\d+)").find(embed)?.groupValues?.get(1)?.let { "https://$host/playlist/$it" })
                ?: continue
            val fhd = Regex("canPlayFHD\\s*=\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(html)

            var path = playlist.substringBefore('?').let { if (it.endsWith(".m3u8")) it else "$it.m3u8" }
            val query = playlist.substringAfter('?', "")
            val parts = mutableListOf<String>()
            if (query.isNotBlank()) parts.add(query)
            parts.add("token=${enc(token)}")
            parts.add("expires=${enc(expires)}")
            if (asn.isNotBlank()) parts.add("asn=${enc(asn)}")
            if (fhd) parts.add("h=1")
            val master = "$path?${parts.joinToString("&")}"

            val masterText = try {
                net.get(master, headers = headers, timeout = 20).text
            } catch (e: Exception) {
                continue
            }
            if (!masterText.contains("#EXTM3U")) continue

            val variants = parseVariants(masterText, master)
                .sortedWith(compareByDescending<Triple<String, Int, String>> { it.second }
                    .thenByDescending { it.first.length })
                .distinctBy { it.second }
                .take(2)

            if (variants.isEmpty()) {
                val height = bestHeight(masterText).takeIf { it > 0 } ?: if (fhd) 1080 else 720
                val quality = qualityFromHeight(height).takeIf { it > 0 } ?: height
                return listOf(Quadruple(master, "${quality}p", quality, headers))
            }
            return variants.map { v ->
                Quadruple(v.first, v.third, v.second, headers)
            }
        }
        return null
    }

    private fun parseVariants(masterText: String, masterUrl: String): List<Triple<String, Int, String>> {
        val out = mutableListOf<Triple<String, Int, String>>()
        var pendingHeight = 0
        var pendingBandwidth = 0
        for (raw in masterText.split('\n')) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = line.substringAfter(':')
                pendingHeight = Regex("RESOLUTION=\\d+x(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (!line.startsWith("#")) {
                val quality = if (pendingHeight > 0) qualityFromHeight(pendingHeight) else 0
                if (quality > 0) {
                    out.add(Triple(absUrl(line, masterUrl), quality, "${quality}p"))
                }
                pendingHeight = 0
                pendingBandwidth = 0
            }
        }
        return out
    }
}
