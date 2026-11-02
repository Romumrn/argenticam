"""
Cuit les LUT Fuji (abpy/FujifilmCameraProfiles, CC BY-NC-SA 4.0) pour ArgentiCam.

Les LUT d'origine attendent une image à contraste linéaire (profil raw "plat"). Le téléphone,
lui, sort une image déjà contrastée, proche du rendu "standard" (Provia). On retire donc
d'abord la courbe de tons neutre de Provia (inverse 1D, par canal), puis on applique la LUT
de la simulation : Provia devient ~neutre sur les gris, les autres simulations gardent leur
écart de couleur et de contraste par rapport à Provia.

Sortie : PNG 1024x32 (32 tranches de bleu côte à côte, x = b*32 + r, y = g).

Usage : python3 -I bake_luts.py <dossier des .cube> <dossier de sortie>
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

N = 32


def load_cube(path):
    rows = []
    size = None
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('LUT_3D_SIZE'):
            size = int(line.split()[1])
            continue
        if line[0].isalpha():
            continue
        rows.append([float(v) for v in line.split()])
    a = np.array(rows, dtype=np.float64)
    assert size and a.shape == (size ** 3, 3), (path, size, a.shape)
    return a.reshape(size, size, size, 3)  # [b][g][r]


def sample(lut, rgb):
    """Trilinéaire. rgb : (..., 3) dans [0,1]."""
    n = lut.shape[0]
    p = np.clip(rgb, 0, 1) * (n - 1)
    i0 = np.floor(p).astype(int)
    i1 = np.minimum(i0 + 1, n - 1)
    f = p - i0
    r0, g0, b0 = i0[..., 0], i0[..., 1], i0[..., 2]
    r1, g1, b1 = i1[..., 0], i1[..., 1], i1[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]

    def L(b, g, r):
        return lut[b, g, r]

    c00 = L(b0, g0, r0) * (1 - fr) + L(b0, g0, r1) * fr
    c01 = L(b0, g1, r0) * (1 - fr) + L(b0, g1, r1) * fr
    c10 = L(b1, g0, r0) * (1 - fr) + L(b1, g0, r1) * fr
    c11 = L(b1, g1, r0) * (1 - fr) + L(b1, g1, r1) * fr
    c0 = c00 * (1 - fg) + c01 * fg
    c1 = c10 * (1 - fg) + c11 * fg
    return c0 * (1 - fb) + c1 * fb


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)

    provia = load_cube(src / 'provia_sRGB.cube')
    v = np.linspace(0, 1, 4096)
    grey = sample(provia, np.stack([v, v, v], -1)).mean(-1)
    grey = np.maximum.accumulate(grey)  # monotone
    print('courbe Provia : P(0)=%.3f P(0.18)=%.3f P(0.5)=%.3f P(1)=%.3f'
          % (grey[0], np.interp(0.18, v, grey), np.interp(0.5, v, grey), grey[-1]))

    def flatten(x):
        return np.interp(x, grey, v)

    # grille de sortie
    ax = np.linspace(0, 1, N)
    b, g, r = np.meshgrid(ax, ax, ax, indexing='ij')
    grid = np.stack([r, g, b], -1)  # [b][g][r] -> (r,g,b)

    names = {
        'provia': 'provia', 'velvia': 'velvia', 'astia': 'astia',
        'classicchrome': 'classic chrome', 'classicneg': 'classic neg',
        'nostalgicneg': 'nostalgic neg', 'pronegstd': 'pro neg std', 'pronEGhi': None,
        'pronehi': 'pro neg hi', 'eterna': 'eterna', 'realaace': 'reala ace',
        'bleachbypass': 'bleach bypass',
    }
    for key, fname in names.items():
        if fname is None:
            continue
        lut = load_cube(src / f'{fname}_sRGB.cube')
        res = sample(lut, flatten(grid))  # [b][g][r][3]
        img = np.zeros((N, N * N, 3), dtype=np.uint8)
        for bi in range(N):
            img[:, bi * N:(bi + 1) * N, :] = np.round(np.clip(res[bi], 0, 1) * 255).astype(np.uint8)
        Image.fromarray(img, 'RGB').save(out / f'fuji_{key}.png', optimize=True)
        mid = sample(lut, flatten(np.array([0.5, 0.5, 0.5])))
        print(f'{key:14s} gris 0.5 -> {np.round(mid, 3)}')


if __name__ == '__main__':
    main()
