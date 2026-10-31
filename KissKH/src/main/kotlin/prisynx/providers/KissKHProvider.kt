package prisynx.providers

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import prisynx.providers.core.LocalSubtitle
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TMDB_API
import prisynx.providers.core.TMDB_KEY
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.bestHeight
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromHeight
import prisynx.providers.core.qualityLabel

/**
 * KissKH: Asian dramas and movies with native Italian/English subtitles and
 * optional DeepL translation.
 *
 * The native KissKH catalogue and search use KissKH's own ids and endpoints.
 * TMDB matching is only used when this provider is asked to resolve a title
 * from an external catalogue. Streams are resolved with a per-episode token.
 */
class KissKHProvider : TmdbProvider("KissKH", KissKHSettings.DEFAULT_BASE) {
    /** One row of the KissKH home page. The hub reuses this list for its settings. */
    data class Catalog(val id: String, val label: String, val type: Int, val country: Int)

    companion object {
        private const val TAG = "KissKH"

        val catalogs: List<Catalog> = listOf(
            Catalog("kr_drama", "Drama coreani", 1, 2),
            Catalog("kr_movie", "Film coreani", 2, 2),
            Catalog("jp_drama", "Drama giapponesi", 1, 3),
            Catalog("jp_movie", "Film giapponesi", 2, 3),
            Catalog("th_drama", "Drama thailandesi", 1, 5),
            Catalog("th_movie", "Film thailandesi", 2, 5),
            Catalog("anime", "Anime", 3, 0)
        )
    }

    private data class KissEpisode(
        val id: String,
        val season: Int,
        val episode: Int,
        val title: String
    )

