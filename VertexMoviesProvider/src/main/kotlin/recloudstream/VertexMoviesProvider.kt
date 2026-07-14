package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * ============================================================================
 *  VertexMoviesProvider
 *  Site : https://vertexmovies.com
 *  Langue : Anglais (films, séries, anime)
 *
 *  IMPORTANT : VertexMovies est une SPA (Single Page Application).
 *  Le contenu de l'accueil est chargé dynamiquement via JavaScript.
 *  Les requêtes HTTP sur l'API interne sont utilisées à la place du scraping HTML.
 *
 *  API observée (via Network > Fetch/XHR dans DevTools) :
 *    GET /api/movies?type=action&from=index&page=N  → JSON liste de films
 *    GET /api/search?q=query                        → JSON résultats
 *    GET /api/movie/{id}                            → JSON détail
 *    GET /api/watch/{id}                            → JSON lecteur
 *
 *  Structure JSON supposée (commune aux SPA de ce type) :
 *    [{ "id", "title", "poster", "type", "year", "overview" }]
 *
 *  NOTE : Si l'API n'est pas accessible directement, on scrape la page
 *  de recherche (search?q=...) qui renvoie parfois du HTML.
 * ============================================================================
 */
class VertexMoviesProvider : MainAPI() {
    override var mainUrl = "https://vertexmovies.com"
    override var name = "VertexMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // -------------------------------------------------------------------------
    // Accueil : on utilise les pages explore par genre (URLs statiques)
    // -------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "/search?q=" to "Recherche vide (accueil)",
        "/explore?type=action&from=index&page=" to "Action",
        "/explore?type=horror&from=index&page=" to "Horreur",
        "/explore?type=sci-fi&from=index&page=" to "Sci-Fi",
        "/explore?type=animation&from=index&page=" to "Animation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // VertexMovies utilise une API JSON interne
        // On tente d'abord l'endpoint API correspondant
        val apiUrl = "$mainUrl/api/explore?type=${request.data.extractType()}&page=$page"
        val resp = try {
            app.get(apiUrl, headers = mapOf("Accept" to "application/json")).parsed<VertexListResponse>()
        } catch (e: Exception) { null }

        val items = resp?.results?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()

        // Si l'API ne répond pas, retourner liste vide (la SPA nécessite JS)
        return newHomePageResponse(request.name, items, hasNextPage = items.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Recherche
    // -------------------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Tenter l'API JSON
        val resp = try {
            app.get(
                "$mainUrl/api/search?q=${query.encodeURL()}&page=$page",
                headers = mapOf("Accept" to "application/json")
            ).parsed<VertexListResponse>()
        } catch (e: Exception) { null }

        return resp?.results?.mapNotNull { it.toSearchResponse() }?.toNewSearchResponseList()
    }

    // -------------------------------------------------------------------------
    // Fiche de détail
    // -------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        // Extraire l'id depuis l'URL (ex: /movie/123 ou /tv/123)
        val id = url.trimEnd('/').substringAfterLast('/')
        val isTv = url.contains("/tv/") || url.contains("tvshows") || url.contains("series")

        val detail = try {
            app.get(
                "$mainUrl/api/${if (isTv) "tv" else "movie"}/$id",
                headers = mapOf("Accept" to "application/json")
            ).parsed<VertexDetailResponse>()
        } catch (e: Exception) { null }

        val title = detail?.title ?: "Sans titre"
        val poster = detail?.poster?.let { abs(it) }
        val plot = detail?.overview

        if (isTv) {
            val episodes = detail?.episodes?.mapIndexed { idx, ep ->
                newEpisode(ep.url ?: url) {
                    this.name = ep.title
                    this.season = ep.season ?: 1
                    this.episode = ep.episode ?: (idx + 1)
                }
            } ?: listOf(newEpisode(url) { this.name = title })

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
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
        val id = data.trimEnd('/').substringAfterLast('/')
        val isTv = data.contains("/tv/")

        // Tenter l'endpoint watch API
        val watchResp = try {
            app.get(
                "$mainUrl/api/watch/$id",
                headers = mapOf("Accept" to "application/json")
            ).parsed<VertexWatchResponse>()
        } catch (e: Exception) { null }

        var found = false

        watchResp?.sources?.forEach { src ->
            if (loadExtractor(src, data, subtitleCallback, callback)) found = true
        }

        // Fallback HTML
        if (!found) {
            val document = app.get(data).document
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { return@forEach }
                if (loadExtractor(abs(src), data, subtitleCallback, callback)) found = true
            }
        }

        return found
    }

    // -------------------------------------------------------------------------
    // Data classes JSON
    // -------------------------------------------------------------------------
    data class VertexListResponse(
        val results: List<VertexItem>? = null,
        val data: List<VertexItem>? = null,
        val movies: List<VertexItem>? = null
    ) {
        val allResults get() = results ?: data ?: movies ?: emptyList()
    }

    data class VertexItem(
        val id: String? = null,
        val title: String? = null,
        val name: String? = null,
        val poster: String? = null,
        val poster_path: String? = null,
        val type: String? = null,   // "movie" | "tv" | "anime"
        val year: String? = null
    ) {
        fun toSearchResponse(): SearchResponse? {
            val finalTitle = title ?: name ?: return null
            val finalId = id ?: return null
            val isTv = type == "tv" || type == "tvshow"
            val finalType = if (isTv) TvType.TvSeries else TvType.Movie
            val url = "https://vertexmovies.com/${if (isTv) "tv" else "movie"}/$finalId"
            return newMovieSearchResponse(finalTitle, url, finalType) {
                this.posterUrl = poster?.let {
                    if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w300$it"
                } ?: poster_path?.let { "https://image.tmdb.org/t/p/w300$it" }
            }
        }
    }

    data class VertexDetailResponse(
        val id: String? = null,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        val poster: String? = null,
        val poster_path: String? = null,
        val episodes: List<VertexEpisode>? = null
    )

    data class VertexEpisode(
        val title: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val url: String? = null
    )

    data class VertexWatchResponse(val sources: List<String>? = null)

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

    /** Extrait le type de genre depuis l'URL de mainPage data */
    private fun String.extractType(): String =
        this.substringAfter("type=").substringBefore("&").ifBlank { "action" }
}
