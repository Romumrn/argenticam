# ArgentiCam

Une appli Android d'appareil photo avec des **simulations de pellicules argentiques**, façon Fujifilm : tu choisis un look, tu vois le rendu **en direct** dans le viseur (couleurs, contraste, grain, vignettage, halation), et la photo enregistrée a exactement le même rendu.

> État : v0.1, expérimental. Écrit sans test sur un vrai téléphone : le rendu des looks et les réglages manuels restent à valider selon les téléphones.

## Fonctionnalités

- Aperçu caméra en direct avec le look appliqué (shader OpenGL)
- 12 looks inspirés de pellicules : Portra 400, Gold 200, Superia 400, Velvia 50, Classic Chrome, Classic Neg, Acros, Tri-X 400, CineStill 800T, Ektachrome, Lomo, et Original (sans filtre)
- Réglage de l'**intensité** de l'effet et de la quantité de **grain**
- **Réglages manuels façon Fujifilm** : vitesse d'obturation, ISO, correction d'exposition (EV), balance des blancs, mise au point manuelle, et un dial **A / M** (auto / manuel). Seules les valeurs que le capteur du téléphone accepte sont proposées.
- Compteur de poses façon rouleau de **36 vues**
- **Galerie intégrée** : miniature de la dernière photo, grille, visionneuse avec swipe, partage et suppression
- Photos ~12 Mpx traitées en pleine résolution avec le même shader que l'aperçu
- Enregistrement dans la galerie : `Pictures/ArgentiCam`

Les noms de pellicules sont cités à titre descriptif : les looks sont des approximations faites à la main, pas des reproductions officielles.

## Télécharger l'APK

1. Ouvre la page **Releases** du dépôt, release `latest`.
2. Télécharge `ArgentiCam.apk` depuis ton téléphone.
3. Ouvre-le et autorise l'installation depuis des sources inconnues si Android le demande.

L'APK est un build de debug, signé avec la clé de debug. Il demande Android 10 ou plus (API 29).

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
| `Manual.kt` | Réglages manuels via Camera2 (ISO, vitesse, EV, balance des blancs, focus) |
| `Gallery.kt`, `PhotoLibrary.kt` | Galerie intégrée et lecture des photos dans MediaStore |

Le shader enchaîne : halation, balance des blancs et exposition, noir et blanc et saturation, contraste avec pied et épaule, teintes dans les ombres et les hautes lumières, noirs relevés, grain (plus visible dans les tons moyens, qui « vit » environ 12 fois par seconde), vignettage, puis mélange avec l'original selon l'intensité.

## Ajouter un look

Ajoute une entrée dans la liste de `Presets.all` (`FilmPreset.kt`). Tous les paramètres ont une valeur par défaut neutre, il suffit de régler ceux qui comptent.

## Pistes pour la suite

- Caméra frontale, zoom, mise au point au toucher, flash, histogramme
- Import et traitement de photos existantes
- Date imprimée façon argentique, cadres, bords de pellicule
- Courbes plus fidèles, ou LUTs 3D
- Changer la signature du build pour une version release

## Licence

À définir.
