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

class PartiteLiveProvider : MainAPI() {
    override var mainUrl = "https://www.partite.cc"
    override var name = "Partite.cc Live"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "it"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.None

    private fun card(ev: PartiteEngine.PartiteEvent): SearchResponse {
        val label = if (ev.isLive) "🔴 ${ev.title}" else "${ev.time} · ${ev.title}"
        return newLiveSearchResponse(label, ev.url, TvType.Live, false) {
            posterUrl = ev.homeLogo ?: ev.awayLogo ?: FALLBACK_POSTER
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = PartiteEngine.fetchEvents()
        val rows = mutableListOf<HomePageList>()

        // 1. Live Now first
        val live = all.filter { it.isLive }
        if (live.isNotEmpty()) {
            rows += HomePageList("🔴 In Diretta Ora", live.map(::card), true)
        }

        // 2. Rows grouped by Sport (Calcio first, then by count)
        all.groupBy { it.sport }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<PartiteEngine.PartiteEvent>>> {
                if (it.key.equals("Calcio", true)) 10000 else it.value.size
            })
            .forEach { (sport, evs) ->
                val emoji = when (sport.lowercase()) {
                    "calcio" -> "⚽"
                    "pallacanestro", "basket" -> "🏀"
                    "hockey" -> "🏒"
                    "pallavolo", "volley" -> "🏐"
                    "football americano" -> "🏈"
                    "tennis" -> "🎾"
                    "mma" -> "🥊"
                    else -> "🏆"
                }
                rows += HomePageList("$emoji $sport", evs.map(::card), true)
            }

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return PartiteEngine.fetchEvents().filter { ev ->
            ev.title.lowercase().contains(q) ||
                ev.competition.lowercase().contains(q) ||
                ev.sport.lowercase().contains(q) ||
                ev.homeName.lowercase().contains(q) ||
                ev.awayName.lowercase().contains(q)
        }.map(::card)
    }

    override suspend fun load(url: String): LoadResponse {
        val ev = PartiteEngine.fetchEvents().find { it.url == url }
        val title = ev?.title ?: "Partita Live"
        val channels = PartiteEngine.fetchEventChannels(url)

        val plot = buildString {
            if (ev != null) {
                append(if (ev.isLive) "🔴 IN DIRETTA ORA" else "🕒 Inizio: ${ev.date} ore ${ev.time}")
                append("<br><br>")
                append("🏆 ${ev.competition} (${ev.sport})<br>")
                if (ev.homeName.isNotBlank() && ev.awayName.isNotBlank()) {
                    append("⚔️ ${ev.homeName} vs ${ev.awayName}<br>")
                }
                if (ev.channelsPreview.isNotEmpty()) {
                    append("📺 TV: ${ev.channelsPreview.joinToString(", ")}<br>")
                }
            }
            if (channels.isNotEmpty()) {
                append("<br>📡 <b>Canali streaming attivi:</b> ")
                append(channels.joinToString(", ") { it.name })
            } else {
                append("<br>⚠️ <i>Nessuno stream live rilevato al momento per questo evento (i flussi compaiono a ridosso del calcio d'inizio).</i>")
            }
        }

        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = ev?.homeLogo ?: ev?.awayLogo ?: FALLBACK_POSTER
            backgroundPosterUrl = ev?.awayLogo ?: ev?.homeLogo
            this.plot = plot
            tags = listOfNotNull(if (ev?.isLive == true) "Live" else "In programma", ev?.sport, ev?.competition)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channels = PartiteEngine.fetchEventChannels(data)
        if (channels.isEmpty()) return false

        var emitted = 0
        for (ch in channels) {
            val resolved = PartiteEngine.resolveStream(ch.configUuid, ch.name) ?: continue
            callback(
                newExtractorLink(
                    source = name,
                    name = "${resolved.name} · HLS",
                    url = resolved.streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = resolved.headers
                    this.referer = "https://prohostmedia.top/"
                    this.quality = Qualities.P1080.value
                }
            )
            emitted++
        }
        return emitted > 0
    }

    companion object {
        private const val FALLBACK_POSTER =
            "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/PaCC.png"
    }
}
