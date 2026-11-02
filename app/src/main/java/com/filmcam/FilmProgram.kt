package com.filmcam

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Programme OpenGL qui applique une recette ([RenderParams]) à une texture.
 *
 * Le même fragment shader sert pour l'aperçu live (texture caméra "external OES")
 * et pour la photo finale (texture 2D issue d'un Bitmap) : le rendu est identique.
 */
class FilmProgram(private val external: Boolean) {

    private val program: Int
    private val locs = HashMap<String, Int>()
    private val lutTextures = HashMap<String, Int>()

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }

    init {
        val header = if (external) "#extension GL_OES_EGL_image_external : require\n" else ""
        val sampler = if (external) "samplerExternalOES" else "sampler2D"
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            header + FRAGMENT.replace("SAMPLER", sampler),
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Link error: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    private fun loc(name: String): Int = locs.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    /**
     * @param texMatrix matrice 4x4 qui convertit les coordonnées d'affichage en coordonnées texture.
     * @param aspect largeur/hauteur de l'image de sortie.
     * @param zoom loupe de mise au point (1 = pas de loupe), centrée sur [zoomX],[zoomY] (0..1, origine en bas à gauche).
     */
    fun draw(
        texId: Int,
        texMatrix: FloatArray,
        p: RenderParams,
        aspect: Float,
        seed: Float,
        peaking: Boolean = false,
        zoom: Float = 1f,
        zoomX: Float = 0.5f,
        zoomY: Float = 0.5f,
    ) {
        val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texId)
        GLES20.glUniform1i(loc("uTex"), 0)

        GLES20.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, texMatrix, 0)
        // Partie linéaire 2x2 de la matrice, pour décaler les coordonnées texture (flous, halation...).
        val z = zoom.coerceAtLeast(1f)
        GLES20.glUniformMatrix2fv(
            loc("uLin"), 1, false,
            floatArrayOf(texMatrix[0] / z, texMatrix[1] / z, texMatrix[4] / z, texMatrix[5] / z), 0,
        )
        val half = 0.5f / z
        GLES20.glUniform1f(loc("uZoom"), z)
        GLES20.glUniform2f(loc("uZoomCenter"), zoomX.coerceIn(half, 1f - half), zoomY.coerceIn(half, 1f - half))

        GLES20.glUniform1f(loc("uAspect"), aspect)
        GLES20.glUniform1f(loc("uSeed"), seed)
        GLES20.glUniform1f(loc("uStrength"), p.strength)
        GLES20.glUniform1f(loc("uExposure"), p.exposure)
        GLES20.glUniform1f(loc("uContrast"), p.contrast)
        GLES20.glUniform1f(loc("uSaturation"), p.saturation)
        GLES20.glUniform1f(loc("uFade"), p.fade)
        GLES20.glUniform1f(loc("uBw"), p.bw)
        GLES20.glUniform3fv(loc("uGain"), 1, p.gain, 0)
        GLES20.glUniform3fv(loc("uShadowTint"), 1, p.shadowTint, 0)
        GLES20.glUniform3fv(loc("uHighlightTint"), 1, p.highlightTint, 0)
        GLES20.glUniform1f(loc("uGrain"), p.grain)
        GLES20.glUniform1f(loc("uGrainCells"), 1100f / p.grainSize)
        GLES20.glUniform1f(loc("uVignette"), p.vignette)
        GLES20.glUniform1f(loc("uHalation"), p.halation)
        GLES20.glUniform1f(loc("uSharp"), p.sharpness)
        GLES20.glUniform1f(loc("uClarity"), p.clarity)
        GLES20.glUniform1f(loc("uChrome"), p.chrome)
        GLES20.glUniform1f(loc("uChromeBlue"), p.chromeBlue)
        GLES20.glUniform1f(loc("uDR"), p.dynamicRange)
        GLES20.glUniform1f(loc("uHighlight"), p.highlight)
        GLES20.glUniform1f(loc("uShadow"), p.shadow)
        GLES20.glUniform1f(loc("uPeaking"), if (peaking) 1f else 0f)
        GLES20.glUniform3fv(loc("uBwMix"), 1, p.bwMix, 0)
        GLES20.glUniform3fv(loc("uMonoTint"), 1, p.monoTint, 0)

        // LUT 3D de la simulation, sur l'unité de texture 1
        val lutTex = p.lut?.let { lutTexture(it) } ?: 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        GLES20.glUniform1i(loc("uLut"), 1)
        GLES20.glUniform1f(loc("uLutOn"), if (lutTex != 0) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindTexture(target, 0)
    }

    private fun lutTexture(name: String): Int {
        lutTextures[name]?.let { return it }
        val bmp = LutCache.bitmap(name) ?: return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        lutTextures[name] = ids[0]
        return ids[0]
    }

    fun release() {
        if (lutTextures.isNotEmpty()) {
            GLES20.glDeleteTextures(lutTextures.size, lutTextures.values.toIntArray(), 0)
            lutTextures.clear()
        }
        GLES20.glDeleteProgram(program)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Shader error: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        private const val VERTEX = """
attribute vec2 aPos;
uniform mat4 uTexMatrix;
uniform float uZoom;
uniform vec2 uZoomCenter;
varying vec2 vUv;
varying vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aPos * 0.5 + 0.5;
    vec2 s = uZoomCenter + (vUv - uZoomCenter) / uZoom;
    vTex = (uTexMatrix * vec4(s, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT = """
precision highp float;
varying vec2 vUv;
varying vec2 vTex;
uniform SAMPLER uTex;
uniform mat2 uLin;
uniform float uAspect;
uniform float uSeed;
uniform float uStrength;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uFade;
uniform float uBw;
uniform vec3 uGain;
uniform vec3 uShadowTint;
uniform vec3 uHighlightTint;
uniform float uGrain;
uniform float uGrainCells;
uniform float uVignette;
uniform float uHalation;
uniform float uSharp;
uniform float uClarity;
uniform float uChrome;
uniform float uChromeBlue;
uniform float uDR;
uniform float uHighlight;
uniform float uShadow;
uniform float uPeaking;
uniform sampler2D uLut;
uniform float uLutOn;
uniform vec3 uBwMix;
uniform vec3 uMonoTint;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// LUT 3D 32x32x32 rangée en bande 1024x32 (x = b*32 + r, y = g), interpolation trilinéaire
vec3 applyLut(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float b = c.b * 31.0;
    float b0 = floor(b);
    float b1 = min(b0 + 1.0, 31.0);
    float x = (c.r * 31.0 + 0.5) / 1024.0;
    float y = (c.g * 31.0 + 0.5) / 32.0;
    vec3 s0 = texture2D(uLut, vec2(x + b0 / 32.0, y)).rgb;
    vec3 s1 = texture2D(uLut, vec2(x + b1 / 32.0, y)).rgb;
    return mix(s0, s1, b - b0);
}

vec3 tap(vec2 displayOffset) { return texture2D(uTex, vTex + uLin * displayOffset).rgb; }

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 src = texture2D(uTex, vTex).rgb;
    vec3 c = src;

    // Netteté : masque flou sur un petit rayon (négatif = adoucit)
    if (abs(uSharp) > 0.001) {
        vec2 o = vec2(0.0012 / uAspect, 0.0012);
        vec3 b = (tap(vec2(o.x, 0.0)) + tap(vec2(-o.x, 0.0)) + tap(vec2(0.0, o.y)) + tap(vec2(0.0, -o.y))) * 0.25;
        c = clamp(c + (c - b) * uSharp * 0.9, 0.0, 1.0);
    }

    // Clarté : contraste local des tons moyens sur un grand rayon
    if (abs(uClarity) > 0.001) {
        float lb = 0.0;
        for (int i = 0; i < 6; i++) {
            float ang = float(i) * 1.047198;
            vec2 dir = vec2(cos(ang) / uAspect, sin(ang));
            vec2 dir2 = vec2(cos(ang + 0.5236) / uAspect, sin(ang + 0.5236));
            lb += luma(tap(dir * 0.01)) + luma(tap(dir2 * 0.022));
        }
        lb /= 12.0;
        float l0 = luma(c);
        float mid = 1.0 - abs(l0 - 0.5) * 2.0;
        c = clamp(c + (l0 - lb) * uClarity * 0.45 * mid, 0.0, 1.0);
    }

    // Halation : les hautes lumières bavent en rouge-orange (absence de couche anti-halo).
    if (uHalation > 0.001) {
        float glow = 0.0;
        for (int i = 0; i < 8; i++) {
            float ang = float(i) * 0.785398;
            vec2 dir = vec2(cos(ang) / uAspect, sin(ang));
            glow += max(luma(tap(dir * 0.012)) - 0.65, 0.0) + max(luma(tap(dir * 0.032)) - 0.65, 0.0);
        }
        glow = glow / 16.0 / 0.35;
        c += uHalation * glow * vec3(1.0, 0.3, 0.12);
    }

    // Balance des blancs (Kelvin + décalage R/B) + exposition
    c *= uGain;
    c *= exp2(uExposure);

    // Plage dynamique (DR200 / DR400) : on retient les hautes lumières, on débouche un peu les ombres
    if (uDR > 0.0) {
        float knee = 0.78 - 0.1 * uDR;
        vec3 over = max(c - knee, 0.0);
        c = c - over * (0.3 * uDR) / (1.0 + over * 2.0);
        c += (1.0 - smoothstep(0.0, 0.3, luma(c))) * 0.02 * uDR;
    }
    c = clamp(c, 0.0, 1.0);

    // Simulation de film mesurée (LUT 3D)
    if (uLutOn > 0.5) c = applyLut(c);

    // N&B (avec filtre de couleur) / saturation (paramètre Couleur)
    c = mix(c, vec3(dot(c, uBwMix)), uBw);
    c = mix(vec3(luma(c)), c, uSaturation);
    c = clamp(c, 0.0, 1.0);

    // Color Chrome : les couleurs très saturées gagnent en densité et en profondeur
    if (uChrome > 0.0) {
        float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
        float m = smoothstep(0.2, 0.6, sat) * uChrome;
        c = mix(vec3(luma(c)), c, 1.0 + 0.25 * m);
        c *= 1.0 - 0.16 * m;
    }
    // Color Chrome FX Bleu : bleus plus profonds
    if (uChromeBlue > 0.0) {
        float m = smoothstep(0.03, 0.25, c.b - max(c.r, c.g)) * uChromeBlue;
        c *= 1.0 - 0.2 * m;
        c.b += 0.035 * m;
    }
    c = clamp(c, 0.0, 1.0);

    // Contraste autour du gris moyen + pied/épaule façon pellicule
    c = clamp((c - 0.45) * uContrast + 0.45, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25 * clamp(abs(uContrast - 1.0) * 6.0, 0.0, 1.0));

    // Tonalité des hautes lumières / des ombres (+ = plus dur, - = plus doux)
    float l = luma(c);
    c += uHighlight * 0.045 * smoothstep(0.45, 1.0, l);
    c -= uShadow * 0.045 * (1.0 - smoothstep(0.0, 0.5, l));

    // Split toning de la pellicule de base
    l = luma(c);
    c += uShadowTint * (1.0 - smoothstep(0.0, 0.55, l));
    c += uHighlightTint * smoothstep(0.45, 1.0, l);

    // Teinte monochrome (chaud/froid, magenta/vert), surtout dans les tons moyens
    if (uBw > 0.5) {
        float lm = luma(c);
        c += uMonoTint * 4.0 * lm * (1.0 - lm);
    }

    // Noirs relevés (look mat)
    c = mix(vec3(uFade), vec3(1.0 - uFade * 0.5), clamp(c, 0.0, 1.0));

    // Grain : plus visible dans les tons moyens, légèrement coloré en couleur
    if (uGrain > 0.001) {
        float lg = luma(c);
        float amp = uGrain * (0.35 + 0.65 * (1.0 - abs(lg - 0.5) * 2.0));
        vec2 cell = floor(vUv * vec2(uGrainCells * uAspect, uGrainCells)) + uSeed;
        float n = hash(cell) - 0.5;
        vec3 chroma = vec3(hash(cell + 17.0), hash(cell + 31.0), hash(cell + 53.0)) - 0.5;
        c += amp * (vec3(n) * 1.6 + chroma * 0.5 * (1.0 - uBw));
    }

    // Vignettage
    if (uVignette > 0.001) {
        vec2 q = vUv - 0.5;
        q.x *= uAspect;
        c *= 1.0 - uVignette * smoothstep(0.22, 0.75, length(q));
    }

    c = mix(src, clamp(c, 0.0, 1.0), uStrength);

    // Focus peaking : les contours nets s'allument en rouge (aide à la mise au point manuelle)
    if (uPeaking > 0.5) {
        vec2 px = vec2(0.0018 / uAspect, 0.0018);
        float gx = luma(tap(vec2(px.x, 0.0))) - luma(tap(vec2(-px.x, 0.0)));
        float gy = luma(tap(vec2(0.0, px.y))) - luma(tap(vec2(0.0, -px.y)));
        float edge = smoothstep(0.07, 0.16, length(vec2(gx, gy)));
        c = mix(c, vec3(1.0, 0.18, 0.12), edge * 0.9);
    }

    gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
