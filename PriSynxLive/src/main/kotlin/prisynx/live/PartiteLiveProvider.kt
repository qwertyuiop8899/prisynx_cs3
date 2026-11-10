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

    private fun poster(ev: PartiteEngine.PartiteEvent) =
        Posters.match(ev.homeName, ev.awayName, ev.title, if (ev.isLive) "LIVE" else "${ev.date} ${ev.time}".trim())

    private fun card(ev: PartiteEngine.PartiteEvent): SearchResponse {
        val label = if (ev.isLive) "🔴 ${ev.title}" else "${ev.date} ${ev.time} · ${ev.title}".trim()
        return newLiveSearchResponse(label, ev.url, TvType.Live, false) { posterUrl = poster(ev) }
    }

    private fun channelCard(c: PartiteEngine.ChannelInfo): SearchResponse =
        newLiveSearchResponse(c.name, "$mainUrl/tv-calcio-live/${c.slug}", TvType.Live, false) {
            posterUrl = Posters.portrait(c.name, c.country)
        }

    private fun emoji(sport: String) = when (sport.lowercase()) {
        "calcio" -> "⚽"; "pallacanestro", "basket" -> "🏀"; "hockey" -> "🏒"
        "pallavolo", "volley" -> "🏐"; "football americano" -> "🏈"; "tennis" -> "🎾"; "mma" -> "🥊"
        else -> "🏆"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = mutableListOf<HomePageList>()

        if (LiveSettings.paLive || LiveSettings.paToday) {
            val home = PartiteEngine.fetchEvents()
            if (LiveSettings.paLive) {
                val live = (PartiteEngine.fetchLiveSports() + home).filter { it.isLive }.distinctBy { it.id }
                if (live.isNotEmpty()) rows += HomePageList("🔴 In diretta ora", live.map(::card), true)
            }
            if (LiveSettings.paToday) {
                home.filter { !it.isLive }.groupBy { it.sport }.entries
                    .sortedByDescending { if (it.key.equals("Calcio", true)) 10000 else it.value.size }
                    .forEach { (sport, evs) -> rows += HomePageList("${emoji(sport)} $sport · in programma", evs.map(::card), true) }
            }
        }

        if (LiveSettings.paLeagues) {
            val names = PartiteEngine.fetchLeagueCatalog().associate { it.key to it.name }
            for (key in LiveSettings.selectedLeagues().sortedBy { names[it] ?: it }) {
                val (title, evs) = PartiteEngine.fetchLeague(key)
                if (evs.isNotEmpty()) rows += HomePageList("🏆 $title", evs.map(::card), true)
            }
        }

        if (LiveSettings.paChannels) {
            PartiteEngine.fetchChannelCatalog()
                .filter { LiveSettings.channelSelected(it.slug, it.country) }
                .groupBy { it.country }
                .forEach { (country, list) -> rows += HomePageList("📺 Canali $country", list.map(::channelCard), true) }
        }

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val chans = PartiteEngine.fetchChannelCatalog().filter { it.name.lowercase().contains(q) }.map(::channelCard)
        val evs = (PartiteEngine.fetchLiveSports() + PartiteEngine.fetchEvents() +
            LiveSettings.selectedLeagues().flatMap { PartiteEngine.fetchLeague(it).second }).distinctBy { it.id }
        return chans + evs.filter { ev ->
            ev.title.lowercase().contains(q) ||
                ev.competition.lowercase().contains(q) ||
                ev.sport.lowercase().contains(q) ||
                ev.homeName.lowercase().contains(q) ||
                ev.awayName.lowercase().contains(q)
        }.map(::card)
    }

    private fun slugOf(url: String) = url.substringAfter("/tv-calcio-live/", "").trim('/').takeIf { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse {
        slugOf(url)?.let { slug ->
            val c = PartiteEngine.fetchChannelCatalog().find { it.slug == slug }
            val name = c?.name ?: slug
            return newLiveStreamLoadResponse(name, url, url) {
                posterUrl = Posters.portrait(name, c?.country)
                backgroundPosterUrl = c?.logo
                plot = "📺 Canale TV ${c?.country.orEmpty()} in diretta 24/7 da Partite.cc"
                tags = listOfNotNull("Live", "TV", c?.country)
            }
        }
        val ev = PartiteEngine.eventByUrl(url) ?: PartiteEngine.fetchEvents().find { it.url == url }
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
            posterUrl = ev?.let(::poster) ?: FALLBACK_POSTER
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
        val channels = slugOf(data)?.let { slug ->
            val cfg = PartiteEngine.fetchChannelConfig(slug) ?: return false
            listOf(PartiteEngine.PartiteChannel(PartiteEngine.channelBySlug(slug)?.name ?: slug, cfg))
        } ?: PartiteEngine.fetchEventChannels(data)
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
