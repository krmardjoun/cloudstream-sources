package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * ============================================================================
 *  JustWatchMovieProvider
 *  Site : https://justwatchmovie.biz/fr  (version française)
 *
 *  Structure des cartes :
 *    <article id="TMDB_ID" class="item ">
 *      <div class="thumb mb-4">
 *        <a href="/fr/movie/TMDB_ID/slug">
 *          <div class="_img_holder">
 *            <img class="img-fluid rounded" src="//i0.wp.com/...">
 *          </div>
 *        </a>
 *        <header class="entry-header">
 *          <h2 class="entry-title">
 *            <a href="/fr/movie/TMDB_ID/slug" class="_title">Titre (2026)</a>
 *          </h2>
 *        </header>
 *      </div>
 *    </article>
 *
 *  URLs :
 *    Films  : /fr/movie-popular?page=N
 *    Séries : /fr/tv-popular?page=N
 *    Recherche : /fr?s=query&page=N
 *    Détail : /fr/movie/TMDB_ID/slug ou /fr/tv/TMDB_ID/slug
 * ============================================================================
 */
class JustWatchMovieProvider : MainAPI() {
    override var mainUrl = "https://justwatchmovie.biz"
    override var name = "JustWatchMovie (FR)"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Préfixe de langue
    private val langPrefix = "/fr"

    // -------------------------------------------------------------------------
    // Accueil
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$langPrefix/movie-popular?page=" to "Films Populaires",
        "$langPrefix/movie-now-playing?page=" to "Actuellement en Salles",
        "$langPrefix/tv-popular?page=" to "Séries Populaires",
        "$langPrefix/tv-on-the-air?page=" to "Séries en cours"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        val document = app.get(url).document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // -------------------------------------------------------------------------
    // Helper carte
    // -------------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a._title")?.attr("href")
            ?: this.selectFirst("a[href]")?.attr("href")
            ?: return null
        val title = this.selectFirst("a._title")?.attr("title")
            ?: this.selectFirst("a._title")?.text()
            ?: this.selectFirst("h2.entry-title a")?.text()
            ?: return null
        val poster = this.selectFirst("img.img-fluid")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")
        val isTv = link.contains("/tv/")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, abs(link), type) {
            this.posterUrl = poster?.let { abs(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl$langPrefix?s=${query.encodeURL()}&page=$page").document
        return document.select("article.item")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    // -------------------------------------------------------------------------
    // Fiche de détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("h1")?.text() ?: "Sans titre"
        val poster = document.selectFirst("img.poster-image, .poster img, img.img-fluid.rounded")
            ?.attr("src")
        val plot = document.selectFirst(".entry-content p, .overview, .synopsis p")?.text()

        // Détecter série (présence d'épisodes/saisons)
        val isTv = url.contains("/tv/") || document.selectFirst(".seasons, #seasons") != null

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            document.select("ul.episodios li, .episode-item, tr.episode").forEach { ep ->
                val epLink = ep.selectFirst("a")?.attr("href") ?: return@forEach
                val epName = ep.selectFirst("a")?.text()
                val epNum = ep.selectFirst(".numerando, .episode-number")?.text()
                    ?.filter { it.isDigit() }?.toIntOrNull()
                episodes.add(newEpisode(abs(epLink)) {
                    this.name = epName
                    this.episode = epNum
                })
            }
            if (episodes.isEmpty()) {
                // Fallback : lien direct comme "data"
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                    newEpisode(url) { this.name = title }
                )) {
                    this.posterUrl = poster?.let { abs(it) }
                    this.plot = plot
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
    // -------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // Iframes directes
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { return@forEach }
            if (loadExtractor(abs(src), data, subtitleCallback, callback)) found = true
        }

        // Liens embed dans des boutons/liens
        document.select("a[href*=embed], a[href*=player], a.play-button[href]").forEach { a ->
            val src = a.attr("href").ifBlank { return@forEach }
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

    private fun String.encodeURL(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
