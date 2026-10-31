package prisynx.providers

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import prisynx.providers.core.LocalSubtitle
import prisynx.providers.core.UA_PC
import prisynx.providers.core.enc
import prisynx.providers.core.json
import prisynx.providers.core.net
import java.net.URI
import java.net.URLEncoder

internal data class KissStream(
    val url: String,
    val subApiUrl: String?
)

internal data class KissSubtitle(
    val url: String,
    val lang: String,
    val label: String,
    val legacy: Boolean
)

/**
 * Thin client for the KissKH JSON API.
 *
 * KissKH fronts its API with Cloudflare. Plain requests work from most
 * residential IPs, but some networks receive a 403 challenge. In that case the
 * client returns null/empty lists instead of retrying a challenge it cannot
 * solve; users can configure a relay in the extension settings.
 */
internal object KissKHApi {
    private const val TAG = "KissKH"
    private val MEDIA_URL = Regex("(\\.m3u8|\\.mp4|/master/|/tmp/|cdnvideo)", RegexOption.IGNORE_CASE)
    private val CHALLENGE = Regex("just a moment|cf-chl|attention required", RegexOption.IGNORE_CASE)

    fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA_PC,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9,it;q=0.8",
        "Referer" to "${KissKHSettings.baseUrl}/"
    )

    fun absolute(pathOrUrl: String): String {
        val value = pathOrUrl.trim()
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> KissKHSettings.baseUrl + value
            else -> "${KissKHSettings.baseUrl}/$value"
        }
    }

    fun proxied(url: String): String {
        val template = KissKHSettings.proxyTemplate
        if (template.isBlank()) return url
        val encoded = URLEncoder.encode(url, "UTF-8").replace("+", "%20")
        return if (template.contains("{url}")) template.replace("{url}", encoded)
        else template + encoded
    }

    fun hasRelay(): Boolean = KissKHSettings.proxyTemplate.isNotBlank()

    /** Base del relay (stessa origine del proxy template) per pubblicare testi. */
    private fun storeEndpoint(): String? {
        val template = KissKHSettings.proxyTemplate
        if (template.isBlank()) return null
        return try {
            val uri = URI(template)
            val authority = uri.authority ?: return null
            val path = uri.path.orEmpty()
            val root = "${uri.scheme ?: "http"}://$authority${path.substringBeforeLast('/', "")}"
            val token = Regex("(?:^|&)token=([^&]+)")
                .find(uri.rawQuery.orEmpty())
                ?.groupValues
                ?.get(1)
            if (token.isNullOrBlank()) "$root/store" else "$root/store?token=$token"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pubblica un sottotitolo generato (decifrato o tradotto) sul relay, così il
     * player non deve mai raggiungere il server locale del telefono.
     */
    suspend fun publishSubtitle(text: String): String? {
        storeEndpoint()?.let { endpoint ->
            try {
                val response = net.post(
                    endpoint,
                    requestBody = text.toRequestBody("application/x-subrip; charset=utf-8".toMediaType()),
                    timeout = 20
                )
                if (response.isSuccessful) {
                    val url = json.readTree(response.text).path("url").asText("")
                    if (url.isNotBlank()) return url
                } else {
                    Log.w(TAG, "relay store HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "relay store failed: ${e.message}")
            }
        }
        return LocalSubtitle.publish(text, "srt")
    }

    suspend fun getText(pathOrUrl: String, timeout: Long = 20): String? {
        val url = proxied(absolute(pathOrUrl))
        return try {
            val response = net.get(url, headers = headers(), timeout = timeout)
            when {
                response.code == 403 -> {
                    Log.w(TAG, "HTTP 403 (Cloudflare challenge?) ${url.substringBefore('?')}")
                    null
                }
                !response.isSuccessful -> {
                    Log.w(TAG, "HTTP ${response.code} ${url.substringBefore('?')}")
                    null
                }
                else -> response.text
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed (${e.javaClass.simpleName}): ${e.message}")
            null
        }
    }

    suspend fun getJson(pathOrUrl: String, timeout: Long = 20): JsonNode? {
        val text = getText(pathOrUrl, timeout)?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("<") || CHALLENGE.containsMatchIn(text.take(800))) {
            Log.w(TAG, "Cloudflare challenge page")
            return null
        }
        return try {
            json.readTree(text)
        } catch (e: Exception) {
            Log.w(TAG, "invalid JSON: ${e.message}")
            null
        }
    }

    suspend fun search(query: String, type: Int = 0): List<JsonNode> {
        if (query.isBlank()) return emptyList()
        var path = "/api/DramaList/Search?q=${enc(query)}"
        if (type > 0) path += "&type=$type"
        val data = getJson(path) ?: return emptyList()
        return when {
            data.isArray -> data.toList()
            data.path("data").isArray -> data.path("data").toList()
            else -> emptyList()
        }
    }

    suspend fun list(type: Int, country: Int, page: Int, pageSize: Int): List<JsonNode> {
        val path = "/api/DramaList/List?page=$page&type=$type&sub=0&country=$country" +
            "&status=0&order=3&pageSize=$pageSize"
        val data = getJson(path) ?: return emptyList()
        return when {
            data.isArray -> data.toList()
            data.path("data").isArray -> data.path("data").toList()
            else -> emptyList()
        }
    }

    suspend fun details(seriesId: String): JsonNode? =
        getJson("/api/DramaList/Drama/$seriesId?isq=false")

    suspend fun episode(episodeId: String): KissStream? {
        val videoKey = KissKHCrypto.makeKkey(episodeId, KissKHCrypto.VI_GUID)
        val path = "/api/DramaList/Episode/$episodeId.png" +
            "?err=false&ts=null&time=null&kkey=$videoKey"
        val data = getJson(path, 25) ?: return null

        var video = ""
        for (field in listOf("Video", "Video_tmp", "ThirdVideo", "BackupVideo")) {
            val node = data.path(field)
            val value = when {
                node.isTextual -> node.asText("")
                node.isObject -> node.path("url").asText("")
                    .ifBlank { node.path("src").asText("") }
                else -> ""
            }.trim()
            if (value.isNotEmpty() && MEDIA_URL.containsMatchIn(value)) {
                video = value
                break
            }
        }
        if (video.isEmpty()) {
            Log.i(TAG, "episode $episodeId has no playable video URL")
            return null
        }
        if (video.startsWith("//")) video = "https:$video"
        video = video.replace("&amp;", "&")

        val subtitleKey = KissKHCrypto.makeKkey(episodeId, KissKHCrypto.SUB_GUID)
        return KissStream(video, "/api/Sub/$episodeId?kkey=$subtitleKey")
    }

    suspend fun subtitles(subApiUrl: String): List<KissSubtitle> {
        val data = getJson(subApiUrl, 25) ?: return emptyList()
        val list = when {
            data.isArray -> data.toList()
            data.path("data").isArray -> data.path("data").toList()
            else -> emptyList()
        }
        val result = mutableListOf<KissSubtitle>()
        val seen = mutableSetOf<String>()
        for (node in list) {
            val url = subtitleUrl(node) ?: continue
            val label = node.path("land").asText("")
                .ifBlank { node.path("label").asText("") }
                .ifBlank { node.path("language").asText("") }
                .ifBlank { node.path("lang").asText("") }
            val lang = KissKHText.detectSubtitleLang(label, url) ?: continue
            if (!seen.add(lang)) continue
            val legacy = Regex("\\.txt1?(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)
            if (!legacy && !Regex("\\.(srt|vtt)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                continue
            }
            result.add(KissSubtitle(url = url, lang = lang, label = label, legacy = legacy))
        }
        return result
    }

    private fun subtitleUrl(node: JsonNode): String? {
        val direct = node.path("src").asText("").trim()
        if (direct.isNotEmpty()) return absolute(direct)

        val get = node.path("GET")
        if (get.isMissingNode || get.isNull) return null
        val host = get.path("host").asText("").trim()
        val filename = get.path("filename").asText("").trim()
        if (host.isEmpty() || filename.isEmpty()) return null
        val scheme = get.path("scheme").asText("https").ifBlank { "https" }
        val base = if (host.startsWith("http://") || host.startsWith("https://")) host
        else "$scheme://$host"
        var url = base + filename
        val version = get.path("query").path("v").asText("")
        if (version.isNotEmpty()) {
            url += (if (url.contains("?")) "&" else "?") + "v=" + enc(version)
        }
        return absolute(url)
    }
}
