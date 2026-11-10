package prisynx.providers.core

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

val UA_POOL = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15",
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
)

fun getRandomUa(): String = UA_POOL.random()

val UP_UA: String get() = getRandomUa()
private const val UP_COOKIE2 = "ccd14f7d7ee32ff386eefcfda920a476"
private const val UC_URL = "https://withered-dawn-64ee.openlellino1.workers.dev"
private const val UC_PEER = "https://upchapta-nuvio.openlellino2.workers.dev"
private const val UC_KEY = "prisynx"
private val MS_PLAYER = Regex(
    "^https?://(?:www\\.)?maxstream\\.video/(?!uprot)[A-Za-z0-9]+/[A-Za-z0-9]+/?$",
    RegexOption.IGNORE_CASE
)

@Volatile
private var cachedMaxstreamPrefix: String? = null
@Volatile
private var cachedPrefixTime: Long = 0L

suspend fun getActiveMaxstreamPrefix(ua: String = UP_UA): String {
    val now = System.currentTimeMillis()
    val cached = cachedMaxstreamPrefix
    if (cached != null && now - cachedPrefixTime < 3600000L) {
        return cached
    }
    return try {
        val html = net.get("https://maxstream.video/", headers = mapOf("User-Agent" to ua), timeout = 10).text
        val m = Regex("src=[\"']https?://(?:www\\.)?maxstream\\.video/([A-Za-z0-9]+)/[A-Za-z0-9]+[\"']", RegexOption.IGNORE_CASE).find(html)
        val prefix = m?.groupValues?.get(1) ?: cached ?: "emisghyy"
        cachedMaxstreamPrefix = prefix
        cachedPrefixTime = now
        prefix
    } catch (e: Exception) {
        cached ?: "emisghyy"
    }
}

suspend fun normalizeMaxstreamPlayer(url: String?, ua: String = UP_UA): String? {
    if (url.isNullOrBlank()) return null
    val prefix = getActiveMaxstreamPrefix(ua)
    val m = Regex("^https?://(?:www\\.)?maxstream\\.video/([A-Za-z0-9]+)/([A-Za-z0-9]+)/?$", RegexOption.IGNORE_CASE).find(url)
    return if (m != null && m.groupValues[1] != prefix) {
        "https://maxstream.video/$prefix/${m.groupValues[2]}"
    } else {
        url
    }
}

data class UprotStream(
    val url: String,
    val quality: Int,
    val headers: Map<String, String>
)

/**
 * Resolve a uprot.net /msf|msfi|msei/ link to a playable MaxStream HLS URL.
 * The worker cache is consulted first: one captcha solves a link for every user.
 */
suspend fun resolveUprot(link: String, sessionUa: String = getRandomUa()): UprotStream? {
    val key = uprotKey(link)
    val cached = cachedPlayer(key)
    var media: String? = null
    if (cached != null) {
        val norm = normalizeMaxstreamPlayer(cached, sessionUa) ?: cached
        media = maxstreamM3u8(norm, sessionUa)
        if (media != null && norm != cached) {
            storePlayer(key, norm)
        }
    }
    if (media == null) {
        // No cached player, or the cached one has expired: unlock and cache a fresh link.
        val uprots = unlockUprot(link, sessionUa) ?: return null
        val player = maxstreamPlayerUrl(uprots, sessionUa) ?: return null
        val normPlayer = normalizeMaxstreamPlayer(player, sessionUa) ?: player
        media = maxstreamM3u8(normPlayer, sessionUa) ?: return null
        storePlayer(key, normPlayer)
    }
    val headers = mapOf(
        "User-Agent" to sessionUa,
        "Referer" to "https://maxstream.video/"
    )
    val quality = hlsQuality(media, headers)
    return UprotStream(media, quality, headers)
}

// ---------------------------------------------------------------- captcha

private data class UpCell(val idx: Int, val src: String)

private data class UpCaptcha(
    val field: String,
    val target: String,
    val cells: List<UpCell>
)

