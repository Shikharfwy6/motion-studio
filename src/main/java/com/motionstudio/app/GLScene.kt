package com.motionstudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * GPU compositor. Every layer is drawn as a textured quad with a transform matrix and alpha.
 * Text and shapes are rasterised once and cached as textures, images are uploaded once,
 * and videos are decoded by the hardware decoder directly into GPU textures.
 * The same class is used for the live preview and for the export.
 */
class GLScene {
    private class Tx(val id: Int, val qw: Float, val qh: Float)
    private class Made(val bmp: Bitmap, val qw: Float, val qh: Float, val recycle: Boolean)

    private class Prog(val id: Int) {
        val aPos = GLES20.glGetAttribLocation(id, "aPos")
        val aUV = GLES20.glGetAttribLocation(id, "aUV")
        val uMVP = GLES20.glGetUniformLocation(id, "uMVP")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
        val uAlpha = GLES20.glGetUniformLocation(id, "uAlpha")
        val uTexMat = GLES20.glGetUniformLocation(id, "uTexMat")
    }

    private val textures = HashMap<String, Tx>()
    private val decoders = HashMap<Int, VideoDecoder>()
    private var progTex: Prog? = null
    private var progOes: Prog? = null

    private val proj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ident = FloatArray(16)

    private fun fb(a: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a)
        b.position(0)
        return b
    }

    private val posBuf = fb(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
    private val uvBuf = fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val uvFlipBuf = fb(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    private val vsTex = """
        uniform mat4 uMVP;
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            vUV = aUV;
        }
    """.trimIndent()

    private val fsTex = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform float uAlpha;
        void main() {
            gl_FragColor = texture2D(uTex, vUV) * uAlpha;
        }
    """.trimIndent()

    private val vsOes = """
        uniform mat4 uMVP;
        uniform mat4 uTexMat;
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            vUV = (uTexMat * vec4(aUV, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fsOes = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUV;
        uniform samplerExternalOES uTex;
        uniform float uAlpha;
        void main() {
            gl_FragColor = texture2D(uTex, vUV) * uAlpha;
        }
    """.trimIndent()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun program(vs: String, fs: String): Prog {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return Prog(p)
    }

    /** Call with a GL context current. */
    fun init() {
        Matrix.setIdentityM(ident, 0)
        progTex = program(vsTex, fsTex)
        progOes = program(vsOes, fsOes)
    }

    /** Drops every GPU resource (also used when the GL context was lost). */
    fun release() {
        for (d in decoders.values) d.release()
        decoders.clear()
        if (textures.isNotEmpty()) {
            val ids = IntArray(textures.size)
            var i = 0
            for (t in textures.values) ids[i++] = t.id
            try { GLES20.glDeleteTextures(ids.size, ids, 0) } catch (e: Exception) {}
            textures.clear()
        }
    }

    private fun upload(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    private fun tex(key: String, maker: () -> Made?): Tx? {
        val hit = textures[key]
        if (hit != null) return hit
        if (textures.size > 150) {
            val ids = IntArray(textures.size)
            var i = 0
            for (t in textures.values) ids[i++] = t.id
            GLES20.glDeleteTextures(ids.size, ids, 0)
            textures.clear()
        }
        val m = maker() ?: return null
        val id = upload(m.bmp)
        if (m.recycle) m.bmp.recycle()
        val t = Tx(id, m.qw, m.qh)
        textures[key] = t
        return t
    }

    private fun shapeBitmap(l: Layer): Made {
        val sc = min(1f, 1024f / max(l.w, l.h))
        val bw = max(2, (l.w * sc).toInt())
        val bh = max(2, (l.h * sc).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(bw / 2f, bh / 2f)
        c.scale(bw / l.w, bh / l.h)
        paint.color = l.color
        paint.style = Paint.Style.FILL
        Renderer.shapePath(l.shape, l.w, l.h, l.radius, path)
        c.drawPath(path, paint)
        return Made(bmp, l.w, l.h, true)
    }

    private fun textBitmap(l: Layer): Made {
        val sz = Renderer.sizeOf(l)
        val pad = 12f
        val qw = sz[0] + 2 * pad
        val qh = sz[1] + 2 * pad
        val sc = min(1f, 2048f / max(qw, qh))
        val bmp = Bitmap.createBitmap(max(2, (qw * sc).toInt()), max(2, (qh * sc).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bmp.width / qw, bmp.height / qh)
        c.translate(qw / 2f, qh / 2f)
        textPaint.color = l.color
        textPaint.textSize = l.size
        val fm = textPaint.fontMetrics
        val lines = l.text.split("\n")
        val lh = l.size * 1.2f
        for ((i, ln) in lines.withIndex()) {
            val yy = (i - (lines.size - 1) / 2f) * lh - (fm.ascent + fm.descent) / 2f
            c.drawText(ln, 0f, yy, textPaint)
        }
        return Made(bmp, qw, qh, true)
    }

    private fun quad(
        pr: Prog, target: Int, texId: Int, x: Float, y: Float, rot: Float, sc: Float,
        qw: Float, qh: Float, alpha: Float, oes: Boolean, texMat: FloatArray
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, 0f)
        Matrix.rotateM(model, 0, rot, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, sc * qw, sc * qh, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        GLES20.glUseProgram(pr.id)
        GLES20.glUniformMatrix4fv(pr.uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(pr.uTexMat, 1, false, texMat, 0)
        GLES20.glUniform1f(pr.uAlpha, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texId)
        GLES20.glUniform1i(pr.uTex, 0)
        GLES20.glEnableVertexAttribArray(pr.aPos)
        GLES20.glVertexAttribPointer(pr.aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(pr.aUV)
        GLES20.glVertexAttribPointer(pr.aUV, 2, GLES20.GL_FLOAT, false, 0, if (oes) uvFlipBuf else uvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pr.aPos)
        GLES20.glDisableVertexAttribArray(pr.aUV)
    }

    fun draw(p: Project, t: Float, vw: Int, vh: Int) {
        val layers = ArrayList(p.layers)
        val tp = progTex ?: return
        val op = progOes ?: return
        GLES20.glViewport(0, 0, vw, vh)
        val bg = p.bg
        GLES20.glClearColor(Color.red(bg) / 255f, Color.green(bg) / 255f, Color.blue(bg) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        Matrix.orthoM(proj, 0, 0f, p.W.toFloat(), p.H.toFloat(), 0f, -1f, 1f)

        val alive = HashSet<Int>()
        for (l in layers) alive.add(l.id)
        val gone = decoders.keys.filter { it !in alive }
        for (g in gone) decoders.remove(g)?.release()

        for (l in layers) {
            if (!l.visible(t)) continue
            try {
                drawLayer(p, l, t, tp, op)
            } catch (e: Exception) {
                // the layer was edited while drawing; it will be drawn correctly on the next frame
            }
        }
    }

    private fun drawLayer(p: Project, l: Layer, t: Float, tp: Prog, op: Prog) {
        val x = l.eval(P_X, t)
        val y = l.eval(P_Y, t)
        val sc = l.eval(P_SCALE, t)
        val rot = l.eval(P_ROT, t)
        val a = l.eval(P_ALPHA, t).coerceIn(0f, 1f)
        if (a <= 0f || sc == 0f) return
        val gl2d = GLES20.GL_TEXTURE_2D
        when (l.type) {
            T_SHAPE -> {
                val key = "S${l.shape}:${l.w}:${l.h}:${l.radius}:${l.color}"
                val tx = tex(key) { shapeBitmap(l) } ?: return
                quad(tp, gl2d, tx.id, x, y, rot, sc, tx.qw, tx.qh, a, false, ident)
            }
            T_TEXT -> {
                val key = "T${l.text}|${l.size}|${l.color}"
                val tx = tex(key) { textBitmap(l) } ?: return
                quad(tp, gl2d, tx.id, x, y, rot, sc, tx.qw, tx.qh, a, false, ident)
            }
            T_IMAGE -> {
                val m = l.media ?: return
                val file = Store.mediaPath(p.id, m)
                val tx = tex("I$file") {
                    val bmp = MediaCache.image(file)
                    if (bmp == null) null else Made(bmp, 1f, 1f, false)
                } ?: return
                quad(tp, gl2d, tx.id, x, y, rot, sc, l.w, l.h, a, false, ident)
            }
            T_VIDEO -> {
                val m = l.media ?: return
                val file = Store.mediaPath(p.id, m)
                val dec = decoders.getOrPut(l.id) { VideoDecoder(file) }
                val srcUs = (max(0f, t - l.start + l.trim) * 1_000_000f).toLong()
                if (dec.frameAt(srcUs)) {
                    quad(op, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, dec.texId, x, y, rot, sc, l.w, l.h, a, true, dec.texMatrix)
                }
            }
        }
    }
}
