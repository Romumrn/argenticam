# AGENTS.md — ArgentiCam

Guide pour les agents de code (et les humains) qui travaillent sur ce dépôt.

## Le projet

Appli Android d'appareil photo avec des simulations de pellicules et des **recettes façon Fujifilm**.
Le viseur montre le rendu final en direct (shader OpenGL), et la photo est développée avec **le même
shader**. Réglages de prise de vue façon boîtier : molettes rétro, A/M, AF-S/AF-C/MF, Kelvin.

- Langue : **français** pour l'interface, les commentaires et les messages à l'utilisateur.
- Kotlin, Jetpack Compose (Material 3), CameraX 1.3 + interop Camera2, OpenGL ES 2.0.
- `minSdk 29`, `targetSdk/compileSdk 34`, JDK 17, AGP 8.5, Kotlin 2.0, Gradle 8.9 (wrapper).
- Package : `com.argenticam` (le code), `applicationId` : `com.argenticam`.

## Compiler et vérifier

```bash
./gradlew assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
node tools/check_shader.js       # compile le shader GLSL (Playwright + Chromium requis)
```

- Il n'y a **pas de tests automatisés** ni d'émulateur dans la CI. Toute modification doit au
  minimum compiler (`assembleDebug`) et, si `FilmProgram.kt` change, passer `check_shader.js` :
  Gradle ne compile pas le GLSL, une erreur de shader ne se voit qu'au lancement sur le téléphone.
- La CI (`.github/workflows/build.yml`) compile à chaque push et publie l'APK dans la release
  GitHub `latest` (`ArgentiCam.apk`) quand on pousse sur `main`.
- Maven Central peut répondre 429 (limite de débit) : relancer la commande suffit.
- **Version** : `versionCode` = numéro du run GitHub Actions (`GITHUB_RUN_NUMBER`), nombre de commits en local ;
  `versionName` = `0.<versionCode>`. Ne pas remettre de version en dur.
