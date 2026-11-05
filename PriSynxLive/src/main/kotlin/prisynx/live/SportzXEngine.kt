package prisynx.live

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * SportzX (es.bandspx.com) client. Payloads are {"data": base64url} encrypted with
 * the app's ChaCha-like AEAD: [0x03][16B salt][12B nonce][ciphertext][16B MAC].
 */
object SportzXEngine {
    private const val BASE = "https://es.bandspx.com"
    private const val UA = "Dalvik/2.1.0 (Linux; Android 13)"
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val SECRET_A = hex("1676ec7db4771b0d826d70369b579684b182d2c0133be041bdd55f5d6d79a98b")
    private val SECRET_B = byteArrayOf(
        -79, 101, 125, -29, 9, -42, -25, -112, 96, 122, 92, 78, -25, -47, -81, 72,
        83, -11, 113, -123, 125, -55, 101, -26, 92, -75, 103, 67, -110, 58, 69, 121
    )
    private val QR = arrayOf(
        intArrayOf(0, 4, 8, 12), intArrayOf(1, 5, 9, 13), intArrayOf(2, 6, 10, 14), intArrayOf(3, 7, 11, 15),
        intArrayOf(0, 5, 10, 15), intArrayOf(1, 6, 11, 12), intArrayOf(2, 7, 8, 13), intArrayOf(3, 4, 9, 14)
    )

    fun hex(s: String): ByteArray {
        val clean = s.trim()
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun permute(s: IntArray) {
        repeat(10) {
            for (q in QR) {
                val a = q[0]; val b = q[1]; val c = q[2]; val d = q[3]
                s[a] += s[b]; s[d] = rotl(s[a] xor s[d], 13)
                s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 17)
                s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 9)
                s[c] += s[d]; s[b] = rotl(s[c] xor s[b], 25)
            }
        }
    }

    private fun wordsLE(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): IntArray {
        val bb = ByteBuffer.wrap(buf, off, len).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(len shr 2) { bb.int }
    }

    private fun bytesLE(words: IntArray, count: Int = words.size): ByteArray {
        val bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putInt(words[i])
        return bb.array()
    }

    private fun spongeHash(data: ByteArray): ByteArray {
        val st = IntArray(16) { -1640531527 * (it + 1) }
        val buf = ByteArray((data.size / 32 + 1) * 32)
        System.arraycopy(data, 0, buf, 0, data.size)
        buf[data.size] = 0x80.toByte()
        for (off in buf.indices step 32) {
            val blk = wordsLE(buf, off, 32)
            for (i in 0 until 8) st[i] = st[i] xor blk[i]
            permute(st)
        }
        return bytesLE(st, 8)
    }

    fun decryptPayload(data: String): String {
        val p = Base64.decode(data.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(p.size >= 46) { "payload too short" }
        val salt = p.copyOfRange(1, 17)
        val master = spongeHash("SXR3/kdf".toByteArray() + SECRET_A + SECRET_B + salt)
        val j = wordsLE(spongeHash(master + byteArrayOf(1)))
        val g2 = spongeHash(master + byteArrayOf(2))
        val mac = spongeHash(g2 + p.copyOfRange(0, p.size - 16) + g2).copyOfRange(0, 16)
        if (!mac.contentEquals(p.copyOfRange(p.size - 16, p.size))) throw SecurityException("MAC mismatch")
        val j2 = wordsLE(p, 17, 12)
        val body = p.copyOfRange(29, p.size - 16)
        for (i in body.indices step 64) {
            val st = intArrayOf(
                1398297139, -1640531527, 2101127200, -1028477379,
                j[0], j[1], j[2], j[3], j[4], j[5], j[6], j[7],
                i / 64, j2[0], j2[1], j2[2]
            )
            val ks = st.clone()
            permute(ks)
            for (k in 0 until 16) ks[k] += st[k]
            val kb = bytesLE(ks)
            for (k in 0 until minOf(64, body.size - i)) body[i + k] = (body[i + k].toInt() xor kb[k].toInt()).toByte()
        }
        return String(body, Charsets.UTF_8)
    }

    private data class Envelope(@JsonProperty("data") val data: String? = null)

    private suspend fun fetchDecrypted(path: String): String? {
        val text = app.get(
            "$BASE/$path",
            headers = mapOf("User-Agent" to UA, "Accept" to "application/json,*/*")
        ).text
        val data = parseJson<Envelope>(text).data ?: return null
        return decryptPayload(data)
    }

    suspend fun fetchEvents(): List<SportzEvent> {
        val json = fetchDecrypted("events.json") ?: return emptyList()
        val now = System.currentTimeMillis()
        return parseJson<List<RawEvent>>(json).mapNotNull { raw ->
            val id = raw.id?.toString() ?: return@mapNotNull null
            val info = raw.eventInfo
            val start = parseDate(info?.startTime)
            val end = parseDate(info?.endTime)
            val image = raw.image?.takeIf { it.startsWith("http") }
                ?: info?.eventBanner?.takeIf { it.startsWith("http") }
                ?: info?.teamAFlag?.takeIf { it.startsWith("http") }
            SportzEvent(
                id = id,
                title = raw.title?.takeIf { it.isNotBlank() } ?: info?.eventName ?: "Evento $id",
                category = raw.cat?.trim()?.takeIf { it.isNotBlank() } ?: "Altro",
                eventName = info?.eventName?.trim().orEmpty(),
                teams = listOfNotNull(info?.teamA, info?.teamB).filter { it.isNotBlank() && it != "null" }.distinct(),
                image = image,
                start = start ?: now,
                end = end,
                isLive = start != null && start <= now && (end == null || end >= now)
            )
        }.filter { it.end == null || it.end >= now }
            .sortedWith(compareByDescending<SportzEvent> { it.isLive }.thenBy { it.start })
    }

    suspend fun fetchChannels(eventId: String): List<SportzChannel> {
        val json = fetchDecrypted("channels/$eventId.json") ?: return emptyList()
        return parseJson(json)
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    /** "URL|header=value&header=value" -> url + headers with lower-case keys. */
    fun parseLink(raw: String): Pair<String, Map<String, String>> {
        val parts = raw.split("|")
        val headers = mutableMapOf<String, String>()
        parts.drop(1).forEach { seg ->
            seg.split("&").forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0) headers[kv.substring(0, eq).trim().lowercase()] = kv.substring(eq + 1).trim()
            }
        }
        return parts[0].trim() to headers
    }

    data class RawEvent(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cat") val cat: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("eventInfo") val eventInfo: RawEventInfo? = null
    )

    data class RawEventInfo(
        @JsonProperty("teamA") val teamA: String? = null,
        @JsonProperty("teamB") val teamB: String? = null,
        @JsonProperty("eventName") val eventName: String? = null,
        @JsonProperty("eventBanner") val eventBanner: String? = null,
        @JsonProperty("startTime") val startTime: String? = null,
        @JsonProperty("endTime") val endTime: String? = null,
        @JsonProperty("teamAFlag") val teamAFlag: String? = null,
        @JsonProperty("teamBFlag") val teamBFlag: String? = null
    )

    data class SportzEvent(
        val id: String,
        val title: String,
        val category: String,
        val eventName: String,
        val teams: List<String>,
        val image: String?,
        val start: Long,
        val end: Long?,
        val isLive: Boolean
    )

    data class SportzChannel(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("api") val api: String? = null
    )
}
