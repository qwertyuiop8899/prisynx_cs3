package prisynx.providers

import android.util.Base64
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.absUrl
import prisynx.providers.core.bestHeight
import prisynx.providers.core.enc
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.normalizeTitle
import prisynx.providers.core.qualityFromHeight
import java.net.URI

/**
 * Real Time / NOVE / DMAX / Food Network (Discovery Italia).
 *
 * The catalogue, show pages and episode lists come from Discovery's Aurora
 * API, so this provider has its own native catalogue instead of using the
 * shared TMDB rows. Playback URLs are signed with the device IP and expire
 * quickly.
 */
class RealtimeProvider : TmdbProvider("Real Time", "https://realtime.it") {
    override val supportsMovies = false
    override val supportsTv = true

    private val api = "https://public.aurora.enhanced.live"

    private data class Channel(
        val env: String,
        val parent: String,
        val site: String,
        val label: String
    ) {
        val host: String
            get() = URI(site).host.removePrefix("www.")
    }

    private val channels = listOf(
        Channel("realtime", "programmi-real-time", "https://realtime.it", "Real Time"),
        Channel("nove", "programmi-nove", "https://nove.tv", "NOVE"),
        Channel("dmaxit", "programmi-dmax", "https://dmax.it", "DMAX"),
        Channel("foodnetwork", "programmi-food-network", "https://foodnetwork.it", "Food Network")
    )

