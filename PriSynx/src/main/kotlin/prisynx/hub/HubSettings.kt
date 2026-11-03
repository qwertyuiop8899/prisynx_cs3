package prisynx.hub

import android.content.Context
import android.content.SharedPreferences
import prisynx.providers.core.DebridSettings
import prisynx.providers.core.ProviderOptions
import prisynx.providers.AltadefinizioneProvider
import prisynx.providers.AnimeSaturnProvider
import prisynx.providers.AnimeUnityProvider
import prisynx.providers.AnimeWorldProvider
import prisynx.providers.Cb01Provider
import prisynx.providers.CorsaroViolaProvider
import prisynx.providers.DualSyncProvider
import prisynx.providers.EurostreamingProvider
import prisynx.providers.FilmezProvider
import prisynx.providers.GuardoserieProvider
import prisynx.providers.KissKHProvider
import prisynx.providers.MovyitaProvider
import prisynx.providers.OnlineSerieTvProvider
import prisynx.providers.PaCCProvider
import prisynx.providers.RealtimeProvider
import prisynx.providers.ToonitaliaProvider
import prisynx.providers.VidxGoProvider
import prisynx.providers.VixSrcProvider
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.TmdbRow

class HubSource(val id: String, val label: String, factory: () -> TmdbProvider) {
    val api: TmdbProvider by lazy(factory)
}

class HubTmdbRow(val id: String, val defaultOn: Boolean, val row: TmdbRow)

object HubSettings {
    const val DEFAULT_TOPX_PORTRAIT =
        "https://topx.stremio-italia.eu/c/Uty9zjSPGPwVbRdjaIspCldE5AIW1RKgy_kL88pC7Mo3HLu5qqmDizIL4ECylNn5cCyofuzOHhKWM3H6djV6aR81_vw2OD-aik9HyW4iUefTwonsXRmqfKs8ORN4oMdtep-cull1rFKnwJS_PwZwKx-aTgSmkpdb4P7gqgoCBpKNAGVTD2hMEeKai1sMzf4TweWBx6gAB62L_Yc66EvD0JXk5l1vj5DOYtwOzypwujVCrk6E-59lqq1SnSyBEh1IAXQY-FYArtgc5MlXz5P7WAU6GsWAeJQAZ6geFIDcdop7vArAlFLhIDp54vBQ94mzhChB2kTEj6titWa0VcYEFQPpvwWP1GXiCLRk4WyMXG3oPjJNe6S8ohvRoYNxUKURGmd8qsPJ4D2Q4-Pc3p2mgkYLrqIgPfdmx5fUE2Hm1unR4fCp59dO18Dea9VGAqf3wyhUgwFhjcNkUWwa71powY9ktEn3BdO1HbjeIPNHo5sfGafyIna95W2Z3HfyFnnJ98HpkPXPKC5p-r8mBLlfIpb0cVlXEPkzkxN6aHeTPIFTXEwq7sgO1hfjvyaxBYqAfokzhu771TGLeveh5IupE6pYCC4P_Qe8bVwNuLX9tYesLoPcvzf-mDKCx7AciCQ-v1LYFAVDbrj2Cb2zH_86u-O9UP9Tl0aQMuANMu2--ho-Z3Aa6z4/manifest.json"

    const val DEFAULT_TOPX_LANDSCAPE =
        "https://topx.stremio-italia.eu/c/-kIZGCIuPndmniQKIOb6TYAZgz00cQxVQIdOkB7TuRWaJPMkh0PkM3mgOO6MNamOgO8umTwJAuXxQ_edvfDSVg8rf7I56NNvqYShaVAmQOyEWpz2TbOWxWadRW_T0RM7SVJAzxFlJYEqa46twZw10ZIwYVXG5FC4vi2_SXmJzjW_U2PmKvV600rN-Hy3xfKXSvigMc8o0t1BAlespZt7MrQL05IZlzkkt5Lo-ca6mglzQwkSH_TNXdjQAcRWY0Vpf7gULUJLrcDtaoRQXGanqW-3L8Ki0KMIoA3gGkLFz22lgfxZcd8k4ew6KvrEn6e03Alt0neKLnPpGZrXzOz3Hsg4xenhTIsvBJKD-SG4CuO1Le2JIJ-V3UN562CZcKHg8mgY7Iv64gXzUegXK1blLaHbqJYDdbUUmDsV5w_-CVhzinFntX4wZRtfCW_5MyXp-tFGLzQqziUzD6M0M_etv8u_mg3fcW9oF88tJumfM8qRoPYEiYZ9UWtI01VwCJEPUEgEKzJ3Z0YYUNnmdNEX8xo48m4h1pG8BR4pYnS35lwAATM9xcb4KjUcSe9ZPhz8TZOXZn7IzLh-FZ5rRxY-BoTWzsza0Kex163adH0534eWCFw6WSUApCPtktyHouuV4-Ni58CiCllUOY1SAQ5dLxFDftj50HI25qr0EROnWbR3UKsA2JfZMNuH/manifest.json"

