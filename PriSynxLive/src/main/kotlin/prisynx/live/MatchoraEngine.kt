package prisynx.live

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * HTSport + Matchora client and OkHttp interceptors.
 *
 * Supported stream types:
 * 1. Matchora (`/embed/channel/<id>` and `/embed/match/<id>`): HLS with fake-PNG
 *    segments (`iTXt` `payload-*`, zlib, Base64). [MatchoraInterceptor] unwraps every
 *    segment on the fly into clean MPEG-TS and renews expired playlist tokens.
 * 2. `_econfig` embeds (`ordernap.net`, `sportsonliine`/`dyntiptoe`, etc.): Base64-scrambled
 *    JSON containing a direct plain MPEG-TS `.m3u8` (6h token, no interceptor needed).
 * 3. WideIPTV / hesgoalsss (`wideiptv.top/player/<slug>`): plain HLS with a 5-minute
 *    token renewed transparently by [WideIptvInterceptor].
 * 4. Direct `.m3u8` embeds (e.g. Rai 1 / Rai 2).
 */
object MatchoraEngine {
    const val HTSPORT_BASE = "https://htsport.me"
    const val MATCHORA_BASE = "https://matchora.pro"
    const val WIDEIPTV_BASE = "https://wideiptv.top"
    const val DATA_PREFIX = "prisynx:matchora:"
    const val WIDEIPTV_PREFIX = "prisynx:wideiptv:"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0"

    val MEDIA_HEADERS = mapOf(
        "User-Agent" to UA,
        "Referer" to "$MATCHORA_BASE/",
        "Origin" to MATCHORA_BASE,
        "Accept" to "*/*"
    )

    val WIDEIPTV_HEADERS = mapOf(
        "User-Agent" to UA,
        "Referer" to "$WIDEIPTV_BASE/",
        "Origin" to WIDEIPTV_BASE,
        "Accept" to "*/*"
    )

    private val http by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class PlaySession(
        val channelId: String,
        val m3u8Url: String,
        val pp: String,
        val fx: String,
        val expiresIn: Int
    )

    data class WideIptvSession(
        val slug: String,
        val m3u8Url: String,
        val token: String
    )

    data class DirectStream(
        val m3u8Url: String,
        val referer: String,
        val origin: String
    )

    data class MatchoraChannel(
        val id: String,
        val name: String,
        val quality: String,
        val country: String,
        val playing: Boolean = true,
        val dead: Boolean = false
    )

    data class MatchoraEvent(
        val id: String,
        val title: String,
        val sport: String,
        val league: String,
        val kickoffSec: Long,
        val live: Boolean,
        val score: String?,
        val minute: String?,
        val poster: String?,
        val channels: List<MatchoraChannel>
    )

    data class HtLink(val label: String, val page: String)

    data class HtMatch(
        val id: String,
        val title: String,
        val time: String,
        val category: String,
        val live: Boolean,
        val links: List<HtLink>
    )

    data class HtHome(val channels: List<HtLink>, val matches: List<HtMatch>)

    data class ResolvedHtPage(
        val matchoraChannelId: String? = null,
        val matchoraEventId: String? = null,
        val wideIptvSlug: String? = null,
        val econfigUrl: String? = null,
        val econfigReferer: String? = null,
        val directM3u8: String? = null
    )

    /** Built-in Italian Matchora channels used as fallback if htsport.me is unreachable. */
    val FALLBACK_IT_CHANNELS = listOf(
        MatchoraChannel("692437", "Sky Sport 24", "HD", "IT"),
        MatchoraChannel("692391", "Sky Sport Uno", "HD", "IT"),
        MatchoraChannel("692415", "Sky Sport Calcio", "HD", "IT"),
        MatchoraChannel("692403", "Sky Sport 254 / Calcio 4", "HD", "IT"),
        MatchoraChannel("692427", "Sky Sport F1", "HD", "IT"),
        MatchoraChannel("692421", "Sky Sport MotoGP", "HD", "IT"),
        MatchoraChannel("1917708", "Sky Sport Max", "HD", "IT"),
        MatchoraChannel("1917707", "Sky Sport Mix", "HD", "IT"),
        MatchoraChannel("692434", "Sky Sport Arena", "HD", "IT"),
        MatchoraChannel("692418", "Sky Sport Basket", "HD", "IT"),
        MatchoraChannel("920093", "DAZN 1", "HD", "IT"),
        MatchoraChannel("920074", "DAZN Web 1", "HD", "IT")
    )

    // ── caches ──────────────────────────────────────────────────────────────────