private suspend fun unlockUprot(link: String, ua: String): String? {
    val jar = linkedMapOf(
        "uprot_session_pxx" to "1"
    )
    // 1. Initial GET to establish clean session cookies (PHPSESSID) and bypass if already unlocked
    var html = upGet(link, jar, ua)
    if (html.isBlank()) {
        html = upPost(link, emptyMap(), jar, ua)
    }
    for (attempt in 0 until 4) {
        findButtok(html)?.let { return it }
        val cap = parseUpCaptcha(html) ?: return null
        if (!jar.containsKey("PHPSESSID") || attempt == 3) return null
        val answer = solveUpCaptcha(cap) ?: ""
        val body = if (answer.isNotEmpty()) mapOf(cap.field to answer) else emptyMap()
        // 2. 1.5s delay before POST to avoid HTTP 503 "Request limit exceeded"
        delay(1500L)
        html = upPost(link, body, jar, ua)
    }
    return null
}

private suspend fun upGet(
    link: String,
    jar: MutableMap<String, String>,
    ua: String
): String {
    val headers = mutableMapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )
    val cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookie.isNotBlank()) headers["Cookie"] = cookie

    val response = try {
        net.get(
            link,
            headers = headers,
            allowRedirects = false,
            timeout = 25
        )
    } catch (e: Exception) {
        return ""
    }
    response.cookies.forEach { (k, v) -> jar[k] = v }
    response.headers.values("set-cookie").forEach { line ->
        Regex("(?:^|,)\\s*([A-Za-z0-9_\\-]+)=([^;,]*)").findAll(line).forEach { m ->
            jar[m.groupValues[1]] = m.groupValues[2]
        }
    }
    return response.text
}

private suspend fun upPost(
    link: String,
    body: Map<String, String>,
    jar: MutableMap<String, String>,
    ua: String
): String {
    val headers = mutableMapOf(
        "User-Agent" to ua,
        "Origin" to "https://uprot.net",
        "Referer" to "https://uprot.net/",
        "Content-Type" to "application/x-www-form-urlencoded"
    )
    val cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookie.isNotBlank()) headers["Cookie"] = cookie

    val response = try {
        val form = FormBody.Builder()
        body.forEach { (k, v) -> form.add(k, v) }
        net.post(
            link,
            headers = headers,
            data = null,
            requestBody = form.build(),
            allowRedirects = false,
            timeout = 25
        )
    } catch (e: Exception) {
        return ""
    }
    response.cookies.forEach { (k, v) -> jar[k] = v }
    response.headers.values("set-cookie").forEach { line ->
        Regex("(?:^|,)\\s*([A-Za-z0-9_\\-]+)=([^;,]*)").findAll(line).forEach { m ->
            jar[m.groupValues[1]] = m.groupValues[2]
        }
    }
    return response.text
}

