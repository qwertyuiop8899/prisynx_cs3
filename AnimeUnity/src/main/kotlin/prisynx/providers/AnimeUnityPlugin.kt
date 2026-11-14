package prisynx.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.providers.core.CatalogSettings
import prisynx.providers.core.CatalogSettingsDialog

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    override fun load(context: Context) {
        CatalogSettings.attach(context, "animeunity_catalogs")
        openSettings = { ctx ->
            CatalogSettingsDialog.show(
                ctx,
                "AnimeUnity",
                AnimeUnityProvider.catalogs,
                showType = true
            )
        }
        registerMainAPI(AnimeUnityProvider())
    }
}