    @Volatile private var htCache: Pair<Long, HtHome>? = null
    @Volatile private var liveCache: Pair<Long, List<MatchoraEvent>>? = null
    @Volatile private var schedCache: Pair<Long, List<MatchoraEvent>>? = null
    @Volatile private var itChCache: Pair<Long, List<MatchoraChannel>>? = null
    private val htPageCache = ConcurrentHashMap<String, Pair<Long, ResolvedHtPage>>()
    private val eventById = ConcurrentHashMap<String, MatchoraEvent>()
    private val htMatchById = ConcurrentHashMap<String, HtMatch>()

    fun cachedEvent(id: String): MatchoraEvent? = eventById[id]
    fun cachedHtMatch(id: String): HtMatch? = htMatchById[id]

    private fun getText(url: String, headers: Map<String, String> = emptyMap()): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> req.header(k, v) }
        return try {
            http.newCall(req.build()).execute().use { res ->
                if (res.code in 200..299) res.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun originOf(url: String): String = try {
        val u = URI(url)
        val port = if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Exception) {
        HTSPORT_BASE
    }

    private fun normalizeIframeUrl(raw: String, baseUrl: String): String? {
        val cleaned = raw.replace("\\/", "/").trim()
        if (cleaned.isBlank()) return null
        return when {
            cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            else -> runCatching { URI(baseUrl).resolve(cleaned).toString() }.getOrNull()
        }
    }

    // ── Matchora play resolution ────────────────────────────────────────────────

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetchEmbedParams(channelId: String): Pair<String, String>? {
        val html = getText(
            "$MATCHORA_BASE/embed/channel/$channelId",
            mapOf(
                "Referer" to "$HTSPORT_BASE/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8"
            )
        ) ?: return null
        val pp = Regex("""var\s+PP\s*=\s*"([^"_][^"]*)"""").find(html)?.groupValues?.get(1) ?: return null
        val fx = Regex("""\bFX\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        return pp to fx
    }

    suspend fun resolvePlay(
        channelId: String,
        knownPp: String? = null,
        knownFx: String? = null,
        blockedHost: String? = null
    ): PlaySession? = withContext(Dispatchers.IO) {
        var pp = knownPp?.takeIf { it.isNotBlank() && !it.startsWith("_") }.orEmpty()
        var fx = knownFx.orEmpty()
        var refreshed = false

        if (pp.isBlank()) {
            val pair = fetchEmbedParams(channelId) ?: return@withContext null
            pp = pair.first
            if (fx.isBlank()) fx = pair.second
            refreshed = true
        }

        val embedRef = "$MATCHORA_BASE/embed/channel/$channelId"
        for (attempt in 0..2) {
            val query = buildList {
                if (fx.isNotBlank()) add("fx=${enc(fx)}")
                if (pp.isNotBlank()) add("pp=${enc(pp)}")
                if (!blockedHost.isNullOrBlank()) add("blocked=${enc(blockedHost)}")
            }.joinToString("&")
            val apiUrl = "$MATCHORA_BASE/api/play/$channelId" + if (query.isNotEmpty()) "?$query" else ""

            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", UA)
                .header("Referer", embedRef)
                .header("Accept", "*/*")
                .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            val body = try {
                http.newCall(req).execute().use { it.body?.string() }
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
            val nextPp = json.optString("pp", "")
            if (nextPp.isNotBlank() && !nextPp.startsWith("_")) pp = nextPp

            val ready = json.optBoolean("ready", false)
            val streamUrl = json.optString("url", "")
            if (ready && streamUrl.startsWith("http")) {
                return@withContext PlaySession(
                    channelId = channelId,
                    m3u8Url = streamUrl,
                    pp = pp,
                    fx = fx,
                    expiresIn = json.optInt("expires_in", 600)
                )
            }

            val reason = json.optString("reason", "").lowercase()
            if ((reason == "bot" || reason == "expired" || reason == "pass") && !refreshed) {
                val pair = fetchEmbedParams(channelId) ?: return@withContext null
                pp = pair.first
                if (fx.isBlank()) fx = pair.second
                refreshed = true
                continue
            }
            if (reason in setOf("warming", "starting", "loading", "wait", "buffering") && attempt < 2) {
                delay(1_500L)
                continue
            }
            return@withContext null
        }
        null
    }

    // ── _econfig embed resolver (ordernap / dyntiptoe / sportsonline) ───────────

    private fun b64DecodeLatin1(input: String): String {
        val bytes = Base64.decode(input.trim(), Base64.DEFAULT)
        return String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * Decodes `window._econfig` used by ordernap.net / dyntiptoe.net embeds:
     * base64 -> 4 equal chunks -> strip index 3 char -> base64 -> reorder [2,0,3,1] -> base64 -> JSON.
     */
    fun decodeEconfig(encoded: String): JSONObject? = try {
        val layer1 = b64DecodeLatin1(encoded)
        val chunkSize = (layer1.length + 3) / 4
        val order = intArrayOf(2, 0, 3, 1)
        val reordered = arrayOfNulls<String>(4)
        for (i in 0 until 4) {
            val start = i * chunkSize
            val end = minOf(layer1.length, start + chunkSize)
            if (start >= end) return null
            val chunk = layer1.substring(start, end)
            val cleaned = if (chunk.length > 4) chunk.substring(0, 3) + chunk.substring(4) else chunk
            reordered[order[i]] = b64DecodeLatin1(cleaned)
        }
        val joined = reordered.joinToString("") { it.orEmpty() }
        val jsonBytes = Base64.decode(joined, Base64.DEFAULT)
        JSONObject(String(jsonBytes, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    suspend fun resolveEconfig(embedUrl: String, referer: String): DirectStream? = withContext(Dispatchers.IO) {
        val html = getText(
            embedUrl,
            mapOf(
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ) ?: return@withContext null
        val encoded = Regex("""window\._econfig\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: return@withContext null
        val cfg = decodeEconfig(encoded) ?: return@withContext null
        // Skip only if Turnstile is actually enforced for this stream.
        if (cfg.optString("turnstile_key", "").isNotBlank()) return@withContext null
        val streamUrl = cfg.optString("stream_url", "").takeIf { it.startsWith("http") }
            ?: cfg.optString("stream_url2", "").takeIf { it.startsWith("http") }
            ?: return@withContext null
        val origin = originOf(embedUrl)
        DirectStream(m3u8Url = streamUrl, referer = "$origin/", origin = origin)
    }

    // ── WideIPTV resolver (wideiptv.top & hesgoalsss) ───────────────────────────

    suspend fun resolveWideIptv(slug: String): WideIptvSession? = withContext(Dispatchers.IO) {
        val clean = slug.trim()
        if (clean.isBlank()) return@withContext null
        val html = getText(
            "$WIDEIPTV_BASE/player/${enc(clean)}",
            mapOf(
                "Referer" to "$HTSPORT_BASE/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ) ?: return@withContext null
        val streamUrl = Regex("""streamUrl\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http") }
            ?: return@withContext null
        val token = Regex("""currentToken\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]token=([^&]+)""").find(streamUrl)?.groupValues?.get(1)
            ?: return@withContext null
        val channelSlug = Regex("""channelSlug\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() } ?: clean
        WideIptvSession(slug = channelSlug, m3u8Url = streamUrl, token = token)
    }

    private fun refreshWideIptvToken(slug: String, currentToken: String): String? {
        val payload = JSONObject()
            .put("channel", slug)
            .put("current_token", currentToken)
            .toString()
        val req = Request.Builder()
            .url("$WIDEIPTV_BASE/api/refresh_token.php")
            .header("User-Agent", UA)
            .header("Referer", "$WIDEIPTV_BASE/player/${enc(slug)}")
            .header("Origin", WIDEIPTV_BASE)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val renewed = try {
            http.newCall(req).execute().use { res ->
                if (res.code !in 200..299) null
                else {
                    val j = JSONObject(res.body?.string().orEmpty())
                    if (j.optBoolean("success", false)) j.optString("token", "").takeIf { it.isNotBlank() } else null
                }
            }
        } catch (_: Exception) {
            null
        }
        if (renewed != null) return renewed
        // Fallback: re-fetch the player page for a fresh token.
        return runBlocking { withTimeoutOrNull(12_000L) { resolveWideIptv(slug)?.token } }
    }

    // ── HTSport scraping ────────────────────────────────────────────────────────

    suspend fun fetchHtHome(): HtHome = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        htCache?.takeIf { now - it.first < 90_000L }?.let { return@withContext it.second }

        val html = getText("$HTSPORT_BASE/") ?: return@withContext htCache?.second ?: HtHome(emptyList(), emptyList())
        val doc = Jsoup.parse(html, "$HTSPORT_BASE/")
        val channels = mutableListOf<HtLink>()
        val matches = mutableListOf<HtMatch>()
        var currentCategory = "Sport"

        for (el in doc.select("span.category-label, div.match-card")) {
            if (el.hasClass("category-label")) {
                val cat = el.text().trim().trimEnd('-').trim()
                if (cat.isNotBlank()) currentCategory = cat
                continue
            }
            val links = el.select(".btn-group a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.endsWith(".htm", true) || href.equals("index.htm", true)) return@mapNotNull null
                val label = a.text().replace(Regex("\\s+"), " ").trim()
                if (label.isBlank()) null else HtLink(label, href.substringAfterLast('/'))
            }.distinctBy { it.page.lowercase() }
            if (links.isEmpty()) continue

            val timeText = el.selectFirst(".time-box")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val teamsText = el.selectFirst(".teams-box")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val isChannelBox = timeText.contains("Elenco Canali", true) ||
                (teamsText.equals("Live", true) && links.size >= 8)

            if (isChannelBox) {
                if (channels.isEmpty()) channels.addAll(links)
            } else if (teamsText.isNotBlank()) {
                val id = "${matches.size}_${teamsText.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
                matches += HtMatch(
                    id = id,
                    title = teamsText,
                    time = timeText.replace(Regex("(?i)^live\\s*"), "").trim(),
                    category = currentCategory,
                    live = el.hasClass("is-live"),
                    links = links
                )
            }
        }

        matches.forEach { htMatchById[it.id] = it }
        val result = HtHome(channels, matches)
        if (channels.isNotEmpty() || matches.isNotEmpty()) htCache = now to result
        result
    }

    suspend fun resolveHtPage(page: String): ResolvedHtPage = withContext(Dispatchers.IO) {
        val clean = page.trim().substringAfterLast('/')
        val now = System.currentTimeMillis()
        htPageCache[clean]?.takeIf { now - it.first < 10 * 60_000L }?.let { return@withContext it.second }

        val pageUrl = "$HTSPORT_BASE/$clean"
        val html = getText(pageUrl, mapOf("Referer" to "$HTSPORT_BASE/"))
            ?: return@withContext ResolvedHtPage()

        // 1. Matchora channel or match embed on any TLD (.pro, .st, .to, ...)
        Regex("""matchora\.[a-z0-9.-]+/embed/channel/(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { cid ->
                val res = ResolvedHtPage(matchoraChannelId = cid)
                htPageCache[clean] = now to res
                return@withContext res
            }

        Regex("""matchora\.[a-z0-9.-]+/embed/match/(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { eid ->
                val res = ResolvedHtPage(matchoraEventId = eid)
                htPageCache[clean] = now to res
                return@withContext res
            }

        // 2. WideIPTV direct or via hesgoalsss (?id=...)
        Regex("""wideiptv\.top/player/([^"'?&#\s<>]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { slug ->
                val res = ResolvedHtPage(wideIptvSlug = runCatching { URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug))
                htPageCache[clean] = now to res
                return@withContext res
            }

        Regex("""hesgoalsss\.blogspot\.com/p/ntv\.html\?id=([^"'&#\s<>]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { slug ->
                val res = ResolvedHtPage(wideIptvSlug = runCatching { URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug))
                htPageCache[clean] = now to res
                return@withContext res
            }

        // 3. Direct m3u8 (e.g. m3u8player.online wrapper for Rai 1/2)
        val wrappedM3u8 = Regex("""m3u8player\.online/embed/m3u8\?url=([^"'&\s<>]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val directM3u8 = wrappedM3u8 ?: Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?.takeUnless { it.contains("ondemand.st/player/hls", true) }
        if (directM3u8 != null) {
            val res = ResolvedHtPage(directM3u8 = directM3u8)
            htPageCache[clean] = now to res
            return@withContext res
        }

        // 4. Inspect iframe (up to 2 hops) for _econfig players (ordernap, sportsonliine -> dyntiptoe, etc.)
        val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).mapNotNull { normalizeIframeUrl(it.groupValues[1], pageUrl) }.toList()
        for (iframeUrl in iframes) {
            if (iframeUrl.contains("zicotv.cc", true)) continue
            val hop1 = getText(iframeUrl, mapOf("Referer" to pageUrl)) ?: continue
            if (hop1.contains("window._econfig")) {
                val res = ResolvedHtPage(econfigUrl = iframeUrl, econfigReferer = pageUrl)
                htPageCache[clean] = now to res
                return@withContext res
            }
            // Check 1 nested iframe (e.g. sportsonliine.click/channels/hd/hd7.php -> dyntiptoe.net/e/...)
            val inner = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(hop1)?.groupValues?.get(1)?.let { normalizeIframeUrl(it, iframeUrl) }
                ?: continue
            Regex("""matchora\.[a-z0-9.-]+/embed/channel/(\d+)""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)?.let { cid ->
                    val res = ResolvedHtPage(matchoraChannelId = cid)
                    htPageCache[clean] = now to res
                    return@withContext res
                }
            val hop2 = getText(inner, mapOf("Referer" to iframeUrl)) ?: continue
            if (hop2.contains("window._econfig")) {
                val res = ResolvedHtPage(econfigUrl = inner, econfigReferer = iframeUrl)
                htPageCache[clean] = now to res
                return@withContext res
            }
        }

        val empty = ResolvedHtPage()
        htPageCache[clean] = now to empty
        empty
    }

    // ── Matchora public catalog APIs ────────────────────────────────────────────

    private fun parseEventObject(o: JSONObject): MatchoraEvent? {
        val id = o.optString("id", "").ifBlank { return null }
        val home = o.optString("home", "").trim()
        val away = o.optString("away", "").trim()
        val title = when {
            home.isNotBlank() && away.isNotBlank() && !home.equals(away, true) -> "$home vs $away"
            home.isNotBlank() -> home
            away.isNotBlank() -> away
            else -> o.optString("league", "Evento $id")
        }
        val chArr = o.optJSONArray("channels") ?: JSONArray()
        val channels = ArrayList<MatchoraChannel>(chArr.length())
        for (j in 0 until chArr.length()) {
            val c = chArr.optJSONObject(j) ?: continue
            val cid = c.optString("id", "").ifBlank {
                Regex("""/channel/(\d+)""").find(c.optString("embed_url", ""))?.groupValues?.get(1) ?: continue
            }
            channels += MatchoraChannel(
                id = cid,
                name = c.optString("name", "Canale $cid").trim(),
                quality = c.optString("quality", "").trim(),
                country = c.optString("country", "").trim(),
                playing = c.optBoolean("playing", true),
                dead = c.optBoolean("dead", false)
            )
        }
        if (channels.isEmpty()) return null
        val poster = listOf("league_badge", "home_badge", "away_badge")
            .firstNotNullOfOrNull { k -> o.optString(k, "").takeIf { it.startsWith("http") } }
        return MatchoraEvent(
            id = id,
            title = title,
            sport = o.optString("sport", "Sport").trim().ifBlank { "Sport" },
            league = o.optString("league", "").trim(),
            kickoffSec = o.optLong("kickoff", 0L),
            live = o.optBoolean("live", false),
            score = o.optString("score", "").takeIf { it.isNotBlank() && it != "null" },
            minute = o.optString("minute", "").takeIf { it.isNotBlank() && it != "null" },
            poster = poster,
            channels = channels
        )
    }

    private fun parseEvents(text: String): List<MatchoraEvent> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<MatchoraEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseEventObject(o)?.let { out += it }
        }
        out.forEach { eventById[it.id] = it }
        return out
    }

    suspend fun fetchEventById(eventId: String): MatchoraEvent? = withContext(Dispatchers.IO) {
        eventById[eventId]?.let { return@withContext it }
        val text = getText("$MATCHORA_BASE/api/v1/events/${enc(eventId)}") ?: return@withContext null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val evObj = root.optJSONObject("event") ?: root
        val parsed = parseEventObject(evObj) ?: return@withContext null
        eventById[parsed.id] = parsed
        eventById[eventId] = parsed
        parsed
    }

    suspend fun fetchLiveEvents(): List<MatchoraEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        liveCache?.takeIf { now - it.first < 60_000L }?.let { return@withContext it.second }
        val text = getText("$MATCHORA_BASE/api/v1/live") ?: return@withContext liveCache?.second.orEmpty()
        val list = parseEvents(text)
        if (list.isNotEmpty()) liveCache = now to list
        list
    }

    suspend fun fetchSchedule(): List<MatchoraEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        schedCache?.takeIf { now - it.first < 180_000L }?.let { return@withContext it.second }
        val text = getText("$MATCHORA_BASE/api/v1/schedule?watchable=1")
            ?: return@withContext schedCache?.second.orEmpty()
        val list = parseEvents(text)
        if (list.isNotEmpty()) schedCache = now to list
        list
    }

    suspend fun fetchItalianChannels(): List<MatchoraChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        itChCache?.takeIf { now - it.first < 10 * 60_000L }?.let { return@withContext it.second }
        val text = getText("$MATCHORA_BASE/api/v1/channels")
            ?: return@withContext itChCache?.second ?: FALLBACK_IT_CHANNELS
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext FALLBACK_IT_CHANNELS
        val arr = root.optJSONArray("channels") ?: return@withContext FALLBACK_IT_CHANNELS
        val seen = HashSet<String>()
        val out = mutableListOf<MatchoraChannel>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val groupName = c.optString("name", "").trim()
            val groupQ = c.optString("quality", "").trim()
            val feeds = c.optJSONArray("feeds") ?: continue
            for (j in 0 until feeds.length()) {
                val f = feeds.optJSONObject(j) ?: continue
                if (!f.optString("country", "").equals("IT", true)) continue
                val id = Regex("""/channel/(\d+)""").find(f.optString("embed_url", ""))?.groupValues?.get(1)
                    ?: continue
                if (!seen.add(id)) continue
                val name = f.optString("label", "").trim().ifBlank { groupName.ifBlank { "Canale $id" } }
                val q = f.optString("quality", "").trim().ifBlank { groupQ }
                out += MatchoraChannel(id = id, name = name, quality = q, country = "IT")
            }
        }
        val priority = listOf("sky sport", "dazn 1", "dazn", "champions", "euro", "rai", "sport")
        val sorted = out.sortedWith(
            compareBy<MatchoraChannel>(
                { ch ->
                    val low = ch.name.lowercase()
                    val idx = priority.indexOfFirst { low.contains(it) }
                    if (idx >= 0) idx else priority.size
                },
                { it.name.lowercase() }
            )
        )
        if (sorted.isNotEmpty()) itChCache = now to sorted
        sorted.ifEmpty { FALLBACK_IT_CHANNELS }
    }

    // ── Fake-PNG -> MPEG-TS unwrapper ───────────────────────────────────────────

    private class PngPart(
        val idx: Int,
        val isPayload: Boolean,
        val compFlag: Int,
        val offset: Int,
        val length: Int
    )

    private fun indexOfZero(buf: ByteArray, start: Int, end: Int): Int {
        for (i in start until end) if (buf[i] == 0.toByte()) return i
        return -1
    }

    private fun readUInt32BE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFFL) shl 24) or
            ((buf[off + 1].toLong() and 0xFFL) shl 16) or
            ((buf[off + 2].toLong() and 0xFFL) shl 8) or
            (buf[off + 3].toLong() and 0xFFL)

    private fun findTsOffset(buf: ByteArray): Int {
        val limit = minOf(buf.size - 189, 65536)
        for (i in 0..limit) {
            if (buf[i] == 0x47.toByte() && buf[i + 188] == 0x47.toByte()) return i
        }
        return -1
    }

    private fun inflateInto(src: ByteArray, offset: Int, length: Int, out: ByteArrayOutputStream): Boolean {
        val inflater = Inflater()
        return try {
            inflater.setInput(src, offset, length)
            val tmp = ByteArray(16384)
            while (!inflater.finished()) {
                val n = inflater.inflate(tmp)
                if (n > 0) {
                    out.write(tmp, 0, n)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            inflater.end()
        }
    }

    fun isPlaylistBytes(buf: ByteArray): Boolean {
        var i = 0
        while (i < buf.size && i < 16 && (buf[i] == ' '.code.toByte() || buf[i] == '\n'.code.toByte() ||
                buf[i] == '\r'.code.toByte() || buf[i] == '\t'.code.toByte())
        ) i++
        if (i + 7 > buf.size) return false
        return buf[i] == '#'.code.toByte() &&
            buf[i + 1] == 'E'.code.toByte() &&
            buf[i + 2] == 'X'.code.toByte() &&
            buf[i + 3] == 'T'.code.toByte() &&
            buf[i + 4] == 'M'.code.toByte() &&
            buf[i + 5] == '3'.code.toByte() &&
            buf[i + 6] == 'U'.code.toByte()
    }

    /**
     * Extracts MPEG-TS bytes hidden inside a Matchora PNG (`iTXt` `payload-*` chunks,
     * optional zlib compression, Base64). Plain MPEG-TS and fMP4 buffers pass through.
     */
    fun unwrapSegment(buf: ByteArray): ByteArray {
        if (buf.size < 8 || buf[0] == 0x47.toByte()) return buf
        val isPng = buf[0] == 0x89.toByte() && buf[1] == 0x50.toByte() &&
            buf[2] == 0x4E.toByte() && buf[3] == 0x47.toByte()
        if (!isPng) {
            if (buf.size >= 8) {
                val box = String(buf, 4, 4, Charsets.ISO_8859_1)
                if (box == "ftyp" || box == "moof" || box == "styp") return buf
            }
            val off = findTsOffset(buf)
            return if (off > 0) buf.copyOfRange(off, buf.size) else buf
        }

        val parts = ArrayList<PngPart>(64)
        var off = 8
        while (off + 12 <= buf.size) {
            val lenL = readUInt32BE(buf, off)
            if (lenL < 0 || off + 12L + lenL > buf.size) break
            val len = lenL.toInt()
            if (buf[off + 4] == 'I'.code.toByte() && buf[off + 5] == 'E'.code.toByte() &&
                buf[off + 6] == 'N'.code.toByte() && buf[off + 7] == 'D'.code.toByte()
            ) break
            if (buf[off + 4] == 'i'.code.toByte() && buf[off + 5] == 'T'.code.toByte() &&
                buf[off + 6] == 'X'.code.toByte() && buf[off + 7] == 't'.code.toByte()
            ) {
                val dataStart = off + 8
                val dataEnd = dataStart + len
                val kwEnd = indexOfZero(buf, dataStart, dataEnd)
                if (kwEnd > dataStart && kwEnd + 3 <= dataEnd) {
                    val keyword = String(buf, dataStart, kwEnd - dataStart, Charsets.ISO_8859_1)
                    val compFlag = buf[kwEnd + 1].toInt() and 0xFF
                    val langEnd = indexOfZero(buf, kwEnd + 3, dataEnd)
                    val transEnd = if (langEnd >= 0) indexOfZero(buf, langEnd + 1, dataEnd) else -1
                    if (transEnd >= 0 && transEnd + 1 <= dataEnd) {
                        val textStart = transEnd + 1
                        val textLen = dataEnd - textStart
                        val idx = Regex("""(\d+)""").find(keyword)?.groupValues?.get(1)?.toIntOrNull()
                            ?: parts.size
                        parts.add(
                            PngPart(
                                idx = idx,
                                isPayload = keyword.startsWith("payload-"),
                                compFlag = compFlag,
                                offset = textStart,
                                length = textLen
                            )
                        )
                    }
                }
            }
            off += 12 + len
        }

        if (parts.isEmpty()) return buf
        val selected = parts.filter { it.isPayload }.ifEmpty { parts }.sortedBy { it.idx }
        val b64Out = ByteArrayOutputStream(buf.size + buf.size / 3)
        for (p in selected) {
            if (p.compFlag == 1) {
                if (!inflateInto(buf, p.offset, p.length, b64Out)) return buf
            } else {
                b64Out.write(buf, p.offset, p.length)
            }
        }

        val ts = try {
            Base64.decode(b64Out.toByteArray(), Base64.DEFAULT)
        } catch (_: Exception) {
            return buf
        }
        if (ts.isNotEmpty() && ts[0] == 0x47.toByte()) return ts
        val tsOff = findTsOffset(ts)
        return if (tsOff >= 0) ts.copyOfRange(tsOff, ts.size) else buf
    }

    // ── CloudStream video interceptors ──────────────────────────────────────────

    fun interceptorFor(link: ExtractorLink): Interceptor? {
        val data = link.extractorData ?: return null
        if (data.startsWith(DATA_PREFIX)) {
            val fields = data.removePrefix(DATA_PREFIX).split("|")
            val channelId = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            return MatchoraInterceptor(
                channelId = channelId,
                initialUrl = link.url,
                initialPp = fields.getOrNull(1).orEmpty(),
                initialFx = fields.getOrNull(2).orEmpty()
            )
        }
        if (data.startsWith(WIDEIPTV_PREFIX)) {
            val fields = data.removePrefix(WIDEIPTV_PREFIX).split("|")
            val slug = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val token = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            return WideIptvInterceptor(slug = slug, initialToken = token)
        }
        return null
    }

    private class WideIptvInterceptor(
        private val slug: String,
        initialToken: String
    ) : Interceptor {
        private val lock = Any()
        @Volatile private var currentToken = initialToken
        @Volatile private var tokenAt = System.currentTimeMillis()
        @Volatile private var lastRenewAt = 0L

        private fun withToken(url: okhttp3.HttpUrl, token: String): okhttp3.HttpUrl {
            if (url.queryParameter("token") == null || url.queryParameter("token") == token) return url
            val b = url.newBuilder().removeAllQueryParameters("token")
            return b.addQueryParameter("token", token).build()
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            if (System.currentTimeMillis() - tokenAt > 240_000L) {
                renew(force = false)
            }
            val req = orig.newBuilder()
                .url(withToken(orig.url, currentToken))
                .header("User-Agent", UA)
                .header("Referer", "$WIDEIPTV_BASE/")
                .header("Origin", WIDEIPTV_BASE)
                .build()
            var response = chain.proceed(req)
            if (response.code == 401 || response.code == 403) {
                val fresh = renew(force = true)
                if (fresh != null) {
                    response.close()
                    val retry = orig.newBuilder()
                        .url(withToken(orig.url, fresh))
                        .header("User-Agent", UA)
                        .header("Referer", "$WIDEIPTV_BASE/")
                        .header("Origin", WIDEIPTV_BASE)
                        .header("Cache-Control", "no-cache")
                        .build()
                    response = chain.proceed(retry)
                }
            }
            return response
        }

        private fun renew(force: Boolean): String? = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - tokenAt <= 240_000L) return currentToken
            if (now - lastRenewAt < 5_000L) return currentToken
            lastRenewAt = now
            val next = refreshWideIptvToken(slug, currentToken) ?: return null
            currentToken = next
            tokenAt = System.currentTimeMillis()
            next
        }
    }

    private class MatchoraInterceptor(
        private val channelId: String,
        private val initialUrl: String,
        initialPp: String,
        initialFx: String
    ) : Interceptor {
        private val lock = Any()
        @Volatile private var activePlaylistUrl = initialUrl
        @Volatile private var currentPp = initialPp
        @Volatile private var currentFx = initialFx
        @Volatile private var lastRenewAt = 0L

        private val tsMediaType = "video/mp2t".toMediaType()
        private val m3u8MediaType = "application/vnd.apple.mpegurl".toMediaType()

        override fun intercept(chain: Interceptor.Chain): Response {
            val origReq = chain.request()
            val reqUrl = origReq.url.toString()
            val isRootPlaylist = reqUrl == initialUrl || reqUrl == activePlaylistUrl
            val isPlaylistUrl = isRootPlaylist || origReq.url.encodedPath.endsWith(".m3u8", true)

            val targetUrl = if (isRootPlaylist) activePlaylistUrl else reqUrl
            val reqBuilder = origReq.newBuilder()
                .url(targetUrl)
                .header("User-Agent", UA)
                .header("Referer", "$MATCHORA_BASE/")
                .header("Origin", MATCHORA_BASE)
            if (!isPlaylistUrl) {
                reqBuilder.header("Accept-Encoding", "identity")
            }

            var response = chain.proceed(reqBuilder.build())

            if (isPlaylistUrl) {
                val invalidPlaylist = !response.isSuccessful || !peekIsM3u8(response)
                if (invalidPlaylist) {
                    val fresh = renewPlaylist(if (response.isSuccessful) hostOf(targetUrl) else null)
                    if (fresh != null) {
                        response.close()
                        val retryReq = origReq.newBuilder()
                            .url(fresh)
                            .header("User-Agent", UA)
                            .header("Referer", "$MATCHORA_BASE/")
                            .header("Origin", MATCHORA_BASE)
                            .header("Cache-Control", "no-cache")
                            .build()
                        response = chain.proceed(retryReq)
                    }
                }
                return response
            }

            if (!response.isSuccessful) return response
            val origContentType = response.body?.contentType()
            val raw = response.body?.bytes() ?: return response
            if (isPlaylistBytes(raw)) {
                return response.newBuilder()
                    .body(raw.toResponseBody(m3u8MediaType))
                    .header("Content-Type", m3u8MediaType.toString())
                    .header("Content-Length", raw.size.toString())
                    .removeHeader("Content-Encoding")
                    .build()
            }

            val unwrapped = unwrapSegment(raw)
            val mediaType = if (unwrapped !== raw || (unwrapped.isNotEmpty() && unwrapped[0] == 0x47.toByte())) {
                tsMediaType
            } else {
                origContentType ?: tsMediaType
            }
            return response.newBuilder()
                .body(unwrapped.toResponseBody(mediaType))
                .header("Content-Type", mediaType.toString())
                .header("Content-Length", unwrapped.size.toString())
                .removeHeader("Content-Encoding")
                .build()
        }

        private fun peekIsM3u8(response: Response): Boolean = try {
            val bytes = response.peekBody(64).bytes()
            isPlaylistBytes(bytes)
        } catch (_: Exception) {
            false
        }

        private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

        private fun renewPlaylist(blockedHost: String?): String? = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastRenewAt < 5_000L) return activePlaylistUrl.takeIf { it != initialUrl }
            lastRenewAt = now
            val session = runBlocking {
                withTimeoutOrNull(15_000L) {
                    resolvePlay(
                        channelId = channelId,
                        knownPp = currentPp,
                        knownFx = currentFx,
                        blockedHost = blockedHost
                    )
                }
            } ?: return null
            activePlaylistUrl = session.m3u8Url
            currentPp = session.pp
            if (session.fx.isNotBlank()) currentFx = session.fx
            session.m3u8Url
        }
    }
}
