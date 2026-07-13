package recloudstream

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * ============================================================================
 *  MODÈLE DE PROVIDER (à personnaliser) — ne cible aucun site par défaut.
 * ============================================================================
 *
 *  CloudStream appelle 4 méthodes dans cet ordre :
 *
 *    getMainPage()  → la page d'accueil (rangées de vignettes)
 *    search()       → quand l'utilisateur cherche un titre
 *    load()         → quand il ouvre une fiche film/série (titre, résumé, épisodes)
 *    loadLinks()    → quand il lance la lecture (trouve le vrai lien vidéo)
 *
 *  Pour l'adapter à un site :
 *   1. Mets `mainUrl` = l'adresse du site.
 *   2. Ouvre le site dans Chrome → clic droit → "Inspecter" pour trouver les
 *      sélecteurs CSS (classes des cartes, du titre, du lecteur...).
 *   3. Remplace les sélecteurs marqués « TODO » ci-dessous.
 *
 *  Astuce : `loadExtractor()` sait déjà extraire des dizaines d'hébergeurs
 *  vidéo courants (voir la liste des "extractors" de CloudStream). La plupart
 *  du temps, il suffit de lui passer l'URL de l'iframe du lecteur.
 * ============================================================================
 */
class SiteTemplateProvider : MainAPI() {
    // TODO — adresse du site à utiliser
    override var mainUrl = "https://example.com"
    override var name = "Modèle (à configurer)"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Rangées de l'accueil : "chemin de l'URL" to "Titre affiché".
    // TODO — adapte ces chemins à la structure du site.
    override val mainPage = mainPageOf(
        "/movies/page/" to "Films récents",
        "/tv-series/page/" to "Séries récentes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // request.data = le chemin défini dans mainPage ; on ajoute le n° de page.
        val document = app.get("$mainUrl${request.data}$page").document

        // TODO — sélecteur des "cartes" de la grille (ex: "div.movie-item", ".ml-item").
        val items = document.select("div.movie-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    /** Transforme une carte HTML en vignette CloudStream. */
    private fun Element.toSearchResult(): SearchResponse? {
        // TODO — dans la carte : le lien, le titre, l'affiche.
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("a")?.attr("title")
            ?: this.selectFirst(".title")?.text()
            ?: return null
        val poster = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, abs(link), TvType.Movie) {
            this.posterUrl = poster?.let { abs(it) }
        }
    }

    /** Rend une URL absolue (préfixe mainUrl si elle est relative). */
    private fun abs(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> mainUrl.trimEnd('/') + url
        else -> mainUrl.trimEnd('/') + "/" + url
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // TODO — format de l'URL de recherche du site.
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movie-item")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // TODO — sélecteurs de la fiche détaillée.
        val title = document.selectFirst("h1")?.text() ?: "Sans titre"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.description")?.text()

        // Cas simple : un film. On passe l'URL de la page comme "data" pour loadLinks.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let { abs(it) }
            this.plot = plot
        }
        // (Pour une série, on utiliserait newTvSeriesLoadResponse + une liste d'épisodes,
        //  chaque épisode portant l'URL de sa page/lecteur comme data.)
    }

    override suspend fun loadLinks(
        data: String,              // ici : l'URL de la page (ou de l'épisode)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // TODO — récupère la ou les URL des lecteurs (iframes des hébergeurs vidéo).
        val iframes = document.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }

        var found = false
        for (iframe in iframes) {
            // loadExtractor reconnaît automatiquement beaucoup d'hébergeurs
            // et remplit `callback` avec les liens vidéo jouables.
            if (loadExtractor(abs(iframe), data, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}
