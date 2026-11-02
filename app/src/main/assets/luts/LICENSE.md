# LUT des simulations Fujifilm

Les fichiers `fuji_*.png` de ce dossier sont dérivés des LUT du projet
**FujifilmCameraProfiles** de abpy : https://github.com/abpy/FujifilmCameraProfiles

Licence : **Creative Commons BY-NC-SA 4.0** (https://creativecommons.org/licenses/by-nc-sa/4.0/)
— attribution obligatoire, pas d'usage commercial, partage dans les mêmes conditions.
Ces fichiers dérivés sont distribués sous la même licence.

Modifications apportées (script `tools/bake_luts.py`) :
- la courbe de tons neutre de Provia est retirée en entrée, pour que les LUT s'appliquent à
  une image de téléphone (déjà contrastée) au lieu d'une image raw à contraste linéaire ;
- rééchantillonnage en bande PNG 1024x32 (cube 32x32x32) pour le shader OpenGL ES.

Les noms « Fujifilm », « Provia », « Velvia », « Astia », etc. sont des marques de FUJIFILM
Corporation, utilisées uniquement pour identifier les rendus. Ce ne sont pas des produits officiels.
Les photos produites avec ces LUT sont entièrement les tiennes : la licence ne couvre que les LUT.
