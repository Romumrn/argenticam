# ArgentiCam

Une appli Android d'appareil photo avec des **simulations de pellicules argentiques**, façon Fujifilm : tu choisis un look, tu vois le rendu **en direct** dans le viseur (couleurs, contraste, grain, vignettage, halation), et la photo enregistrée a exactement le même rendu.

> État : v0.1, expérimental. Écrit sans test sur un vrai téléphone : le rendu des looks et les réglages manuels restent à valider selon les téléphones.

## Fonctionnalités

**Recettes façon Fujifilm**
- **Simulations Fujifilm mesurées** (LUT 3D construites à partir des profils Camera Matching Fuji) : Provia, Velvia, Astia, Classic Chrome, Reala Ace, Pro Neg Hi, Pro Neg Std, Classic Neg, Nostalgic Neg, Eterna, Bleach Bypass
- **Acros** et **Monochrome**, avec les filtres N&B Jaune / Rouge / Vert et la teinte monochrome chaud/froid et magenta/vert
- **Pellicules instantanées** : Polaroid 600, SX-70, i-Type, Polaroid N&B, Polaroid périmé, Instax
- **Appareils jetables** : Jetable 400, Jetable 800, Jetable N&B
- Pellicules argentiques (approximations faites main) : Portra 400, Gold 200, Superia 400, Tri-X 400, CineStill 800T, Ektachrome, Lomo, et Original
- Quelques recettes prêtes à l'emploi : Kodachrome 64, Portra (recette), Nostalgie 70s, Acros +R, Nuit tungstène
- **Crée et sauvegarde tes propres recettes**, avec l'aperçu en direct pendant l'édition. Tous les réglages d'un boîtier Fuji :
  - Effet grain (Off / Faible / Fort, petit ou gros), Color Chrome, Color Chrome FX Bleu
  - Balance des blancs **Auto ou en Kelvin** (2500 à 10000 K, molette au pas de 100 K, raccourcis Tungstène / Fluo / Soleil / Nuageux / Ombre) et **décalage R/B** de -9 à +9
  - Plage dynamique DR100 / DR200 / DR400, Hautes lumières et Ombres de -2 à +4, Couleur, Netteté, Clarté, Réduction du bruit
  - En plus : vignettage, halation, intensité du film

**Prise de vue**
- Aperçu en direct avec la recette appliquée (shader OpenGL), photo développée avec exactement le même shader
- **Mise au point au doigt** : touche le viseur pour faire le point à cet endroit, en **AF-S** (point verrouillé, « AF-L ») ou **AF-C** (suivi continu)
- **MF** : bague de mise au point graduée en distance, **loupe ×3** pendant qu'on tourne la bague, **focus peaking**
- **Molette rétro** pour la vitesse (au tiers de diaphragme), l'ISO et l'EV, avec un dial **A / M**
- **Lecture réelle du capteur** : l'écran affiche la vitesse, l'ISO et la distance de mise au point que le téléphone applique *vraiment*, et prévient s'il ignore un réglage manuel
- Choix de l'**objectif** (ultra grand-angle, principal, télé) selon ce que le téléphone expose
- **Mode Polaroid** : cadre Polaroid 600 dans le viseur (le carré visé = la photo), tirage carré posé sur un cadre papier crème aux vraies proportions (88 × 107 mm), et **développement animé** après la prise de vue. En mode Polaroid, seules les recettes sur pellicule instantanée sont proposées
- **Pellicule 35 mm** : la photo au format 24×36 sur une bande de film scannée, avec perforations, nom de la pellicule et numéro de vue imprimés sur les bords (aperçu dans le viseur)
- **Appareil jetable** : couleurs saturées, gros grain, vignettage, coins flous d'objectif en plastique, vrai flash du téléphone et date orange automatique ; seules les pellicules jetables sont proposées
- **Date et petit mot** imprimés sur la photo : date orange lumineuse façon compact argentique (`'26 11 03`) sur les photos normales, légende manuscrite au feutre sur le bord du Polaroid ; le texte se tape au clavier, l'aperçu est visible dans le viseur
- Compteur de poses façon rouleau de **36 vues**, **galerie intégrée** (grille, visionneuse avec **zoom** au pincement et au double-tap, partage, suppression)
- **Qualité maximale** : capture à la plus haute résolution que le téléphone autorise (50 Mpx et plus selon le capteur), développement par tuiles sans perte de résolution, JPEG qualité 100, métadonnées EXIF conservées (date, appareil, vitesse, ISO, focale ; la recette est notée dans la description)
- Photos enregistrées dans `Pictures/ArgentiCam`

### Limites selon les téléphones