private fun findButtok(html: String): String? {
    val clean = html
        .replace(Regex("<!--[\\s\\S]*?-->"), "")
        .replace(
            Regex(
                "<div[^>]*style=[\"'][^\"']*display\\s*:\\s*none[^\"']*[\"'][^>]*>[\\s\\S]*?</div>",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
    val atobMatch = Regex("id=[\"']buttok[\"'][^>]*onclick=[\"'][^\"']*atob\\([\"']([A-Za-z0-9+/=]+)[\"']\\)", RegexOption.IGNORE_CASE).find(clean)
        ?: Regex("onclick=[\"'][^\"']*atob\\([\"']([A-Za-z0-9+/=]+)[\"']\\)[^\"']*[\"'][^>]*id=[\"']buttok[\"']", RegexOption.IGNORE_CASE).find(clean)
    if (atobMatch != null) {
        val b64 = atobMatch.groupValues[1]
        try {
            val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            if (Regex("^https?://[^\\s\"'<>]+$", RegexOption.IGNORE_CASE).matches(decoded)) {
                return decoded
            }
        } catch (_: Exception) {}
    }
    return Regex(
        "href=[\"'](https?://[^\"'/]+/uprot[a-z]{0,4}/[^/\"']+/[^/\"']+/?)[\"'][^>]*>\\s*<button",
        RegexOption.IGNORE_CASE
    ).find(clean)?.groupValues?.get(1)
}

private fun parseUpCaptcha(html: String): UpCaptcha? {
    val field = Regex("name=[\"'](upcapt_[A-Za-z0-9]+)[\"']").find(html)
        ?.groupValues?.get(1) ?: return null
    val first = Regex(
        "<[^>]+class=[\"'][^\"']*upcaptcha-cell[^\"']*[\"'][^>]*data-idx=",
        RegexOption.IGNORE_CASE
    ).find(html)?.range?.first ?: return null

    val before = html.substring(maxOf(0, first - 4000), first)
    var target: String? = null
    Regex("<strong>([^<]{1,40})</strong>").findAll(before).forEach { m ->
        target = decodeHtmlEntities(m.groupValues[1]).trim()
    }
    val cells = mutableListOf<UpCell>()
    Regex(
        "<[^>]+class=[\"'][^\"']*upcaptcha-cell[^\"']*[\"'][^>]*data-idx=[\"'](\\d+)[\"'][^>]*>[\\s\\S]*?<img[^>]+src=[\"'](data:image/(?:jpeg|png);base64,[A-Za-z0-9+/=]+)[\"']",
        RegexOption.IGNORE_CASE
    ).findAll(html).forEach { m ->
        cells.add(UpCell(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2]))
    }
    val wanted = target ?: return null
    if (cells.size < 2) return null
    return UpCaptcha(field, wanted, cells)
}

private suspend fun solveUpCaptcha(cap: UpCaptcha): String? {
    val payload = json.writeValueAsString(
        mapOf(
            "target" to cap.target,
            "images" to cap.cells.map { it.src }
        )
    )
    for (base in listOf(UC_URL, UC_PEER)) {
        val response = try {
            net.post(
                base,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-worker-auth" to UC_KEY
                ),
                data = null,
                requestBody = payload.toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 30
            )
        } catch (e: Exception) {
            continue
        }
        val data = runCatching { json.readTree(response.text) }.getOrNull() ?: continue
        val indices = data.path("indices")
        if (!indices.isArray) continue
        val picked = mutableListOf<Int>()
        indices.forEach { node ->
            val index = node.asInt(-1)
            val cell = cap.cells.getOrNull(index) ?: return@forEach
            if (cell.idx !in picked) picked.add(cell.idx)
        }
        if (picked.isNotEmpty()) {
            picked.sort()
            return picked.joinToString(",")
        }
    }
    return null
}

// ---------------------------------------------------------------- maxstream

private suspend fun maxstreamPlayerUrl(uprotsUrl: String, ua: String): String? {
    var vid: String? = null

    fun vidFrom(url: String?): String? {
        val value = url ?: return null
        return Regex("/freeswatc[A-Za-z0-9]*/([A-Za-z0-9]+)").find(value)?.groupValues?.get(1)
            ?: Regex("/videostreax/[A-Za-z0-9]+/([A-Za-z0-9]+)").find(value)?.groupValues?.get(1)
    }

    suspend fun guess(): String? {
        val v = vid ?: return null
        val prefix = getActiveMaxstreamPrefix(ua)
        return "https://maxstream.video/$prefix/$v"
    }

    suspend fun hop(url: String, referer: String, left: Int): String? {
        val response = try {
            net.get(
                url,
                headers = mapOf("User-Agent" to ua, "Referer" to referer),
                allowRedirects = false,
                timeout = 20
            )
        } catch (e: Exception) {
            return null
        }
        val location = response.headers["location"]
            ?: response.headers["Location"]
            ?: ""
        if (vid == null) vid = vidFrom(url) ?: vidFrom(location)
        val body = response.text
        Regex("rawHex\\s*=\\s*[\"']([a-fA-F0-9]{20,8192})[\"']").find(body)?.let { m ->
            val player = hexToText(m.groupValues[1]).reversed()
            if (Regex("^https?://[^\\s\"'<>]+$").matches(player)) return normalizeMaxstreamPlayer(player, ua)
        }
        val next = if (location.isNotBlank()) absUrl(location, url) else ""
        if (vid == null && MS_PLAYER.matches(next)) return normalizeMaxstreamPlayer(next, ua)
        if (next.isNotBlank() && left > 0) {
            hop(next, url, left - 1)?.let { return it }
        }
        if (vid == null) vid = vidFrom(next)
        return guess()
    }

    return try {
        val p = hop(uprotsUrl, "https://maxstream.video/", 1)
        if (p != null) normalizeMaxstreamPlayer(p, ua) else guess()
    } catch (e: Exception) {
        guess()
    }
}

