package prisynx.live

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * zlive.st client and OkHttp interceptor.
 *
 * Channels come from `https://iptv.zlive.st/channels.json` and EPG from `epg.json`.
 * Playback URLs are resolved via `GET /session/ticket` + `POST /resolve` (AES-256-GCM
 * request envelope). Because some backends wrap MPEG-TS segments inside PNG/WebP
 * images (`KWP1`, `TIKTIKPX`+gzip, raw IDAT/trailing IEND, `TIKTIKRAW`, `TIKTIKTSGZ`),
 * [ZLiveInterceptor] unwraps them on the fly inside CloudStream's ExoPlayer pipeline
 * and renews expired/failing stream URLs transparently.
 */
object ZLiveEngine {
    const val SITE_BASE = "https://zlive.st"
    const val API_BASE = "https://iptv.zlive.st"
    const val DATA_PREFIX = "prisynx:zlive:"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0"

    private const val RESOLVE_SECRET = "SfrcOczV4qSNuSMiouJIDtTOVXUYWzbgFnLDdF9DUSf"

    val MEDIA_HEADERS = mapOf(
        "User-Agent" to UA,
        "Origin" to SITE_BASE,
        "Referer" to "$SITE_BASE/",
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

    private val rng = SecureRandom()

    data class ZChannel(
        val id: String,
        val name: String,
        val tagline: String,
        val region: String,
        val flag: String,
        val quality: String,
        val category: String,
        val live: Boolean,
        val order: Int,
        val sourceKey: String
    )

    data class ZEpg(
        val nowTitle: String,
        val nowDesc: String,
        val nextTitle: String
    )

    @Volatile private var chCache: Pair<Long, List<ZChannel>>? = null
    @Volatile private var epgCache: Pair<Long, Map<String, ZEpg>>? = null
    private val chById = ConcurrentHashMap<String, ZChannel>()

    fun cachedChannel(id: String): ZChannel? = chById[id]

    fun isItalian(ch: ZChannel): Boolean = ch.name.contains(" it", ignoreCase = true)

    private fun getText(url: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Origin", SITE_BASE)
            .header("Referer", "$SITE_BASE/")
        extraHeaders.forEach { (k, v) -> req.header(k, v) }
        return try {
            http.newCall(req.build()).execute().use { res ->
                if (res.code in 200..299) res.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Catalog & EPG ───────────────────────────────────────────────────────────

    suspend fun fetchChannels(): List<ZChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        chCache?.takeIf { now - it.first < 2 * 60_000L }?.let { return@withContext it.second }

        val text = getText("$API_BASE/channels.json") ?: return@withContext chCache?.second.orEmpty()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return@withContext chCache?.second.orEmpty()
        val out = ArrayList<ZChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            val name = o.optString("name", "").trim()
            if (id.isBlank() || name.isBlank()) continue
            val cat = o.optString("sport", "").trim().ifBlank { "Other" }
            val sources = o.optJSONArray("sources")
            val sourceKey = sources?.optJSONObject(0)?.optString("key", "")?.trim().orEmpty().ifBlank { id }
            val ch = ZChannel(
                id = id,
                name = name,
                tagline = o.optString("tagline", "").trim(),
                region = o.optString("region", "").trim(),
                flag = o.optString("flag", "").trim().lowercase(),
                quality = o.optString("quality", "").trim(),
                category = cat,
                live = o.optBoolean("live", true),
                order = o.optInt("order", 0),
                sourceKey = sourceKey
            )
            out += ch
            chById[id] = ch
        }
        if (out.isNotEmpty()) {
            chCache = now to out
            LiveSettings.rememberZliveCats(out.map { it.category }.toSet())
        }
        out
    }

    suspend fun fetchEpg(): Map<String, ZEpg> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        epgCache?.takeIf { now - it.first < 3 * 60_000L }?.let { return@withContext it.second }

        val text = getText("$API_BASE/epg.json") ?: return@withContext epgCache?.second.orEmpty()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext epgCache?.second.orEmpty()
        val arr = root.optJSONArray("channels") ?: return@withContext epgCache?.second.orEmpty()
        val map = HashMap<String, ZEpg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim().ifBlank { continue }
            val nowObj = o.optJSONObject("now")
            val nextObj = o.optJSONObject("next")
            val nowTitle = nowObj?.optString("title", "").orEmpty().trim()
            val nowDesc = nowObj?.optString("desc", "").orEmpty().trim()
            val nextTitle = nextObj?.optString("title", "").orEmpty().trim()
            if (nowTitle.isNotBlank() || nextTitle.isNotBlank()) {
                map[id] = ZEpg(nowTitle, nowDesc, nextTitle)
            }
        }
        if (map.isNotEmpty()) epgCache = now to map
        map
    }

