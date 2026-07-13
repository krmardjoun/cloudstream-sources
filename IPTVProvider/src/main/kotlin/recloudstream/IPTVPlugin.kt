package recloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IPTVPlugin : BasePlugin() {
    override fun load() {
        // Point d'entrée : on enregistre notre source IPTV.
        registerMainAPI(IPTVProvider())
    }
}
