package prisynx.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.providers.core.CatalogSettings
import prisynx.providers.core.CatalogSettingsDialog

@CloudstreamPlugin
class AnimeSaturnPlugin : Plugin() {
    override fun load(context: Context) {
        CatalogSettings.attach(context, "animesaturn_catalogs")
        openSettings = { ctx ->
            CatalogSettingsDialog.show(ctx, "AnimeSaturn", AnimeSaturnProvider.catalogs)
        }
        registerMainAPI(AnimeSaturnProvider())
    }
}
