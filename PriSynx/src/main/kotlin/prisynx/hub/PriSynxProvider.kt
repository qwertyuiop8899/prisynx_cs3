package prisynx.hub

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import prisynx.providers.VidxGo
import com.fasterxml.jackson.databind.JsonNode
import prisynx.providers.core.Kitsu
import prisynx.providers.core.KitsuRef
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.isTmdbAnime
import prisynx.providers.core.kitsuCard

class PriSynxProvider : TmdbProvider("PriSynx", "https://www.themoviedb.org") {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = HubSettings.tmdbRows.filter { HubSettings.isTmdbRowEnabled(it) }
        return coroutineScope {
            val tmdbJob = rows.map { spec -> async { fetchRow(spec.row, page) } }
            val topxJob = async { if (page <= 1 && HubSettings.topxEnabled) topxRows() else emptyList() }
            val raiJob = async { if (page <= 1 && HubSettings.raiHome) raiRows() else emptyList() }
            val kitsuJob = async { if (HubSettings.kitsuHome) kitsuRows(page) else emptyList() }
            val kisskhJob = async {
                if (HubSettings.kisskhHome) KissKHHub.rows(page) else KissKHHub.Home.EMPTY
            }
            val tmdb = tmdbJob.awaitAll()
            val tmdbLists = tmdb.filter { it.list.isNotEmpty() }.map { HomePageList(it.name, it.list) }
            val topx = topxJob.await()
            val rai = raiJob.await()
            val kitsu = kitsuJob.await()
            val kisskh = kisskhJob.await()
            val groups = mapOf(
                "tmdb" to tmdbLists,
                "topx" to topx,
                "kitsu" to kitsu,
                "rai" to rai,
                "kisskh" to KissKHHub.visibleRows(kisskh.rows)
            )
            val lists = HubSettings.homeOrder().flatMap { groups[it].orEmpty() }
            newHomePageResponse(
                lists,
                tmdb.any { it.hasNext } || kitsu.isNotEmpty() || kisskh.hasNext
            )
        }
    }

    private suspend fun raiRows(): List<HomePageList> =
        RaiHub.rows()
            .filter { HubSettings.isRaiRowEnabled(RaiHub.key(it)) }
            .map { HomePageList("RaiPlay · ${it.name}", it.list, isHorizontalImages = true) }

    // ------------------------------------------------------------ Anime (Kitsu)

    private suspend fun kitsuRows(page: Int): List<HomePageList> = coroutineScope {
        Kitsu.rows.filter { HubSettings.isKitsuRowEnabled(it.id) }.map { row ->
            async {
                val cards = try {
                    Kitsu.catalog(row, page).mapNotNull { kitsuCard(it) }
                } catch (e: Exception) {
                    emptyList()
                }
                if (cards.isEmpty()) null else HomePageList(row.name, cards)
            }
        }.awaitAll().filterNotNull()
    }

    override fun acceptCard(node: JsonNode, isTv: Boolean): Boolean =
        !(HubSettings.hideTmdbAnime && isTmdbAnime(node))

    override suspend fun resolveKitsu(
        ref: KitsuRef,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val active = HubSettings.enabledSources().filter { it.api.supportsKitsu }
        return runOrdered(active, callback) { api, emit -> api.resolveKitsu(ref, subtitleCallback, emit) }
    }

    // ------------------------------------------------------------ RaiPlay routing

    private suspend fun tmdbSearch(query: String): List<SearchResponse> = super.search(query)

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val kitsu = async {
            if (!HubSettings.kitsuSearch) emptyList()
            else withTimeoutOrNull(RAI_TIMEOUT_MS) {
                try {
                    Kitsu.search(query).mapNotNull { kitsuCard(it) }
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        }
        val rai = async {
            if (!HubSettings.raiSearch) emptyList()
            else withTimeoutOrNull(RAI_TIMEOUT_MS) {
                try {
                    RaiHub.api.search(query)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        }
        val kisskh = async {
            if (!HubSettings.kisskhSearch) emptyList()
            else withTimeoutOrNull(KISSKH_TIMEOUT_MS) {
                try {
                    KissKHHub.api.searchNative(query)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        }
        val tmdb = try {
            tmdbSearch(query).onEach { result ->
                MediaRef.parse(result.url)?.let {
                    result.posterUrl = TopX.posterFor(it.isTv, it.id)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        tmdb + kitsu.await() + rai.await() + kisskh.await()
    }

    override suspend fun load(url: String): LoadResponse {
        val ref = MediaRef.parse(url)
        if (ref != null) {
            // TopX portrait cover for TMDB titles; backdrop is left pure TMDB without overlays.
            return super.load(url).apply {
                posterUrl = TopX.posterFor(ref.isTv, ref.id)
            }
        }
        if (KitsuRef.parse(url) != null) return super.load(url)
        if (KissKHHub.isKissUrl(url)) return KissKHHub.api.load(url)
        return RaiHub.api.load(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("RaiPlay: contenuto non disponibile")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (MediaRef.parse(data) != null || KitsuRef.parse(data) != null) {
            return super.loadLinks(data, isCasting, subtitleCallback, callback)
        }
        if (KissKHHub.isKissUrl(data)) {
            return KissKHHub.api.loadLinks(data, isCasting, subtitleCallback, callback)
        }
        return RaiHub.api.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun topxRows(): List<HomePageList> {
        val url = HubSettings.topxUrl
        val landscape = HubSettings.topxLandscape
        val catalogs = HubSettings.orderTopx(TopX.catalogs(url)) { it.key }
            .filter { HubSettings.isTopxCatalogEnabled(it.key) }
        val gate = Semaphore(6)
        return coroutineScope {
            catalogs.map { catalog ->
                async {
                    gate.withPermit {
                        val cards = TopX.items(url, catalog).map { item -> card(item) }
                        if (cards.isEmpty()) null
                        else HomePageList(catalog.label, cards, isHorizontalImages = landscape)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun card(item: TopxItem): SearchResponse {
        val ref = MediaRef(item.tmdbId, item.isTv)
        val url = ref.asWebUrl() + TopX.FROM_TOPX
        return if (item.isTv) {
            newTvSeriesSearchResponse(item.title, url, TvType.TvSeries, fix = false) {
                posterUrl = item.poster
                year = item.year
            }
        } else {
            newMovieSearchResponse(item.title, url, TvType.Movie, fix = false) {
                posterUrl = item.poster
                year = item.year
            }
        }
    }

    /**
     * Runs every enabled source in parallel. Links are forwarded in the order
     * chosen in the settings: the source at the head of the queue streams its
     * links immediately, later ones are buffered until the earlier ones finish.
     */
    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val active = HubSettings.enabledSources().filter { source ->
            if (ref.isTv) source.api.supportsTv else source.api.supportsMovies
        }
        return runOrdered(active, callback) { api, emit -> api.resolve(ref, meta, subtitleCallback, emit) }
    }

    private suspend fun runOrdered(
        active: List<HubSource>,
        callback: (ExtractorLink) -> Unit,
        block: suspend (TmdbProvider, (ExtractorLink) -> Unit) -> Boolean
    ): Boolean {
        if (active.isEmpty()) return false

        val lock = Any()
        val buffers = List(active.size) { mutableListOf<ExtractorLink>() }
        val done = BooleanArray(active.size)
        var head = 0
        var found = false

        fun onLink(index: Int, link: ExtractorLink) = synchronized(lock) {
            found = true
            if (index == head) callback(link) else buffers[index].add(link)
        }

        fun onDone(index: Int) = synchronized(lock) {
            done[index] = true
            while (head < active.size && done[head]) {
                head++
                if (head < active.size) {
                    buffers[head].forEach(callback)
                    buffers[head].clear()
                }
            }
        }

        coroutineScope {
            active.forEachIndexed { index, source ->
                launch {
                    try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            block(source.api) { onLink(index, it) }
                        }
                    } catch (e: Exception) {
                        // one broken source must not break the others
                    } finally {
                        onDone(index)
                    }
                }
            }
        }
        return found
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        VidxGo.interceptorFor(extractorLink)

    private companion object {
        const val SOURCE_TIMEOUT_MS = 60_000L
        const val RAI_TIMEOUT_MS = 20_000L
        const val KISSKH_TIMEOUT_MS = 20_000L
    }
}