    // ── Stream resolution (ticket + AES-256-GCM envelope) ───────────────────────

    private fun utcDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fetchTicket(): String? {
        val text = getText("$API_BASE/session/ticket") ?: return null
        return runCatching { JSONObject(text).optString("w", "") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun buildEnvelope(channelKey: String, ticket: String): String {
        val d = utcDateString()
        val x = sha256("$RESOLVE_SECRET:$d".toByteArray(Charsets.UTF_8))
        val o = hmacSha256(x, "k6".toByteArray(Charsets.UTF_8))
        val key = hmacSha256(o, "$ticket|v6".toByteArray(Charsets.UTF_8))
        val iv = ByteArray(12).also { rng.nextBytes(it) }

        val plain = JSONObject()
            .put("s", channelKey)
            .put("u", System.currentTimeMillis() / 1000L)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("$ticket|$d".toByteArray(Charsets.UTF_8))
        val out = cipher.doFinal(plain)
        val ctLen = out.size - 16
        val ct = out.copyOfRange(0, ctLen)
        val tag = out.copyOfRange(ctLen, out.size)

        return JSONObject()
            .put("a", b64(ct))
            .put("b", b64(iv))
            .put("c", b64(tag))
            .put("d", d)
            .put("e", ticket)
            .toString()
    }

    private fun requestLocation(channelKey: String): String? {
        val ticket = fetchTicket() ?: return null
        val body = try {
            buildEnvelope(channelKey, ticket)
        } catch (_: Exception) {
            return null
        }
        val req = Request.Builder()
            .url("$API_BASE/resolve")
            .header("User-Agent", UA)
            .header("Origin", SITE_BASE)
            .header("Referer", "$SITE_BASE/")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                if (res.code !in 200..299) null
                else JSONObject(res.body?.string().orEmpty()).optString("location", "")
                    .takeIf { it.startsWith("http") }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun checkM3u8Reachable(url: String): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Origin", SITE_BASE)
            .header("Referer", "$SITE_BASE/")
            .header("Accept", "*/*")
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) false
                else {
                    val text = res.body?.string().orEmpty()
                    text.trimStart().startsWith("#EXTM3U")
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves a channel key to a live `.m3u8` URL. Because `/resolve` rotates across
     * multiple backends (and an individual backend may return 502), probes up to 3
     * times to return a reachable `#EXTM3U` URL when available.
     */
    suspend fun resolveStream(channelKey: String): String? = withContext(Dispatchers.IO) {
        if (channelKey.startsWith("http://", true) || channelKey.startsWith("https://", true)) {
            return@withContext channelKey
        }
        var lastUrl: String? = null
        val tried = HashSet<String>()
        for (attempt in 0 until 3) {
            val loc = requestLocation(channelKey) ?: continue
            lastUrl = loc
            val host = runCatching { java.net.URI(loc).host.orEmpty() }.getOrDefault("")
            if (checkM3u8Reachable(loc)) return@withContext loc
            tried.add(host)
        }
        lastUrl
    }

    // ── Segment unwrapper (full port of zlive.st DD()) ──────────────────────────

    private val MARK_RAW = "TIKTIKRAW".toByteArray(Charsets.ISO_8859_1)
    private val MARK_TSGZ = "TIKTIKTSGZ".toByteArray(Charsets.ISO_8859_1)
    private val MARK_PX = "TIKTIKPX".toByteArray(Charsets.ISO_8859_1)
    private val MARK_KWP1 = "KWP1".toByteArray(Charsets.ISO_8859_1)

    private fun readU32BE(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFFL) shl 24) or
            ((b[o + 1].toLong() and 0xFFL) shl 16) or
            ((b[o + 2].toLong() and 0xFFL) shl 8) or
            (b[o + 3].toLong() and 0xFFL)

    private fun readU32LE(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFFL) or
            ((b[o + 1].toLong() and 0xFFL) shl 8) or
            ((b[o + 2].toLong() and 0xFFL) shl 16) or
            ((b[o + 3].toLong() and 0xFFL) shl 24)

    private fun matchesAt(buf: ByteArray, off: Int, pat: ByteArray): Boolean {
        if (off < 0 || off + pat.size > buf.size) return false
        for (i in pat.indices) if (buf[off + i] != pat[i]) return false
        return true
    }

    private fun indexOfPattern(buf: ByteArray, pat: ByteArray, maxEnd: Int = buf.size): Int {
        val limit = minOf(maxEnd, buf.size) - pat.size
        if (limit < 0) return -1
        val first = pat[0]
        for (i in 0..limit) {
            if (buf[i] == first && matchesAt(buf, i, pat)) return i
        }
        return -1
    }

    private fun gunzip(src: ByteArray, offset: Int, length: Int): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(src, offset, length)).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun inflate(src: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(src)
            val out = ByteArrayOutputStream(src.size * 2)
            val tmp = ByteArray(16384)
            while (!inflater.finished()) {
                val n = inflater.inflate(tmp)
                if (n > 0) out.write(tmp, 0, n)
                else if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun unwrapWebpExif(t: ByteArray): ByteArray? {
        if (t.size < 16) return null
        if (String(t, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
            String(t, 8, 4, Charsets.ISO_8859_1) != "WEBP"
        ) return null
        var i = 12
        while (i + 8 <= t.size) {
            val tag = String(t, i, 4, Charsets.ISO_8859_1)
            val lenL = readU32LE(t, i + 4)
            i += 8
            if (lenL < 0 || i + lenL > t.size) return null
            val len = lenL.toInt()
            if (tag == "EXIF") {
                return if (len >= 189 && t[i] == 0x47.toByte() && t[i + 188] == 0x47.toByte()) {
                    t.copyOfRange(i, i + len)
                } else null
            }
            i += len + (len and 1)
        }
        return null
    }

    private fun unwrapPngTrailing(t: ByteArray): ByteArray? {
        if (t.size < 16 || t[0] != 0x89.toByte() || t[1] != 0x50.toByte()) return null
        var s = 8
        while (s + 8 <= t.size) {
            val lenL = readU32BE(t, s)
            if (lenL < 0 || lenL > t.size - s - 12L) return null
            val len = lenL.toInt()
            val isIend = t[s + 4] == 'I'.code.toByte() && t[s + 5] == 'E'.code.toByte() &&
                t[s + 6] == 'N'.code.toByte() && t[s + 7] == 'D'.code.toByte()
            s += 12 + len
            if (isIend) {
                return if (s + 188 < t.size && t[s] == 0x47.toByte() && t[s + 188] == 0x47.toByte()) {
                    t.copyOfRange(s, t.size)
                } else null
            }
        }
        return null
    }

    private fun unwrapPngRawIdat(t: ByteArray): ByteArray? {
        if (t.size < 16 || t[0] != 0x89.toByte() || t[1] != 0x50.toByte()) return null
        var s = 8
        val out = ByteArrayOutputStream()
        while (s + 8 <= t.size) {
            val lenL = readU32BE(t, s)
            if (lenL < 0 || lenL > t.size - s - 12L) return null
            val len = lenL.toInt()
            val type = String(t, s + 4, 4, Charsets.ISO_8859_1)
            if (type == "IDAT") out.write(t, s + 8, len)
            else if (type == "IEND") break
            s += 12 + len
        }
        if (out.size() < 564) return null
        val a = out.toByteArray()
        return if (a[0] == 0x47.toByte() && a[188] == 0x47.toByte() && a[376] == 0x47.toByte()) a else null
    }

    private fun decodePngPixels(t: ByteArray): ByteArray? {
        if (t.size < 8 || t[0] != 0x89.toByte() || t[1] != 0x50.toByte()) return null
        var s = 8
        var w = 0
        var h = 0
        var bd = 0
        var ct = 0
        var interlace = 0
        var kwLn = -1
        val idatOut = ByteArrayOutputStream(t.size)
        while (s + 8 <= t.size) {
            val lenL = readU32BE(t, s)
            if (lenL < 0 || lenL > t.size - s - 12L) return null
            val len = lenL.toInt()
            val type = String(t, s + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "kwLn" -> if (len == 4) kwLn = readU32BE(t, s + 8).toInt()
                "IHDR" -> if (len >= 13) {
                    w = readU32BE(t, s + 8).toInt()
                    h = readU32BE(t, s + 12).toInt()
                    bd = t[s + 16].toInt() and 0xFF
                    ct = t[s + 17].toInt() and 0xFF
                    interlace = t[s + 20].toInt() and 0xFF
                }
                "IDAT" -> idatOut.write(t, s + 8, len)
                "IEND" -> break
            }
            s += 12 + len
        }
        val bpp = when (ct) {
            0 -> 1
            2 -> 3
            4 -> 2
            6 -> 4
            else -> return null
        }
        if (w <= 0 || h <= 0 || bd != 8 || interlace != 0) return null
        val outBpp = when (ct) {
            4 -> 1
            6 -> 3
            else -> bpp
        }
        val raw = inflate(idatOut.toByteArray()) ?: return null
        val stride = w * bpp
        val totalOut = w.toLong() * h.toLong() * outBpp.toLong()
        if (totalOut <= 0 || totalOut > 64L * 1024 * 1024) return null
        val pixels = ByteArray(totalOut.toInt())
        var pos = 0
        var outPos = 0
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        for (row in 0 until h) {
            if (pos + 1 + stride > raw.size) return null
            val filt = raw[pos++].toInt() and 0xFF
            for (x in 0 until stride) {
                val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                var v = raw[pos + x].toInt() and 0xFF
                when (filt) {
                    0 -> {}
                    1 -> v += a
                    2 -> v += b
                    3 -> v += (a + b) ushr 1
                    4 -> v += paeth(a, b, c)
                    else -> return null
                }
                cur[x] = (v and 0xFF).toByte()
            }
            pos += stride
            if (outBpp == bpp) {
                System.arraycopy(cur, 0, pixels, outPos, stride)
                outPos += stride
            } else {
                var x = 0
                while (x < stride) {
                    for (k in 0 until outBpp) pixels[outPos++] = cur[x + k]
                    x += bpp
                }
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return if (kwLn in 0..pixels.size) pixels.copyOfRange(0, kwLn) else pixels
    }

    private fun unwrapPngPixels(t: ByteArray): ByteArray? {
        val n = decodePngPixels(t) ?: return null
        if (n.size < 12) return null
        // 1. KWP1 + uint32 length + MPEG-TS
        if (matchesAt(n, 0, MARK_KWP1)) {
            val len = readU32BE(n, 4)
            if (len > 0 && 8L + len <= n.size) return n.copyOfRange(8, 8 + len.toInt())
            return null
        }
        // 2. Direct TS inside pixel buffer
        if (n[0] == 0x47.toByte() && n.size >= 376) {
            var l = (n.size / 188) * 188
            while (l >= 376 && (n[l - 188] != 0x47.toByte() || n[l - 376] != 0x47.toByte())) l -= 188
            if (l > 0) return n.copyOfRange(0, l)
        }
        // 3. TIKTIKPX + uint32 length + GZIP MPEG-TS
        val idx = indexOfPattern(n, MARK_PX, minOf(n.size, (1 shl 20) + 12))
        if (idx >= 0 && idx + 12 <= n.size) {
            val len = readU32BE(n, idx + 8)
            if (len > 0 && idx + 12L + len <= n.size) {
                val start = idx + 12
                if (n[start] == 0x1F.toByte() && n[start + 1] == 0x8B.toByte()) {
                    val out = gunzip(n, start, len.toInt())
                    if (out != null && out.isNotEmpty() && out[0] == 0x47.toByte()) return out
                }
            }
        }
        return null
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

    fun unwrapSegment(t: ByteArray): ByteArray = try {
        if (t.size >= 189 && t[0] == 0x47.toByte() && t[188] == 0x47.toByte()) {
            t
        } else {
            unwrapWebpExif(t)
                ?: unwrapPngTrailing(t)
                ?: unwrapPngRawIdat(t)
                ?: (if (t.size >= 8 && t[0] == 0x89.toByte() && t[1] == 0x50.toByte()) unwrapPngPixels(t) else null)
                ?: run {
                    val ib = indexOfPattern(t, MARK_RAW)
                    if (ib >= 0 && ib + MARK_RAW.size < t.size && t[ib + MARK_RAW.size] == 0x47.toByte()) {
                        return@run t.copyOfRange(ib + MARK_RAW.size, t.size)
                    }
                    val iw = indexOfPattern(t, MARK_TSGZ)
                    if (iw >= 0 && iw + MARK_TSGZ.size < t.size) {
                        val s = iw + MARK_TSGZ.size
                        gunzip(t, s, t.size - s)?.let { return@run it }
                    }
                    // Leave fMP4 boxes untouched.
                    if (t.size >= 8) {
                        val box = String(t, 4, 4, Charsets.ISO_8859_1)
                        if (box == "ftyp" || box == "moof" || box == "styp") return@run t
                    }
                    var a = 0
                    val limit = minOf(t.size - 565, 65536)
                    while (a <= limit) {
                        if (t[a] == 0x47.toByte() && t[a + 188] == 0x47.toByte() &&
                            t[a + 376] == 0x47.toByte() && t[a + 564] == 0x47.toByte()
                        ) {
                            return@run t.copyOfRange(a, t.size)
                        }
                        a++
                    }
                    t
                }
        }
    } catch (_: Exception) {
        t
    }

    // ── CloudStream video interceptor ───────────────────────────────────────────

    fun interceptorFor(link: ExtractorLink): Interceptor? {
        val key = link.extractorData?.takeIf { it.startsWith(DATA_PREFIX) }?.removePrefix(DATA_PREFIX)
            ?.takeIf { it.isNotBlank() } ?: return null
        return ZLiveInterceptor(channelKey = key, initialUrl = link.url)
    }

    private class ZLiveInterceptor(
        private val channelKey: String,
        private val initialUrl: String
    ) : Interceptor {
        private val lock = Any()
        @Volatile private var activePlaylistUrl = initialUrl
        @Volatile private var lastRenewAt = 0L

        private val tsMediaType = "video/mp2t".toMediaType()
        private val m3u8MediaType = "application/vnd.apple.mpegurl".toMediaType()

        override fun intercept(chain: Interceptor.Chain): Response {
            val origReq = chain.request()
            val reqUrl = origReq.url.toString()
            val isRoot = reqUrl == initialUrl || reqUrl == activePlaylistUrl
            val isPlaylistPath = isRoot || origReq.url.encodedPath.endsWith(".m3u8", true)

            val targetUrl = if (isRoot) activePlaylistUrl else reqUrl
            val reqBuilder = origReq.newBuilder()
                .url(targetUrl)
                .header("User-Agent", UA)
                .header("Origin", SITE_BASE)
                .header("Referer", "$SITE_BASE/")
            if (!isPlaylistPath) {
                reqBuilder.header("Accept-Encoding", "identity")
            }

            var response = chain.proceed(reqBuilder.build())

            if (isPlaylistPath) {
                val bad = !response.isSuccessful || !peekIsM3u8(response)
                if (bad && isRoot) {
                    val fresh = renewPlaylist()
                    if (fresh != null) {
                        response.close()
                        val retryReq = origReq.newBuilder()
                            .url(fresh)
                            .header("User-Agent", UA)
                            .header("Origin", SITE_BASE)
                            .header("Referer", "$SITE_BASE/")
                            .header("Cache-Control", "no-cache")
                            .build()
                        response = chain.proceed(retryReq)
                    }
                }
                if (!response.isSuccessful) return response
                // Note: some proxies serve segments under a `.m3u8` URL path (e.g. m3u8-proxy.m3u8?data=...).
                // If the body is NOT a playlist, unwrap it as a segment!
                val ct = response.body?.contentType()
                val raw = response.body?.bytes() ?: return response
                if (isPlaylistBytes(raw)) {
                    return response.newBuilder()
                        .body(raw.toResponseBody(m3u8MediaType))
                        .header("Content-Type", m3u8MediaType.toString())
                        .header("Content-Length", raw.size.toString())
                        .removeHeader("Content-Encoding")
                        .build()
                }
                return wrapSegmentResponse(response, raw, ct)
            }

            if (!response.isSuccessful) return response
            val ct = response.body?.contentType()
            val raw = response.body?.bytes() ?: return response
            if (isPlaylistBytes(raw)) {
                return response.newBuilder()
                    .body(raw.toResponseBody(m3u8MediaType))
                    .header("Content-Type", m3u8MediaType.toString())
                    .header("Content-Length", raw.size.toString())
                    .removeHeader("Content-Encoding")
                    .build()
            }
            return wrapSegmentResponse(response, raw, ct)
        }

        private fun wrapSegmentResponse(
            response: Response,
            raw: ByteArray,
            origContentType: okhttp3.MediaType?
        ): Response {
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
            isPlaylistBytes(response.peekBody(64).bytes())
        } catch (_: Exception) {
            false
        }

        private fun renewPlaylist(): String? = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastRenewAt < 5_000L) return activePlaylistUrl.takeIf { it != initialUrl }
            lastRenewAt = now
            val fresh = runBlocking {
                withTimeoutOrNull(18_000L) { resolveStream(channelKey) }
            } ?: return null
            activePlaylistUrl = fresh
            fresh
        }
    }
}
