# Mes sources CloudStream (app TV)

Dépôt d'**extensions CloudStream** compilées automatiquement par GitHub, à installer
dans l'app CloudStream sur ta TV / box Android TV.

## Ce qu'il contient

| Source | Type | Contenu |
|---|---|---|
| **IPTV (M3U)** | Live | Ta playlist IPTV/Xtream (à configurer) — par défaut chaînes libres iptv-org |
| **Internet Archive** | Films | Milliers de films légaux / domaine public |
| **Modèle (SiteTemplate)** | — | Squelette commenté pour créer ton propre scraper |
| Dailymotion / Youtube / Invidious / Twitch | Vidéos | Sources publiques fournies avec le template |

---

## 🔧 Étape 1 — Personnaliser (optionnel)

- **Ta playlist IPTV** : ouvre
  `IPTVProvider/src/main/kotlin/recloudstream/IPTVProvider.kt`
  et remplace la valeur de `PLAYLIST_URL` par l'URL de ta playlist (M3U ou lien Xtream `get.php`).
- Incrémente `version = 1` → `2` dans `IPTVProvider/build.gradle.kts` à chaque changement
  (pour que l'app propose la mise à jour).

## 🚀 Étape 2 — Mettre en ligne sur GitHub

1. Crée un dépôt **public** sur GitHub nommé exactement **`cloudstream-sources`**
   (https://github.com/new). Ne coche PAS "Add a README".
2. `repo.json` est déjà configuré pour `krmardjoun/cloudstream-sources` — rien à changer.
3. Pousse le projet :
   ```bash
   git init
   git add .
   git commit -m "Mes sources CloudStream"
   git branch -M master
   git remote add origin https://github.com/krmardjoun/cloudstream-sources.git
   git push -u origin master
   ```
4. Crée la branche vide **`builds`** (le robot y déposera les fichiers compilés) :
   ```bash
   git checkout --orphan builds
   git rm -rf .
   git commit --allow-empty -m "init builds"
   git push origin builds
   git checkout master
   ```
5. Sur GitHub : **Settings → Actions → General → Workflow permissions → "Read and write permissions"** → Save.
   (Sinon le robot ne pourra pas publier les fichiers compilés.)

## 🤖 Étape 3 — Compilation automatique

À chaque `git push` sur `master`, GitHub Actions compile les `.cs3` et les publie sur la branche `builds`
(voir l'onglet **Actions** du dépôt ; ~2-3 min). Aucune installation Android nécessaire sur ton PC.

Ton **URL de dépôt** à retenir (celle à coller dans l'app) :
```
https://raw.githubusercontent.com/krmardjoun/cloudstream-sources/master/repo.json
```

## 📺 Étape 4 — Installer sur la TV

1. Installe l'app **CloudStream** sur ta box/TV Android
   (APK officiel : https://github.com/recloudstream/cloudstream/releases — fonctionne à la télécommande).
2. Dans l'app : **Paramètres → Extensions → Dépôts (Repositories) → +**
   et colle l'URL de dépôt ci-dessus.
3. Ouvre le dépôt, installe les extensions voulues (**IPTV**, **Internet Archive**...).
4. Elles apparaissent sur l'accueil et dans la recherche. 🎬

---

## 🧩 Créer ta propre source à partir du modèle

Copie le dossier `SiteTemplateProvider`, renomme-le, puis adapte dans le `.kt` :
`mainUrl`, les sélecteurs CSS (`getMainPage`/`search`/`load`) et l'extraction du lecteur (`loadLinks`).
Tout est commenté étape par étape. `loadExtractor()` gère déjà la plupart des hébergeurs vidéo courants.

> ⚠️ N'utilise que des sources que tu as le droit d'utiliser (playlist que tu paies,
> contenu libre/domaine public, ton propre serveur).
