package com.filmcam

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Programme OpenGL qui applique un [Look] à une texture.
 *
 * Le même fragment shader sert pour l'aperçu live (texture caméra "external OES")
 * et pour la photo finale (texture 2D issue d'un Bitmap) : le rendu est identique.
 */
class FilmProgram(private val external: Boolean) {

    private val program: Int
    private val locs = HashMap<String, Int>()

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
     */
    fun draw(
        texId: Int,
        texMatrix: FloatArray,
        look: Look,
        aspect: Float,
        seed: Float,
    ) {
        val p = look.preset
        val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texId)
        GLES20.glUniform1i(loc("uTex"), 0)

        GLES20.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, texMatrix, 0)
        // Partie linéaire 2x2 de la matrice, pour décaler les coordonnées texture (halation).
        GLES20.glUniformMatrix2fv(
            loc("uLin"), 1, false,
            floatArrayOf(texMatrix[0], texMatrix[1], texMatrix[4], texMatrix[5]), 0,
        )

        GLES20.glUniform1f(loc("uAspect"), aspect)
        GLES20.glUniform1f(loc("uSeed"), seed)
        GLES20.glUniform1f(loc("uStrength"), look.strength)
        GLES20.glUniform1f(loc("uExposure"), p.exposure)
        GLES20.glUniform1f(loc("uContrast"), p.contrast)
        GLES20.glUniform1f(loc("uSaturation"), p.saturation)
        GLES20.glUniform1f(loc("uFade"), p.fade)
        GLES20.glUniform1f(loc("uBw"), if (p.bw) 1f else 0f)
        GLES20.glUniform3fv(loc("uGain"), 1, p.gain, 0)
        GLES20.glUniform3fv(loc("uShadowTint"), 1, p.shadowTint, 0)
        GLES20.glUniform3fv(loc("uHighlightTint"), 1, p.highlightTint, 0)
        GLES20.glUniform1f(loc("uGrain"), p.grain * look.grainScale)
        GLES20.glUniform1f(loc("uGrainCells"), 1100f / p.grainSize)
        GLES20.glUniform1f(loc("uVignette"), p.vignette)
        GLES20.glUniform1f(loc("uHalation"), p.halation)

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindTexture(target, 0)
    }

    fun release() {
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
varying vec2 vUv;
varying vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aPos * 0.5 + 0.5;
    vTex = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
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

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 src = texture2D(uTex, vTex).rgb;
    vec3 c = src;

    // Halation : les hautes lumières bavent en rouge-orange (absence de couche anti-halo).
    if (uHalation > 0.001) {
        float glow = 0.0;
        for (int i = 0; i < 8; i++) {
            float ang = float(i) * 0.785398;
            vec2 dir = vec2(cos(ang) / uAspect, sin(ang));
            vec3 s1 = texture2D(uTex, vTex + uLin * (dir * 0.012)).rgb;
            vec3 s2 = texture2D(uTex, vTex + uLin * (dir * 0.032)).rgb;
            glow += max(luma(s1) - 0.65, 0.0) + max(luma(s2) - 0.65, 0.0);
        }
        glow = glow / 16.0 / 0.35;
        c += uHalation * glow * vec3(1.0, 0.3, 0.12);
    }

    // Balance des blancs + exposition
    c *= uGain;
    c *= exp2(uExposure);
    c = clamp(c, 0.0, 1.0);

    // N&B / saturation
    float l = luma(c);
    c = mix(c, vec3(l), uBw);
    c = mix(vec3(luma(c)), c, uSaturation);

    // Contraste autour du gris moyen + léger pied/épaule façon pellicule
    c = clamp((c - 0.45) * uContrast + 0.45, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25);

    // Split toning
    l = luma(c);
    c += uShadowTint * (1.0 - smoothstep(0.0, 0.55, l));
    c += uHighlightTint * smoothstep(0.45, 1.0, l);

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
    gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
