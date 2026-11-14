package prisynx.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.providers.core.CatalogSettings
import prisynx.providers.core.CatalogSettingsDialog

@CloudstreamPlugin
class AnimeWorldPlugin : Plugin() {
    override fun load(context: Context) {
        CatalogSettings.attach(context, "animeworld_catalogs")
        openSettings = { ctx ->
            CatalogSettingsDialog.show(ctx, "AnimeWorld", AnimeWorldProvider.catalogs)
        }
        registerMainAPI(AnimeWorldProvider())
    }
}