private suspend fun maxstreamM3u8(playerUrl: String, ua: String): String? {
    val html = try {
        net.get(
            playerUrl,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://maxstream.video/"
            ),
            timeout = 20
        ).text
    } catch (e: Exception) {
        return null
    }
    var text = html
    val packed = Regex(
        "eval\\(function\\(p,a,c,k,e,d\\)[\\s\\S]*?\\}\\([\\s\\S]*?\\.split\\(['\"]\\|['\"]\\)[\\s\\S]*?\\)\\s*\\)",
        RegexOption.IGNORE_CASE
    ).findAll(html)
    for (match in packed) {
        unpackPacked(match.value)?.let { text += "\n$it" }
    }
    val m = Regex(
        "src\\s*:\\s*[\"'](https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']",
        RegexOption.IGNORE_CASE
    ).find(text) ?: Regex(
        "[\"'](https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']",
        RegexOption.IGNORE_CASE
    ).find(text)
    return m?.groupValues?.get(1)
}

private fun unpackPacked(packed: String): String? {
    val m = Regex(
        "\\}\\(\\s*'((?:\\\\.|[^'\\\\])*)'\\s*,\\s*(\\d+|\\[\\])\\s*,\\s*(\\d+)\\s*,\\s*'((?:\\\\.|[^'\\\\])*)'\\s*\\.split\\(['\"]\\|['\"]\\)"
    ).find(packed) ?: return null
    val p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
    val a = if (m.groupValues[2] == "[]") 62 else m.groupValues[2].toIntOrNull() ?: 62
    val c = m.groupValues[3].toIntOrNull() ?: return null
    val k = m.groupValues[4].split("|")
    return unpackJs(p, a, c, k)
}

private suspend fun hlsQuality(url: String, headers: Map<String, String>): Int {
    val text = try {
        net.get(url, headers = headers, timeout = 15).text
    } catch (e: Exception) {
        return 0
    }
    val height = bestHeight(text)
    return qualityFromHeight(height).takeIf { it > 0 } ?: 0
}

// ---------------------------------------------------------------- workers

private fun uprotKey(link: String): String? = Regex(
    "^https?://uprot\\.net/(ms(?:fi?|ei)/[A-Za-z0-9+/=_.-]{4,300}?)/?$",
    RegexOption.IGNORE_CASE
).find(link)?.groupValues?.get(1)

private suspend fun cachedPlayer(key: String?): String? {
    if (key.isNullOrBlank()) return null
    for (base in listOf(UC_URL, UC_PEER)) {
        val response = try {
            net.get(
                "$base/player?k=${enc(key)}",
                headers = mapOf("x-worker-auth" to UC_KEY),
                timeout = 15
            )
        } catch (e: Exception) {
            continue
        }
        val data = runCatching { json.readTree(response.text) }.getOrNull() ?: continue
        val player = data.path("player").asText("")
        if (MS_PLAYER.matches(player)) return player
    }
    return null
}

private suspend fun storePlayer(key: String?, player: String) {
    if (key.isNullOrBlank()) return
    val m = Regex(
        "^https?://(?:www\\.)?maxstream\\.video/([A-Za-z0-9]+)/([A-Za-z0-9]+)/?$",
        RegexOption.IGNORE_CASE
    ).find(player) ?: return
    val canonical = "https://maxstream.video/${m.groupValues[1]}/${m.groupValues[2]}"
    try {
        net.post(
            "$UC_URL/player",
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-worker-auth" to UC_KEY
            ),
            data = null,
            requestBody = json.writeValueAsString(
                mapOf("k" to key, "player" to canonical)
            ).toRequestBody("application/json".toMediaTypeOrNull()),
            timeout = 15
        )
    } catch (e: Exception) {
        // Cache is best-effort.
    }
}

// ---------------------------------------------------------------- misc

fun hexToText(hex: String): String {
    val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
    val out = StringBuilder(clean.length / 2)
    var i = 0
    while (i + 1 < clean.length) {
        val code = clean.substring(i, i + 2).toIntOrNull(16) ?: 0
        out.append(code.toChar())
        i += 2
    }
    return out.toString()
}

fun JsonNode.asIntOrNull(): Int? = asText("").toIntOrNull()
