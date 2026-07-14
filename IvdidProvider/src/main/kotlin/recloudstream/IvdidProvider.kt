package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * ============================================================================
 *  IvdidProvider
 *  Site : https://ivdid.com/2alhobzy6/home/ivdid  (films en français/arabe)
 *  Langue : Français (fr)
 *
 *  Structure des cartes :
 *    <a class="trend-card" href="/2alhobzy6/b/ivdid/XXXXXXXX">
 *      <div class="trend-card-poster">
 *        <img class="trend-card-img" src="https://image.tmdb.org/t/p/w300/..." alt="Titre">
 *        <div class="trend-card-title">Titre du film</div>
 *        <span class="trend-card-cat">
 *          <span class="trend-card-date">2026</span> · Aventure
 *        </span>
 *      </div>
 *    </a>
 *
 *  URLs :
 *    Base : https://ivdid.com
 *    Accueil : /2alhobzy6/home/ivdid
 *    Catégorie : /2alhobzy6/c/ivdid/{genre_id}/{page}
 *    Film : /2alhobzy6/b/ivdid/{film_id}
 *    Recherche : POST /2alhobzy6/home/ivdid avec searchword=query
 *
 *  IDs de genre :
 *    1=Action, 2=Animation, 4=Aventure, 6=Comédie, 7=Drame,
 *    8=Fantastique, 9=Horreur, 10=Policier, 11=Sci-Fi, 12=Thriller,
 *    26=Documentaire, 29=À l'affiche
 * ============================================================================
 */
class IvdidProvider : MainAPI() {
    override var mainUrl = "https://ivdid.com"
    override var name = "Ivdid"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Préfixe de navigation du site
    private val baseNav = "/2alhobzy6"

    // -------------------------------------------------------------------------
    // Accueil : rangées par genre
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$baseNav/c/ivdid/29/" to "À l'affiche",
        "$baseNav/c/ivdid/1/"  to "Action",
        "$baseNav/c/ivdid/4/"  to "Aventure",
        "$baseNav/c/ivdid/6/"  to "Comédie",
        "$baseNav/c/ivdid/7/"  to "Drame",
        "$baseNav/c/ivdid/8/"  to "Fantastique",
        "$baseNav/c/ivdid/9/"  to "Horreur",
        "$baseNav/c/ivdid/11/" to "Science-Fiction",
        "$baseNav/c/ivdid/2/"  to "Animation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}${page - 1}"   // page commence à 0 sur ce site
        val document = app.get(url).document
        val items = document.select("a.trend-card, a.film-card, a.showcase-card")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNextPage = items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Helper carte
    // -------------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.attr("href").ifBlank { return null }
        val title = this.selectFirst("div.trend-card-title, .film-card-title, .showcase-card-title")
            ?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null
        val poster = this.selectFirst("img.trend-card-img, img.film-card-img, img")?.attr("src")

        return newMovieSearchResponse(title, abs(link), TvType.Movie) {
            this.posterUrl = poster?.let { abs(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Recherche (formulaire POST)
    // -------------------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.post(
            "$mainUrl$baseNav/home/ivdid",
            data = mapOf("searchword" to query),
            referer = "$mainUrl$baseNav/home/ivdid"
        ).document

        return document.select("a.trend-card, a.film-card, a.showcase-card, a.film-list-item")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    // -------------------------------------------------------------------------
    // Fiche de détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Titre
        val title = document.selectFirst(".film-detail-title, h1.film-title, .film-detail-meta h1")
            ?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Sans titre"

        // Affiche
        val poster = document.selectFirst(".film-detail-poster img, img.film-poster")?.attr("src")

        // Synopsis
        val plot = document.selectFirst(".film-synopsis-text, .synopsis-modal-text, .film-detail-desc, p.film-desc")
            ?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let { abs(it) }
            this.plot = plot
        }
    }

    // -------------------------------------------------------------------------
    // Liens vidéo
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // Sélecteurs iframe
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { return@forEach }
            if (src.contains("ivdid.com") || src.startsWith("/")) return@forEach // ignorer les iframes internes
            if (loadExtractor(abs(src), data, subtitleCallback, callback)) found = true
        }

        // Liens directs vers des players
        document.select("a[href*=player], a[href*=embed], a.svgplayer[href]").forEach { a ->
            val src = a.attr("href").ifBlank { return@forEach }
            if (loadExtractor(abs(src), data, subtitleCallback, callback)) found = true
        }

        // Chercher un data-src ou data-url dans les éléments de player
        document.select("[data-src], [data-url], [data-embed]").forEach { el ->
            val src = el.attr("data-src").ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-embed") }.ifBlank { return@forEach }
            if (loadExtractor(abs(src), data, subtitleCallback, callback)) found = true
        }

        return found
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun abs(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> mainUrl.trimEnd('/') + url
        else -> mainUrl.trimEnd('/') + "/" + url
    }
}
