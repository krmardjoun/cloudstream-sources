# 📘 Guide : ajouter une source à CloudStream

Ce guide explique, étape par étape, comment créer ta **propre extension** (source) pour
CloudStream à partir du modèle `SiteTemplateProvider` fourni dans ce dépôt.

> ⚖️ **Rappel** : n'utilise que des sources que tu as le droit d'utiliser (playlist IPTV que tu
> paies, contenu libre / domaine public, ton propre serveur). Scraper un site qui diffuse des
> films/séries sous copyright sans licence est illégal dans la plupart des pays.

---

## 🧠 1. Comment ça marche (le principe)

Une source CloudStream = une classe Kotlin qui hérite de `MainAPI` et implémente **4 méthodes**.
CloudStream les appelle dans cet ordre :

| Méthode | Quand | Ce qu'elle doit renvoyer |
|---|---|---|
| `getMainPage()` | À l'ouverture de la source | Des rangées de vignettes (page d'accueil) |
| `search()` | Quand l'utilisateur cherche | Une liste de résultats |
| `load()` | Quand on ouvre une fiche | Titre, résumé, affiche, (+ épisodes si série) |
| `loadLinks()` | Quand on lance la lecture | Le(s) vrai(s) lien(s) vidéo jouables |

Le principe du scraping : tu **télécharges** la page HTML (`app.get(url).document`), puis tu
**extrais** les infos avec des **sélecteurs CSS** (via Jsoup, comme jQuery).

---

## 🛠️ 2. Créer le dossier de ta source

1. **Copie** le dossier `SiteTemplateProvider/` et renomme-le, ex. `MaSourceProvider/`.
2. Renomme les 2 fichiers `.kt` à l'intérieur, ex. `MaSourcePlugin.kt` et `MaSourceProvider.kt`.
3. Dans `MaSourcePlugin.kt`, renomme la classe et ce qu'elle enregistre :
   ```kotlin
   @CloudstreamPlugin
   class MaSourcePlugin : BasePlugin() {
       override fun load() {
           registerMainAPI(MaSourceProvider())
       }
   }
   ```
4. Dans `MaSourceProvider/build.gradle.kts`, adapte la description, les `tvTypes` et
   passe `status = 1` (au lieu de 3) quand ta source fonctionne.

> Le fichier `settings.gradle.kts` détecte **automatiquement** tout nouveau dossier
> contenant un `build.gradle.kts` — rien d'autre à déclarer.

---

## 🔍 3. Trouver les sélecteurs CSS (l'étape clé)

Ouvre le site dans **Chrome** (sur PC) :

1. **Clic droit** sur une vignette de film → **Inspecter**.
2. Le panneau de code s'ouvre sur l'élément. Repère la structure, par exemple :
   ```html
   <div class="movie-item">
     <a href="/film/matrix" title="Matrix">
       <img src="/img/matrix.jpg">
     </a>
     <div class="title">Matrix</div>
   </div>
   ```
3. Tu en déduis tes sélecteurs :
   - carte : `div.movie-item`
   - lien : `a` → attribut `href`
   - titre : `a` → attribut `title` (ou `.title` → texte)
   - affiche : `img` → attribut `src`

💡 **Aide-mémoire Jsoup :**

| Besoin | Code |
|---|---|
| Tous les éléments d'une classe | `document.select("div.movie-item")` |
| Le premier correspondant | `element.selectFirst("a")` |
| Le texte | `.text()` |
| Un attribut | `.attr("href")` / `.attr("src")` |
| Enfant direct | `select("div > a")` |

---

## ✍️ 4. Remplir les 4 méthodes

Dans `MaSourceProvider.kt`, remplace juste les `TODO` du modèle.

### a) En-tête de la source
```kotlin
override var mainUrl = "https://exemple.com"   // l'adresse du site
override var name = "Ma Source"
override var lang = "fr"
override val hasMainPage = true
override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
```

### b) `getMainPage` — l'accueil
```kotlin
override val mainPage = mainPageOf(
    "/films/page/" to "Films récents",
    "/series/page/" to "Séries récentes",
)

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val document = app.get("$mainUrl${request.data}$page").document
    val items = document.select("div.movie-item").mapNotNull { it.toSearchResult() }
    return newHomePageResponse(request.name, items)
}
```

