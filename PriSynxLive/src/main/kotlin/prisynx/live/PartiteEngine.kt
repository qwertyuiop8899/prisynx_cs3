package prisynx.live

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject
import org.jsoup.Jsoup

object PartiteEngine {
    private const val MAIN_URL = "https://www.partite.cc"
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class PartiteEvent(
        val id: String,
        val title: String,
        val url: String,
        val isLive: Boolean,
        val date: String,
        val time: String,
        val competition: String,
        val sport: String,
        val homeName: String,
        val homeLogo: String?,
        val awayName: String,
        val awayLogo: String?,
        val channelsPreview: List<String>
    )

    data class PartiteChannel(
        val name: String,
        val configUuid: String
    )

    data class ResolvedStream(
        val name: String,
        val streamUrl: String,
        val headers: Map<String, String>
    )

    @Volatile private var cachedEvents: List<PartiteEvent> = emptyList()
    @Volatile private var cachedAt: Long = 0L

    suspend fun fetchEvents(): List<PartiteEvent> {
        val now = System.currentTimeMillis()
        if (cachedEvents.isNotEmpty() && now - cachedAt < 90_000L) {
            return cachedEvents
        }

        val html = runCatching {
            app.get(
                MAIN_URL,
                headers = mapOf("User-Agent" to BROWSER_UA),
                timeout = 15
            ).text
        }.getOrNull() ?: return cachedEvents

        val parsed = parseEventsHtml(html)
        if (parsed.isNotEmpty()) {
            cachedEvents = parsed
            cachedAt = now
        }
        return parsed.ifEmpty { cachedEvents }
    }

