package prisynx.live

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.vavoo.VavooProvider

@CloudstreamPlugin
class LivePlugin : Plugin() {
    override fun load(context: Context) {
        LiveSettings.init(context)
        registerMainAPI(SportzXProvider())
        registerMainAPI(VavooProvider())
        registerMainAPI(PartiteLiveProvider())
        openSettings = { ctx -> LiveSettingsDialog.show(ctx) }
    }
}