- **Signature** : clé fixe fournie par `ARGENTICAM_KEYSTORE` / `ARGENTICAM_KEYSTORE_PASSWORD` (alias `argenticam`),
  décodée en CI depuis les secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`. **Jamais** de clé ni de mot de passe
  dans le dépôt. Ne pas changer de clé : une autre clé empêche la mise à jour de l'appli installée.
- La release `latest` est supprimée puis recréée à chaque build de `main` (un seul fichier : `ArgentiCam.apk`).

## Architecture (`app/src/main/java/com/argenticam/`)

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Permission caméra, init `LutCache`, lance `ArgentiApp` |
| `ArgentiApp.kt` | Navigation caméra / grille / visionneuse, compteur de poses |
| `CameraScreen.kt` | Écran caméra : liaison CameraX, viseur, mise au point au toucher, bande de réglages, molettes, éditeur |
| `Manual.kt` | Réglages de prise de vue, capacités du capteur (`CameraCaps`), envoi Camera2 (`applyManual`), lecture réelle (`LiveReadout`), objectifs |
| `Recipe.kt` | Modèle `Recipe` (recette Fuji), `RenderParams` (uniforms du shader), recettes intégrées, sauvegarde JSON (`RecipeStore`) |
| `RecipeEditor.kt` | Éditeur de recettes (aperçu en direct) |
| `FilmPreset.kt` | Pellicules de base (`Presets.all`) : simulations Fuji (LUT) + pellicules paramétriques |
| `FilmProgram.kt` | **Le shader** (vertex + fragment) et son envoi d'uniforms |
| `FilmPreviewView.kt` | `GLSurfaceView` alimentée par CameraX (texture OES) |
| `FilmProcessor.kt` | Développement pleine résolution **par tuiles** (framebuffer 2048 px + marges), sans limite de taille |
| `PhotoPipeline.kt` | JPEG CameraX → rotation → shader → MediaStore (`Pictures/ArgentiCam`) |
| `LutCache.kt` | Chargement des LUT 3D depuis `assets/luts/` |
| `RetroDial.kt` | La molette rétro (crans, vibration, inertie) |
| `Frames.kt` | `PhotoFormat` (Standard, Polaroid, 35 mm, Jetable) et `PrintFrame` : géométrie en mm, recadrage, composition, `drawBorder` partagé viseur / photo |
| `Polaroid.kt` | Mode Polaroid : géométrie (Polaroid 600), recadrage carré, cadre papier, masque du viseur, développement animé |
| `Gallery.kt`, `PhotoLibrary.kt` | Galerie intégrée, MediaStore, compteur de rouleau |
| `Inscription.kt` | Date / petit mot imprimés (dos dateur orange, légende manuscrite Polaroid), même code pour le viseur et la photo |
| `ZoomableImage.kt` | Image zoomable de la visionneuse (pincement, déplacement, double-tap ; ne consomme pas le swipe du pager à ×1) |

### Chaîne de rendu

`Recipe` → `Recipe.render()` → `RenderParams` → `FilmProgram.draw()` (uniforms) → shader.
Ordre dans le shader : netteté → clarté → halation → gain BdB (Kelvin + décalage R/B) + exposition
→ plage dynamique → **LUT 3D** → N&B (filtre) / saturation → Color Chrome → FX Bleu → contraste
→ hautes lumières / ombres → split toning → teinte mono → noirs relevés → grain → vignettage
→ mélange avec l'original (intensité) → focus peaking (aperçu seulement).

## Règles à respecter

- **Un seul shader pour l'aperçu et la photo.** Toute modification du rendu passe par
  `FilmProgram.kt`. Ne pas ajouter de traitement CPU qui ne serait que sur la photo (ou que sur l'aperçu).
- Le shader est en **GLSL ES 1.00** (GLES 2) : `texture2D`, `gl_FragColor`, boucles à bornes
  constantes, pas de tableaux initialisés. Deux variantes : `samplerExternalOES` (aperçu) et
  `sampler2D` (photo) — le texte `SAMPLER` est remplacé à la compilation.
- Les effets à rayon (netteté, clarté, halation, peaking) passent par `tap(d)` avec `d` en **fraction de
  la hauteur de la photo** (isotrope). `tap` convertit via `uFrame.w`, `uAspect` et `uLin` : c'est ce qui rend
  l'aperçu, le Polaroid et chaque tuile du développement identiques. Tout nouvel effet qui lit des pixels
  voisins doit passer par `tap` et rester sous `FilmProcessor.REACH` (marge des tuiles), sinon il y aura des coutures.
- Grain et vignettage se calculent en coordonnées de la photo entière : `(vUv - uFrame.xy) / uFrame.zw`.
- Qualité : capture `HIGHEST_AVAILABLE_STRATEGY` + `PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE`, JPEG 100,
  EXIF recopiés (`PhotoPipeline.copyExif`). Ne pas réintroduire de plafond de résolution.
- **Orientation de l'aperçu** : la matrice du `SurfaceTexture` inclut déjà la rotation capteur → écran.
  Ne pas rajouter de rotation (ça a déjà penché l'image). L'activité est verrouillée en portrait.
- **Réglages manuels** : beaucoup de téléphones (Xiaomi notamment) n'autorisent pas les applis
  tierces à régler vitesse/ISO/mise au point. Toujours vérifier `CameraCaps` et comparer avec
  `LiveReadout` (ce que le capteur applique vraiment) plutôt que de supposer que ça marche.
- **Mode Polaroid** : le carré visible dans le viseur (`Polaroid.holeRect`) et le recadrage de la photo
  (`Polaroid.cropFraction`) viennent de la même géométrie — garder ce lien (ce qu'on voit = ce qu'on obtient).
  Le shader reçoit `uFrame` pour centrer grain et vignettage sur le carré (aperçu) ; la photo est recadrée *avant*
  le shader, donc `FULL_FRAME`.
- Viseur Polaroid : dessiner le cadre avec des rectangles pleins, **jamais** de `Path` troué en `EvenOdd`
  (rempli entièrement sur certains Xiaomi). Les pellicules `instant = true` sont les seules proposées en mode Polaroid.
- **Formats encadrés** : tout passe par `PrintFrame` (`Frames.kt`). `drawBorder` dessine le cadre sans jamais toucher
  la fenêtre de l'image, avec le même code dans le viseur et sur la photo. Un nouveau format = une nouvelle `PrintFrame`.
  Les pellicules `disposable = true` sont les seules proposées en mode Jetable (comme `instant` en Polaroid).
- **Balance des blancs** : `AUTO` ou `KELVIN`. En Kelvin, le téléphone est calé en lumière du jour
  et le shader convertit vers la température choisie (`kelvinGain` dans `Recipe.kt`).
- Pas de `AnimatedVisibility` directement dans un `Box` imbriqué dans un `Column` (conflit de
  receveur implicite, ça ne compile pas) : utiliser un simple `if`.
- Recettes : les formats JSON doivent rester **rétrocompatibles** (`RecipeStore.fromJson` lit les
  anciennes clés et valeurs ; `Presets.byId` gère les anciens identifiants via `aliases`).
- Les textes de l'interface sont en dur en français dans le code Compose (pas de `strings.xml` pour l'instant).

## LUT des simulations Fujifilm

- `app/src/main/assets/luts/fuji_*.png` : bandes 1024×32 (cube 32³, `x = b*32 + r`, `y = g`).
- Dérivées de [abpy/FujifilmCameraProfiles](https://github.com/abpy/FujifilmCameraProfiles),
  licence **CC BY-NC-SA 4.0** : garder l'attribution (`assets/luts/LICENSE.md`, README, mention
  dans l'éditeur) et **aucun usage commercial** tant que ces fichiers sont dans l'appli.
- Régénération : `python3 -I tools/bake_luts.py "<dossier .cube>" app/src/main/assets/luts`.
  Le script retire la courbe de tons de Provia en entrée (les LUT d'origine attendent une image
  raw à contraste linéaire, le téléphone fournit une image déjà contrastée).
- Acros et Monochrome n'ont pas de LUT : ils sont paramétriques (`bw`, filtres `BwFilter`, teinte mono).

## Git

- Branche principale : `main`. Messages de commit en anglais, courts, à l'impératif.
- Ne pas committer `local.properties`, `build/`, `.gradle/`.
