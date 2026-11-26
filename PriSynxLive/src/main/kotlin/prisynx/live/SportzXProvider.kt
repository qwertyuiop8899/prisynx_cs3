package prisynx.live

import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * SportzX live channels via v3 category/channel feeds (`cats.json` -> `cats/<cat>.json`).
 * ClearKey DASH is decrypted by ExoPlayer itself; WASM-gated embed pages are skipped.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class, Prerelease::class)
class SportzXProvider : MainAPI() {
    override var mainUrl = "https://es.bandspx.com"
    override var name = "SportzX Live"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "it"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val PREFIX = "sportzx://"
        private const val FALLBACK_POSTER =
            "https://raw.githubusercontent.com/qwertyuiop8899/tvvoo/main/public/tvvoo.png"

        /** Hosts gated by a Referer the feed doesn't always carry. */
        private val FORCED_REFERERS = mapOf(
            "vivo155.com" to "https://player.online909.com/",
            "hereisman.net" to "https://gooz.aapmains.net"
        )
        private val EMBED_HOSTS = listOf("taifood-blog.asia", "embedindia.st")

        @Volatile private var catCache: List<SportzXEngine.SportzCategory> = emptyList()
        @Volatile private var catCacheAt = 0L
        private val chanCache = HashMap<String, Pair<Long, List<SportzXEngine.SportzCatalogChannel>>>()
        private const val TTL = 90_000L

