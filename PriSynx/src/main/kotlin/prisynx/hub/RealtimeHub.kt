package prisynx.hub

import com.lagradost.cloudstream3.HomePageList
import kotlinx.coroutines.withTimeoutOrNull
import prisynx.providers.RealtimeProvider

/**
 * Real Time / NOVE / DMAX / Food Network inside the hub. Discovery's Aurora
 * API has its own catalogue and page urls, so rows, search results and pages
 * are forwarded here. The provider is renamed "PriSynx" so every card and
 * episode it builds is routed back to the hub.
 */
object RealtimeHub {
    val api: RealtimeProvider by lazy { RealtimeProvider().apply { name = "PriSynx" } }

    private var cache: List<HomePageList> = emptyList()
    private var cachedAt = 0L

    suspend fun rows(): List<HomePageList> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < CACHE_MS) return cache
        val rows = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                api.catalogueRows()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
        if (rows.isNotEmpty()) {
            cache = rows
            cachedAt = now
        }
        return rows.ifEmpty { cache }
    }

    fun key(row: HomePageList): String =
        row.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** Drops the channel rows disabled in the hub settings. */
    fun visibleRows(rows: List<HomePageList>): List<HomePageList> =
        rows.filter { HubSettings.isRealtimeRowEnabled(key(it)) }

    /** True for the native show/episode pages built by Real Time. */
    fun isNativeUrl(url: String): Boolean = api.ownsUrl(url)

    fun clearCache() {
        cache = emptyList()
        cachedAt = 0L
    }

    private const val CACHE_MS = 20 * 60_000L
    private const val TIMEOUT_MS = 25_000L
}
