// Numéro de version : incrémente-le à chaque mise à jour pour que l'app propose l'update.
version = 2

cloudstream {
    description = "Lecteur IPTV / M3U — colle ta propre playlist (M3U ou Xtream)"
    authors = listOf("krm")

    /**
     * Status :
     * 0: Down | 1: Ok | 2: Slow | 3: Beta-only
     */
    status = 1

    language = "fr"
    // Type affiché dans l'app (chaînes en direct)
    tvTypes = listOf("Live")

    iconUrl = "https://www.google.com/s2/favicons?domain=iptv-org.github.io&sz=%size%"

    isCrossPlatform = true
}
