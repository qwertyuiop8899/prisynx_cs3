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
 * episodes. Long shows are completed through /api/anime/{id}/episodes in tabs of 120.
 * Playback: /anime/{id}-{slug}/{videoId} embedUrl -> VixCloud signed master playlist.
 */
class AnimeUnityProvider : TmdbProvider("AnimeUnity", "https://animeunity.cx") {
    @Volatile
    private var auBase = "https://animeunity.cx"

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

    /** lang: "ita" / "jpn", or null for every available language. */
    private data class NativeRef(val path: String, val episode: Int? = null, val lang: String? = null)

    private fun parseNative(value: String): NativeRef? {
        val uri = try {
            URI(value.trim())
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.removePrefix("www.") ?: return null
        if (!Regex("^animeunity\\.[a-z]{2,4}$", RegexOption.IGNORE_CASE).matches(host)) return null
        val match = Regex("^/anime/[^/]+", RegexOption.IGNORE_CASE)
            .find(uri.path.orEmpty()) ?: return null
        val lang = queryValue(uri.rawQuery, "lang")?.lowercase()?.takeIf { it == "ita" || it == "jpn" }
        return NativeRef(match.value, queryValue(uri.rawQuery, "ep")?.toIntOrNull(), lang)
    }

    private fun queryValue(query: String?, name: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').firstOrNull {
            it.substringBefore('=').equals(name, ignoreCase = true)
        }?.substringAfter('=', "")
    }

    // ------------------------------------------------------------ native cards

    private fun animeTitle(node: JsonNode): String {
        for (field in listOf("title_it", "title", "title_ja")) {
            val value = node.path(field).asText("").trim()
            if (value.isNotBlank() && value != "null") return decodeHtmlEntities(value)
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
        val images = node.path("images")
        for (field in listOf("poster", "cover", "banner")) {
            val value = images.path(field).asText("").trim()
            if (value.isNotBlank() && value != "null") return absUrl(value, auBase)
        }
        return ""
    }

    private fun languages(node: JsonNode): Set<String> =
        node.path("variants").mapNotNull { it.path("language").asText("").lowercase().ifBlank { null } }.toSet()

    /** The site now has one page per anime; dubbed shows still get a separate "(ITA)" card. */
    private fun cardsFromNode(node: JsonNode): List<CatalogCard> {
        val title = animeTitle(node)
        val path = animePath(node)
        if (title.isBlank() || path.isBlank()) return emptyList()
        val langs = languages(node)
        val type = node.path("type").asText("").takeIf { it.isNotBlank() }
        val year = node.path("year").asInt(0).takeIf { it > 0 }
        val poster = animePoster(node).takeIf { it.isNotBlank() }
        fun card(label: String, lang: String?, dub: Boolean) = CatalogCard(
            card = newAnimeSearchResponse(label, auBase + path + (lang?.let { "?lang=$it" } ?: ""), TvType.Anime, fix = false) {
                posterUrl = poster
                this.year = year
            },
            dub = dub,
            type = type
        )
        return when {
            "ita" in langs && langs.size > 1 -> listOf(card(title, "jpn", false), card("$title (ITA)", "ita", true))
            "ita" in langs -> listOf(card("$title (ITA)", "ita", true))
            else -> listOf(card(title, null, false))
        }
    }

    /** Inertia.js page: every prop lives in the JSON of the data-page attribute. */
    private fun pageProps(html: String?): JsonNode? {
        val raw = Regex("data-page=\"([^\"]*)\"").find(html ?: return null)?.groupValues?.get(1) ?: return null
        return runCatching { json.readTree(decodeHtmlEntities(raw)).path("props") }.getOrNull()
            ?.takeIf { it.isObject }
    }

    private fun cards(nodes: JsonNode?): List<CatalogCard> =
        nodes?.flatMap(::cardsFromNode).orEmpty().distinctBy { it.card.url }.take(40)

    private fun cleanText(value: String?): String = decodeHtmlEntities(
        value.orEmpty()
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    private suspend fun fetchText(url: String, referer: String = "$auBase/"): String? = try {
        val response = net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30
        )
        followDomain(response.url)
        response.text
    } catch (e: Exception) {
        null
    }

    /** Adopts the final host when animeunity moves again (.to -> .co -> .cx ...). */
    private fun followDomain(finalUrl: String?) {
        val host = runCatching { URI(finalUrl ?: return).host }.getOrNull()?.removePrefix("www.") ?: return
        if (Regex("^animeunity\\.[a-z]{2,4}$", RegexOption.IGNORE_CASE).matches(host) && !auBase.endsWith("//$host")) {
            auBase = "https://$host"
            mainUrl = auBase
        }
    }

    private suspend fun fetchJson(url: String, referer: String = "$auBase/"): JsonNode? = try {
        val text = net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Referer" to referer,
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            timeout = 25
        ).text
        json.readTree(text)
    } catch (e: Exception) {
        null
    }

