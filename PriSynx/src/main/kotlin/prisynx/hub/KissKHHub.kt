package prisynx.hub

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.withTimeoutOrNull
import prisynx.providers.KissKHProvider
import prisynx.providers.KissKHSettings

/**
 * KissKH inside the hub.
 *
 * KissKH is a native catalogue provider (its own ids and endpoints), so the
 * hub cannot treat it like the TMDB sources: its rows, search and pages are
 * forwarded here. The provider is renamed "PriSynx" so every card and episode
 * it builds is routed back to the hub.
 */
object KissKHHub {
    val api: KissKHProvider by lazy { KissKHProvider().apply { name = "PriSynx" } }

    data class Home(val rows: List<HomePageList>, val hasNext: Boolean) {
        companion object {
            val EMPTY = Home(emptyList(), false)
        }
    }

    private var cache: Home = Home.EMPTY
    private var cachedAt = 0L
    private var failedAt = 0L

    suspend fun rows(page: Int): Home {
        val now = System.currentTimeMillis()
        if (page <= 1) {
            if (cache.rows.isNotEmpty() && now - cachedAt < CACHE_MS) return cache
            // Do not stall every home load when KissKH is unreachable.
            if (now - failedAt < RETRY_MS) return Home.EMPTY
        }
        val home = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val response = api.getMainPage(page, MainPageRequest("KissKH", "", false))
                Home(response.items, response.hasNext)
            } catch (e: Exception) {
                null
            }
        } ?: Home.EMPTY
        if (page <= 1) {
            if (home.rows.isNotEmpty()) {
                cache = home
                cachedAt = System.currentTimeMillis()
            } else {
                failedAt = System.currentTimeMillis()
            }
        }
        return home
    }

    /** Drops the rows disabled in the hub settings; unknown rows are kept. */
    fun visibleRows(rows: List<HomePageList>): List<HomePageList> = rows.filter { row ->
        val catalog = catalogOf(row) ?: return@filter true
        HubSettings.isKisskhRowEnabled(catalog.id)
    }

    fun catalogOf(row: HomePageList): KissKHProvider.Catalog? =
        KissKHProvider.catalogs.firstOrNull { it.label == row.name }

    /** True for the native pages and the episode refs built by KissKH. */
    fun isKissUrl(url: String): Boolean {
        val value = url.trim()
        if (value.startsWith("kisskh:")) return true
        return value.startsWith(KissKHSettings.baseUrl, ignoreCase = true)
    }

    fun clearCache() {
        cache = Home.EMPTY
        cachedAt = 0L
        failedAt = 0L
        KissKHProvider.forgetPages()
    }

    private const val CACHE_MS = 20 * 60_000L
    private const val RETRY_MS = 3 * 60_000L
    private const val TIMEOUT_MS = 25_000L
}
