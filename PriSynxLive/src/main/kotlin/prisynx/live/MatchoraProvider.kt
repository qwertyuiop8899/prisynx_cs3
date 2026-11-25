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
 * HTSport live sports & Italian channels provider.
 *
 * Resolves Matchora (with in-app PNG->TS OkHttp interceptor), `_econfig` direct
 * HLS embeds (Live 2/7/8/11/12/13/16, no interceptor needed), WideIPTV (with
 * automatic 5-min token refresh interceptor), and direct HLS feeds.
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
        private const val CH_PREFIX = "https://matchora.pro/_cs/ch/"
        private const val HT_CH_PREFIX = "https://matchora.pro/_cs/htch/"
        private const val HT_MATCH_PREFIX = "https://matchora.pro/_cs/htmatch/"
        private const val EV_PREFIX = "https://matchora.pro/_cs/ev/"
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
            if (c.country.equals("IT", true) || c.country.contains("🇮🇹")) return true
            if (c.country.isNotBlank() && c.country != "📺") return false
            val low = c.name.lowercase()
            return low.contains("italia") || low.contains("calcio") || low.contains("dazn it") ||
                (low.contains("sky sport") && !low.contains("austria") && !low.contains("de") && !low.contains("uk"))
        }

        private fun matchTokens(title: String): Set<String> {
            val norm = title.lowercase()
                .replace("paris saint-germain", "psg")
                .replace("paris saint germain", "psg")
                .replace("manchester united", "manutd")
                .replace("manchester city", "mancity")
                .replace("bayern munich", "bayern")
                .replace("bayern monaco", "bayern")
                .replace("stuttgart", "stoccarda")
                .replace("barcelona", "barcellona")
                .replace("atletico madrid", "atlmadrid")
                .replace("atl madrid", "atlmadrid")
                .replace("club brugge", "bruges")
                .replace("inter milan", "inter")
                .replace("internazionale", "inter")
                .replace("as roma", "roma")
                .replace("ac milan", "milan")
                .replace("slavia prague", "slavia")
                .replace("slavia praga", "slavia")
            val stop = setOf("fc", "cf", "ac", "as", "sc", "cp", "vs", "sv", "ss", "us", "cd", "ud", "de", "la", "el", "di")
            return Regex("[a-z0-9]{3,}").findAll(norm).map { it.value }.filter { it !in stop }.toSet()
        }

        private fun findMatchingHtMatch(ev: MatchoraEngine.MatchoraEvent, htMatches: List<MatchoraEngine.HtMatch>): MatchoraEngine.HtMatch? {
            val evTok = matchTokens(ev.title)
            if (evTok.isEmpty()) return null
            var best: Pair<Int, MatchoraEngine.HtMatch>? = null
            for (m in htMatches) {
                val mTok = matchTokens(m.title)
                val common = evTok.intersect(mTok).size
                val needed = if (evTok.size >= 2 && mTok.size >= 2) 2 else 1
                if (common >= needed && (best == null || common > best.first)) {
                    best = common to m
                }
            }
            return best?.second
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
            MatchoraEngine.FALLBACK_IT_CHANNELS.forEach { cards += chCard(it) }
            htDef.await()?.channels
                ?.filter { it.page.equals("rai1.htm", true) || it.page.equals("rai2.htm", true) }
                ?.forEach { cards += htChCard(it) }
            rows += HomePageList("📺 Canali Italiani", cards, false)
        }

        if (LiveSettings.mxHtMatches) {
            val matches = htDef.await()?.matches.orEmpty()
            if (matches.isNotEmpty()) {
                rows += HomePageList("⚽ Partite del giorno (HTSport)", matches.map(::htMatchCard), false)
            }
        }

        if (LiveSettings.mxLive) {
            val live = liveDef.await()
            if (live.isNotEmpty()) {
                rows += HomePageList("🔴 In diretta ora", live.map(::evCard), false)
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
            url.contains("/_cs/ch/") -> {
                val id = url.substringAfter("/_cs/ch/").substringBefore('?').trim('/')
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

            url.contains("/_cs/htch/") -> {
                val page = url.substringAfter("/_cs/htch/").substringBefore('?').trim('/')
                val title = queryParam(url, "n")?.takeIf { it.isNotBlank() } ?: page.removeSuffix(".htm")
                return newLiveStreamLoadResponse(title, url, url) {
                    posterUrl = FALLBACK_POSTER
                    plot = "📺 Canale in diretta da HTSport"
                    tags = listOf("Live", "HTSport")
                }
            }

            url.contains("/_cs/htmatch/") -> {
                val id = url.substringAfter("/_cs/htmatch/").substringBefore('?').trim('/')
                val m = MatchoraEngine.cachedHtMatch(id) ?: MatchoraEngine.fetchHtHome().matches.find { it.id == id }
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
                val id = url.substringAfter("/_cs/ev/").substringBefore('?').trim('/')
                val ev = MatchoraEngine.cachedEvent(id)
                    ?: MatchoraEngine.fetchLiveEvents().find { it.id == id }
                    ?: MatchoraEngine.fetchSchedule().find { it.id == id }
                    ?: MatchoraEngine.fetchEventById(id)
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

    private suspend fun emitMatchoraSession(
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

    private suspend fun emitWideIptvSession(
        session: MatchoraEngine.WideIptvSession,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$label · HLS",
                url = session.m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = MatchoraEngine.WIDEIPTV_HEADERS
                this.referer = "${MatchoraEngine.WIDEIPTV_BASE}/"
                this.quality = Qualities.P720.value
                this.extractorData = "${MatchoraEngine.WIDEIPTV_PREFIX}${session.slug}|${session.token}"
            }
        )
    }

    private suspend fun emitDirectStream(
        stream: MatchoraEngine.DirectStream,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$label · Direct",
                url = stream.m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "User-Agent" to MatchoraEngine.UA,
                    "Referer" to stream.referer,
                    "Origin" to stream.origin,
                    "Accept" to "*/*"
                )
                this.referer = stream.referer
                this.quality = Qualities.P1080.value
            }
        )
    }

    private fun pickEventChannels(ev: MatchoraEngine.MatchoraEvent, limit: Int): List<MatchoraEngine.MatchoraChannel> =
        ev.channels
            .sortedWith(
                compareBy<MatchoraEngine.MatchoraChannel>(
                    { it.dead },
                    { !isItalian(it) },
                    { !it.playing },
                    { !it.quality.contains("HD", true) }
                )
            )
            .distinctBy { it.id }
            .take(limit)

    private fun channelLabel(ch: MatchoraEngine.MatchoraChannel): String {
        val flag = ch.country.takeIf { it.isNotBlank() && it != "IT" } ?: if (isItalian(ch)) "🇮🇹" else ""
        val q = ch.quality.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return listOf(flag, ch.name + q).filter { it.isNotBlank() }.joinToString(" ")
    }

    private suspend fun emitMatchoraEvent(
        ev: MatchoraEngine.MatchoraEvent,
        limit: Int,
        seenChannelIds: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val picked = pickEventChannels(ev, limit).filter { seenChannelIds.add(it.id) }
        val resolved = picked.map { ch ->
            async { ch to MatchoraEngine.resolvePlay(ch.id) }
        }.awaitAll()
        var emitted = false
        for ((ch, session) in resolved) {
            if (session == null) continue
            emitMatchoraSession(session, channelLabel(ch), ch.quality, callback)
            emitted = true
        }
        emitted
    }

    private suspend fun resolveAndEmitHtLinks(
        links: List<MatchoraEngine.HtLink>,
        callback: (ExtractorLink) -> Unit,
        includeMatchoraEvents: Boolean = true
    ): Boolean = coroutineScope {
        val pages = links.map { link ->
            async { link to MatchoraEngine.resolveHtPage(link.page) }
        }.awaitAll()

        val seenMxCh = HashSet<String>()
        val seenMxEv = HashSet<String>()
        val seenWide = HashSet<String>()
        val seenEcfg = HashSet<String>()
        val seenDirect = HashSet<String>()

        val mxChJobs = pages.mapNotNull { (link, res) ->
            res.matchoraChannelId?.takeIf { seenMxCh.add(it) }?.let { cid ->
                async { link.label to MatchoraEngine.resolvePlay(cid) }
            }
        }
        val mxEvJobs = if (includeMatchoraEvents) {
            pages.mapNotNull { (link, res) ->
                res.matchoraEventId?.takeIf { seenMxEv.add(it) }?.let { eid ->
                    async { link.label to MatchoraEngine.fetchEventById(eid) }
                }
            }
        } else emptyList()
        val ecfgJobs = pages.mapNotNull { (link, res) ->
            val u = res.econfigUrl?.takeIf { seenEcfg.add(it) } ?: return@mapNotNull null
            val ref = res.econfigReferer ?: "${MatchoraEngine.HTSPORT_BASE}/${link.page}"
            async { link.label to MatchoraEngine.resolveEconfig(u, ref) }
        }
        val wideJobs = pages.mapNotNull { (link, res) ->
            res.wideIptvSlug?.takeIf { seenWide.add(it.lowercase()) }?.let { slug ->
                async { link.label to MatchoraEngine.resolveWideIptv(slug) }
            }
        }

        val mxChRes = mxChJobs.awaitAll()
        val mxEvRes = mxEvJobs.awaitAll()
        val ecfgRes = ecfgJobs.awaitAll()
        val wideRes = wideJobs.awaitAll()

        var emitted = false

        // 1. Direct _econfig streams first (plain TS, 6h token, no interceptor needed)
        for ((label, stream) in ecfgRes) {
            if (stream != null) {
                emitDirectStream(stream, label, callback)
                emitted = true
            }
        }

        // 2. Matchora direct channels
        for ((label, session) in mxChRes) {
            if (session != null) {
                emitMatchoraSession(session, label, "HD", callback)
                emitted = true
            }
        }

        // 3. Matchora match embeds (/embed/match/<id>)
        for ((_, ev) in mxEvRes) {
            if (ev != null && emitMatchoraEvent(ev, 4, seenMxCh, callback)) {
                emitted = true
            }
        }

        // 4. WideIPTV / hesgoalsss streams (with 5-min token refresh interceptor)
        for ((label, session) in wideRes) {
            if (session != null) {
                emitWideIptvSession(session, label, callback)
                emitted = true
            }
        }

        // 5. Plain direct .m3u8 (Rai 1 / Rai 2)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        when {
            data.contains("/_cs/ch/") -> {
                val id = data.substringAfter("/_cs/ch/").substringBefore('?').trim('/')
                val label = queryParam(data, "n")?.takeIf { it.isNotBlank() } ?: "Canale $id"
                val q = queryParam(data, "q").orEmpty()
                val session = MatchoraEngine.resolvePlay(id) ?: return@coroutineScope false
                emitMatchoraSession(session, label, q, callback)
                true
            }

            data.contains("/_cs/htch/") -> {
                val page = data.substringAfter("/_cs/htch/").substringBefore('?').trim('/')
                val label = queryParam(data, "n")?.takeIf { it.isNotBlank() } ?: page.removeSuffix(".htm")
                resolveAndEmitHtLinks(listOf(MatchoraEngine.HtLink(label, page)), callback)
            }

            data.contains("/_cs/htmatch/") -> {
                val id = data.substringAfter("/_cs/htmatch/").substringBefore('?').trim('/')
                val m = MatchoraEngine.cachedHtMatch(id)
                    ?: MatchoraEngine.fetchHtHome().matches.find { it.id == id }
                    ?: return@coroutineScope false
                resolveAndEmitHtLinks(m.links, callback)
            }

            else -> {
                val id = data.substringAfter("/_cs/ev/").substringBefore('?').trim('/')
                val ev = MatchoraEngine.cachedEvent(id)
                    ?: MatchoraEngine.fetchLiveEvents().find { it.id == id }
                    ?: MatchoraEngine.fetchSchedule().find { it.id == id }
                    ?: MatchoraEngine.fetchEventById(id)
                    ?: return@coroutineScope false
                var emitted = false
                val htMatch = runCatching {
                    findMatchingHtMatch(ev, MatchoraEngine.fetchHtHome().matches)
                }.getOrNull()
                if (htMatch != null) {
                    // Emit HTSport direct/ITA links (e.g. Live 2 🇮🇹, Live 7 🇮🇹) first, skipping duplicate matchora match embed
                    val nonMxEvLinks = htMatch.links
                    if (resolveAndEmitHtLinks(nonMxEvLinks, callback, includeMatchoraEvents = false)) {
                        emitted = true
                    }
                }
                if (emitMatchoraEvent(ev, 6, HashSet(), callback)) {
                    emitted = true
                }
                emitted
            }
        }
    }
}