        suspend fun categories(): List<SportzXEngine.SportzCategory> {
            val now = System.currentTimeMillis()
            if (catCache.isNotEmpty() && now - catCacheAt < TTL) return catCache
            val list = runCatching { SportzXEngine.fetchCategories() }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                catCache = list
                catCacheAt = now
                LiveSettings.rememberSports(list.map { it.title }.toSet())
            }
            return list.ifEmpty { catCache }
        }

        suspend fun channelsFor(cat: SportzXEngine.SportzCategory): List<SportzXEngine.SportzCatalogChannel> {
            val now = System.currentTimeMillis()
            synchronized(chanCache) {
                chanCache[cat.catLink]?.let { (at, list) ->
                    if (now - at < TTL && list.isNotEmpty()) return list
                }
            }
            val list = runCatching { SportzXEngine.fetchCategoryChannels(cat) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                synchronized(chanCache) { chanCache[cat.catLink] = now to list }
            }
            return list
        }

        private fun encodeRef(catLink: String, chId: String, adsLimit: Int): String =
            "$PREFIX${URLEncoder.encode(catLink, "UTF-8")}/$chId/$adsLimit"

        private fun parseRef(url: String): Triple<String, String, Int> {
            val raw = url.substringAfterLast(PREFIX).substringAfterLast("sportzx:").trim('/')
            val parts = raw.split('/')
            return if (parts.size >= 3) {
                val ads = parts.last().toIntOrNull() ?: 0
                val chId = parts[parts.size - 2]
                val catLink = URLDecoder.decode(parts.dropLast(2).joinToString("/"), "UTF-8")
                Triple(catLink, chId, ads)
            } else {
                Triple("", raw, 0)
            }
        }
    }

    private fun card(ch: SportzXEngine.SportzCatalogChannel): SearchResponse =
        newLiveSearchResponse(ch.title, encodeRef(ch.catLink, ch.id, ch.adsLimit), TvType.Live, false) {
            posterUrl = ch.image ?: FALLBACK_POSTER
        }

    private suspend fun enabledCategoryChannels(): List<Pair<SportzXEngine.SportzCategory, List<SportzXEngine.SportzCatalogChannel>>> {
        val cats = categories()
            .filter { LiveSettings.isSportEnabled(it.title) }
            .sortedByDescending { if (it.title.contains("sport", true)) 1 else 0 }
        return coroutineScope {
            cats.map { cat ->
                async { cat to channelsFor(cat) }
            }.awaitAll().filter { it.second.isNotEmpty() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val rows = enabledCategoryChannels().map { (cat, list) ->
            HomePageList("🏆 ${cat.title}", list.map(::card), false)
        }
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return enabledCategoryChannels().flatMap { (_, list) -> list }.filter { ch ->
            ch.title.lowercase().contains(q) ||
                ch.category.lowercase().contains(q) ||
                ch.formats.any { it.lowercase().contains(q) }
        }.map(::card)
    }

    override suspend fun load(url: String): LoadResponse {
        val (catLink, chId, adsLimit) = parseRef(url)
        val cat = categories().find { it.catLink == catLink }
        val ch = cat?.let { channelsFor(it).find { c -> c.id == chId } }
        val title = ch?.title ?: "Canale SportzX $chId"
        val plot = buildString {
            append("🏆 ${ch?.category ?: catLink.ifBlank { "SportzX" }}<br>")
            if (!ch?.formats.isNullOrEmpty()) {
                append("📺 ${ch!!.formats.take(12).joinToString(" · ")}<br>")
            }
            append("<br>I canali disponibili si scelgono dal player: se uno non parte prova il successivo.")
        }
        return newLiveStreamLoadResponse(title, encodeRef(catLink, chId, adsLimit), encodeRef(catLink, chId, adsLimit)) {
            posterUrl = ch?.image ?: cat?.image ?: FALLBACK_POSTER
            backgroundPosterUrl = ch?.image ?: cat?.image
            this.plot = plot
            tags = listOfNotNull("Live", ch?.category ?: cat?.title)
        }
    }

    private fun host(url: String) = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
    private fun hostMatches(h: String, d: String) = h == d || h.endsWith(".$d")

    private fun b64url(hex: String) =
        Base64.encodeToString(SportzXEngine.hex(hex), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (_, chId, adsLimit) = parseRef(data)
        val channels = runCatching { SportzXEngine.fetchChannelStreams(chId, adsLimit) }.getOrDefault(emptyList())
        var emitted = 0
        val decodedApis = channels.map { it to SportzXEngine.deobfApi(it.api, adsLimit) }
        val ordered = decodedApis.sortedBy { if (it.second.contains(':')) 1 else 0 }

        ordered.forEachIndexed { index, (ch, api) ->
            val (url, h) = SportzXEngine.parseLink(ch.link ?: return@forEachIndexed)
            if (!url.startsWith("http")) return@forEachIndexed
            val hostName = host(url)
            if (EMBED_HOSTS.any { hostMatches(hostName, it) } ||
                (url.contains("/embed/", true) && !Regex("\\.(m3u8|mpd|ts|mp4)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url))
            ) return@forEachIndexed

            val isDrm = api.contains(':')
            if (isDrm && !LiveSettings.sportShowDrm) return@forEachIndexed
            val isDash = url.contains(".mpd", true)
            val title = ch.title?.trim()?.takeIf { it.isNotBlank() } ?: "Canale ${index + 1}"

            val referer = h["referer"]?.takeIf { it.isNotBlank() }
                ?: FORCED_REFERERS.entries.firstOrNull { hostMatches(hostName, it.key) }?.value
            val headers = buildMap {
                put("User-Agent", h["user-agent"]?.takeIf { it.isNotBlank() } ?: SportzXEngine.BROWSER_UA)
                referer?.let { put("Referer", it) }
                (h["origin"]?.takeIf { it.isNotBlank() } ?: referer?.trimEnd('/'))?.let { put("Origin", it) }
                h["cookie"]?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }

            if (isDrm && isDash) {
                val (kid, key) = api.split(",").first().split(":").map { it.trim() }.let {
                    (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "")
                }
                if (kid.length < 32 || key.length < 32) return@forEachIndexed
                callback(
                    newDrmExtractorLink(
                        source = name,
                        name = "$title · DASH 🔐",
                        url = url,
                        type = ExtractorLinkType.DASH,
                        uuid = CLEARKEY_DRM_UUID
                    ) {
                        this.kid = b64url(kid)
                        this.key = b64url(key)
                        this.headers = headers
                        this.referer = referer.orEmpty()
                        this.quality = Qualities.P1080.value
                    }
                )
                emitted++
            } else {
                val type = when {
                    isDash -> ExtractorLinkType.DASH
                    url.contains(".mp4", true) -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.M3U8
                }
                callback(
                    newExtractorLink(name, "$title · ${if (type == ExtractorLinkType.M3U8) "HLS" else "Stream"}", url, type) {
                        this.headers = headers
                        this.referer = referer.orEmpty()
                        this.quality = Qualities.P1080.value
                    }
                )
                emitted++
            }
        }
        return emitted > 0
    }
}
