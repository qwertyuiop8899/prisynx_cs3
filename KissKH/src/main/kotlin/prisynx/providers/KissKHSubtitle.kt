package prisynx.providers

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.FormBody
import prisynx.providers.core.json
import prisynx.providers.core.net
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Subtitle helpers: fetch, legacy decryption, cue parsing and DeepL
 * translation.
 *
 * KissKH serves most tracks as plain .srt on its CDN. Older shows use
 * .txt/.txt1 files where each dialogue line is AES-128-CBC + base64; the key
 * rotated between catalog eras, so every known key is tried and the one that
 * decrypts the most lines into readable text wins.
 */
internal object KissKHSubtitle {
    private const val TAG = "KissKH"
    private const val MAX_BATCH_TEXTS = 50
    private const val MAX_BATCH_CHARS = 12_000
    private const val MAX_LINE = 42
    private const val MAX_LINES = 2

    private val LEGACY_KEYS = listOf(
        "8056483646328763" to "6852612370185273",
        "AmSmZVcH93UQUezi" to "ReBKWW8cqdjPEnF6",
        "sWODXX04QRTkHdlZ" to "8pwhapJeC4hrS9hO"
    )

    private val CIPHER_LINE = Regex("^[A-Za-z0-9+/=]{16,}$")
    // Handles both SubRip (HH:MM:SS,mmm) and the short WebVTT form (MM:SS.mmm).
    private val TIMESTAMP = Regex("(?:(\\d{2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
    private val TAGS = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")

    private data class Cue(val time: String, var textLines: List<String>)

    // ------------------------------------------------------------- fetching

    private fun isLegacy(url: String): Boolean =
        Regex("\\.txt1?(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun subtitleHeaders(): Map<String, String> =
        KissKHApi.headers() + ("Accept" to "*/*")

    suspend fun fetchText(url: String): String? {
        val text = download(url) ?: return null
        return if (isLegacy(url)) decryptLegacy(text) else text
    }

    /**
     * Scarica il file passando dal relay quando configurato (il CDN dei
     * sottotitoli sta dietro lo stesso challenge Cloudflare delle API), con
     * fallback diretto se il relay non risponde o non conosce l'host.
     */
    private suspend fun download(url: String): String? {
        val relayed = KissKHApi.proxied(url)
        var response = try {
            net.get(relayed, headers = subtitleHeaders(), timeout = 30)
        } catch (e: Exception) {
            Log.w(TAG, "subtitle fetch failed: ${e.message}")
            null
        }

        if ((response == null || !response.isSuccessful) && relayed != url) {
            Log.w(TAG, "subtitle relay ${response?.code}, retry direct ${url.substringBefore('?')}")
            response = try {
                net.get(url, headers = subtitleHeaders(), timeout = 30)
            } catch (e: Exception) {
                Log.w(TAG, "subtitle fetch failed: ${e.message}")
                null
            }
        }

        if (response == null) return null
        if (!response.isSuccessful) {
            Log.w(TAG, "subtitle HTTP ${response.code} ${url.substringBefore('?')}")
            return null
        }

        return response.body.bytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    suspend fun translateUrl(
        url: String,
        from: String,
        to: String,
        context: String?,
        key: String
    ): String? {
        val raw = fetchText(url) ?: return null
        if (key.isBlank()) return raw
        // Do not fall back to the original text: it would be served with the
        // target-language label even though it is still in the source language.
        return translateSrt(raw, from, to, context, key)
    }

    // ---------------------------------------------------------- decryption

    private fun decryptLegacy(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        val cipherLines = lines.indices.filter { CIPHER_LINE.matches(lines[it].trim()) }
        if (cipherLines.isEmpty()) return decodeEntities(lines.joinToString("\n"))

        var bestKey: Pair<String, String>? = null
        var bestCount = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (key in LEGACY_KEYS) {
            var count = 0
            var score = 0.0
            for (index in cipherLines) {
                val decrypted = tryDecrypt(lines[index].trim(), key.first, key.second)
                if (decrypted != null) {
                    count++
                    score += decrypted.second
                }
            }
            if (count > bestCount || (count == bestCount && score > bestScore)) {
                bestCount = count
                bestScore = score
                bestKey = key
            }
        }

        if (bestKey != null && bestCount > 0) {
            for (index in cipherLines) {
                val decrypted = tryDecrypt(lines[index].trim(), bestKey.first, bestKey.second)
                if (decrypted != null) lines[index] = decrypted.first
            }
        }
        return decodeEntities(lines.joinToString("\n"))
    }

    private fun tryDecrypt(line: String, key: String, iv: String): Pair<String, Double>? {
        return try {
            val data = Base64.decode(line, Base64.DEFAULT)
            if (data.size < 16 || data.size % 16 != 0) return null
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.ISO_8859_1), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.ISO_8859_1))
            )
            val text = String(cipher.doFinal(data), Charsets.UTF_8).trim()
            val score = scoreText(text)
            if (score < 0.35) null else text to score
        } catch (e: Exception) {
            null
        }
    }

    private fun scoreText(text: String): Double {
        if (text.length < 2) return Double.NEGATIVE_INFINITY
        var letters = 0
        var spaces = 0
        for (character in text) {
            if (character == '\uFFFD') return Double.NEGATIVE_INFINITY
            val code = character.code
            if (code < 32) {
                if (character != '\r' && character != '\n' && character != '\t') {
                    return Double.NEGATIVE_INFINITY
                }
                spaces++
            } else {
                if (character.isLetterOrDigit()) letters++
                if (character == ' ') spaces++
            }
        }
        if (letters == 0) return Double.NEGATIVE_INFINITY
        return (letters + spaces).toDouble() / text.length
    }

    private fun decodeEntities(text: String): String = text
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // ------------------------------------------------------------- cues

    private fun parseCues(text: String): List<Cue> {
        val source = text.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val cues = mutableListOf<Cue>()
        for (block in source.split(Regex("\n{2,}"))) {
            val lines = block.split('\n').filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeIndex < 0) continue
            val textLines = lines.drop(timeIndex + 1)
            if (textLines.isEmpty()) continue
            cues.add(Cue(normalizeTimeLine(lines[timeIndex]), textLines))
        }
        return cues
    }

    private fun normalizeTimeLine(value: String): String {
        val timing = Regex("^(\\S+)\\s+-->\\s+(\\S+)").find(value.trim())
        val normalized = if (timing != null) {
            "${timing.groupValues[1]} --> ${timing.groupValues[2]}"
        } else {
            value.trim()
        }
        return TIMESTAMP.replace(normalized) { match ->
            val hours = match.groupValues[1].ifEmpty { "00" }
            "$hours:${match.groupValues[2]}:${match.groupValues[3]},${match.groupValues[4]}"
        }
    }

    private fun buildSrt(cues: List<Cue>): String =
        cues.mapIndexed { index, cue ->
            "${index + 1}\n${cue.time}\n${cue.textLines.joinToString("\n")}"
        }.joinToString("\n\n") + "\n"

    fun wrapCue(text: String): List<String> {
        val normalized = text.trim().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= MAX_LINE) return listOf(normalized)

        val lines = mutableListOf<String>()
        var current = ""
        for (word in normalized.split(' ')) {
            if (current.isEmpty()) {
                current = word
            } else if (current.length + 1 + word.length <= MAX_LINE) {
                current += " $word"
            } else {
                lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        if (lines.size <= MAX_LINES) return lines

        val head = lines.take(MAX_LINES - 1).toMutableList()
        head.add(lines.drop(MAX_LINES - 1).joinToString(" "))
        return head
    }

    // --------------------------------------------------------- translation

    suspend fun translateSrt(
        text: String,
        from: String,
        to: String,
        context: String?,
        key: String
    ): String? {
        val cues = parseCues(text)
        if (cues.isEmpty()) return null

        val flat = mutableListOf<String>()
        val refs = mutableListOf<Int>()
        val italic = mutableListOf<Boolean>()
        cues.forEachIndexed { index, cue ->
            val joined = cue.textLines.joinToString(" ")
            val plain = joined.replace(TAGS, "").replace(WHITESPACE, " ").trim()
            if (plain.isEmpty()) return@forEachIndexed
            refs.add(index)
            flat.add(plain)
            italic.add(Regex("<i>", RegexOption.IGNORE_CASE).containsMatchIn(joined))
        }
        if (flat.isEmpty()) return null

        val translations = translateTexts(flat, from, to, context, key) ?: return null
        refs.forEachIndexed { position, cueIndex ->
            val translated = translations.getOrNull(position)?.trim().orEmpty()
            if (translated.isEmpty()) return@forEachIndexed
            val lines = wrapCue(translated)
            cues[cueIndex].textLines = if (italic[position]) {
                lines.map { "<i>$it</i>" }
            } else {
                lines
            }
        }
        return buildSrt(cues)
    }

    private suspend fun translateTexts(
        texts: List<String>,
        from: String,
        to: String,
        context: String?,
        key: String
    ): List<String>? {
        val output = mutableListOf<String>()
        for (batch in batches(texts)) {
            val form = FormBody.Builder()
            for (text in batch) form.add("text", text)
            form.add("target_lang", to.uppercase(Locale.ROOT))
            if (from.isNotBlank()) form.add("source_lang", from.uppercase(Locale.ROOT))
            if (!context.isNullOrBlank()) form.add("context", context.take(1200))
            form.add("preserve_formatting", "1")

            val response = try {
                net.post(
                    endpoint(key),
                    headers = mapOf(
                        "Authorization" to "DeepL-Auth-Key $key",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    requestBody = form.build(),
                    timeout = 120
                )
            } catch (e: Exception) {
                Log.w(TAG, "DeepL request failed: ${e.message}")
                return null
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "DeepL HTTP ${response.code}: ${response.text.take(300)}")
                return null
            }

            val node: JsonNode = try {
                json.readTree(response.text)
            } catch (e: Exception) {
                Log.w(TAG, "DeepL invalid JSON: ${e.message}")
                return null
            }
            val translations = node.path("translations")
            if (!translations.isArray || translations.size() != batch.size) {
                Log.w(TAG, "DeepL unexpected payload")
                return null
            }
            for (translation in translations) {
                output.add(translation.path("text").asText(""))
            }
        }
        return output
    }

    private fun batches(texts: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var characters = 0
        for (text in texts) {
            if (current.size >= MAX_BATCH_TEXTS ||
                (characters + text.length > MAX_BATCH_CHARS && current.isNotEmpty())
            ) {
                result.add(current)
                current = mutableListOf()
                characters = 0
            }
            current.add(text)
            characters += text.length
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    private fun endpoint(key: String): String =
        if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate"
        else "https://api.deepl.com/v2/translate"

    private fun usageEndpoint(key: String): String =
        if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/usage"
        else "https://api.deepl.com/v2/usage"

    /** Used by the settings dialog to validate a pasted key before saving. */
    suspend fun checkKey(key: String): String {
        val cleaned = key.trim()
        if (cleaned.isEmpty()) return "Inserisci la chiave DeepL"
        return try {
            val response = net.get(
                usageEndpoint(cleaned),
                headers = mapOf("Authorization" to "DeepL-Auth-Key $cleaned"),
                timeout = 20
            )
            when {
                response.isSuccessful -> {
                    val node = runCatching { json.readTree(response.text) }.getOrNull()
                    val used = node?.path("character_count")?.asLong(-1L) ?: -1L
                    val limit = node?.path("character_limit")?.asLong(-1L) ?: -1L
                    if (used >= 0 && limit > 0) {
                        "Chiave valida · $used/$limit caratteri usati"
                    } else {
                        "Chiave valida"
                    }
                }
                response.code == 403 -> "Chiave DeepL non valida"
                response.code == 456 -> "Quota DeepL esaurita"
                else -> "DeepL HTTP ${response.code}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "DeepL key check failed: ${e.message}")
            "Verifica fallita: ${e.message}"
        }
    }
}