    private suspend fun nativeCards(): Map<String, List<CatalogCard>> {
        val now = System.currentTimeMillis()
        homeCache?.let { (rows, at) ->
            if (now - at < 120_000L) return rows
        }

        // Home, top and archive are three independent pages. Fetching them in
        // parallel cuts the cold home-page load roughly to the slowest request.
        val rows = coroutineScope {
            val homeDeferred = async { pageProps(fetchText("$auBase/")) }
            val topDeferred = async { pageProps(fetchText("$auBase/top-anime")) }
            val archiveDeferred = async { pageProps(fetchText("$auBase/archive")) }

            val rows = LinkedHashMap<String, List<CatalogCard>>()
            homeDeferred.await()?.let { home ->
                cards(home.path("carouselAnimes")).takeIf { it.isNotEmpty() }?.let { rows["featured"] = it }
                val latest = home.path("latestEpisodes").path("data").mapNotNull { v ->
                    v.path("episode").path("anime").takeIf { it.isObject }
                }
                cards(json.valueToTree<JsonNode>(latest)).ifEmpty { cards(home.path("latestAnimes")) }
                    .takeIf { it.isNotEmpty() }?.let { rows["latest"] = it }
            }
            topDeferred.await()?.let { top ->
                cards(top.path("animes").path("data")).takeIf { it.isNotEmpty() }?.let { rows["top"] = it }
            }
            archiveDeferred.await()?.let { archive ->
                cards(archive.path("animes").path("data")).takeIf { it.isNotEmpty() }?.let { rows["archive"] = it }
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
        val data = fetchJson("$auBase/api/search?q=${enc(query)}")?.path("data")
            ?: return super.search(query)
        val cards = data.flatMap(::cardsFromNode).distinctBy { it.card.url }
            .filter { it.matches(CatalogSettings.edition, CatalogSettings.type) }
            .map { it.card }
        return cards.ifEmpty { super.search(query) }
    }

    // ------------------------------------------------------------ native load

    private fun episodeNumber(node: JsonNode): Int? =
        node.path("number").asText("").replace(',', '.').toDoubleOrNull()
            ?.let { kotlin.math.round(it).toInt() }?.takeIf { it > 0 }

    /** Episodes of the page plus the missing 120-episode tabs from /api/anime/{id}/episodes. */
    private suspend fun allEpisodes(props: JsonNode, referer: String): List<JsonNode> {
        val loaded = props.path("loadedEpisodes")
        val byNumber = LinkedHashMap<Int, JsonNode>()
        fun add(nodes: JsonNode?) = nodes?.forEach { n -> episodeNumber(n)?.let { byNumber.putIfAbsent(it, n) } }
        add(loaded.path("episodes"))
        val total = loaded.path("total").asInt(0)
        val tab = loaded.path("tab_size").asInt(120).takeIf { it > 0 } ?: 120
        val id = props.path("anime").path("id").asInt(0)
        if (id > 0 && total > byNumber.size) {
            val starts = (1..total step tab).filter { start -> (start until minOf(start + tab, total + 1)).any { it !in byNumber } }
            val semaphore = Semaphore(4)
            coroutineScope {
                starts.map { start ->
                    async { semaphore.withPermit { fetchJson("$auBase/api/anime/$id/episodes?start=$start", referer)?.path("episodes") } }
                }.awaitAll()
            }.forEach { add(it) }
        }
        return byNumber.entries.sortedBy { it.key }.map { it.value }
    }

    private fun hasLanguage(episode: JsonNode, lang: String?) =
        lang == null || episode.path("videos").any { it.path("language").asText("") == lang && !it.path("is_hidden").asBoolean(false) }

    private suspend fun loadNative(ref: NativeRef, url: String): LoadResponse {
        val props = pageProps(fetchText(auBase + ref.path))
            ?: throw ErrorLoadingException("AnimeUnity non raggiungibile")
        val anime = props.path("anime").takeIf { it.isObject }
            ?: throw ErrorLoadingException("Scheda AnimeUnity non trovata")
        val path = animePath(anime).ifBlank { ref.path }

        val baseTitle = animeTitle(anime).ifBlank { ref.path.substringAfterLast('/') }
        val title = if (ref.lang == "ita") "$baseTitle (ITA)" else baseTitle
        val poster = animePoster(anime)
        val banner = anime.path("images").path("banner").asText("").takeIf { it.isNotBlank() && it != "null" }
        val plot = cleanText(anime.path("plot").asText(""))
        val year = anime.path("year").asInt(0).takeIf { it > 0 }
        val duration = anime.path("episode_runtime").asInt(0).takeIf { it > 0 }
        val tags = anime.path("genres").mapNotNull { it.path("name").asText("").ifBlank { null } }

        val langQuery = ref.lang?.let { "&lang=$it" } ?: ""
        val episodes = allEpisodes(props, auBase + path)
            .filter { hasLanguage(it, ref.lang) }
            .mapNotNull { node ->
                val number = episodeNumber(node) ?: return@mapNotNull null
                newEpisode(
                    url = "$auBase$path?ep=$number$langQuery",
                    initializer = {
                        name = "Episodio ${node.path("number").asText(number.toString())}"
                        season = 1
                        episode = number
                        posterUrl = poster.takeIf { it.isNotBlank() }
                    },
                    fix = false
                )
            }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster.takeIf { it.isNotBlank() }
            backgroundPosterUrl = banner ?: poster.takeIf { it.isNotBlank() }
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
        val langs = ref.lang?.let { listOf(it) } ?: listOf("ita", "jpn")
        return emitEmbeds(resolveEmbeds(ref.path, episode, langs), callback)
    }

    private suspend fun emitEmbeds(embeds: List<Pair<String, String>>, callback: (ExtractorLink) -> Unit): Boolean {
        var emitted = 0
        val seen = HashSet<String>()
        for ((lang, embed) in embeds) {
            val streams = resolveVix(embed) ?: continue
            for (stream in streams.take(2)) {
                if (!seen.add(stream.first)) continue
                emit(
                    callback = callback,
                    url = stream.first,
                    label = "AnimeUnity ${stream.second} " +
                        (if (lang == "ita") "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDDEF\uD83C\uDDF5 SUB"),
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
        // Old "-ita" pages redirect to the single anime page now: the path only tells the language.
        val wanted = paths.map { path -> path to (if (isItalianPath(path)) "ita" else "jpn") }
            .distinctBy { it.second }
        val embeds = mutableListOf<Pair<String, String>>()
        for ((path, lang) in wanted) {
            embeds += resolveEmbeds(path, episodeNumber, listOf(lang))
        }
        return emitEmbeds(embeds.distinctBy { it.second }, callback)
    }

    // ------------------------------------------------------------ playback internals

    /** Returns (language, VixCloud embed) for each requested language available on that episode. */
    private suspend fun resolveEmbeds(animePath: String, episode: Int, langs: List<String>): List<Pair<String, String>> {
        val props = pageProps(fetchText(auBase + animePath)) ?: return emptyList()
        val anime = props.path("anime")
        val path = animePath(anime).ifBlank { animePath }
        val pageUrl = auBase + path

        val loaded = props.path("loadedEpisodes")
        var target = loaded.path("episodes").firstOrNull { episodeNumber(it) == episode }
        val id = anime.path("id").asInt(0)
        if (target == null && id > 0) {
            val tab = loaded.path("tab_size").asInt(120).takeIf { it > 0 } ?: 120
            val start = ((episode - 1) / tab) * tab + 1
            target = fetchJson("$auBase/api/anime/$id/episodes?start=$start", pageUrl)
                ?.path("episodes")?.firstOrNull { episodeNumber(it) == episode }
        }
        // Films / single-episode entries: the page episode is the only one.
        if (target == null && loaded.path("total").asInt(0) <= 1) {
            target = props.path("episode").takeIf { it.isObject }
        }
        val videos = target?.path("videos") ?: return emptyList()

        val currentId = props.path("video").path("id").asInt(0)
        val currentEmbed = props.path("embedUrl").asText("")
        val out = mutableListOf<Pair<String, String>>()
        for (lang in langs) {
            val video = videos.firstOrNull {
                it.path("language").asText("") == lang && !it.path("is_hidden").asBoolean(false)
            } ?: continue
            val videoId = video.path("id").asInt(0)
            val embed = if (videoId == currentId && currentEmbed.startsWith("http")) currentEmbed
            else pageProps(fetchText("$pageUrl/$videoId", pageUrl))?.path("embedUrl")?.asText("").orEmpty()
            if (embed.startsWith("http")) out += lang to embed
        }
        return out
    }

    private suspend fun resolveVix(originalEmbed: String): List<Quadruple<String, String, Int, Map<String, String>>>? {
        val hosts = listOf("vixcloud.cc", "vixcloud.co", "vixsrc.to")
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