    private data class NativeRef(
        val channel: Channel,
        val parent: String,
        val slug: String,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun headers(channel: Channel, extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to UA_PC,
            "Referer" to "${channel.site}/",
            "Origin" to channel.site
        ) + extra

    private fun jsonHeaders(channel: Channel): Map<String, String> =
        headers(channel, mapOf("Accept" to "application/json, text/plain, */*"))

    // ------------------------------------------------------------ native refs

    private fun parseNative(value: String): NativeRef? {
        val clean = value.trim().substringBefore('?').substringBefore('#')
        val uri = try {
            URI(clean)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.removePrefix("www.") ?: return null
        val channel = channels.firstOrNull { it.host.equals(host, ignoreCase = true) } ?: return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val parent = parts[0]
        val slug = parts[1]
        val season = Regex("season-(\\d+)").find(parts.getOrNull(2).orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = Regex("episode-(\\d+)").find(parts.getOrNull(2).orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return NativeRef(channel, parent, slug, season, episode)
    }

    private fun showUrl(channel: Channel, parent: String, slug: String): String =
        "${channel.site}/$parent/$slug"

    private fun episodeUrl(channel: Channel, parent: String, slug: String, season: Int, episode: Int): String =
        "${showUrl(channel, parent, slug)}/season-$season-episode-$episode"

    /**
     * Discovery's S3 bucket rejects direct browser requests with HTTP 403.
     * The public website therefore serves every poster through a CloudFront
     * image-resizer endpoint. Encode the same payload so CloudStream gets a
     * loadable portrait poster instead of the blocked S3 URL.
     */
    private fun imageUrl(raw: String, width: Int = 400, height: Int = 600): String {
        val value = raw.trim().replace("\\/", "/")
        if (value.isBlank() || !value.startsWith("http")) return value

        val regional = Regex(
            "^https?://s3[.-][^/]+\\.amazonaws\\.com/([^/]+)/(.+)$",
            RegexOption.IGNORE_CASE
        )
        val global = Regex(
            "^https?://s3\\.amazonaws\\.com/([^/]+)/(.+)$",
            RegexOption.IGNORE_CASE
        )
        val virtualHost = Regex(
            "^https?://([^.]+)\\.s3[.-][^/]+\\.amazonaws\\.com/(.+)$",
            RegexOption.IGNORE_CASE
        )
        val virtualGlobal = Regex(
            "^https?://([^.]+)\\.s3\\.amazonaws\\.com/(.+)$",
            RegexOption.IGNORE_CASE
        )
        val match = regional.find(value) ?: global.find(value)
            ?: virtualHost.find(value) ?: virtualGlobal.find(value)
            ?: return value
        val bucket = match.groupValues[1]
        val key = match.groupValues[2]
        if (bucket.isBlank() || key.isBlank()) return value

        val edits = linkedMapOf<String, Any>(
            "resize" to mapOf("fit" to "cover", "width" to width, "height" to height)
        )
        val lowerKey = key.lowercase()
        if (lowerKey.endsWith(".jpg") || lowerKey.endsWith(".jpeg")) {
            edits["jpeg"] = mapOf("quality" to 55, "progressive" to true)
        }
        val payload = linkedMapOf<String, Any>(
            "bucket" to bucket,
            "key" to key,
            "edits" to edits
        )
        return try {
            val encoded = Base64.encodeToString(
                json.writeValueAsString(payload).toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            "https://d2v9mhsiek5lbq.cloudfront.net/$encoded"
        } catch (e: Exception) {
            value
        }
    }

    // ------------------------------------------------------------ catalogue

    private fun mediaUrl(node: JsonNode, roles: List<String>): String {
        val direct = node.path("image").path("url").asText("")
        if (direct.isNotBlank()) return direct

        val metaMedia = node.path("metaMedia")
        if (metaMedia.isArray) {
            for (role in roles) {
                for (media in metaMedia) {
                    if (media.path("role").asText("") == role) {
                        val url = media.path("media").path("url").asText("")
                        if (url.isNotBlank()) return url
                    }
                }
            }
            for (media in metaMedia) {
                val url = media.path("media").path("url").asText("")
                if (url.isNotBlank()) return url
            }
        }

        val poster = node.path("poster").path("src").asText("")
        if (poster.isNotBlank()) return poster
        return ""
    }

    private fun catalogCard(item: JsonNode, channel: Channel): SearchResponse? {
        val kind = item.path("pageType").asText("").ifBlank { item.path("type").asText("") }
        if (kind != "showpage") return null
        val slug = item.path("slug").asText("").ifBlank { item.path("url").asText("") }
        if (slug.isBlank()) return null
        val title = item.path("title").asText("").ifBlank { item.path("pageTitleOverride").asText("") }
        if (title.isBlank()) return null
        val parent = item.path("parentUrl").asText("")
            .ifBlank { item.path("parentSlug").asText("") }
            .ifBlank { channel.parent }
        val poster = imageUrl(mediaUrl(item, listOf("default", "preview", "image")))
        return newTvSeriesSearchResponse(title, showUrl(channel, parent, slug), TvType.TvSeries, fix = false) {
            posterUrl = poster.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun fetchHome(channel: Channel): JsonNode? {
        val url = "$api/site/page/homepage/?include=default" +
            "&filter%5Benvironment%5D=${channel.env}&v=2"
        return try {
            json.readTree(net.get(url, headers = jsonHeaders(channel), timeout = 30).text)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun catalogRows(): List<HomePageList> = coroutineScope {
        val perChannel = channels.map { channel ->
            async {
                val page = fetchHome(channel) ?: return@async null
                val shows = LinkedHashMap<String, SearchResponse>()
                for (block in page.path("blocks")) {
                    if (block.path("type").asText("") != "collectionBlock") continue
                    for (item in block.path("items")) {
                        val card = catalogCard(item, channel) ?: continue
                        val slug = item.path("slug").asText("").ifBlank { item.path("url").asText("") }
                        if (!shows.containsKey(slug)) shows[slug] = card
                    }
                }
                val list = shows.values.toList()
                if (list.isEmpty()) null else channel.label to list.take(80)
            }
        }.awaitAll().filterNotNull()

        if (perChannel.isEmpty()) return@coroutineScope emptyList()
        listOf(HomePageList("In evidenza", perChannel.map { it.second.first() })) +
            perChannel.map { HomePageList(it.first, it.second) }
    }

    /** Discovery catalogue rows (also used by the PriSynx hub). */
    suspend fun catalogueRows(): List<HomePageList> = catalogRows()

    /** True for the native show/episode pages built by this provider. */
    fun ownsUrl(url: String): Boolean = parseNative(url) != null

    @Volatile
    private var homeCacheAt: Long = 0L

    @Volatile
    private var homeCacheRows: List<HomePageList>? = null

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val now = System.currentTimeMillis()
        val cached = homeCacheRows
        if (cached != null && now - homeCacheAt < 10 * 60 * 1000L) {
            return newHomePageResponse(cached, false)
        }
        val rows = catalogRows()
        if (rows.isNotEmpty()) {
            homeCacheRows = rows
            homeCacheAt = now
            return newHomePageResponse(rows, false)
        }
        // If Discovery is temporarily unavailable, keep the provider usable.
        return super.getMainPage(page, request)
    }

    // ------------------------------------------------------------ search

    private fun titleVariants(meta: MediaMeta): List<String> {
        val out = mutableListOf(meta.title, meta.originalTitle)
        for (title in listOf(meta.title, meta.originalTitle)) {
            val base = title.split(Regex("\\s+[-\\u2013\\u2014]\\s+|:\\s+")).firstOrNull().orEmpty()
            if (base.isNotBlank() && base != title) out.add(base)
        }
        return out.map { normalizeTitle(it) }.filter { it.isNotBlank() }.distinct()
    }

    private fun titleScore(candidate: String, variants: List<String>): Int {
        val t = normalizeTitle(candidate)
        if (t.isBlank()) return 0
        var best = 0
        for (x in variants) {
            if (x.isBlank()) continue
            if (t == x) {
                best = maxOf(best, 3)
            } else if (t.contains(x) || x.contains(t)) {
                val ratio = minOf(t.length, x.length).toDouble() / maxOf(t.length, x.length)
                if (ratio >= 0.6) best = maxOf(best, 2)
            }
        }
        return best
    }

    private suspend fun searchChannel(channel: Channel, query: String): List<JsonNode> {
        val url = "$api/site/search/page/?include=default&filter%5Benvironment%5D=${channel.env}" +
            "&v=2&q=${enc(query)}&page%5Bnumber%5D=1&page%5Bsize%5D=20"
        val body = try {
            net.get(url, headers = jsonHeaders(channel), timeout = 20).text
        } catch (e: Exception) {
            return emptyList()
        }
        return runCatching { json.readTree(body).path("data").toList() }.getOrDefault(emptyList())
    }

    private suspend fun nativeSearch(query: String): List<SearchResponse> = coroutineScope {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val pages = channels.map { channel -> async { searchChannel(channel, query) } }.awaitAll()
        for ((index, items) in pages.withIndex()) {
            val channel = channels[index]
            for (item in items) {
                if (item.path("type").asText("") != "showpage") continue
                val card = catalogCard(item, channel) ?: continue
                val slug = item.path("slug").asText("").ifBlank { item.path("url").asText("") }
                if (seen.add("${channel.env}:$slug")) out.add(card)
            }
        }
        out
    }

    /** Discovery-only search (no TMDB fallback), used by the PriSynx hub. */
    internal suspend fun searchNative(query: String): List<SearchResponse> =
        if (query.isBlank()) emptyList() else nativeSearch(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // Keep TMDB as a fallback for older links or a temporary Aurora outage.
        return nativeSearch(query).ifEmpty { super.search(query) }
    }

    // ------------------------------------------------------------ show pages

    private data class Show(
        val slug: String,
        val title: String,
        val channel: Channel,
        val parent: String
    )

    private suspend fun findShow(meta: MediaMeta): Show? {
        val variants = titleVariants(meta)
        for (query in variants) {
            var best: Show? = null
            var bestScore = 0
            for (channel in channels) {
                for (item in searchChannel(channel, query)) {
                    if (item.path("slug").asText("").isBlank()) continue
                    if (item.path("type").asText("") != "showpage") continue
                    val score = titleScore(item.path("title").asText(""), variants)
                    if (score > bestScore) {
                        bestScore = score
                        val parent = item.path("parentSlug").asText("")
                            .ifBlank { channel.parent }
                        best = Show(
                            item.path("slug").asText(""),
                            item.path("title").asText(""),
                            channel,
                            parent
                        )
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }

    private suspend fun fetchShowPage(ref: NativeRef): JsonNode? {
        val url = "$api/site/page/${enc(ref.slug)}/?include=default" +
            "&filter%5Benvironment%5D=${ref.channel.env}&v=2&parent_slug=${enc(ref.parent)}"
        val page = try {
            json.readTree(net.get(url, headers = jsonHeaders(ref.channel), timeout = 25).text)
        } catch (e: Exception) {
            return null
        }
        return if (page.path("type").asText("") == "showpage" ||
            page.path("type").asText("") == "showVideosList"
        ) page else null
    }

    private fun itemsOf(block: JsonNode): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        val item = block.path("item")
        if (item.isObject) {
            out.add(item)
            val video = item.path("video")
            if (video.isObject) out.add(video)
        }
        val items = block.path("items")
        if (items.isArray) items.forEach { out.add(it) }
        return out
    }

    private fun showIdOf(page: JsonNode): String {
        val fromAttributes = page.path("attributes").path("showId").asText("")
        if (fromAttributes.isNotBlank()) return fromAttributes
        for (block in page.path("blocks")) {
            val direct = block.path("showId").asText("")
            if (direct.isNotBlank()) return direct
            val nested = block.path("show").path("id").asText("")
            if (nested.isNotBlank()) return nested
            val itemShow = block.path("item").path("show").path("id").asText("")
            if (itemShow.isNotBlank()) return itemShow
        }
        return ""
    }

    private fun findEpisode(page: JsonNode, season: Int, episode: Int): JsonNode? {
        val showId = showIdOf(page)
        for (block in page.path("blocks")) {
            for (item in itemsOf(block)) {
                if (item.path("id").asText("").isBlank()) continue
                if (item.path("videoType").asText("") != "EPISODE") continue
                val itemShow = item.path("show").path("id").asText("")
                if (showId.isNotBlank() && itemShow.isNotBlank() && itemShow != showId) continue
                if (item.path("seasonNumber").asInt(-1) == season &&
                    item.path("episodeNumber").asInt(-1) == episode
                ) {
                    return item
                }
            }
        }
        return null
    }

    private fun collectEpisodes(page: JsonNode, ref: NativeRef): List<Episode> {
        val showId = showIdOf(page)
        val byNumber = LinkedHashMap<String, Episode>()

        for (block in page.path("blocks")) {
            for (item in itemsOf(block)) {
                if (item.path("videoType").asText("") != "EPISODE") continue
                val season = item.path("seasonNumber").asInt(-1)
                val episode = item.path("episodeNumber").asInt(-1)
                if (season <= 0 || episode <= 0) continue
                val itemShow = item.path("show").path("id").asText("")
                if (showId.isNotBlank() && itemShow.isNotBlank() && itemShow != showId) continue

                val key = "$season:$episode"
                if (byNumber.containsKey(key)) continue

                val poster = imageUrl(
                    absUrl(item.path("poster").path("src").asText(""), ref.channel.site),
                    300,
                    450
                )
                val duration = (item.path("videoDuration").asLong(0L) / 60_000L).toInt()
                byNumber[key] = newEpisode(
                    url = episodeUrl(ref.channel, ref.parent, ref.slug, season, episode),
                    initializer = {
                        name = item.path("title").asText("").ifBlank { "Episodio $episode" }
                        this.season = season
                        this.episode = episode
                        description = item.path("description").asText("").ifBlank { null }
                        posterUrl = poster.takeIf { it.isNotBlank() }
                        runTime = duration.takeIf { it > 0 }
                    },
                    fix = false
                )
            }
        }

        return byNumber.values.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: 0 })
        )
    }

    private fun tokenOf(page: JsonNode): String {
        val realm = page.path("userMeta").path("realm")
        return realm.path("X-REALM-IT").asText("")
            .ifBlank { realm.path("X-REALM-DPLAY").asText("") }
    }

    override suspend fun load(url: String): LoadResponse {
        val native = parseNative(url) ?: return super.load(url)
        val page = fetchShowPage(native)
            ?: throw ErrorLoadingException("Programma non trovato")

        val title = page.path("title").asText("").ifBlank { native.slug }
        val poster = imageUrl(mediaUrl(page, listOf("default", "preview")), 400, 600)
        val background = imageUrl(mediaUrl(page, listOf("preview", "default")), 1280, 720)
        val description = page.path("subtitle").asText("")
            .ifBlank { page.path("description").asText("") }
        val year = page.path("datePublished").asText("").take(4).toIntOrNull()
        val tags = page.path("taxonomies").mapNotNull {
            it.path("title").asText("").ifBlank { null }
        }
        val episodes = collectEpisodes(page, native)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster.takeIf { it.isNotBlank() }
            backgroundPosterUrl = background.takeIf { it.isNotBlank() }
            plot = description.takeIf { it.isNotBlank() }
            this.year = year
            this.tags = tags
        }
    }

    // ------------------------------------------------------------ playback

    private suspend fun resolveNative(
        ref: NativeRef,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val season = ref.season ?: return false
        val episode = ref.episode ?: return false
        val page = fetchShowPage(ref) ?: return false
        val token = tokenOf(page)
        if (token.isBlank()) return false
        val item = findEpisode(page, season, episode) ?: return false
        return playEpisode(ref.channel, token, item, subtitleCallback, callback)
    }

    private suspend fun playEpisode(
        channel: Channel,
        token: String,
        item: JsonNode,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = mapOf(
            "deviceInfo" to mapOf(
                "adBlocker" to false,
                "drmSupported" to false,
                "hdrCapabilities" to listOf("SDR"),
                "hwDecodingCapabilities" to emptyList<String>(),
                "soundCapabilities" to listOf("STEREO")
            ),
            "wisteriaProperties" to emptyMap<String, String>(),
            "videoId" to item.path("id").asText("")
        )
        val playback = try {
            json.readTree(
                net.post(
                    "$api/playback/v3/videoPlaybackInfo",
                    headers = headers(
                        channel,
                        mapOf(
                            "Accept" to "*/*",
                            "Content-Type" to "application/json",
                            "X-disco-client" to "WEB:UNKNOWN:wbdatv:2.1.9",
                            "X-disco-params" to "realm=it",
                            "Authorization" to "Bearer $token"
                        )
                    ),
                    data = null,
                    requestBody = json.writeValueAsString(payload)
                        .toRequestBody("application/json".toMediaTypeOrNull()),
                    timeout = 25
                ).text
            )
        } catch (e: Exception) {
            return false
        }

        val streaming = playback.path("data").path("attributes").path("streaming")
        val streamUrl = streaming.firstOrNull {
            it.path("type").asText("") == "hls" &&
                it.path("url").asText("").isNotBlank() &&
                !it.path("protection").path("drmEnabled").asBoolean(false)
        }?.path("url")?.asText("") ?: return false

        val master = try {
            net.get(streamUrl, headers = headers(channel), timeout = 20).text
        } catch (e: Exception) {
            return false
        }
        if (!master.contains("#EXTM3U")) return false
        val quality = qualityFromHeight(bestHeight(master))
        emit(
            callback = callback,
            url = streamUrl,
            label = "${channel.label} ${if (quality > 0) "${quality}p" else "HLS"} \uD83C\uDDEE\uD83C\uDDF9",
            quality = quality,
            type = ExtractorLinkType.M3U8,
            referer = "${channel.site}/",
            headers = headers(channel)
        )
        return true
    }

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!ref.isTv || meta.title.isBlank()) return false
        val show = findShow(meta) ?: return false
        val native = NativeRef(
            channel = show.channel,
            parent = show.parent,
            slug = show.slug,
            season = ref.season ?: 1,
            episode = ref.episode ?: 1
        )
        return resolveNative(native, subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val native = parseNative(data)
        if (native != null && native.season != null && native.episode != null) {
            return resolveNative(native, subtitleCallback, callback)
        }
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }
}
