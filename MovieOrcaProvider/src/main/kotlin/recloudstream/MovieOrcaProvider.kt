package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * ============================================================================
 *  MovieOrcaProvider
 *  Site : https://movieorca.cam
 *  Thème : DooPlay (WordPress)
 *
 *  Structure des cartes (ex. page /movies/) :
 *    <article id="post-XXX" class="item movies">
 *      <div class="poster">
 *        <img src="...poster...">
 *        <a href="/movies/slug/"><div class="see play1"></div></a>
 *      </div>
 *      <div class="data">
 *        <h3><a href="/movies/slug/">Titre</a></h3>
 *        <span>Date</span>
 *      </div>
 *    </article>
 *
 *  Page de détail :
 *    <div class="sheader">
 *      <div class="poster"><img src="..."></div>
 *      <div class="data"><h1>Titre</h1>...</div>
 *    </div>
 *    <div class="wp-content"><p>Synopsis</p></div>
 *    Player : boutons data-post/data-nume → iframe chargée via AJAX DooPlay
 * ============================================================================
 */
class MovieOrcaProvider : MainAPI() {
    override var mainUrl = "https://movieorca.cam"
    override var name = "MovieOrca"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // -------------------------------------------------------------------------
    // Accueil : rangées affichées dans CloudStream
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "/movies/page/" to "Films récents",
        "/tvshows/page/" to "Séries récentes",
        "/trending/page/" to "Tendances"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        val document = app.get(url).document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // -------------------------------------------------------------------------
    // Helper : convertit une <article class="item"> en SearchResponse
    // -------------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        // Lien principal
        val link = this.selectFirst("div.poster > a")?.attr("href")
            ?: this.selectFirst("div.data h3 a")?.attr("href")
            ?: return null
        // Titre
        val title = this.selectFirst("div.data h3 a")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null
        // Affiche
        val poster = this.selectFirst("div.poster img")?.attr("src")
        // Type (movie ou tvshow)
        val type = if (this.hasClass("tvshows") || this.hasClass("episodes"))
            TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, abs(link), type) {
            this.posterUrl = poster?.let { abs(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/?s=${query.encodeURL()}").document
        return document.select("article.item")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    // -------------------------------------------------------------------------
    // Fiche de détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.sheader div.data h1")?.text()
            ?: document.selectFirst("h1")?.text() ?: "Sans titre"
        val poster = document.selectFirst("div.sheader div.poster img")?.attr("src")
        val plot = document.selectFirst("div#info .wp-content p")?.text()
            ?: document.selectFirst(".wp-content")?.text()

        // Détecter si c'est une série (présence du sélecteur de saisons)
        val isSeries = document.selectFirst("#seasons") != null

        if (isSeries) {
            // Récupérer tous les épisodes listés dans les saisons
            val episodes = mutableListOf<Episode>()
            document.select("#seasons .se-c").forEach { season ->
                val seasonNum = season.selectFirst(".se-q span.se-t")?.text()?.toIntOrNull()
                season.select("ul.episodios li").forEach { li ->
                    val epLink = li.selectFirst(".episodiotitle a")?.attr("href") ?: return@forEach
                    val epTitle = li.selectFirst(".episodiotitle a")?.text()
                    val epNum = li.selectFirst(".numerando")?.text()
                        ?.split("-")?.lastOrNull()?.trim()?.toIntOrNull()
                    episodes.add(newEpisode(abs(epLink)) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { abs(it) }
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let { abs(it) }
            this.plot = plot
        }
    }

    // -------------------------------------------------------------------------
    // Liens vidéo
    // DooPlay charge les iframes via AJAX : POST /wp-admin/admin-ajax.php
    // avec action=doo_player_ajax & post=<postId> & nume=<n> & type=movie|tv
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Récupérer les options de player (boutons data-post / data-nume)
        val options = document.select("li.dooplay_player_option")
        var found = false

        for (opt in options) {
            val postId = opt.attr("data-post").ifBlank { continue }
            val nume = opt.attr("data-nume").ifBlank { continue }
            val type = opt.attr("data-type").ifBlank { "movie" }

            // Appel AJAX DooPlay pour obtenir l'iframe du serveur
            val ajaxResp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                referer = data
            ).parsed<DooPlayerResponse>()

            val iframeSrc = ajaxResp?.embed_url ?: continue
            if (loadExtractor(abs(iframeSrc), data, subtitleCallback, callback)) {
                found = true
            }
        }

        // Fallback : iframes déjà présentes dans la page
        if (!found) {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { return@forEach }
                if (loadExtractor(abs(src), data, subtitleCallback, callback)) found = true
            }
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

    private fun String.encodeURL(): String = java.net.URLEncoder.encode(this, "UTF-8")

    data class DooPlayerResponse(val embed_url: String?)
}
