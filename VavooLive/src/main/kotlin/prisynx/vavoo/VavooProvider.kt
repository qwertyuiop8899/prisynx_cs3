package prisynx.vavoo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Vavoo country groups (API group name -> Italian label). */
data class VavooCountry(val group: String, val label: String)

object VavooOptions {
    val countries: List<VavooCountry> = listOf(
        VavooCountry("Italy", "Italia"),
        VavooCountry("Germany", "Germania"),
        VavooCountry("France", "Francia"),
        VavooCountry("Spain", "Spagna"),
        VavooCountry("United Kingdom", "Regno Unito"),
        VavooCountry("Portugal", "Portogallo"),
        VavooCountry("Netherlands", "Paesi Bassi"),
        VavooCountry("Turkey", "Turchia"),
        VavooCountry("Albania", "Albania"),
        VavooCountry("Poland", "Polonia"),
        VavooCountry("Balkans", "Balcani"),
        VavooCountry("Croatia", "Croazia"),
        VavooCountry("Romania", "Romania"),
        VavooCountry("Bulgaria", "Bulgaria"),
        VavooCountry("Russia", "Russia"),
        VavooCountry("Arabia", "Arabia")
    )

    /** Countries shown in home/search (the hub overrides this from its settings). */
    @Volatile
    var enabled: Set<String> = setOf("Italy")

    fun labelOf(group: String) = countries.firstOrNull { it.group == group }?.label ?: group
}

/**
 * Vavoo live TV.
 *
 * The catalog/resolve endpoints need the "addonSig" returned by the Vypn app ping.
 * Vavoo lists every channel several times (".c", ".s", ... mirrors): one card per
 * channel name, every mirror is offered as a separate link (".s" first, it is the
 * most reliable).
 */
class VavooProvider : MainAPI() {
    override var mainUrl = "https://vavoo.to"
    override var name = "Vavoo Live"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "it"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.None

    data class Mirror(val name: String, val url: String)
    data class Channel(val country: String, val name: String, val logo: String, val mirrors: List<Mirror>) {
        /** Stable id: the Vavoo id of the first mirror (hex, URL safe). */
        val id: String get() = mirrors.first().url.substringAfterLast('/')
        val ref: String get() = "$SCHEME$country/$id"
    }

    companion object {
        const val SCHEME = "vavoo://"

        private const val PING = "https://www.vypn.net/api/app/ping"
        private const val CATALOG = "https://vavoo.to/mediahubmx-catalog.json"
        private const val RESOLVE = "https://vavoo.to/mediahubmx-resolve.json"
        private const val COVERS =
            "https://raw.githubusercontent.com/qwertyuiop8899/tvvoo/main/src/tvvoo-cover-maps.generated.json"
        const val FALLBACK_LOGO = "https://raw.githubusercontent.com/qwertyuiop8899/tvvoo/main/public/tvvoo.png"

        private const val PING_UA = "electron-fetch/1.0 electron (+https://github.com/arantes555/electron-fetch)"
        private const val API_UA = "okhttp/4.11.0"
        private const val RESOLVE_UA = "MediaHubMX/2"
        private const val STREAM_UA = "VAVOO/2.6"

        private const val CHANNELS_TTL = 60 * 60_000L
        private const val SIG_TTL = 60 * 60_000L

        private val mapper: ObjectMapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private val lock = Mutex()
        private var sig: String? = null
        private var sigAt = 0L
        private var covers: Pair<Map<String, String>, Map<String, String>>? = null
        private val channels = HashMap<String, Pair<Long, List<Channel>>>()

        private val SUFFIX = Regex("\\s*(\\.[a-z0-9]{1,3})+$", RegexOption.IGNORE_CASE)
        private val PAREN = Regex("\\s*\\((?:\\d+|[A-Za-z]{1,3})\\)\\s*$")

        /** CloudStream may prefix the card url with mainUrl: accept the scheme anywhere. */
        fun isVavooUrl(url: String) = url.contains(SCHEME)

        private fun refOf(url: String): Pair<String, String>? {
            if (!isVavooUrl(url)) return null
            val rest = url.substringAfter(SCHEME)
            val country = URLDecoder.decode(rest.substringBefore('/'), "UTF-8")
            val id = URLDecoder.decode(rest.substringAfter('/', ""), "UTF-8")
            return if (country.isBlank() || id.isBlank()) null else country to id
        }

        fun clearCache() {
            synchronized(channels) { channels.clear() }
        }

        private val categories: List<Pair<String, (String) -> Boolean>> = listOf(
            "Intrattenimento" to { n: String ->
                listOf("rai", "mediaset", "canale 5", "italia 1", "rete 4", "la7", "tv8", "nove", "20 ", "iris", "real time")
                    .any { n.contains(it) }
            },
            "Cinema" to { n: String -> listOf("cinema", "movie", "film").any { n.contains(it) } },
            "Sport" to { n: String ->
                listOf("sport", "calcio", "dazn", "motogp", "f1", "eurosport", "tennis", "golf", "football")
                    .any { n.contains(it) }
            },
            "Documentari" to { n: String ->
                listOf("doc", "discovery", "history", "nat geo", "geographic", "focus", "arte").any { n.contains(it) }
            },
            "Bambini" to { n: String ->
                listOf("cartoon", "boing", "disney", "nick", "junior", "kids", "frisbee", "cartoonito", "baby")
                    .any { n.contains(it) }
            }
        )
    }

