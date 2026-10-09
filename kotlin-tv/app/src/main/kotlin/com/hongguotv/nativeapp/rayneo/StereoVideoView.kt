// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.rayneo

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.hongguotv.core.StereoGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** ExoPlayer owns decoding; this view owns its output Surface and two-eye presentation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoVideoView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var boundSurface: Surface? = null
    private var currentSurface: Surface? = null
    private val fresh = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var generation = 0
    @Volatile private var aspect = 1f
    @Volatile private var rotation = 0
    @Volatile private var mode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var textureId = 0
    private var program = 0
    private var frame = false
    private var outputWidth = 0
    private var outputHeight = 0
    private val matrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateSize(videoSize) }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,0,0,0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = false
    }

    fun bind(next: ExoPlayer?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (next === player) return
        player?.let { previous ->
            previous.removeListener(listener)
            boundSurface?.let(previous::clearVideoSurface)
        }
        boundSurface = null
        player = if (closed) null else next
        player?.let { p ->
            p.addListener(listener)
            updateSize(p.videoSize)
            currentSurface?.let { p.setVideoSurface(it); boundSurface = it }
        }
    }

    private fun updateSize(size: VideoSize) {
        var ratio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 1f
        @Suppress("DEPRECATION") val degrees = size.unappliedRotationDegrees
        rotation = degrees
        if (degrees == 90 || degrees == 270) ratio = 1f / ratio
        aspect = ratio.takeIf { it.isFinite() && it > 0 } ?: 1f
        requestRender()
    }

    fun setResizeMode(value: Int) { mode = value; requestRender() }

    fun release() {
        if (closed) return
        closed = true
        bind(null)
        generation++
        currentSurface = null
        // Detach may stop GLThread before queueEvent runs. Native outputs must be released here.
        synchronized(this) {
            surface?.release(); surface = null
            texture?.setOnFrameAvailableListener(null); texture?.release(); texture = null
        }
        queueEvent {
            if (textureId != 0) glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) glDeleteProgram(program)
            textureId = 0; program = 0
        }
        requestRender()
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    private fun shader(type: Int, source: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, source); glCompileShader(id)
        val result = IntArray(1); glGetShaderiv(id, GL_COMPILE_STATUS, result, 0)
        check(result[0] != 0) { glGetShaderInfoLog(id) }
        return id
    }

    @Synchronized override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (closed) return
        val ticket = ++generation
        val oldSurface = surface
        val oldTexture = texture
        frame = false; fresh.set(false)
        val v = shader(GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; uniform mat4 transform; uniform vec2 scale; varying vec2 tex; void main(){ gl_Position=vec4(position*scale,0.,1.); tex=(transform*vec4(uv,0.,1.)).xy; }")
        val f = shader(GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES video; varying vec2 tex; void main(){ gl_FragColor=texture2D(video,tex); }")
        program = glCreateProgram(); glAttachShader(program,v); glAttachShader(program,f); glLinkProgram(program)
        glDeleteShader(v); glDeleteShader(f)
        val linked = IntArray(1); glGetProgramiv(program,GL_LINK_STATUS,linked,0)
        check(linked[0] != 0) { glGetProgramInfoLog(program) }
        val ids = IntArray(1); glGenTextures(1,ids,0); textureId=ids[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        val nextTexture = SurfaceTexture(textureId)
        texture = nextTexture
        val nextSurface = Surface(nextTexture)
        surface = nextSurface
        nextTexture.setOnFrameAvailableListener({
            if (!closed && generation == ticket) { fresh.set(true); requestRender() }
        }, main)
        main.post {
            // Bind only after the previous decoder output has been detached on its application thread.
            if (closed || generation != ticket) {
                if (boundSurface === oldSurface && oldSurface != null) {
                    player?.clearVideoSurface(oldSurface); boundSurface = null
                }
                oldSurface?.release(); oldTexture?.release()
                return@post
            }
            boundSurface?.let { player?.clearVideoSurface(it) }
            currentSurface = nextSurface
            player?.setVideoSurface(nextSurface)
            boundSurface = if (player == null) null else nextSurface
            oldSurface?.release(); oldTexture?.release()
            Log.i("RayNeoVideo", "OES output attached generation=$ticket")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outputWidth = width; outputHeight = height
        Log.i("RayNeoVideo", "stereo output ${width}x$height")
    }

    @Synchronized override fun onDrawFrame(gl: GL10?) {
        glDisable(GL_SCISSOR_TEST)
        glClearColor(0f,0f,0f,1f); glClear(GL_COLOR_BUFFER_BIT)
        if (closed || outputWidth < 2 || outputHeight <= 0) return
        val t = texture ?: return
        if (fresh.getAndSet(false)) {
            t.updateTexImage(); t.getTransformMatrix(matrix)
            if (!frame) Log.i("RayNeoVideo", "first stereo frame")
            frame = true
        }
        if (!frame) return
        val eyeWidth = outputWidth / 2
        val (sx, sy) = StereoGeometry.scale(aspect, eyeWidth, outputHeight, mode)
        Matrix.setIdentityM(rotationMatrix,0)
        Matrix.translateM(rotationMatrix,0,.5f,.5f,0f)
        Matrix.rotateM(rotationMatrix,0,-rotation.toFloat(),0f,0f,1f)
        Matrix.translateM(rotationMatrix,0,-.5f,-.5f,0f)
        Matrix.multiplyMM(combinedMatrix,0,matrix,0,rotationMatrix,0)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        glUniform1i(glGetUniformLocation(program,"video"),0)
        glUniform2f(glGetUniformLocation(program,"scale"),sx,sy)
        glUniformMatrix4fv(glGetUniformLocation(program,"transform"),1,false,combinedMatrix,0)
        val position = glGetAttribLocation(program,"position")
        val uv = glGetAttribLocation(program,"uv")
        vertices.position(0); glVertexAttribPointer(position,2,GL_FLOAT,false,16,vertices); glEnableVertexAttribArray(position)
        vertices.position(2); glVertexAttribPointer(uv,2,GL_FLOAT,false,16,vertices); glEnableVertexAttribArray(uv)
        glEnable(GL_SCISSOR_TEST)
        for (eye in 0..1) {
            glViewport(eye*eyeWidth,0,eyeWidth,outputHeight)
            glScissor(eye*eyeWidth,0,eyeWidth,outputHeight)
            glDrawArrays(GL_TRIANGLE_STRIP,0,4)
        }
        glDisable(GL_SCISSOR_TEST)
    }
}