    private fun parseEventsHtml(html: String): List<PartiteEvent> {
        val doc = Jsoup.parse(html)
        val list = mutableListOf<PartiteEvent>()

        for (sec in doc.select("section.gl-cmp")) {
            val cmpBtn = sec.selectFirst("button.gl-cmp-h")
            val cmpName = cmpBtn?.selectFirst(".gl-name")?.text()?.trim().orEmpty().ifEmpty { "Campionato" }
            val meta = cmpBtn?.selectFirst(".gl-meta")?.text()?.trim().orEmpty()
            val sport = if (meta.contains("·")) {
                meta.substringAfterLast("·").trim().ifEmpty { "Sport" }
            } else {
                meta.ifEmpty { "Sport" }
            }

            for (a in sec.select("a.gl-ev")) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue
                val fullUrl = if (href.startsWith("http")) href else "$MAIN_URL$href"
                val id = href.substringAfterLast("/").trim()

                val whenSpan = a.selectFirst(".gl-when")
                val d = whenSpan?.attr("data-ark-d").orEmpty()
                val t = whenSpan?.attr("data-ark-t").orEmpty()
                val isLive = a.hasClass("gl-live") || whenSpan?.selectFirst(".gl-live-b") != null

                val homeSpan = a.selectFirst(".gl-side.gl-h")
                val homeName = homeSpan?.selectFirst(".gl-nm")?.text()?.trim().orEmpty()
                val homeLogo = homeSpan?.selectFirst("img.gl-tm")?.attr("src")?.takeIf { it.isNotBlank() }

                val awaySpan = a.selectFirst(".gl-side.gl-a")
                val awayName = awaySpan?.selectFirst(".gl-nm")?.text()?.trim().orEmpty()
                val awayLogo = awaySpan?.selectFirst("img.gl-tm")?.attr("src")?.takeIf { it.isNotBlank() }

                val title = if (homeName.isNotBlank() && awayName.isNotBlank()) {
                    "$homeName vs $awayName"
                } else {
                    a.selectFirst(".gl-mt")?.text()?.trim() ?: "Partita"
                }

                val broadcasters = a.select(".gl-chs .gl-ch").map { it.text().trim() }

                list.add(
                    PartiteEvent(
                        id = id,
                        title = title,
                        url = fullUrl,
                        isLive = isLive,
                        date = d,
                        time = t,
                        competition = cmpName,
                        sport = sport,
                        homeName = homeName,
                        homeLogo = homeLogo,
                        awayName = awayName,
                        awayLogo = awayLogo,
                        channelsPreview = broadcasters
                    )
                )
            }
        }
        return list
    }

    suspend fun fetchEventChannels(eventUrl: String): List<PartiteChannel> {
        val html = runCatching {
            app.get(
                eventUrl,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to "$MAIN_URL/"
                ),
                timeout = 15
            ).text
        }.getOrNull() ?: return emptyList()

        val doc = Jsoup.parse(html)
        val channels = mutableListOf<PartiteChannel>()

        val tabs = doc.select("#ch-tabs .ch-tab")
        for ((idx, tab) in tabs.withIndex()) {
            val dataUrl = tab.attr("data-url")
            val config = Regex("""config=([a-fA-F0-9-]+)""").find(dataUrl)?.groupValues?.get(1)
            if (!config.isNullOrBlank()) {
                val name = tab.text().trim().ifBlank { "Canale ${idx + 1}" }
                channels.add(PartiteChannel(name, config))
            }
        }

        if (channels.isEmpty()) {
            val iframe = doc.selectFirst("iframe#sport-player")
            val src = iframe?.attr("src").orEmpty()
            val config = Regex("""config=([a-fA-F0-9-]+)""").find(src)?.groupValues?.get(1)
            if (!config.isNullOrBlank()) {
                channels.add(PartiteChannel("Canale Principale", config))
            }
        }

        return channels
    }

    suspend fun resolveStream(configUuid: String, channelName: String): ResolvedStream? {
        return runCatching {
            // 1. Fetch player embed page to extract __ARK_AUTH__ parent_proof JWT
            val embedUrl = "https://prohostmedia.top/embed/player?config=$configUuid"
            val embedHtml = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to "$MAIN_URL/"
                ),
                timeout = 10
            ).text

            val proofMatch = Regex("""id="__ARK_AUTH__"[^>]*>\s*(\{[^<]+\})""").find(embedHtml)
                ?: return null
            val proofJson = JSONObject(proofMatch.groupValues[1])
            val parentProof = proofJson.optString("parent_proof").takeIf { it.isNotBlank() }
                ?: return null

            // 2. Fetch public embed config to extract streamId
            val cfgUrl = "https://panel.streamhostingcdn.top/api/embed-configs/public/$configUuid"
            val cfgRes = app.get(cfgUrl, headers = mapOf("User-Agent" to BROWSER_UA), timeout = 10).text
            val cfgJson = JSONObject(cfgRes)
            val streamId = cfgJson.optInt("stream", 0)
            if (streamId == 0) return null

            // 3. Request signed stream token
            val tokenUrl = "https://panel.streamhostingcdn.top/api/auth/get-stream-token"
            val tokenPayload = JSONObject().put("stream_id", streamId).toString()
            val tokenRes = app.post(
                tokenUrl,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $parentProof",
                    "Origin" to "https://prohostmedia.top",
                    "Referer" to "https://prohostmedia.top/",
                    "User-Agent" to BROWSER_UA
                ),
                json = JsonAsString(tokenPayload),
                timeout = 10
            ).text

            val tokenJson = JSONObject(tokenRes)
            val token = tokenJson.optString("token")
            val exp = tokenJson.optLong("exp", 0L)
            val relay = tokenJson.optString("r", "p7").ifBlank { "p7" }
            if (token.isBlank()) return null

            // 4. Construct live m3u8 playlist
            val m3u8Url = "https://$relay.streamhostingcdn.top/stream/$streamId/index.m3u8?token=$token&exp=$exp&ark_r=$relay"
            val headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "https://prohostmedia.top/",
                "Origin" to "https://prohostmedia.top"
            )

            ResolvedStream(
                name = channelName,
                streamUrl = m3u8Url,
                headers = headers
            )
        }.getOrNull()
    }
}