    private data class KissRef(val seriesId: String, val episodeId: String)

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )

    // ---------------------------------------------------------------- catalogue

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = coroutineScope {
            catalogs.map { catalog ->
                async {
                    val items = KissKHApi.list(catalog.type, catalog.country, page, 24)
                    val cards = items.mapNotNull { item ->
                        if (KissKHFilters.isAdult(item, catalog.type, catalog.country)) null
                        else searchResponse(item)
                    }
                    val row = cards.takeIf { it.isNotEmpty() }?.let { HomePageList(catalog.label, it) }
                    row to (items.size >= 24)
                }
            }.awaitAll()
        }
        val rows = results.mapNotNull { it.first }
        return newHomePageResponse(rows, results.any { it.second })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val native = searchNative(query)
        return native.ifEmpty { super.search(query) }
    }

    /** KissKH-native results only: the hub already searches TMDB by itself. */
    internal suspend fun searchNative(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return KissKHApi.search(query)
            .mapNotNull { item ->
                val type = item.path("type").asInt(0)
                if (KissKHFilters.isAdult(item, type, 0)) null else searchResponse(item)
            }
    }

    private fun searchResponse(item: JsonNode): SearchResponse? {
        val id = item.path("id").asText("").trim()
        val title = item.path("title").asText("").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val type = when (item.path("type").asInt(1)) {
            2 -> TvType.Movie
            3 -> TvType.Anime
            else -> TvType.TvSeries
        }
        val url = "${KissKHSettings.baseUrl}/Drama/$id"
        val poster = item.path("thumbnail").asText("")
            .ifBlank { item.path("poster").asText("") }
        val year = KissKHText.yearOf(item.path("releaseDate").asText(""))
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, type, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }
                this.year = year
            }
        }
    }

    // -------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        val seriesId = seriesIdFromUrl(url) ?: return super.load(url)
        val details = KissKHApi.details(seriesId)
            ?: throw ErrorLoadingException("KissKH non raggiungibile")

        val type = details.path("type").asInt(1)
        val country = details.path("country").asInt(0)
        if (KissKHFilters.isAdult(details, type, country)) {
            throw ErrorLoadingException("Titolo non disponibile")
        }

        val title = details.path("title").asText("").ifBlank { "KissKH" }
        val poster = details.path("thumbnail").asText("")
            .ifBlank { details.path("poster").asText("") }
        val plot = details.path("description").asText("").ifBlank { null }
        val year = KissKHText.yearOf(details.path("releaseDate").asText(""))
        val episodes = episodesOf(details)
        if (episodes.isEmpty()) throw ErrorLoadingException("Nessun episodio disponibile")

        if (type == 2) {
            val episode = episodes.first()
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                "kisskh:$seriesId:${episode.id}"
            ) {
                posterUrl = poster.takeIf { it.isNotBlank() }
                backgroundPosterUrl = poster.takeIf { it.isNotBlank() }
                this.plot = plot
                this.year = year
            }
        }

        val tvType = if (type == 3) TvType.Anime else TvType.TvSeries
        val episodeList = episodes.map { episode ->
            newEpisode(
                url = "kisskh:$seriesId:${episode.id}",
                initializer = {
                    name = episode.title
                    season = episode.season
                    this.episode = episode.episode
                    posterUrl = poster.takeIf { it.isNotBlank() }
                },
                fix = false
            )
        }
        return newTvSeriesLoadResponse(title, url, tvType, episodeList) {
            posterUrl = poster.takeIf { it.isNotBlank() }
            backgroundPosterUrl = poster.takeIf { it.isNotBlank() }
            this.plot = plot
            this.year = year
        }
    }

    private fun seriesIdFromUrl(url: String): String? {
        val value = url.trim()
        Regex("kisskh:(\\d+)").find(value)?.groupValues?.get(1)?.let { return it }
        Regex("[?&]id=(\\d+)").find(value)?.groupValues?.get(1)?.let { return it }
        Regex("/Drama/(?:[^/?#]+/)?(\\d+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun episodesOf(details: JsonNode): List<KissEpisode> {
        val episodes = mutableListOf<KissEpisode>()
        for (node in details.path("episodes")) {
            val rawId = node.path("id").asText("")
                .ifBlank { node.path("episodeId").asText("") }
            val id = numericId(rawId)
            if (id.isEmpty()) continue
            val number = node.path("episode").asInt(0).takeIf { it > 0 }
                ?: node.path("number").asInt(0).takeIf { it > 0 }
                ?: 1
            val season = node.path("season").asInt(1).takeIf { it > 0 } ?: 1
            val title = node.path("title").asText("").ifBlank { "Episodio $number" }
            episodes.add(KissEpisode(id, season, number, title))
        }
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun numericId(value: String): String {
        val tail = value.substringAfterLast(':').trim()
        if (tail.isNotEmpty() && tail.all { it.isDigit() }) return tail
        return value.filter { it.isDigit() }
    }

    private fun pickEpisode(
        episodes: List<KissEpisode>,
        season: Int,
        episode: Int,
        isMovie: Boolean
    ): KissEpisode? {
        if (episodes.isEmpty()) return null
        if (isMovie) return episodes.first()
        return episodes.firstOrNull { it.season == season && it.episode == episode }
            ?: episodes.firstOrNull { it.episode == episode }
            ?: episodes.getOrNull(episode - 1)
    }

    // ---------------------------------------------------------------- playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val direct = parseRef(data)
        if (direct != null) {
            return try {
                val details = KissKHApi.details(direct.seriesId)
                resolveEpisode(
                    direct.seriesId,
                    direct.episodeId,
                    contextOf(details),
                    subtitleCallback,
                    callback
                )
            } catch (e: Exception) {
                Log.w(TAG, "direct loadLinks failed: ${e.message}")
                false
            }
        }
        return super.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val match = findBestMatch(
                meta,
                englishTitle(ref),
                ref.isTv,
                ref.season ?: 1
            ) ?: return false
            val seriesId = match.path("id").asText("").trim()
            if (seriesId.isEmpty()) return false

            val details = KissKHApi.details(seriesId) ?: return false
            val type = details.path("type").asInt(if (ref.isTv) 1 else 2)
            val country = details.path("country").asInt(0)
            if (KissKHFilters.isAdult(details, type, country)) return false

            val episode = pickEpisode(
                episodesOf(details),
                ref.season ?: 1,
                ref.episode ?: 1,
                !ref.isTv
            ) ?: return false
            resolveEpisode(
                seriesId,
                episode.id,
                contextOf(details),
                subtitleCallback,
                callback
            )
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed: ${e.message}")
            false
        }
    }

    private fun parseRef(data: String): KissRef? {
        val match = Regex("^kisskh:(\\d+):(\\d+)$").find(data.trim()) ?: return null
        return KissRef(match.groupValues[1], match.groupValues[2])
    }

    private suspend fun resolveEpisode(
        seriesId: String,
        episodeId: String,
        context: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = KissKHApi.episode(episodeId) ?: return false
        Log.i(TAG, "resolved $seriesId:$episodeId")

        val isHls = Regex("\\.m3u8(\\?.*)?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(stream.url) || stream.url.contains("/master/", ignoreCase = true)
        var quality = 0
        if (isHls) {
            val master = try {
                net.get(stream.url, headers = KissKHApi.headers(), timeout = 15).text
            } catch (e: Exception) {
                ""
            }
            quality = qualityFromHeight(bestHeight(master))
        } else {
            quality = Regex("(2160|1080|720|480)p", RegexOption.IGNORE_CASE)
                .find(stream.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        val label = buildString {
            append("KissKH ")
            append(if (isHls) "HLS" else "MP4")
            if (quality > 0) append(' ').append(qualityLabel(quality))
        }
        emit(
            callback = callback,
            url = stream.url,
            label = label,
            quality = quality,
            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            referer = "${KissKHSettings.baseUrl}/",
            headers = KissKHApi.headers()
        )

        if (stream.subApiUrl != null) {
            emitSubtitles(stream.subApiUrl, context, subtitleCallback)
        }
        return true
    }

    // --------------------------------------------------------------- subtitles

    private suspend fun emitSubtitles(
        subApiUrl: String,
        context: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subtitles = KissKHApi.subtitles(subApiUrl)
        if (subtitles.isEmpty()) return

        var english: KissSubtitle? = null
        for (subtitle in subtitles) {
            if (subtitle.lang == "en") english = subtitle

            // I file normali vanno al player tramite il relay; quelli cifrati
            // (legacy) vengono decifrati qui e pubblicati sul relay, così la TV
            // non deve né contattare il CDN né leggere dal server locale.
            val url = if (subtitle.legacy) {
                val text = KissKHSubtitle.fetchText(subtitle.url) ?: continue
                KissKHApi.publishSubtitle(text) ?: continue
            } else {
                KissKHApi.proxied(subtitle.url)
            }
            subtitleCallback(newSubtitleFile(subtitle.lang, url) {
                headers = KissKHApi.headers()
            })
        }

        val target = KissKHText.sanitizeTargetLang(KissKHSettings.targetLang) ?: "it"
        val source = english ?: subtitles.firstOrNull { it.lang != target } ?: return
        if (target.substringBefore('-') == "en" || !KissKHSettings.translationActive()) return

        val translatedUrl = if (KissKHApi.hasRelay()) {
            val text = KissKHSubtitle.translateUrl(
                url = source.url,
                from = source.lang,
                to = target,
                context = context,
                key = KissKHSettings.deeplKey
            ) ?: return
            KissKHApi.publishSubtitle(text)
        } else {
            LocalSubtitle.publishLazy("srt") {
                KissKHSubtitle.translateUrl(
                    url = source.url,
                    from = source.lang,
                    to = target,
                    context = context,
                    key = KissKHSettings.deeplKey
                )
            }
        } ?: return

        subtitleCallback(newSubtitleFile(KissKHText.deeplLabel(target), translatedUrl))
    }

    // ------------------------------------------------------------- matching

    private suspend fun findBestMatch(
        meta: MediaMeta,
        english: String,
        isTv: Boolean,
        season: Int
    ): JsonNode? {
        val queries = linkedSetOf<String>()
        for (value in listOf(meta.title, meta.originalTitle, english)) {
            val clean = value.trim()
            if (clean.isNotEmpty()) queries.add(clean)
        }
        if (queries.isEmpty()) return null

        val types = if (isTv) listOf(0, 1, 3) else listOf(2, 0)
        val candidates = LinkedHashMap<String, JsonNode>()
        for (query in queries) {
            for (type in types) {
                for (item in KissKHApi.search(query, type)) {
                    val id = item.path("id").asText("")
                    if (id.isNotEmpty()) candidates.putIfAbsent(id, item)
                }
            }
        }

        if (candidates.isEmpty()) {
            val listTypes = if (isTv) listOf(1, 3) else listOf(2)
            val cleanQuery = KissKHText.cleanTitle(queries.first())
            val words = cleanQuery.split(' ').filter { it.length > 2 }
            for (page in 1..2) {
                for (type in listTypes) {
                    for (item in KissKHApi.list(type, 0, page, 100)) {
                        val title = KissKHText.cleanTitle(item.path("title").asText(""))
                        val hit = title.contains(cleanQuery) ||
                            (words.size >= 2 && words.count { title.contains(it) } >= 2)
                        if (!hit) continue
                        val id = item.path("id").asText("")
                        if (id.isNotEmpty()) candidates.putIfAbsent(id, item)
                    }
                }
            }
        }

        var best: JsonNode? = null
        var bestScore = 0.0
        for (item in candidates.values) {
            val type = item.path("type").asInt(if (isTv) 1 else 2)
            if (KissKHFilters.isAdult(item, type, 0)) continue
            val score = scoreItem(item, queries, meta.year, isTv, season)
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }

        val chosen = best ?: return null
        if (bestScore < 0.45) {
            Log.i(TAG, "no confident match for \"${queries.first()}\" (best ${"%.2f".format(bestScore)})")
            return null
        }
        Log.i(
            TAG,
            "matched \"${chosen.path("title").asText("")}\" " +
                "(${chosen.path("id").asText("")}) score=${"%.2f".format(bestScore)}"
        )
        return chosen
    }

    private fun scoreItem(
        item: JsonNode,
        queries: Set<String>,
        year: Int?,
        isTv: Boolean,
        season: Int
    ): Double {
        var best = 0.0
        for (query in queries) {
            best = maxOf(best, KissKHText.titleSimilarity(query, item.path("title").asText("")))
        }
        val itemYear = KissKHText.yearOf(item.path("releaseDate").asText(""))
        if (year != null && itemYear != null && kotlin.math.abs(itemYear - year) <= 1) {
            best += 0.08
        }
        val type = item.path("type").asInt(0)
        if (type > 0) {
            if (isTv && type == 2) best -= 0.35
            if (!isTv && type != 2) best -= 0.35
        }
        if (isTv && season > 0) {
            val itemSeason = KissKHText.seasonOfTitle(item.path("title").asText(""))
            if (itemSeason > 0) {
                best += if (itemSeason == season) 0.25 else -0.5
            }
        }
        return best
    }

    private suspend fun englishTitle(ref: MediaRef): String {
        val type = if (ref.isTv) "tv" else "movie"
        val node = fetchJson("$TMDB_API/$type/${ref.id}?api_key=$TMDB_KEY&language=en-US")
            ?: return ""
        return if (ref.isTv) node.path("name").asText("") else node.path("title").asText("")
    }

    private fun contextOf(details: JsonNode?): String? {
        if (details == null) return null
        val bits = listOf(
            details.path("title").asText(""),
            details.path("description").asText("")
        ).filter { it.isNotBlank() }
        return bits.joinToString(". ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1200)
            .ifBlank { null }
    }
}
