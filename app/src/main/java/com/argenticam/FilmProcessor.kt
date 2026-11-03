package com.argenticam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Développe une photo en pleine résolution avec exactement le même shader que l'aperçu.
 *
 * La photo est traitée par tuiles (rendu dans un framebuffer), pour ne dépendre ni de la taille
 * maximale des textures du GPU ni de celle des pbuffers : un capteur de 50 ou 108 Mpx passe.
 * Chaque tuile est chargée avec une marge autour d'elle, pour que les effets qui lisent les pixels
 * voisins (halation, clarté, netteté) soient continus d'une tuile à l'autre. Le grain et le
 * vignettage sont calculés en coordonnées de la photo entière (uniform uFrame), donc sans couture.
 *
 * À appeler depuis un thread d'arrière-plan.
 */
object FilmProcessor {

    /** Côté d'une tuile de sortie. */
    private const val TILE = 2048

    /**
     * Rayon maximal lu autour d'un pixel, en fraction de la hauteur de la photo
     * (halation 0,032 ; clarté 0,022 ; netteté 0,0012), plus une petite sécurité.
     */
    private const val REACH = 0.036f

    fun process(src: Bitmap, params: RenderParams): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL: no display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL: init failed" }

        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program: FilmProgram? = null
        val srcTex = IntArray(1)
        val fboTex = IntArray(1)
        val fbo = IntArray(1)
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                "EGL: no config"
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            // Surface minuscule : on rend dans un framebuffer, pas dans le pbuffer.
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL: makeCurrent failed" }

            val maxTex = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            val margin = ceil(REACH * h).toInt() + 4
            // la tuile + ses marges doivent tenir dans une texture
            val tile = max(256, min(TILE, maxTex - 2 * margin))

            // Framebuffer de sortie (une tuile)
            GLES20.glGenTextures(1, fboTex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tile, tile, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[0], 0,
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer incomplet"
            }

            GLES20.glGenTextures(1, srcTex, 0)
            program = FilmProgram(external = false)
            val seed = (System.nanoTime() % 1000L).toFloat() * 1.618f
            val buf = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
            val tileBmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)

            var ty = 0
            while (ty < h) {
                val th = min(tile, h - ty)
                var tx = 0
                while (tx < w) {
                    val tw = min(tile, w - tx)

                    // Zone source = tuile + marges (bornée à la photo)
                    val sx = max(0, tx - margin)
                    val sy = max(0, ty - margin)
                    val sw = min(w, tx + tw + margin) - sx
                    val sh = min(h, ty + th + margin) - sy
                    val region = Bitmap.createBitmap(src, sx, sy, sw, sh)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex[0])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, region, 0)
                    if (region !== src) region.recycle()

                    // Coordonnées de la tuile (0..1, ligne 0 = haut de la tuile) -> texture de la zone source.
                    // Comme pour une image entière, la ligne 0 de la texture est le haut de l'image, et
                    // glReadPixels lit de bas en haut : la tuile ressort à l'endroit.
                    val texMatrix = floatArrayOf(
                        tw.toFloat() / sw, 0f, 0f, 0f,
                        0f, th.toFloat() / sh, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        (tx - sx).toFloat() / sw, (ty - sy).toFloat() / sh, 0f, 1f,
                    )
                    // Position de la photo entière dans la tuile (pour le grain et le vignettage)
                    val frame = floatArrayOf(
                        -tx.toFloat() / tw, -ty.toFloat() / th, w.toFloat() / tw, h.toFloat() / th,
                    )

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                    GLES20.glViewport(0, 0, tw, th)
                    program.draw(srcTex[0], texMatrix, params, tw.toFloat() / th, seed, frame = frame)

                    buf.clear()
                    GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    buf.rewind()
                    if (tw == tile && th == tile) {
                        tileBmp.copyPixelsFromBuffer(buf)
                        canvas.drawBitmap(tileBmp, tx.toFloat(), ty.toFloat(), null)
                    } else {
                        val edge = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                        buf.limit(tw * th * 4)
                        edge.copyPixelsFromBuffer(buf)
                        canvas.drawBitmap(edge, tx.toFloat(), ty.toFloat(), null)
                        edge.recycle()
                    }
                    tx += tile
                }
                ty += tile
            }
            tileBmp.recycle()
            return out
        } catch (e: Throwable) {
            out.recycle()
            throw e
        } finally {
            program?.release()
            if (srcTex[0] != 0) GLES20.glDeleteTextures(1, srcTex, 0)
            if (fboTex[0] != 0) GLES20.glDeleteTextures(1, fboTex, 0)
            if (fbo[0] != 0) GLES20.glDeleteFramebuffers(1, fbo, 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
