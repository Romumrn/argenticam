package com.filmcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Aperçu caméra rendu en OpenGL : chaque image du capteur passe par le shader de pellicule
 * avant d'être affichée, donc on voit le rendu final en direct.
 */
class FilmPreviewView(context: Context) : GLSurfaceView(context) {

    private val renderer = FilmRenderer(this)

    /** À donner à `Preview.setSurfaceProvider`. */
    val surfaceProvider = Preview.SurfaceProvider { request -> renderer.onSurfaceRequested(request) }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Paramètres de rendu + aides au cadrage (peaking, loupe). */
    fun setRender(
        params: RenderParams,
        peaking: Boolean,
        zoom: Float,
        zoomX: Float,
        zoomY: Float,
        frame: FloatArray = FilmProgram.FULL_FRAME,
    ) {
        renderer.params = params
        renderer.peaking = peaking
        renderer.zoom = zoom
        renderer.zoomX = zoomX
        renderer.zoomY = zoomY
        renderer.frame = frame
        requestRender()
    }
}

private class FilmRenderer(private val host: GLSurfaceView) : GLSurfaceView.Renderer {

    @Volatile var params: RenderParams = Recipes.builtIns[0].render()
    @Volatile var peaking: Boolean = false
    @Volatile var zoom: Float = 1f
    @Volatile var zoomX: Float = 0.5f
    @Volatile var zoomY: Float = 0.5f
    @Volatile var frame: FloatArray = FilmProgram.FULL_FRAME

    @Volatile private var pendingRequest: SurfaceRequest? = null

    private var glReady = false
    private var program: FilmProgram? = null
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameAvailable = AtomicBoolean(false)
    private var hasFrame = false

    private var viewW = 1
    private var viewH = 1
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val texMatrix = FloatArray(16)
    private val startNs = System.nanoTime()

    // ---- appelé depuis CameraX (thread principal) ----
    fun onSurfaceRequested(request: SurfaceRequest) {
        pendingRequest?.willNotProvideSurface()
        pendingRequest = request
        host.queueEvent { tryProvideSurface() }
    }

    // ---- thread GL ----
    private fun tryProvideSurface() {
        val request = pendingRequest ?: return
        if (!glReady) return // sera relancé par onSurfaceCreated
        pendingRequest = null

        val st = surfaceTexture ?: SurfaceTexture(texId).also {
            it.setOnFrameAvailableListener {
                frameAvailable.set(true)
                host.requestRender()
            }
            surfaceTexture = it
        }
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        val executor = ContextCompat.getMainExecutor(host.context)

        request.provideSurface(surface, executor) { surface.release() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Contexte (re)créé : les anciennes ressources GL sont invalides.
        surfaceTexture?.release()
        surfaceTexture = null
        hasFrame = false

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = FilmProgram(external = true)
        glReady = true
        tryProvideSurface()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val st = surfaceTexture ?: return
        val prog = program ?: return
        if (frameAvailable.getAndSet(false)) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                hasFrame = true
            } catch (_: Exception) {
                return
            }
        }
        if (!hasFrame) return

        // Le SurfaceTexture embarque déjà la rotation capteur -> écran (portrait) : on ne
        // rajoute rien. (Une rotation manuelle en plus donnait une image penchée.)
        System.arraycopy(stMatrix, 0, texMatrix, 0, 16)

        // Le grain "vit" à ~12 images/s, comme un vrai grain qui change à chaque photogramme.
        val seed = ((System.nanoTime() - startNs) / 83_000_000L % 1000L).toFloat() * 1.618f
        prog.draw(texId, texMatrix, params, viewW.toFloat() / viewH, seed, peaking, zoom, zoomX, zoomY, frame)
    }

}