    val sources: List<HubSource> = listOf(
        HubSource("vixsrc", "VixSrc") { VixSrcProvider() },
        HubSource("vidxgo", "VidxGo") { VidxGoProvider() },
        HubSource("guardoserie", "Guardoserie") { GuardoserieProvider() },
        HubSource("eurostreaming", "Eurostreaming") { EurostreamingProvider() },
        HubSource("cb01", "CB01") { Cb01Provider() },
        HubSource("altadefinizione", "Altadefinizione") { AltadefinizioneProvider() },
        HubSource("filmez", "Filmez") { FilmezProvider() },
        HubSource("onlineserietv", "OnlineSerieTV") { OnlineSerieTvProvider() },
        HubSource("movyita", "MovyITA") { MovyitaProvider() },
        HubSource("dualsync", "DualSync") { DualSyncProvider() },
        HubSource("corsaroviola", "IlCorsaroViola") { CorsaroViolaProvider() },
        HubSource("pacc", "PaCC") { PaCCProvider() },
        HubSource("realtime", "Real Time") { RealtimeProvider() },
        HubSource("toonitalia", "Toonitalia") { ToonitaliaProvider() },
        HubSource("animeunity", "AnimeUnity") { AnimeUnityProvider() },
        HubSource("animesaturn", "AnimeSaturn") { AnimeSaturnProvider() },
        HubSource("animeworld", "AnimeWorld") { AnimeWorldProvider() },
        HubSource("kisskh", "KissKH") { KissKHProvider() }
    )

    val tmdbRows: List<HubTmdbRow> = listOf(
        HubTmdbRow("trending", true, TmdbRow("Tendenze della settimana", "trending/all/week")),
        HubTmdbRow("movie_popular", true, TmdbRow("Film popolari", "movie/popular", forcedTv = false)),
        HubTmdbRow("tv_popular", true, TmdbRow("Serie TV popolari", "tv/popular", forcedTv = true)),
        HubTmdbRow("movie_top", true, TmdbRow("Film più votati", "movie/top_rated", forcedTv = false)),
        HubTmdbRow("tv_top", true, TmdbRow("Serie TV più votate", "tv/top_rated", forcedTv = true)),
        HubTmdbRow("movie_now", false, TmdbRow("Al cinema", "movie/now_playing", "region=IT", forcedTv = false)),
        HubTmdbRow("movie_upcoming", false, TmdbRow("Film in arrivo", "movie/upcoming", "region=IT", forcedTv = false)),
        HubTmdbRow("tv_on_air", false, TmdbRow("Serie TV in onda", "tv/on_the_air", forcedTv = true))
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("prisynx_hub", Context.MODE_PRIVATE)
        DebridSettings.attach(context)
        ProviderOptions.torrentMaxResults = torrentMax
        ProviderOptions.syncedOnly = syncedOnly
    }

