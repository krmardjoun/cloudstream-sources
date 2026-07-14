// Modèle / squelette : à copier et adapter pour un site que tu as le droit d'utiliser.
version = 2

cloudstream {
    description = "MODÈLE — squelette commenté pour scraper un site (à personnaliser)"
    authors = listOf("krm")

    // 3 = Beta-only : ce provider n'apparaît que si l'utilisateur active les plugins beta,
    // ce qui est logique tant que ce n'est qu'un modèle non configuré.
    status = 3

    language = "fr"
    // Adapte selon le contenu du site : "Movie", "TvSeries", "Anime", "Cartoon"...
    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=example.com&sz=%size%"

    isCrossPlatform = true
}
