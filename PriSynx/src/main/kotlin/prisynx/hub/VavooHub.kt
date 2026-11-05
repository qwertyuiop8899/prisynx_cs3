package prisynx.hub

import com.lagradost.cloudstream3.HomePageList
import kotlinx.coroutines.withTimeoutOrNull
import prisynx.vavoo.VavooProvider

/** Vavoo live TV inside the hub (provider renamed "PriSynx" so links route back here). */
object VavooHub {
    val api: VavooProvider by lazy { VavooProvider().apply { name = "PriSynx" } }

    suspend fun rows(): List<HomePageList> =
        withTimeoutOrNull(TIMEOUT_MS) {
            try {
                api.enabledRows()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

    fun isVavooUrl(url: String) = VavooProvider.isVavooUrl(url)

    fun clearCache() = VavooProvider.clearCache()

    private const val TIMEOUT_MS = 30_000L
}