### c) `search` — la recherche
```kotlin
override suspend fun search(query: String, page: Int): SearchResponseList? {
    val document = app.get("$mainUrl/?s=$query").document   // adapte l'URL de recherche du site
    return document.select("div.movie-item")
        .mapNotNull { it.toSearchResult() }
        .toNewSearchResponseList()
}
```

### d) `load` — la fiche détaillée
```kotlin
override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document
    val title = document.selectFirst("h1")?.text() ?: "Sans titre"
    val poster = document.selectFirst("div.poster img")?.attr("src")
    val plot = document.selectFirst("div.description")?.text()

    return newMovieLoadResponse(title, url, TvType.Movie, url) {
        this.posterUrl = poster?.let { abs(it) }
        this.plot = plot
    }
}
```

### e) `loadLinks` — récupérer la vidéo
La plupart des sites intègrent un **lecteur** dans une `<iframe>` pointant vers un hébergeur
vidéo (Doodstream, Voe, StreamTape, etc.). `loadExtractor()` sait déjà en gérer beaucoup :
il suffit de lui passer l'URL de l'iframe.
```kotlin
override suspend fun loadLinks(
    data: String, isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    val iframes = document.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
    var found = false
    for (iframe in iframes) {
        if (loadExtractor(abs(iframe), data, subtitleCallback, callback)) found = true
    }
    return found
}
```

> `abs()` (helper déjà dans le modèle) rend une URL relative (`/film/…`) absolue
> (`https://exemple.com/film/…`).

### 📺 Cas d'une série (épisodes)
Au lieu de `newMovieLoadResponse`, utilise `newTvSeriesLoadResponse` avec une liste
d'`Episode`, chaque épisode portant l'URL de sa page/lecteur comme `data` :
```kotlin
val episodes = document.select("ul.episodes li a").map { ep ->
    newEpisode(abs(ep.attr("href"))) {
        this.name = ep.text()
        this.episode = ep.text().filter { it.isDigit() }.toIntOrNull()
    }
}
return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
    this.posterUrl = poster?.let { abs(it) }
    this.plot = plot
}
```

---

## 🚀 5. Compiler et tester

1. Incrémente `version` dans le `build.gradle.kts` de ta source à **chaque** modification.
2. Pousse sur GitHub :
   ```bash
   git add .
   git commit -m "Ajout de Ma Source"
   git push
   ```
3. GitHub Actions compile automatiquement (onglet **Actions**, ~3 min).
   - ✅ vert → le `.cs3` est publié sur la branche `builds`.
   - ❌ rouge → clique sur le run, ouvre l'étape « Build Plugins », lis les lignes qui
     commencent par `e:` (ce sont les erreurs Kotlin) et corrige.
4. Sur la TV : CloudStream → **Extensions → ton dépôt → Mettre à jour** → ta source apparaît.

---

## 🧪 6. Déboguer quand « ça ne trouve rien »

- La page d'accueil est vide → ton sélecteur de carte (`div.movie-item`) ne correspond pas.
  Reviens à l'étape 3, revérifie la vraie classe dans l'inspecteur.
- La lecture ne démarre pas → l'hébergeur de l'iframe n'est pas géré par `loadExtractor`.
  Vérifie l'URL de l'iframe ; il faut parfois écrire un « extractor » dédié (plus avancé).
- Certains sites chargent le contenu en **JavaScript** (donc absent du HTML brut) : il faut
  alors appeler l'**API interne** du site (regarde l'onglet **Network** de Chrome pour repérer
  les requêtes JSON) plutôt que de parser le HTML.

---

## 📚 Ressources

- Modèle commenté : [`SiteTemplateProvider`](SiteTemplateProvider/src/main/kotlin/recloudstream/SiteTemplateProvider.kt)
- Exemple réel complet (API JSON) : [`InternetArchiveProvider`](InternetArchiveProvider/src/main/kotlin/recloudstream/InternetArchiveProvider.kt)
- Exemple IPTV / M3U : [`IPTVProvider`](IPTVProvider/src/main/kotlin/recloudstream/IPTVProvider.kt)
- Doc officielle : https://recloudstream.github.io/csdocs/