    // ------------------------------------------------------------ auth

    private suspend fun signature(force: Boolean = false): String? = lock.withLock {
        val now = System.currentTimeMillis()
        if (!force && sig != null && now - sigAt < SIG_TTL) return@withLock sig
        val body = mapOf(
            "token" to "",
            "reason" to "app-focus",
            "locale" to "de",
            "theme" to "dark",
            "metadata" to mapOf(
                "device" to mapOf("type" to "phone", "uniqueId" to UUID.randomUUID().toString().replace("-", "").take(16)),
                "os" to mapOf("name" to "android", "version" to "14", "abis" to listOf("arm64-v8a"), "host" to "android"),
                "app" to mapOf("platform" to "android"),
                "version" to mapOf("package" to "net.vypn.app", "binary" to "1.4.1", "js" to "1.4.1")
            ),
            "appFocusTime" to 0,
            "playerActive" to false,
            "playDuration" to 0,
            "devMode" to false,
            "hasAddon" to true,
            "castConnected" to false,
            "package" to "net.vypn.app",
            "version" to "1.4.1",
            "process" to "app",
            "firstAppStart" to now - 86_400_000L,
            "lastAppStart" to now,
            "ipLocation" to null,
            "adblockEnabled" to true,
            "migrationApplied" to false,
            "migrationTargetInstalled" to false,
            "proxy" to mapOf(
                "supported" to listOf("ss"), "engine" to "Mu", "ssVersion" to "2022",
                "enabled" to false, "autoServer" to true, "id" to ""
            ),
            "iap" to mapOf("supported" to false, "error" to "")
        )
        val value = try {
            val text = app.post(
                PING,
                headers = mapOf(
                    "User-Agent" to PING_UA,
                    "Content-Type" to "application/json; charset=utf-8",
                    "Accept" to "application/json",
                    "Accept-Language" to "de"
                ),
                json = JsonAsString(mapper.writeValueAsString(body)),
                timeout = 30
            ).text
            mapper.readTree(text).path("addonSig").asText("").ifBlank { null }
        } catch (e: Exception) {
            null
        }
        if (value != null) {
            sig = value
            sigAt = now
        }
        value ?: sig
    }