Beaucoup de constructeurs (Xiaomi, et d'autres) ne laissent pas les applis tierces régler la vitesse, l'ISO ou la mise au point à la main. L'appli détecte ces limites et l'affiche. Dans ce cas, utilise l'EV et la mise au point au toucher (AF-S).

## Crédits

Les simulations Fujifilm utilisent des LUT dérivées de [abpy/FujifilmCameraProfiles](https://github.com/abpy/FujifilmCameraProfiles), sous licence **CC BY-NC-SA 4.0** (pas d'usage commercial). Elles sont adaptées aux images de téléphone par `tools/bake_luts.py`. Voir `app/src/main/assets/luts/LICENSE.md`.

Les noms de pellicules et de simulations sont des marques de leurs propriétaires, cités pour identifier les rendus. Ce ne sont pas des reproductions officielles.

## Télécharger l'APK

1. Ouvre la page **Releases** du dépôt, release `latest`.
2. Télécharge `ArgentiCam.apk` depuis ton téléphone.
3. Ouvre-le et autorise l'installation depuis des sources inconnues si Android le demande.

Chaque build a un numéro de version qui augmente (affiché dans la galerie). Si les secrets `KEYSTORE_BASE64` et `KEYSTORE_PASSWORD` sont définis dans le dépôt, l'APK est toujours signé avec la même clé et les nouvelles versions s'installent par-dessus l'ancienne. Android 10 ou plus (API 29).

Les archives « Source code » de la release sont ajoutées automatiquement par GitHub et ne peuvent pas être retirées : seul `ArgentiCam.apk` est utile.

Pour les agents de code (et les contributeurs) : voir [`AGENTS.md`](AGENTS.md).

## Compiler soi-même

Il faut JDK 17 et le SDK Android (API 34).

```bash
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`. Tu peux aussi ouvrir le projet dans Android Studio.

Chaque push déclenche le workflow GitHub Actions (`.github/workflows/build.yml`), qui compile l'APK et le publie dans la release `latest` quand on pousse sur `main`.

## Comment ça marche

```
CameraX Preview ──► SurfaceTexture ──► shader "film" ──► GLSurfaceView   (aperçu)
CameraX ImageCapture ──► JPEG ──► Bitmap ──► shader "film" (EGL hors écran) ──► JPEG ──► galerie
```

| Fichier | Rôle |
|---|---|
| `FilmPreset.kt` | Les paramètres de chaque look (courbe, saturation, teintes, grain, vignette, halation…) |
| `FilmProgram.kt` | Le shader GLSL, partagé entre l'aperçu et la photo finale |
| `FilmPreviewView.kt` | Aperçu OpenGL alimenté par CameraX, gère la rotation |
| `FilmProcessor.kt` | Applique le shader à une photo en pleine résolution, hors écran |
| `PhotoPipeline.kt` | Décode, tourne, développe et enregistre la photo |
| `CameraScreen.kt` | Écran caméra (Jetpack Compose) : viseur, bande de réglages, déclencheur |
| `Manual.kt` | Réglages manuels via Camera2 (ISO, vitesse, EV, balance des blancs, focus) et détection des objectifs |
| `RetroDial.kt` | La molette rétro |
| `Frames.kt` | Formats (`PhotoFormat`) et cadres communs : Polaroid, bande 35 mm (géométrie, recadrage, composition, masque du viseur) |
| `Polaroid.kt` | Mode Polaroid : géométrie du cadre, recadrage, composition du tirage, cadre du viseur, animation de développement |
| `Recipe.kt` | Modèle des recettes, recettes intégrées, sauvegarde, conversion en paramètres de rendu |
| `RecipeEditor.kt` | L'éditeur de recettes |
| `LutCache.kt`, `assets/luts/` | LUT 3D des simulations Fujifilm |
| `tools/bake_luts.py` | Génère les LUT de l'appli à partir des `.cube` d'origine |
| `Gallery.kt`, `PhotoLibrary.kt` | Galerie intégrée et lecture des photos dans MediaStore |

Le shader enchaîne : halation, balance des blancs et exposition, noir et blanc et saturation, contraste avec pied et épaule, teintes dans les ombres et les hautes lumières, noirs relevés, grain (plus visible dans les tons moyens, qui « vit » environ 12 fois par seconde), vignettage, puis mélange avec l'original selon l'intensité.

## Ajouter une pellicule de base

Ajoute une entrée dans `Presets.all` (`FilmPreset.kt`). Pour une recette intégrée, ajoute-la dans `Recipes.builtIns` (`Recipe.kt`). Les recettes perso se créent directement dans l'appli.

## Pistes pour la suite

- Caméra frontale, flash, pincement pour zoomer, histogramme
- Partage et import de recettes
- Import et traitement de photos existantes
- Date imprimée façon argentique, cadres, bords de pellicule
- Courbes plus fidèles, ou LUTs 3D
- Changer la signature du build pour une version release

## Licence

À définir.
