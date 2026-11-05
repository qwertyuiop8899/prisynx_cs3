package prisynx.vavoo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VavooPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VavooProvider())
    }
}
