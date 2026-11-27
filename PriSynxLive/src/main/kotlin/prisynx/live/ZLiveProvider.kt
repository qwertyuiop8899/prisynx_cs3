package prisynx.live

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import java.net.URLEncoder

/**
 * ZLive (zlive.st) live TV & sports provider.
 *
 * Groups all channels by their native category (`sport`), plus a dedicated
 * "🇮🇹 Italia" row matching channels whose name contains " it".
 * Uses [ZLiveEngine.interceptorFor] to unwrap disguised PNG/WebP segments
 * directly inside CloudStream's player.
 */
class ZLiveProvider : MainAPI() {
    override var mainUrl = ZLiveEngine.SITE_BASE
    override var name = "ZLive"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "it"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val CH_PREFIX = "https://zlive.st/_cs/ch/"
        private const val FALLBACK_POSTER =
            "https://raw.githubusercontent.com/qwertyuiop8899/tvvoo/main/public/tvvoo.png"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun flagEmoji(code: String): String {
            val c = code.trim().uppercase()
            if (c.length != 2 || !c[0].isLetter() || !c[1].isLetter()) return ""
            val a = 0x1F1E6 + (c[0].code - 'A'.code)
            val b = 0x1F1E6 + (c[1].code - 'A'.code)
            return String(Character.toChars(a)) + String(Character.toChars(b))
        }

        private fun catEmoji(cat: String): String = when (cat.lowercase()) {
            "sports" -> "🏆"
            "f1", "formula 1", "motorsport" -> "🏎️"
            "kids", "cartoon" -> "🧸"
            "entertainment" -> "🎬"
            "news" -> "📰"
            "ufc", "boxing" -> "🥊"
            "other" -> "📺"
            else -> "📡"
        }

        private fun qualityValue(q: String): Int = when {
            q.contains("4k", true) || q.contains("2160") -> Qualities.P2160.value
            q.contains("fhd", true) || q.contains("1080") -> Qualities.P1080.value
            q.contains("hd", true) || q.contains("720") -> Qualities.P720.value
            q.contains("sd", true) || q.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        ZLiveEngine.interceptorFor(extractorLink)

    private fun card(ch: ZLiveEngine.ZChannel): SearchResponse {
        val flag = flagEmoji(ch.flag)
        val qTag = ch.quality.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val label = listOf(flag, ch.name).filter { it.isNotBlank() }.joinToString(" ") + qTag
        val url = "$CH_PREFIX${enc(ch.id)}"
        return newLiveSearchResponse(label, url, TvType.Live, false) {
            posterUrl = FALLBACK_POSTER
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = ZLiveEngine.fetchChannels()
        val rows = mutableListOf<HomePageList>()

        if (LiveSettings.zliveItalia) {
            val italian = all.filter { ZLiveEngine.isItalian(it) }
                .sortedWith(compareBy<ZLiveEngine.ZChannel>({ it.order }, { it.name.lowercase() }))
            if (italian.isNotEmpty()) {
                rows += HomePageList("🇮🇹 Italia", italian.map(::card), false)
            }
        }

        val grouped = all.groupBy { it.category }.entries
            .filter { (cat, _) -> LiveSettings.isZliveCatEnabled(cat) }
            .sortedWith(
                compareBy<Map.Entry<String, List<ZLiveEngine.ZChannel>>>(
                    { it.key.equals("Other", true) },
                    { -it.value.size },
                    { it.key.lowercase() }
                )
            )

        for ((cat, list) in grouped) {
            val ordered = list.sortedWith(compareBy<ZLiveEngine.ZChannel>({ it.order }, { it.name.lowercase() }))
            if (ordered.isNotEmpty()) {
                rows += HomePageList("${catEmoji(cat)} $cat", ordered.map(::card), false)
            }
        }

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = ZLiveEngine.fetchChannels()
        return all.filter { ch ->
            ch.name.lowercase().contains(q) ||
                ch.category.lowercase().contains(q) ||
                ch.region.lowercase().contains(q) ||
                ch.tagline.lowercase().contains(q) ||
                (q in setOf("it", "ita", "italia") && ZLiveEngine.isItalian(ch))
        }.sortedWith(
            compareByDescending<ZLiveEngine.ZChannel> { ZLiveEngine.isItalian(it) }
                .thenBy { it.name.lowercase() }
        ).map(::card)
    }

    private fun idOf(url: String): String {
        val raw = url.substringAfter("/_cs/ch/").substringBefore('?').trim('/')
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = idOf(url)
        val ch = ZLiveEngine.cachedChannel(id) ?: ZLiveEngine.fetchChannels().find { it.id == id }
        val epg = runCatching { ZLiveEngine.fetchEpg()[id] }.getOrNull()
        val title = ch?.name ?: id
        val plot = buildString {
            val header = listOfNotNull(
                ch?.flag?.let(::flagEmoji)?.takeIf { it.isNotBlank() },
                ch?.category?.takeIf { it.isNotBlank() },
                ch?.region?.takeIf { it.isNotBlank() },
                ch?.quality?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (header.isNotBlank()) append("📺 $header<br>")
            if (!ch?.tagline.isNullOrBlank()) append("ℹ️ ${ch!!.tagline}<br>")
            if (epg != null) {
                if (epg.nowTitle.isNotBlank()) {
                    append("<br>🔴 <b>In onda:</b> ${epg.nowTitle}")
                    if (epg.nowDesc.isNotBlank()) append("<br>${epg.nowDesc}")
                }
                if (epg.nextTitle.isNotBlank()) {
                    append("<br>⏭️ <b>A seguire:</b> ${epg.nextTitle}")
                }
            }
        }
        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = FALLBACK_POSTER
            this.plot = plot.ifBlank { "📺 Canale TV in diretta da ZLive" }
            tags = listOfNotNull("Live", "ZLive", ch?.category, ch?.quality?.takeIf { it.isNotBlank() })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idOf(data)
        val ch = ZLiveEngine.cachedChannel(id) ?: ZLiveEngine.fetchChannels().find { it.id == id }
        val key = ch?.sourceKey ?: id
        val streamUrl = ZLiveEngine.resolveStream(key) ?: return false
        val qStr = ch?.quality.orEmpty()
        val label = listOfNotNull(
            ch?.name ?: id,
            qStr.takeIf { it.isNotBlank() },
            "ZLive"
        ).joinToString(" · ")

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = ZLiveEngine.MEDIA_HEADERS
                this.referer = "${ZLiveEngine.SITE_BASE}/"
                this.quality = qualityValue(qStr)
                this.extractorData = "${ZLiveEngine.DATA_PREFIX}$key"
            }
        )
        return true
    }
}
