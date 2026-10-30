package com.motionstudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

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

    // least recently used texture cache: no string keys, no full clears while animating
    private val textures = LinkedHashMap<Long, Tx>(32, 0.75f, true)
    private val paths = HashMap<Int, String>()
    private var curSkx = 0f
    private var curSky = 0f

    /** true while exporting: videos are decoded frame by frame, as fast as the hardware allows */
    var offline = false

    // effects (C++ core), 3D view and particles
    private var fxReady = false
    private var curVw = 1
    private var curVh = 1
    private var fxScale = 1f
    private var forceThreeD = false
    private val fxP = FloatArray(24)
    private val partP = FloatArray(16)
    private val partOut = FloatArray(400 * 4)
    private var progLine: Prog? = null
    private var lineColor = -1
    private val lineArr = FloatArray(3 * 300)
    private val lineFb = ByteBuffer.allocateDirect(3 * 300 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val decoders = HashMap<Int, VideoDecoder>()
    private var progTex: Prog? = null
    private var progOes: Prog? = null

    private val proj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ident = FloatArray(16)
    private val shear = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val vp = FloatArray(16)
    private val vm = FloatArray(16)
    private val pm = FloatArray(16)

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
        val lp = program(
            "uniform mat4 uMVP;\nattribute vec3 aPos;\nvoid main() { gl_Position = uMVP * vec4(aPos, 1.0); }",
            "precision mediump float;\nuniform vec4 uColor;\nvoid main() { gl_FragColor = uColor; }"
        )
        progLine = lp
        lineColor = GLES20.glGetUniformLocation(lp.id, "uColor")
        fxReady = NativeFx.ok && NativeFx.nInit()
    }

    /** Drops every GPU resource (also used when the GL context was lost). */
    fun release() {
        if (fxReady) {
            NativeFx.nRelease()
            fxReady = false
        }
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

    private fun getTex(key: Long): Tx? = textures[key]

    private fun putTex(key: Long, m: Made): Tx {
        val id = upload(m.bmp)
        if (m.recycle) m.bmp.recycle()
        val t = Tx(id, m.qw, m.qh)
        textures[key] = t
        if (textures.size > 120) {
            val it = textures.entries.iterator()
            while (textures.size > 120 && it.hasNext()) {
                val e = it.next()
                if (e.value !== t) {
                    GLES20.glDeleteTextures(1, intArrayOf(e.value.id), 0)
                    it.remove()
                }
            }
        }
        return t
    }

    private fun pathOf(p: Project, l: Layer): String? {
        val m = l.media ?: return null
        return paths.getOrPut(l.id) { Store.mediaPath(p.id, m) }
    }

    private fun fillPaint(l: Layer, w: Float, h: Float, angle: Float): Paint? {
        if (l.fillType == 0) return null
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        pt.style = Paint.Style.FILL
        if (l.fillType == 2) {
            if (l.gradType == 1) {
                pt.shader = RadialGradient(0f, 0f, max(max(w, h) / 2f, 1f), l.color, l.color2, Shader.TileMode.CLAMP)
            } else {
                val a = Math.toRadians(angle.toDouble())
                val dx = (cos(a) * w / 2f).toFloat()
                val dy = (sin(a) * h / 2f).toFloat()
                pt.shader = LinearGradient(-dx, -dy, dx, dy, l.color, l.color2, Shader.TileMode.CLAMP)
            }
        } else {
            pt.color = l.color
        }
        return pt
    }

    private fun drawContent(c: Canvas, l: Layer, pt: Paint, sz: FloatArray) {
        if (l.type == T_TEXT) {
            pt.typeface = Fonts.face(l.fontIdx)
            pt.textSize = l.size
            val fm = pt.fontMetrics
            val lines = l.text.split("\n")
            val lh = l.size * 1.2f
            for ((i, ln) in lines.withIndex()) {
                val yy = (i - (lines.size - 1) / 2f) * lh - (fm.ascent + fm.descent) / 2f
                when (l.align) {
                    0 -> { pt.textAlign = Paint.Align.LEFT; c.drawText(ln, -sz[0] / 2f, yy, pt) }
                    2 -> { pt.textAlign = Paint.Align.RIGHT; c.drawText(ln, sz[0] / 2f, yy, pt) }
                    else -> { pt.textAlign = Paint.Align.CENTER; c.drawText(ln, 0f, yy, pt) }
                }
            }
        } else {
            Renderer.shapePath(l.shape, l.w, l.h, l.radius, path)
            c.drawPath(path, pt)
        }
    }

    /** Shapes and text are rasterised once (fill, gradient, border and shadow included) and cached as a texture. */
    private fun quant(v: Float) = Math.round(v * 2f) / 2f

    private fun mix(h: Long, v: Int): Long = (h xor v.toLong()) * 1099511628211L

    private fun fb(f: Float) = java.lang.Float.floatToIntBits(f)

    /** A cheap cache key (no string building) for everything that changes how a shape or text looks. */
    private fun keyOf(l: Layer, stroke: Float, shBlur: Float, shDx: Float, shDy: Float, grad: Float): Long {
        var h = -3750763034362895579L
        h = mix(h, l.type); h = mix(h, l.shape); h = mix(h, fb(l.w)); h = mix(h, fb(l.h)); h = mix(h, fb(l.radius))
        h = mix(h, l.text.hashCode()); h = mix(h, fb(l.size)); h = mix(h, l.fontIdx); h = mix(h, l.align)
        h = mix(h, l.fillType); h = mix(h, l.color); h = mix(h, l.color2); h = mix(h, l.gradType)
        h = mix(h, fb(grad)); h = mix(h, fb(stroke)); h = mix(h, l.strokeColor)
        h = mix(h, if (l.shadowOn) 1 else 0); h = mix(h, fb(shBlur)); h = mix(h, fb(shDx)); h = mix(h, fb(shDy))
        h = mix(h, l.shadowColor)
        return h
    }

    /** Shapes and text are rasterised once (fill, gradient, border and shadow included) and cached as a texture. */
    private fun raster(l: Layer, stroke: Float, shBlur: Float, shDx: Float, shDy: Float, grad: Float): Made {
        val sz = Renderer.sizeOf(l)
        val sh = if (l.shadowOn) shBlur + max(abs(shDx), abs(shDy)) else 0f
        val pad = 12f + stroke + sh
        val qw = sz[0] + 2 * pad
        val qh = sz[1] + 2 * pad
        val sc = min(1f, 2048f / max(qw, qh))
        val bmp = Bitmap.createBitmap(max(2, (qw * sc).toInt()), max(2, (qh * sc).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bmp.width / qw, bmp.height / qh)
        c.translate(qw / 2f, qh / 2f)
        val fill = fillPaint(l, sz[0], sz[1], grad)
        if (fill != null) {
            if (l.shadowOn) fill.setShadowLayer(shBlur, shDx, shDy, l.shadowColor)
            drawContent(c, l, fill, sz)
            fill.clearShadowLayer()
        }
        if (stroke > 0f) {
            val st = Paint(Paint.ANTI_ALIAS_FLAG)
            st.style = Paint.Style.STROKE
            st.strokeWidth = stroke
            st.strokeJoin = Paint.Join.ROUND
            st.color = l.strokeColor
            drawContent(c, l, st, sz)
        }
        return Made(bmp, qw, qh, true)
    }

    private fun setBlend(mode: Int) {
        when (mode) {
            1 -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            2 -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            3 -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    private fun hash(a: Int, b: Int, c: Int): Float {
        var h = (a * 73856093) xor (b * 19349663) xor (c * 83492791)
        h = h xor (h ushr 13)
        h *= 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun dotBitmap(color: Int): Made {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        pt.shader = RadialGradient(32f, 32f, 32f, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(32f, 32f, 32f, pt)
        return Made(bmp, 1f, 1f, true)
    }

    /**
     * One quad. 3D layers use the camera (perspective) matrix and their Z / X / Y rotation,
     * 2D layers use the flat pixel projection and ignore the camera, like in After Effects.
     */
    private fun quad(
        pr: Prog, target: Int, texId: Int, l: Layer, x: Float, y: Float, z: Float,
        rx: Float, ry: Float, rot: Float, sc: Float,
        qw: Float, qh: Float, alpha: Float, oes: Boolean, texMat: FloatArray
    ) {
        val d3 = l.threeD || forceThreeD
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, if (d3) z else 0f)
        Matrix.rotateM(model, 0, rot, 0f, 0f, 1f)
        if (d3) {
            Matrix.rotateM(model, 0, ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, rx, 1f, 0f, 0f)
        }
        if (curSkx != 0f || curSky != 0f) {
            Matrix.setIdentityM(shear, 0)
            shear[4] = tan(Math.toRadians(curSkx.toDouble())).toFloat()
            shear[1] = tan(Math.toRadians(curSky.toDouble())).toFloat()
            Matrix.multiplyMM(tmpM, 0, model, 0, shear, 0)
            System.arraycopy(tmpM, 0, model, 0, 16)
        }
        Matrix.scaleM(model, 0, sc * qw * (if (l.flipH) -1f else 1f), sc * qh * (if (l.flipV) -1f else 1f), 1f)
        Matrix.multiplyMM(mvp, 0, if (d3) vp else proj, 0, model, 0)
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

    /** Builds view-projection for 3D layers from the active camera layer (or a neutral default camera). */
    private fun buildCamera(p: Project, t: Float) {
        val cam = p.layers.lastOrNull { it.type == T_CAMERA && it.shown(t) }
        val w2 = p.W / 2f
        val h2 = p.H / 2f
        val f = p.H * 1.5f
        var cx = 0f
        var cy = 0f
        var cz = 0f
        var rx = 0f
        var ry = 0f
        var rz = 0f
        var zoom = 1f
        if (forceThreeD) {
            rx = p.orbitRx
            ry = p.orbitRy
            zoom = p.orbitZoom
        } else if (cam != null) {
            cx = cam.eval(P_X, t) - w2
            cy = cam.eval(P_Y, t) - h2
            cz = cam.eval(P_Z, t)
            rx = cam.eval(P_RX, t)
            ry = cam.eval(P_RY, t)
            rz = cam.eval(P_ROT, t)
            zoom = max(0.05f, cam.eval(P_SCALE, t))
        }
        Matrix.setIdentityM(vm, 0)
        Matrix.translateM(vm, 0, 0f, 0f, f)
        Matrix.rotateM(vm, 0, -rz, 0f, 0f, 1f)
        Matrix.rotateM(vm, 0, -ry, 0f, 1f, 0f)
        Matrix.rotateM(vm, 0, -rx, 1f, 0f, 0f)
        Matrix.translateM(vm, 0, -cx, -cy, -cz)
        Matrix.translateM(vm, 0, -w2, -h2, 0f)
        java.util.Arrays.fill(pm, 0f)
        pm[0] = zoom * f / w2
        pm[5] = -zoom * f / h2
        pm[11] = 1f
        Matrix.multiplyMM(vp, 0, pm, 0, vm, 0)
    }

    fun draw(p: Project, t: Float, vw: Int, vh: Int) {
        val layers = ArrayList(p.layers)
        val tp = progTex ?: return
        val op = progOes ?: return
        GLES20.glViewport(0, 0, vw, vh)
        curVw = vw
        curVh = vh
        fxScale = vw.toFloat() / p.W
        forceThreeD = p.view3d && !offline
        val bg = p.bg
        GLES20.glClearColor(Color.red(bg) / 255f, Color.green(bg) / 255f, Color.blue(bg) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        Matrix.orthoM(proj, 0, 0f, p.W.toFloat(), p.H.toFloat(), 0f, -1f, 1f)
        try {
            buildCamera(p, t)
        } catch (e: Exception) {
            return
        }

        if (decoders.isNotEmpty()) {
            val alive = HashSet<Int>()
            for (l in layers) alive.add(l.id)
            val gone = decoders.keys.filter { it !in alive }
            for (g in gone) decoders.remove(g)?.release()
        }

        for (l in layers) {
            if (!l.shown(t) || l.type == T_CAMERA) continue
            try {
                drawLayer(p, l, t, tp, op)
            } catch (e: Exception) {
                // the layer was edited while drawing; it will be drawn correctly on the next frame
            }
        }
            if (forceThreeD) drawFrameAndAxes(p)
    }

    /** With motion blur on, a layer is drawn several times across the shutter interval and averaged. */
    private fun drawLayer(p: Project, l: Layer, t: Float, tp: Prog, op: Prog) {
        val n = if (p.shutter > 0f && l.type != T_PARTICLES && !(fxReady && fillFx(l, t))) 6 else 1
        val interval = p.shutter / 360f / 30f
        for (i in 0 until n) {
            val tt = if (n == 1) t else max(0f, t - interval * (n - 1 - i) / (n - 1))
            drawSample(p, l, t, tt, if (n == 1) 1f else 1f / (i + 1), tp, op)
        }
    }

    private fun drawSample(p: Project, l: Layer, t: Float, tt: Float, mul: Float, tp: Prog, op: Prog) {
        val x = l.eval(P_X, tt)
        val y = l.eval(P_Y, tt)
        val z = if (l.threeD) l.eval(P_Z, tt) else 0f
        val rx = if (l.threeD) l.eval(P_RX, tt) else 0f
        val ry = if (l.threeD) l.eval(P_RY, tt) else 0f
        val sc = l.eval(P_SCALE, tt)
        val rot = l.eval(P_ROT, tt)
        val a = l.eval(P_ALPHA, tt).coerceIn(0f, 1f) * mul
        if (a <= 0f || sc == 0f) return
        val hasFx = fillFx(l, tt)
        if (fxReady && l.type != T_PARTICLES && hasFx) {
            // draw the layer into an offscreen target, run the effect passes, then composite the result
            NativeFx.nBegin(curVw, curVh)
            GLES20.glEnable(GLES20.GL_BLEND)
            setBlend(0)
            drawBody(p, l, t, tt, x, y, z, rx, ry, rot, sc, 1f, tp, op)
            val tex = NativeFx.nRun(fxP)
            GLES20.glViewport(0, 0, curVw, curVh)
            GLES20.glEnable(GLES20.GL_BLEND)
            setBlend(l.blend)
            compositeFull(tp, tex, a)
        } else {
            setBlend(l.blend)
            drawBody(p, l, t, tt, x, y, z, rx, ry, rot, sc, a, tp, op)
        }
    }

    /** Fills the parameters of the effect passes. Returns false when the layer has no active effect. */
    private fun fillFx(l: Layer, tt: Float): Boolean {
        val blur = if (l.isEffectEnabled("blur")) l.eval(P_BLUR, tt) else 0f
        val mblur = if (l.isEffectEnabled("mblur")) l.eval(P_MBLUR, tt) else 0f
        val rblur = if (l.isEffectEnabled("rblur")) l.eval(P_RBLUR, tt) else 0f
        val glow = if (l.isEffectEnabled("glow")) l.eval(P_GLOW, tt) else 0f
        val bright = if (l.isEffectEnabled("bright")) l.eval(P_BRIGHT, tt) else 0f
        val contrast = if (l.isEffectEnabled("bright")) l.eval(P_CONTRAST, tt) else 0f
        val hue = if (l.isEffectEnabled("huesat")) l.eval(P_HUE, tt) else 0f
        val sat = if (l.isEffectEnabled("huesat")) l.eval(P_SAT, tt) else 0f
        val tint = if (l.isEffectEnabled("tint")) l.eval(P_TINT, tt) else 0f
        val pix = if (l.isEffectEnabled("pix")) l.eval(P_PIX, tt) else 0f
        val rgb = if (l.isEffectEnabled("rgb")) l.eval(P_RGB, tt) else 0f
        val vig = if (l.isEffectEnabled("vig")) l.eval(P_VIG, tt) else 0f
        val grain = if (l.isEffectEnabled("vig")) l.eval(P_GRAIN, tt) else 0f
        val inv = if (l.isEffectEnabled("inv")) l.eval(P_INV, tt) else 0f
        val active = blur > 0.3f || mblur > 0.3f || rblur > 0.01f || glow > 0.01f || bright != 0f ||
            contrast != 0f || hue != 0f || sat != 0f || tint > 0.01f || pix > 1f || rgb > 0.3f ||
            vig > 0.01f || grain > 0.01f || inv > 0.01f
        if (!active) return false
        val c = l.tintColor
        fxP[0] = blur
        fxP[1] = mblur
        fxP[2] = l.eval(P_MBANG, tt)
        fxP[3] = rblur
        fxP[4] = glow
        fxP[5] = l.eval(P_GLOWR, tt)
        fxP[6] = bright
        fxP[7] = contrast
        fxP[8] = hue
        fxP[9] = sat
        fxP[10] = tint
        fxP[11] = Color.red(c) / 255f
        fxP[12] = Color.green(c) / 255f
        fxP[13] = Color.blue(c) / 255f
        fxP[14] = pix
        fxP[15] = rgb
        fxP[16] = vig
        fxP[17] = grain
        fxP[18] = inv
        fxP[19] = tt * 7f
        fxP[20] = fxScale
        return true
    }

    /** Draws a screen sized texture (the effect result) onto the screen. */
    private fun compositeFull(tp: Prog, tex: Int, alpha: Float) {
        if (tex == 0) return
        Matrix.setIdentityM(mvp, 0)
        Matrix.scaleM(mvp, 0, 2f, 2f, 1f)
        GLES20.glUseProgram(tp.id)
        GLES20.glUniformMatrix4fv(tp.uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(tp.uTexMat, 1, false, ident, 0)
        GLES20.glUniform1f(tp.uAlpha, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(tp.uTex, 0)
        GLES20.glEnableVertexAttribArray(tp.aPos)
        GLES20.glVertexAttribPointer(tp.aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(tp.aUV)
        GLES20.glVertexAttribPointer(tp.aUV, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(tp.aPos)
        GLES20.glDisableVertexAttribArray(tp.aUV)
    }

    /** 3D view: After Effects style 3D perspective ground grid, XYZ axes, and layer 3D bounding wireframe. */
    private fun drawFrameAndAxes(p: Project) {
        val pr = progLine ?: return
        val w = p.W.toFloat()
        val h = p.H.toFloat()
        val v = lineArr
        var i = 0
        fun pt(x: Float, y: Float, z: Float) {
            if (i + 3 <= v.size) {
                v[i++] = x
                v[i++] = y
                v[i++] = z
            }
        }

        // 1. 3D Perspective Ground Grid (Floor plane like After Effects 3D mode)
        val gridStart = i / 3
        val floorY = h * 1.05f
        val gridStep = max(w, h) * 0.15f
        for (gx in -4..10) {
            val xx = gx * gridStep
            pt(xx, floorY, -1200f); pt(xx, floorY, 1200f)
        }
        for (gz in -4..5) {
            val zz = gz * 300f
            pt(-4 * gridStep, floorY, zz); pt(10 * gridStep, floorY, zz)
        }
        val gridCount = (i / 3) - gridStart

        // 2. Composition 2D frame boundary
        val frameStart = i / 3
        pt(0f, 0f, 0f); pt(w, 0f, 0f); pt(w, 0f, 0f); pt(w, h, 0f)
        pt(w, h, 0f); pt(0f, h, 0f); pt(0f, h, 0f); pt(0f, 0f, 0f)
        val frameCount = (i / 3) - frameStart

        // 3. Composition Center XYZ Axes
        val cx = w / 2f
        val cy = h / 2f
        val ax = max(w, h) * 0.22f
        val axesStart = i / 3
        pt(cx, cy, 0f); pt(cx + ax, cy, 0f) // X (Red)
        pt(cx, cy, 0f); pt(cx, cy + ax, 0f) // Y (Green)
        pt(cx, cy, 0f); pt(cx, cy, ax)      // Z (Blue)
        val axesCount = (i / 3) - axesStart

        // 4. Selected 3D Layer Bounding Wireframe (if any)
        val sel = p.sel
        val selStart = i / 3
        var selCount = 0
        if (sel != null && sel.shown(p.time) && sel.type != T_CAMERA) {
            val sz = Renderer.sizeOf(sel)
            val sc = sel.eval(P_SCALE, p.time)
            val hw = sz[0] * sc / 2f
            val hh = sz[1] * sc / 2f
            val lx = sel.eval(P_X, p.time)
            val ly = sel.eval(P_Y, p.time)
            val lz = if (sel.threeD) sel.eval(P_Z, p.time) else 0f
            pt(lx - hw, ly - hh, lz); pt(lx + hw, ly - hh, lz)
            pt(lx + hw, ly - hh, lz); pt(lx + hw, ly + hh, lz)
            pt(lx + hw, ly + hh, lz); pt(lx - hw, ly + hh, lz)
            pt(lx - hw, ly + hh, lz); pt(lx - hw, ly - hh, lz)
            selCount = (i / 3) - selStart
        }

        lineFb.clear()
        lineFb.put(v, 0, i)
        lineFb.position(0)
        GLES20.glUseProgram(pr.id)
        GLES20.glUniformMatrix4fv(pr.uMVP, 1, false, vp, 0)
        GLES20.glEnableVertexAttribArray(pr.aPos)
        GLES20.glVertexAttribPointer(pr.aPos, 3, GLES20.GL_FLOAT, false, 0, lineFb)

        // Draw 3D floor grid lines in subtle blue-grey
        GLES20.glLineWidth(1f)
        GLES20.glUniform4f(lineColor, 0.25f, 0.3f, 0.45f, 0.45f)
        GLES20.glDrawArrays(GLES20.GL_LINES, gridStart, gridCount)

        // Draw composition boundary
        GLES20.glLineWidth(2f)
        GLES20.glUniform4f(lineColor, 1f, 1f, 1f, 0.8f)
        GLES20.glDrawArrays(GLES20.GL_LINES, frameStart, frameCount)

        // Draw Axes: Red X, Green Y, Blue Z
        GLES20.glLineWidth(3.5f)
        GLES20.glUniform4f(lineColor, 1f, 0.25f, 0.25f, 1f) // X Red
        GLES20.glDrawArrays(GLES20.GL_LINES, axesStart, 2)
        GLES20.glUniform4f(lineColor, 0.25f, 1f, 0.35f, 1f) // Y Green
        GLES20.glDrawArrays(GLES20.GL_LINES, axesStart + 2, 2)
        GLES20.glUniform4f(lineColor, 0.25f, 0.55f, 1f, 1f) // Z Blue
        GLES20.glDrawArrays(GLES20.GL_LINES, axesStart + 4, 2)

        // Draw Selected Layer 3D wireframe box
        if (selCount > 0) {
            GLES20.glLineWidth(2.5f)
            GLES20.glUniform4f(lineColor, 1f, 0.35f, 0.5f, 0.95f) // Pink Accent
            GLES20.glDrawArrays(GLES20.GL_LINES, selStart, selCount)
        }

        GLES20.glDisableVertexAttribArray(pr.aPos)
    }

    private fun drawBody(
        p: Project, l: Layer, t: Float, tt: Float, x: Float, y: Float, z: Float,
        rx: Float, ry: Float, rot: Float, sc: Float, a: Float, tp: Prog, op: Prog
    ) {
        curSkx = l.eval(P_SKX, tt)
        curSky = l.eval(P_SKY, tt)
        val gl2d = GLES20.GL_TEXTURE_2D
        when (l.type) {
            T_SHAPE, T_TEXT -> {
                val stroke = quant(l.eval(P_STROKE, tt))
                val shBlur = quant(l.eval(P_SHBLUR, tt))
                val shDx = quant(l.eval(P_SHDX, tt))
                val shDy = quant(l.eval(P_SHDY, tt))
                val grad = quant(l.eval(P_GRAD, tt))
                val key = keyOf(l, stroke, shBlur, shDx, shDy, grad)
                var tx = getTex(key)
                if (tx == null) tx = putTex(key, raster(l, stroke, shBlur, shDx, shDy, grad))
                quad(tp, gl2d, tx.id, l, x, y, z, rx, ry, rot, sc, tx.qw, tx.qh, a, false, ident)
            }
            T_IMAGE -> {
                val file = pathOf(p, l) ?: return
                val key = 0x1000000000000000L xor file.hashCode().toLong()
                var tx = getTex(key)
                if (tx == null) {
                    val bmp = MediaCache.image(file) ?: return
                    tx = putTex(key, Made(bmp, 1f, 1f, false))
                }
                quad(tp, gl2d, tx.id, l, x, y, z, rx, ry, rot, sc, l.w, l.h, a, false, ident)
            }
            T_VIDEO -> {
                val file = pathOf(p, l) ?: return
                var dec = decoders[l.id]
                if (dec == null) {
                    dec = VideoDecoder(file, offline)
                    decoders[l.id] = dec
                }
                val srcUs = ((max(0f, (t - l.start) * l.speed) + l.trim) * 1_000_000f).toLong()
                if (dec.frameAt(srcUs, p.playing)) {
                    quad(op, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, dec.texId, l, x, y, z, rx, ry, rot, sc, l.w, l.h, a, true, dec.texMatrix)
                }
            }
            T_PARTICLES -> drawParticles(l, t, x, y, sc, rot, a, tp)
        }
    }

    /** Particles are computed from time only (no stored state), so scrubbing and export are exact. */
    private fun drawParticles(l: Layer, t: Float, x: Float, y: Float, sc: Float, rot: Float, alpha: Float, tp: Prog) {
        val dotKey = 0x2000000000000000L xor l.color.toLong()
        var tx = getTex(dotKey)
        if (tx == null) tx = putTex(dotKey, dotBitmap(l.color))
        curSkx = 0f
        curSky = 0f
        val pSpeed = l.eval(P_PSPEED, t)
        val pSize = l.eval(P_PSIZE, t)
        val pGravity = l.eval(P_PGRAV, t)
        val pSpread = l.eval(P_PSPREAD, t)
        val pAngle = l.eval(P_PANGLE, t)
        if (NativeFx.ok) {
            partP[0] = t; partP[1] = l.start; partP[2] = l.pLife; partP[3] = x; partP[4] = y
            partP[5] = sc; partP[6] = rot; partP[7] = pAngle; partP[8] = pSpread; partP[9] = pSpeed
            partP[10] = pGravity; partP[11] = pSize; partP[12] = alpha; partP[13] = l.id.toFloat()
            partP[14] = l.pCount.coerceIn(1, 400).toFloat()
            val count = NativeFx.nParticles(partP, partOut)
            for (k in 0 until count) {
                quad(tp, GLES20.GL_TEXTURE_2D, tx.id, l, partOut[k * 4], partOut[k * 4 + 1], 0f, 0f, 0f, 0f, 1f,
                    partOut[k * 4 + 2], partOut[k * 4 + 2], partOut[k * 4 + 3], false, ident)
            }
            return
        }
        val n = l.pCount.coerceIn(1, 400)
        val life = max(0.2f, l.pLife)
        val age0 = t - l.start
        for (i in 0 until n) {
            val age = age0 - i * life / n
            if (age < 0f) continue
            val cyc = floor(age / life).toInt()
            val a = age - cyc * life
            val u = a / life
            val h1 = hash(i, cyc, l.id)
            val h2 = hash(i, cyc, l.id + 7)
            val h3 = hash(i, cyc, l.id + 13)
            val ang = Math.toRadians((pAngle + rot + (h1 - 0.5f) * pSpread).toDouble())
            val spd = pSpeed * sc * (0.5f + h2)
            val px = x + (cos(ang) * spd * a).toFloat()
            val py = y + (sin(ang) * spd * a).toFloat() + 0.5f * pGravity * sc * a * a
            val size = pSize * sc * (0.4f + 0.6f * (1f - u)) * (0.6f + 0.8f * h3)
            quad(tp, GLES20.GL_TEXTURE_2D, tx.id, l, px, py, 0f, 0f, 0f, 0f, 1f, size, size, alpha * (1f - u), false, ident)
        }
    }
}
