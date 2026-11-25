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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HTSport + Matchora live sports & Italian channels provider.
 *
 * Uses [MatchoraEngine.interceptorFor] as an in-app OkHttp proxy to unwrap
 * fake-PNG HLS segments into clean MPEG-TS and transparently renew expired
 * playlist tokens directly inside CloudStream's player.
 */
class MatchoraProvider : MainAPI() {
    override var mainUrl = MatchoraEngine.MATCHORA_BASE
    override var name = "HTSport"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "it"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val CH_PREFIX = "matchora://ch/"
        private const val HT_CH_PREFIX = "matchora://htch/"
        private const val HT_MATCH_PREFIX = "matchora://htmatch/"
        private const val EV_PREFIX = "matchora://ev/"
        private const val FALLBACK_POSTER =
            "https://raw.githubusercontent.com/qwertyuiop8899/tvvoo/main/public/tvvoo.png"

        private val ROME = TimeZone.getTimeZone("Europe/Rome")
        private fun fmt(pattern: String, sec: Long) =
            SimpleDateFormat(pattern, Locale.ITALIAN).apply { timeZone = ROME }.format(Date(sec * 1000L))

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun sportEmoji(sport: String) = when (sport.lowercase()) {
            "soccer", "calcio", "football" -> "⚽"
            "basketball", "basket" -> "🏀"
            "motorsport", "f1", "motogp" -> "🏎️"
            "tennis" -> "🎾"
            "ice hockey", "hockey" -> "🏒"
            "american football" -> "🏈"
            "cricket" -> "🏏"
            "fighting", "boxing", "ufc", "mma" -> "🥊"
            "rugby" -> "🏉"
            "baseball" -> "⚾"
            "darts" -> "🎯"
            else -> "🏆"
        }

        private fun sportLabelIt(sport: String) = when (sport.lowercase()) {
            "soccer" -> "Calcio"
            "basketball" -> "Basket"
            "motorsport" -> "Motori"
            "ice hockey" -> "Hockey"
            "american football" -> "Football Americano"
            "fighting" -> "Combattimento"
            "darts" -> "Freccette"
            else -> sport
        }

        private fun qualityValue(q: String): Int = when {
            q.contains("4k", true) || q.contains("2160") -> Qualities.P2160.value
            q.contains("fhd", true) || q.contains("1080") -> Qualities.P1080.value
            q.contains("hd", true) || q.contains("720") -> Qualities.P720.value
            q.contains("sd", true) || q.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }

        private fun isItalian(c: MatchoraEngine.MatchoraChannel): Boolean {
            val combined = "${c.country} ${c.name}".lowercase()
            return c.country.equals("IT", true) ||
                c.country.contains("🇮🇹") ||
                combined.contains("italia") ||
                combined.contains("sky sport") ||
                combined.contains("dazn it") ||
                combined.contains("calcio")
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        MatchoraEngine.interceptorFor(extractorLink)

    private fun chCard(ch: MatchoraEngine.MatchoraChannel): SearchResponse {
        val qTag = ch.quality.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val label = "📺 ${ch.name}$qTag"
        val url = "$CH_PREFIX${ch.id}?n=${enc(ch.name)}&q=${enc(ch.quality)}"
        return newLiveSearchResponse(label, url, TvType.Live, false) {
            posterUrl = FALLBACK_POSTER
        }
    }

    private fun htChCard(link: MatchoraEngine.HtLink): SearchResponse {
        val url = "$HT_CH_PREFIX${link.page}?n=${enc(link.label)}"
        return newLiveSearchResponse("📺 ${link.label}", url, TvType.Live, false) {
            posterUrl = FALLBACK_POSTER
        }
    }

    private fun htMatchCard(m: MatchoraEngine.HtMatch): SearchResponse {
        val prefix = when {
            m.live -> "🔴 "
            m.time.isNotBlank() -> "${m.time} · "
            else -> ""
        }
        return newLiveSearchResponse("$prefix${m.title}", "$HT_MATCH_PREFIX${m.id}", TvType.Live, false) {
            posterUrl = FALLBACK_POSTER
        }
    }

    private fun evCard(ev: MatchoraEngine.MatchoraEvent): SearchResponse {
        val score = ev.score?.let { " ($it)" }.orEmpty()
        val label = if (ev.live) {
            "🔴 ${ev.title}$score"
        } else if (ev.kickoffSec > 0) {
            "${fmt("dd/MM HH:mm", ev.kickoffSec)} · ${ev.title}"
        } else {
            ev.title
        }
        return newLiveSearchResponse(label, "$EV_PREFIX${ev.id}", TvType.Live, false) {
            posterUrl = ev.poster ?: FALLBACK_POSTER
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val htDef = async {
            if (LiveSettings.mxHtChannels || LiveSettings.mxHtMatches) {
                runCatching { MatchoraEngine.fetchHtHome() }.getOrNull()
            } else null
        }
        val liveDef = async {
            if (LiveSettings.mxLive) runCatching { MatchoraEngine.fetchLiveEvents() }.getOrDefault(emptyList())
            else emptyList()
        }
        val schedDef = async {
            if (LiveSettings.mxSchedule) runCatching { MatchoraEngine.fetchSchedule() }.getOrDefault(emptyList())
            else emptyList()
        }
        val itChDef = async {
            if (LiveSettings.mxItChannels) runCatching { MatchoraEngine.fetchItalianChannels() }.getOrDefault(emptyList())
            else emptyList()
        }

        val rows = mutableListOf<HomePageList>()

        if (LiveSettings.mxHtChannels) {
            val cards = mutableListOf<SearchResponse>()
            // Curated Italian Matchora HD channels directly addressable by channel ID
            MatchoraEngine.FALLBACK_IT_CHANNELS.forEach { cards += chCard(it) }
            // Also include working direct HLS channels from htsport.me (Rai 1 / Rai 2)
            htDef.await()?.channels
                ?.filter { it.page.equals("rai1.htm", true) || it.page.equals("rai2.htm", true) }
                ?.forEach { cards += htChCard(it) }
            rows += HomePageList("📺 Canali Italiani", cards, false)
        }

        if (LiveSettings.mxLive) {
            val live = liveDef.await()
            if (live.isNotEmpty()) {
                rows += HomePageList("🔴 In diretta ora", live.map(::evCard), false)
            }
        }

        if (LiveSettings.mxHtMatches) {
            val matches = htDef.await()?.matches.orEmpty()
            if (matches.isNotEmpty()) {
                rows += HomePageList("⚽ Partite del giorno", matches.map(::htMatchCard), false)
            }
        }

        if (LiveSettings.mxSchedule) {
            val sched = schedDef.await()
            sched.groupBy { it.sport }.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<MatchoraEngine.MatchoraEvent>>> {
                        if (it.key.equals("Soccer", true)) 100_000 else it.value.size
                    }
                )
                .forEach { (sport, list) ->
                    val ordered = list.sortedWith(
                        compareByDescending<MatchoraEngine.MatchoraEvent> { it.live }.thenBy { it.kickoffSec }
                    ).take(30)
                    if (ordered.isNotEmpty()) {
                        rows += HomePageList(
                            "${sportEmoji(sport)} ${sportLabelIt(sport)}",
                            ordered.map(::evCard),
                            false
                        )
                    }
                }
        }

        if (LiveSettings.mxItChannels) {
            val mainIds = MatchoraEngine.FALLBACK_IT_CHANNELS.map { it.id }.toSet()
            val extra = itChDef.await().filter { it.id !in mainIds }.take(60)
            if (extra.isNotEmpty()) {
                rows += HomePageList("🇮🇹 Altri canali IT", extra.map(::chCard), false)
            }
        }

        newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@coroutineScope emptyList()

        val htDef = async { runCatching { MatchoraEngine.fetchHtHome() }.getOrNull() }
        val liveDef = async { runCatching { MatchoraEngine.fetchLiveEvents() }.getOrDefault(emptyList()) }
        val schedDef = async { runCatching { MatchoraEngine.fetchSchedule() }.getOrDefault(emptyList()) }
        val itChDef = async { runCatching { MatchoraEngine.fetchItalianChannels() }.getOrDefault(emptyList()) }

        val out = mutableListOf<SearchResponse>()
        val seenCh = HashSet<String>()

        (MatchoraEngine.FALLBACK_IT_CHANNELS + itChDef.await()).forEach { ch ->
            if (ch.name.lowercase().contains(q) && seenCh.add(ch.id)) {
                out += chCard(ch)
            }
        }

        htDef.await()?.matches?.forEach { m ->
            if (m.title.lowercase().contains(q) || m.category.lowercase().contains(q)) {
                out += htMatchCard(m)
            }
        }

        val seenEv = HashSet<String>()
        (liveDef.await() + schedDef.await()).forEach { ev ->
            if (seenEv.add(ev.id) && (
                    ev.title.lowercase().contains(q) ||
                        ev.league.lowercase().contains(q) ||
                        ev.sport.lowercase().contains(q) ||
                        ev.channels.any { it.name.lowercase().contains(q) }
                    )
            ) {
                out += evCard(ev)
            }
        }
        out
    }

    private fun queryParam(url: String, key: String): String? =
        Regex("""[?&]$key=([^&]+)""").find(url)?.groupValues?.get(1)?.let(::dec)

    override suspend fun load(url: String): LoadResponse {
        when {
            url.startsWith(CH_PREFIX) -> {
                val id = url.removePrefix(CH_PREFIX).substringBefore('?').trim('/')
                val title = queryParam(url, "n")?.takeIf { it.isNotBlank() }
                    ?: MatchoraEngine.FALLBACK_IT_CHANNELS.find { it.id == id }?.name
                    ?: "Canale $id"
                val q = queryParam(url, "q").orEmpty()
                return newLiveStreamLoadResponse(title, url, url) {
                    posterUrl = FALLBACK_POSTER
                    plot = "📺 Canale TV in diretta su HTSport" +
                        (if (q.isNotBlank()) " ($q)" else "") +
                        "<br><br>Decodifica segmenti integrata nel player CloudStream."
                    tags = listOfNotNull("Live", "HTSport", q.takeIf { it.isNotBlank() })
                }
            }

            url.startsWith(HT_CH_PREFIX) -> {
                val page = url.removePrefix(HT_CH_PREFIX).substringBefore('?').trim('/')
                val title = queryParam(url, "n")?.takeIf { it.isNotBlank() } ?: page.removeSuffix(".htm")
                return newLiveStreamLoadResponse(title, url, url) {
                    posterUrl = FALLBACK_POSTER
                    plot = "📺 Canale in diretta da HTSport"
                    tags = listOf("Live", "HTSport")
                }
            }

            url.startsWith(HT_MATCH_PREFIX) -> {
                val id = url.removePrefix(HT_MATCH_PREFIX).substringBefore('?').trim('/')
                val m = MatchoraEngine.fetchHtHome().matches.find { it.id == id }
                val title = m?.title ?: "Partita HTSport"
                val plot = buildString {
                    if (m != null) {
                        append(if (m.live) "🔴 IN DIRETTA ORA" else "🕒 Orario: ${m.time.ifBlank { "Oggi" }}")
                        append("<br><br>🏆 ${m.category}")
                        if (m.links.isNotEmpty()) {
                            append("<br>📺 Canali: ${m.links.joinToString(", ") { it.label }}")
                        }
                    }
                }
                return newLiveStreamLoadResponse(title, url, url) {
                    posterUrl = FALLBACK_POSTER
                    this.plot = plot
                    tags = listOfNotNull(if (m?.live == true) "Live" else "Oggi", "HTSport")
                }
            }

            else -> {
                val id = url.removePrefix(EV_PREFIX).substringBefore('?').trim('/')
                val ev = (MatchoraEngine.fetchLiveEvents() + MatchoraEngine.fetchSchedule()).find { it.id == id }
                val title = ev?.title ?: "Evento HTSport $id"
                val plot = buildString {
                    if (ev != null) {
                        append(
                            if (ev.live) {
                                "🔴 IN DIRETTA ORA" + (ev.minute?.let { " · $it'" }.orEmpty())
                            } else if (ev.kickoffSec > 0) {
                                "🕒 Inizio: ${fmt("EEEE dd/MM HH:mm", ev.kickoffSec)}"
                            } else {
                                "📅 In programma"
                            }
                        )
                        ev.score?.let { append("<br>📊 Risultato: $it") }
                        if (ev.league.isNotBlank()) append("<br><br>🏆 ${ev.league} (${sportLabelIt(ev.sport)})")
                        if (ev.channels.isNotEmpty()) {
                            append("<br>📺 ${ev.channels.size} canali disponibili: scegli dal player.")
                        }
                    }
                }
                return newLiveStreamLoadResponse(title, url, url) {
                    posterUrl = ev?.poster ?: FALLBACK_POSTER
                    backgroundPosterUrl = ev?.poster
                    this.plot = plot
                    tags = listOfNotNull(if (ev?.live == true) "Live" else "In programma", ev?.sport?.let(::sportLabelIt))
                }
            }
        }
    }

    private suspend fun emitSession(
        session: MatchoraEngine.PlaySession,
        label: String,
        qualityStr: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$label · HTSport",
                url = session.m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = MatchoraEngine.MEDIA_HEADERS
                this.referer = "${MatchoraEngine.MATCHORA_BASE}/"
                this.quality = qualityValue(qualityStr)
                this.extractorData = "${MatchoraEngine.DATA_PREFIX}${session.channelId}|${session.pp}|${session.fx}"
            }
        )
    }

    private suspend fun emitMatchoraChannel(
        channelId: String,
        label: String,
        qualityStr: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val session = MatchoraEngine.resolvePlay(channelId) ?: return false
        emitSession(session, label, qualityStr, callback)
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        when {
            data.startsWith(CH_PREFIX) -> {
                val id = data.removePrefix(CH_PREFIX).substringBefore('?').trim('/')
                val label = queryParam(data, "n")?.takeIf { it.isNotBlank() } ?: "Canale $id"
                val q = queryParam(data, "q").orEmpty()
                emitMatchoraChannel(id, label, q, callback)
            }

            data.startsWith(HT_CH_PREFIX) -> {
                val page = data.removePrefix(HT_CH_PREFIX).substringBefore('?').trim('/')
                val label = queryParam(data, "n")?.takeIf { it.isNotBlank() } ?: page.removeSuffix(".htm")
                val resolved = MatchoraEngine.resolveHtPage(page)
                when {
                    resolved.matchoraId != null -> emitMatchoraChannel(resolved.matchoraId, label, "HD", callback)
                    resolved.directM3u8 != null -> {
                        callback(
                            newExtractorLink(name, "$label · HLS", resolved.directM3u8, ExtractorLinkType.M3U8) {
                                this.headers = mapOf("User-Agent" to MatchoraEngine.UA)
                                this.quality = Qualities.P1080.value
                            }
                        )
                        true
                    }
                    else -> false
                }
            }

            data.startsWith(HT_MATCH_PREFIX) -> {
                val id = data.removePrefix(HT_MATCH_PREFIX).substringBefore('?').trim('/')
                val m = MatchoraEngine.fetchHtHome().matches.find { it.id == id } ?: return@coroutineScope false
                val pages = m.links.map { link ->
                    async { link to MatchoraEngine.resolveHtPage(link.page) }
                }.awaitAll()

                val seenIds = HashSet<String>()
                val seenDirect = HashSet<String>()
                val mxResolved = pages.mapNotNull { (link, res) ->
                    res.matchoraId?.takeIf { seenIds.add(it) }?.let { cid ->
                        async { link.label to MatchoraEngine.resolvePlay(cid) }
                    }
                }.awaitAll()
                var emitted = false
                for ((label, session) in mxResolved) {
                    if (session != null) {
                        emitSession(session, label, "HD", callback)
                        emitted = true
                    }
                }
                for ((link, res) in pages) {
                    val direct = res.directM3u8?.takeIf { seenDirect.add(it) } ?: continue
                    callback(
                        newExtractorLink(name, "${link.label} · HLS", direct, ExtractorLinkType.M3U8) {
                            this.headers = mapOf("User-Agent" to MatchoraEngine.UA)
                            this.quality = Qualities.P1080.value
                        }
                    )
                    emitted = true
                }
                emitted
            }

            else -> {
                val id = data.removePrefix(EV_PREFIX).substringBefore('?').trim('/')
                val ev = (MatchoraEngine.fetchLiveEvents() + MatchoraEngine.fetchSchedule()).find { it.id == id }
                    ?: return@coroutineScope false
                val picked = ev.channels
                    .sortedWith(
                        compareBy<MatchoraEngine.MatchoraChannel>(
                            { it.dead },
                            { !isItalian(it) },
                            { !it.playing },
                            { !it.quality.contains("HD", true) }
                        )
                    )
                    .distinctBy { it.id }
                    .take(6)

                val resolved = picked.map { ch ->
                    async { ch to MatchoraEngine.resolvePlay(ch.id) }
                }.awaitAll()
                var emitted = false
                for ((ch, session) in resolved) {
                    if (session == null) continue
                    val flag = ch.country.takeIf { it.isNotBlank() && it != "IT" } ?: if (isItalian(ch)) "🇮🇹" else ""
                    val q = ch.quality.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                    val label = listOf(flag, ch.name + q).filter { it.isNotBlank() }.joinToString(" ")
                    emitSession(session, label, ch.quality, callback)
                    emitted = true
                }
                emitted
            }
        }
    }
}