    private suspend fun api(url: String, ua: String, body: Map<String, Any?>): JsonNode? {
        for (attempt in 0..1) {
            val s = signature(force = attempt > 0) ?: return null
            try {
                val response = app.post(
                    url,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Content-Type" to "application/json; charset=utf-8",
                        "Accept" to "application/json",
                        "mediahubmx-signature" to s
                    ),
                    json = JsonAsString(mapper.writeValueAsString(body)),
                    timeout = 30
                )
                if (response.code in 200..299) return mapper.readTree(response.text)
            } catch (e: Exception) {
                // retry once with a fresh signature
            }
        }
        return null
    }

    // ------------------------------------------------------------ covers

    private suspend fun coverMaps(): Pair<Map<String, String>, Map<String, String>> {
        covers?.let { return it }
        return try {
            val tree = mapper.readTree(app.get(COVERS, timeout = 30).text)
            fun read(node: JsonNode): Map<String, String> {
                val map = HashMap<String, String>()
                node.fields().forEach { (k, v) -> map[k] = v.asText() }
                return map
            }
            val maps = read(tree.path("ita_portrait_map")) to read(tree.path("world_portrait_map"))
            if (maps.first.isNotEmpty() || maps.second.isNotEmpty()) covers = maps
            maps
        } catch (e: Exception) {
            emptyMap<String, String>() to emptyMap()
        }
    }

    private fun cleanup(name: String) = PAREN.replace(SUFFIX.replace(name, ""), "").trim()

    /** Same key normalisation as TvVoo. */
    private fun normalize(name: String) = name.lowercase().trim()
        .replace("&", " and ")
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^a-z0-9\\-]"), "")
        .replace(Regex("-+"), "-")
        .trim('-')

    private fun findCover(map: Map<String, String>, name: String): String? {
        val keys = listOf(normalize(name), normalize(cleanup(name))).filter { it.isNotBlank() }.distinct()
        keys.forEach { k -> map[k]?.let { return it } }
        val suffixes = listOf("-1", "-2", "-3", "-d", "-mpd", "-backup")
        keys.forEach { k -> suffixes.forEach { sfx -> map[k + sfx]?.let { return it } } }
        keys.forEach { k ->
            val prefix = "$k-"
            map.entries.firstOrNull { it.key.startsWith(prefix) }?.let { return it.value }
        }
        return null
    }

    /** TvVoo-style placeholder: channel name on the PriSynx purple background. */
    private fun placeholder(name: String): String {
        val text = cleanup(name).replace(Regex("\\s+"), " ").trim().uppercase().ifBlank { "TV" }
            .split(' ').joinToString("\\n")
        return "https://placehold.co/400x600/12052b/00FFD1.png?font=montserrat&text=" + URLEncoder.encode(text, "UTF-8")
    }

    private fun coverOf(names: List<String>, country: String, maps: Pair<Map<String, String>, Map<String, String>>): String {
        val primary = if (country == "Italy") maps.first else maps.second
        names.forEach { n -> findCover(primary, n)?.let { return it } }
        if (country == "Italy") names.forEach { n -> findCover(maps.second, n)?.let { return it } }
        return placeholder(names.first())
    }

    // ------------------------------------------------------------ catalog

    suspend fun channels(country: String): List<Channel> {
        val now = System.currentTimeMillis()
        synchronized(channels) {
            channels[country]?.let { (at, list) -> if (now - at < CHANNELS_TTL && list.isNotEmpty()) return list }
        }
        val maps = coverMaps()
        val raw = ArrayList<Triple<String, String, String?>>()
        var cursor: Int? = 0
        var pages = 0
        while (cursor != null && pages < 20) {
            val tree = api(
                CATALOG, API_UA, mapOf(
                    "language" to "de",
                    "region" to "AT",
                    "catalogId" to "iptv",
                    "id" to "iptv",
                    "adult" to false,
                    "search" to "",
                    "sort" to "name",
                    "filter" to mapOf("group" to country),
                    "cursor" to cursor,
                    "clientVersion" to "3.1.0"
                )
            ) ?: break
            tree.path("items").forEach { node ->
                val name = node.path("name").asText("").trim()
                val url = node.path("url").asText("")
                if (name.isNotBlank() && url.isNotBlank() && node.path("group").asText(country) == country) {
                    raw.add(Triple(name, url, node.path("logo").asText("").ifBlank { null }))
                }
            }
            cursor = tree.path("nextCursor").takeIf { it.isNumber }?.asInt()
            pages++
        }
        val list = raw.groupBy { cleanup(it.first).ifBlank { it.first } }
            .map { (base, items) ->
                val mirrors = items
                    .sortedBy { mirrorRank(it.first) }
                    .map { Mirror(it.first, it.second) }
                Channel(country, base, coverOf(items.map { it.first }, country, maps), mirrors)
            }
            .sortedBy { it.name.lowercase() }
        if (list.isNotEmpty()) synchronized(channels) { channels[country] = now to list }
        return list
    }

    /** ".s" mirrors answer far more often than ".c" ones. */
    private fun mirrorRank(name: String): Int {
        val suffix = SUFFIX.find(name)?.value?.trim()?.lowercase()
        return when (suffix) {
            ".s" -> 0
            null -> 1
            ".c" -> 3
            else -> 2
        }
    }

    private suspend fun card(ch: Channel): SearchResponse =
        newLiveSearchResponse(ch.name, ch.ref, TvType.Live, fix = false) { posterUrl = ch.logo }

    /** Home rows of one country, split by category. */
    suspend fun countryRows(country: String): List<HomePageList> {
        val list = channels(country)
        if (list.isEmpty()) return emptyList()
        val label = VavooOptions.labelOf(country)
        val buckets = LinkedHashMap<String, MutableList<Channel>>()
        categories.forEach { buckets[it.first] = mutableListOf() }
        val others = mutableListOf<Channel>()
        list.forEach { ch ->
            val n = ch.name.lowercase()
            val cat = categories.firstOrNull { it.second(n) }?.first
            if (cat != null) buckets.getValue(cat).add(ch) else others.add(ch)
        }
        val rows = buckets.filter { it.value.isNotEmpty() }
            .map { (cat, chs) -> HomePageList("TV $label · $cat", chs.map { card(it) }) }
        return rows + listOfNotNull(
            others.takeIf { it.isNotEmpty() }?.let { HomePageList("TV $label · Altri canali", it.map { ch -> card(ch) }) }
        )
    }

    suspend fun enabledRows(): List<HomePageList> = coroutineScope {
        VavooOptions.countries.filter { it.group in VavooOptions.enabled }
            .map { async { try { countryRows(it.group) } catch (e: Exception) { emptyList() } } }
            .awaitAll().flatten()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        newHomePageResponse(if (page <= 1) enabledRows() else emptyList(), false)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return coroutineScope {
            VavooOptions.countries.filter { it.group in VavooOptions.enabled }
                .map { async { try { channels(it.group) } catch (e: Exception) { emptyList() } } }
                .awaitAll().flatten()
        }.filter { it.name.lowercase().contains(q) }.map { card(it) }
    }

    private suspend fun channelOf(url: String): Channel? {
        val (country, id) = refOf(url) ?: return null
        val list = channels(country)
        return list.firstOrNull { ch -> ch.mirrors.any { it.url.substringAfterLast('/') == id } }
            ?: list.firstOrNull { it.name == id }
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = channelOf(url)
        val country = refOf(url)?.first ?: "Italy"
        val title = ch?.name ?: "Canale non trovato"
        val ref = ch?.ref ?: url
        return newLiveStreamLoadResponse(title, ref, ref) {
            posterUrl = ch?.logo ?: FALLBACK_LOGO
            plot = "Canale TV in diretta (Vavoo · ${VavooOptions.labelOf(country)}). " +
                "${ch?.mirrors?.size ?: 0} sorgenti disponibili: se una non parte prova la successiva."
            tags = listOf("Live", "Vavoo", VavooOptions.labelOf(country))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ch = channelOf(data) ?: return false
        val streams = coroutineScope {
            ch.mirrors.map { mirror ->
                async {
                    val tree = api(
                        RESOLVE, RESOLVE_UA, mapOf(
                            "language" to "de",
                            "region" to "AT",
                            "url" to mirror.url,
                            "clientVersion" to "3.1.0"
                        )
                    )
                    val stream = tree?.takeIf { it.isArray && it.size() > 0 }?.get(0)?.path("url")?.asText("")
                    stream?.takeIf { it.isNotBlank() }?.let { mirror to it }
                }
            }.awaitAll().filterNotNull()
        }
        streams.forEachIndexed { index, (mirror, stream) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "Vavoo ${index + 1} · ${mirror.name}",
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf("User-Agent" to STREAM_UA)
                    quality = Qualities.P1080.value - index
                }
            )
        }
        return streams.isNotEmpty()
    }
}
