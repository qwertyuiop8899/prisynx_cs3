package prisynx.live

import android.util.Base64
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
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SportzX live sports: one card per event, every broadcaster of the event is a link.
 * ClearKey DASH is decrypted by ExoPlayer itself; WASM-gated embed pages are skipped.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
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
            "hereisman.net" to "https://gooz.aapmains.net/"
        )
        private val EMBED_HOSTS = listOf("taifood-blog.asia", "embedindia.st")

        @Volatile private var cache: List<SportzXEngine.SportzEvent> = emptyList()
        @Volatile private var cacheAt = 0L

        suspend fun events(): List<SportzXEngine.SportzEvent> {
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now - cacheAt < 90_000) return cache
            val list = SportzXEngine.fetchEvents()
            if (list.isNotEmpty()) { cache = list; cacheAt = now }
            return list.ifEmpty { cache }
        }

        private val ROME = TimeZone.getTimeZone("Europe/Rome")
        private fun fmt(pattern: String, t: Long) =
            SimpleDateFormat(pattern, Locale.ITALIAN).apply { timeZone = ROME }.format(Date(t))
    }

    private fun visible(all: List<SportzXEngine.SportzEvent>) = all.filter {
        LiveSettings.isSportEnabled(it.category) && (!LiveSettings.sportOnlyLive || it.isLive)
    }

    private fun card(ev: SportzXEngine.SportzEvent): SearchResponse {
        val label = if (ev.isLive) "🔴 ${ev.title}" else "${fmt("dd/MM HH:mm", ev.start)} · ${ev.title}"
        return newLiveSearchResponse(label, "$PREFIX${ev.id}", TvType.Live, false) {
            posterUrl = ev.image ?: FALLBACK_POSTER
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val evs = visible(events())
        val rows = mutableListOf<HomePageList>()
        evs.filter { it.isLive }.takeIf { it.isNotEmpty() }?.let {
            rows += HomePageList("🔴 Sport in diretta", it.map(::card), true)
        }
        evs.groupBy { it.category }.entries
            .sortedByDescending { e -> e.value.size }
            .forEach { (cat, list) -> rows += HomePageList("🏆 $cat", list.map(::card), true) }
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return visible(events()).filter { ev ->
            ev.title.lowercase().contains(q) || ev.category.lowercase().contains(q) ||
                ev.eventName.lowercase().contains(q) || ev.teams.any { it.lowercase().contains(q) }
        }.map(::card)
    }

    private fun idOf(url: String) = url.substringAfterLast("sportzx://").substringAfterLast("sportzx:").trim('/')

    override suspend fun load(url: String): LoadResponse {
        val id = idOf(url)
        val ev = events().find { it.id == id }
        val title = ev?.title ?: "Evento sportivo $id"
        val plot = buildString {
            if (ev == null) return@buildString
            append(if (ev.isLive) "🔴 IN DIRETTA ORA" else "🕒 Inizio: ${fmt("EEEE dd/MM HH:mm", ev.start)}")
            ev.end?.let { append(" – fine prevista ${fmt("HH:mm", it)}") }
            append("<br><br>")
            if (ev.eventName.isNotBlank() && ev.eventName != ev.title) append("🏷️ ${ev.eventName}<br>")
            if (ev.teams.size == 2) append("⚔️ ${ev.teams[0]} vs ${ev.teams[1]}<br>")
            append("🏆 ${ev.category}<br><br>")
            append("I canali disponibili si scelgono dal player: se uno non parte prova il successivo.")
        }
        return newLiveStreamLoadResponse(title, "$PREFIX$id", "$PREFIX$id") {
            posterUrl = ev?.image ?: FALLBACK_POSTER
            backgroundPosterUrl = ev?.image
            this.plot = plot
            tags = listOfNotNull(if (ev?.isLive == true) "Live" else "In programma", ev?.category)
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
        val channels = runCatching { SportzXEngine.fetchChannels(idOf(data)) }.getOrDefault(emptyList())
        var emitted = 0
        // Plain streams first, ClearKey ones after.
        val ordered = channels.sortedBy { if (it.api.orEmpty().contains(':')) 1 else 0 }
        ordered.forEachIndexed { index, ch ->
            val (url, h) = SportzXEngine.parseLink(ch.link ?: return@forEachIndexed)
            if (!url.startsWith("http")) return@forEachIndexed
            val hostName = host(url)
            if (EMBED_HOSTS.any { hostMatches(hostName, it) } ||
                (url.contains("/embed/", true) && !Regex("\\.(m3u8|mpd|ts|mp4)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url))
            ) return@forEachIndexed

            val api = ch.api.orEmpty().trim()
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
