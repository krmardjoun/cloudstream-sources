package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Lecteur IPTV générique : télécharge une playlist M3U (ou Xtream `get.php`),
 * la parse en chaînes, les regroupe par `group-title`, et les lit en direct.
 *
 * Pour utiliser TA playlist : change la constante PLAYLIST_URL ci-dessous,
 * puis relance la compilation (push GitHub). L'app proposera la mise à jour.
 */
class IPTVProvider : MainAPI() {
    override var name = "IPTV (M3U)"
    override var mainUrl = "https://iptv-org.github.io"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "fr"
    override val hasMainPage = true

    companion object {
        // =====================================================================
        //  👉 REMPLACE cette URL par TA playlist.
        //     • M3U classique  : https://exemple.com/ma_playlist.m3u
        //     • Abonnement Xtream :
        //         http://HOTE:PORT/get.php?username=USER&password=PASS&type=m3u_plus&output=ts
        //  (Par défaut : liste de chaînes libres/légales iptv-org, pour tester.)
        // =====================================================================
        private const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.m3u"

        // Nombre de groupes affichés par page sur l'accueil.
        private const val GROUPS_PER_PAGE = 15

        // Durée de vie du cache mémoire (10 min) pour éviter de retélécharger.
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    /** Une chaîne IPTV. Sérialisée en JSON pour transiter entre search → load → loadLinks. */
    data class Channel(
        val title: String,
        val url: String,
        val logo: String?,
        val group: String
    )

    // --- Cache mémoire simple ---------------------------------------------------
    private var cache: List<Channel>? = null
    private var cacheTime: Long = 0L

    private suspend fun getChannels(): List<Channel> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheTime < CACHE_TTL_MS) return it }
        val text = app.get(PLAYLIST_URL).text
        val channels = parseM3U(text)
        cache = channels
        cacheTime = now
        return channels
    }

    /** Parse un fichier M3U (#EXTINF ... , Nom  puis la ligne d'URL). */
    private fun parseM3U(content: String): List<Channel> {
        val result = ArrayList<Channel>()
        val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")
        val groupRegex = Regex("group-title=\"([^\"]*)\"")

        var title = "Sans nom"
        var logo: String? = null
        var group = "Autres"
        var pending = false

        for (raw in content.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    title = line.substringAfterLast(",").trim().ifBlank { "Sans nom" }
                    logo = logoRegex.find(line)?.groupValues?.getOrNull(1)?.ifBlank { null }
                    group = groupRegex.find(line)?.groupValues?.getOrNull(1)?.ifBlank { "Autres" } ?: "Autres"
                    pending = true
                }
                // On ignore les autres directives (#EXTVLCOPT, #EXTGRP, lignes vides...).
                line.isEmpty() || line.startsWith("#") -> Unit
                // Première ligne "normale" après un #EXTINF = l'URL du flux.
                pending -> {
                    result.add(Channel(title, line, logo, group))
                    pending = false
                }
            }
        }
        return result
    }

    // --- Accueil : un bloc par groupe de chaînes -------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val groups = getChannels().groupBy { it.group }.toList()
        val pageGroups = groups.drop((page - 1) * GROUPS_PER_PAGE).take(GROUPS_PER_PAGE)

        val lists = pageGroups.map { (groupName, chans) ->
            // On limite chaque rangée à 100 chaînes pour rester fluide sur TV.
            HomePageList(groupName, chans.take(100).map { it.toSearch() }, true)
        }
        return newHomePageResponse(lists, hasNext = groups.size > page * GROUPS_PER_PAGE)
    }

    // --- Recherche par nom de chaîne -------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return getChannels()
            .filter { it.title.contains(query, ignoreCase = true) }
            .take(100)
            .map { it.toSearch() }
            .toNewSearchResponseList()
    }

    private fun Channel.toSearch(): SearchResponse {
        // L'URL de la SearchResponse encode toute la chaîne en JSON (clé unique).
        return newLiveSearchResponse(title, this.toJson(), TvType.Live) {
            this.posterUrl = logo
        }
    }

    // --- Page d'une chaîne ------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val ch = tryParseJson<Channel>(url)
            ?: throw RuntimeException("Chaîne IPTV invalide")
        // dataUrl = l'URL du flux, transmise telle quelle à loadLinks().
        return newLiveStreamLoadResponse(ch.title, ch.toJson(), ch.url) {
            this.posterUrl = ch.logo
            this.plot = "Groupe : ${ch.group}"
            this.tags = listOf(ch.group)
        }
    }

    // --- Résolution du flux -----------------------------------------------------
    override suspend fun loadLinks(
        data: String,               // = ch.url (le flux direct)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = data
        // .m3u8 = flux HLS ; sinon on laisse CloudStream déduire le type.
        val type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else null

        callback(
            newExtractorLink(this.name, this.name, streamUrl, type) {
                this.quality = Qualities.Unknown.value
                this.referer = ""
            }
        )
        return true
    }
}