    private fun bool(key: String, def: Boolean) = prefs?.getBoolean(key, def) ?: def
    private fun setBool(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    // ------------------------------------------------------------ providers

    fun orderedSources(): List<HubSource> {
        val saved = prefs?.getString("source_order", null)
            ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val byId = sources.associateBy { it.id }
        val ordered = saved.mapNotNull { byId[it] }
        return ordered + sources.filter { it !in ordered }
    }

    fun saveOrder(order: List<HubSource>) {
        prefs?.edit()?.putString("source_order", order.joinToString(",") { it.id })?.apply()
    }

    fun isSourceEnabled(id: String) = bool("source_on_$id", true)
    fun setSourceEnabled(id: String, on: Boolean) = setBool("source_on_$id", on)

    fun enabledSources(): List<HubSource> = orderedSources().filter { isSourceEnabled(it.id) }

    /** Max IlCorsaroViola torrents in the stream list. */
    var torrentMax: Int
        get() = prefs?.getInt("torrent_max", ProviderOptions.DEFAULT_TORRENT_MAX) ?: ProviderOptions.DEFAULT_TORRENT_MAX
        set(value) {
            val v = value.coerceIn(1, 20)
            prefs?.edit()?.putInt("torrent_max", v)?.apply()
            ProviderOptions.torrentMaxResults = v
        }

    // ------------------------------------------------------------ TMDB rows

    fun isTmdbRowEnabled(row: HubTmdbRow) = bool("tmdb_on_${row.id}", row.defaultOn)
    fun setTmdbRowEnabled(row: HubTmdbRow, on: Boolean) = setBool("tmdb_on_${row.id}", on)

    // ------------------------------------------------------------ TopX

    var topxEnabled: Boolean
        get() = bool("topx_on", true)
        set(value) = setBool("topx_on", value)

    var topxFirst: Boolean
        get() = bool("topx_first", true)
        set(value) = setBool("topx_first", value)

    /** Landscape (16:9) TopX covers in horizontal rows instead of portrait posters. */
    var topxLandscape: Boolean
        get() = bool("topx_landscape", false)
        set(value) = setBool("topx_landscape", value)

    fun defaultTopxUrl(landscape: Boolean = topxLandscape) =
        if (landscape) DEFAULT_TOPX_LANDSCAPE else DEFAULT_TOPX_PORTRAIT

    fun topxUrlFor(landscape: Boolean): String {
        val key = if (landscape) "topx_url_landscape" else "topx_url_portrait"
        return prefs?.getString(key, null)?.takeIf { it.isNotBlank() } ?: defaultTopxUrl(landscape)
    }

    /** Manifest of the selected style (portrait or landscape). */
    var topxUrl: String
        get() = topxUrlFor(topxLandscape)
        set(value) {
            val key = if (topxLandscape) "topx_url_landscape" else "topx_url_portrait"
            prefs?.edit()?.putString(key, value.trim())?.apply()
        }

    val topxPortraitUrl: String get() = topxUrlFor(false)

    /** Landscape manifest, used for the title backdrop when a TopX card is opened. */
    val topxLandscapeUrl: String get() = topxUrlFor(true)

    fun isTopxCatalogEnabled(key: String) = bool("topx_cat_$key", true)
    fun setTopxCatalogEnabled(key: String, on: Boolean) = setBool("topx_cat_$key", on)

    fun <T> orderTopx(catalogs: List<T>, key: (T) -> String): List<T> {
        val saved = prefs?.getString("topx_order", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val byKey = catalogs.associateBy(key)
        val ordered = saved.mapNotNull { byKey[it] }
        return ordered + catalogs.filter { it !in ordered }
    }

    fun saveTopxOrder(keys: List<String>) {
        prefs?.edit()?.putString("topx_order", keys.joinToString(","))?.apply()
    }

    // ------------------------------------------------------------ MovyITA / DualSync

    var syncedOnly: Boolean
        get() = bool("synced_only", true)
        set(value) {
            setBool("synced_only", value)
            ProviderOptions.syncedOnly = value
        }

    // ------------------------------------------------------------ RaiPlay

    var raiHome: Boolean
        get() = bool("rai_home", true)
        set(value) = setBool("rai_home", value)

    var raiSearch: Boolean
        get() = bool("rai_search", true)
        set(value) = setBool("rai_search", value)

    fun isRaiRowEnabled(key: String) = bool("rai_row_$key", true)
    fun setRaiRowEnabled(key: String, on: Boolean) = setBool("rai_row_$key", on)

    // ------------------------------------------------------------ KissKH

    var kisskhHome: Boolean
        get() = bool("kisskh_home", true)
        set(value) = setBool("kisskh_home", value)

    var kisskhSearch: Boolean
        get() = bool("kisskh_search", true)
        set(value) = setBool("kisskh_search", value)

    fun isKisskhRowEnabled(id: String) = bool("kisskh_row_$id", true)
    fun setKisskhRowEnabled(id: String, on: Boolean) = setBool("kisskh_row_$id", on)

    // ------------------------------------------------------------ Anime (Kitsu)

    var kitsuHome: Boolean
        get() = bool("kitsu_home", true)
        set(value) = setBool("kitsu_home", value)

    var kitsuSearch: Boolean
        get() = bool("kitsu_search", true)
        set(value) = setBool("kitsu_search", value)

    /** Hide Japanese animation from TMDB rows/search (Kitsu handles anime). */
    var hideTmdbAnime: Boolean
        get() = bool("kitsu_hide_tmdb", true)
        set(value) = setBool("kitsu_hide_tmdb", value)

    fun isKitsuRowEnabled(id: String) = bool("kitsu_row_$id", true)
    fun setKitsuRowEnabled(id: String, on: Boolean) = setBool("kitsu_row_$id", on)

    // ------------------------------------------------------------ home order

    val homeGroups: List<Pair<String, String>> = listOf(
        "topx" to "Cataloghi TopX",
        "tmdb" to "Cataloghi TMDB",
        "kitsu" to "Anime (Kitsu)",
        "rai" to "RaiPlay",
        "kisskh" to "KissKH"
    )

    fun homeOrder(): List<String> {
        val ids = homeGroups.map { it.first }
        val saved = prefs?.getString("home_order", null)
            ?.split(',')?.filter { it in ids }?.distinct() ?: emptyList()
        val fallback = if (topxFirst) {
            listOf("topx", "tmdb", "kitsu", "rai", "kisskh")
        } else {
            listOf("tmdb", "topx", "kitsu", "rai", "kisskh")
        }
        return saved + fallback.filter { it !in saved }
    }

    fun saveHomeOrder(order: List<String>) {
        prefs?.edit()?.putString("home_order", order.joinToString(","))?.apply()
    }

    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
        ProviderOptions.torrentMaxResults = ProviderOptions.DEFAULT_TORRENT_MAX
        ProviderOptions.syncedOnly = true
        DebridSettings.reset()
    }
}
