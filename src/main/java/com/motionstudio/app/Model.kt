package com.motionstudio.app

import android.graphics.Color
import android.graphics.Typeface
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

val C_INK = 0xFF101118.toInt()
val C_PANEL = 0xFF171923.toInt()
val C_PANEL2 = 0xFF202330.toInt()
val C_LINE = 0xFF2C3040.toInt()
val C_TEXT = 0xFFECEEF5.toInt()
val C_DIM = 0xFF8D92A8.toInt()
val C_ACCENT = 0xFFFF5A7A.toInt()
val C_KEY = 0xFFFFC247.toInt()
val C_GREEN = 0xFF00F5A0.toInt()

/** Layer colour tags shown in the timeline (index 0 = none). */
val TAG_COLORS = intArrayOf(
    0, 0xFFEF4444.toInt(), 0xFFEC4899.toInt(), 0xFFF97316.toInt(),
    0xFFFACC15.toInt(), 0xFF2DD4BF.toInt(), 0xFF38BDF8.toInt()
)

object Fonts {
    val names = arrayOf("Sans", "Sans Bold", "Serif", "Mono", "Condensed", "Light", "Casual", "Script")
    private val cache = HashMap<Int, Typeface>()

    fun face(i: Int): Typeface = cache.getOrPut(i) {
        when (i) {
            1 -> Typeface.create("sans-serif", Typeface.BOLD)
            2 -> Typeface.create("serif", Typeface.BOLD)
            3 -> Typeface.create("monospace", Typeface.BOLD)
            4 -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            5 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            6 -> Typeface.create("casual", Typeface.NORMAL)
            7 -> Typeface.create("cursive", Typeface.NORMAL)
            else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
}

const val P_X = 0
const val P_Y = 1
const val P_SCALE = 2
const val P_ROT = 3
const val P_ALPHA = 4
const val P_Z = 5
const val P_RX = 6
const val P_RY = 7
const val P_SKX = 8
const val P_SKY = 9
const val P_STROKE = 10
const val P_SHBLUR = 11
const val P_SHDX = 12
const val P_SHDY = 13
const val P_GRAD = 14
const val P_PSPEED = 15
const val P_PSIZE = 16
const val P_PGRAV = 17
const val P_PSPREAD = 18
const val P_COUNT = 19

const val T_TEXT = 0
const val T_SHAPE = 1
const val T_IMAGE = 3
const val T_VIDEO = 4
const val T_CAMERA = 5
const val T_PARTICLES = 6

fun fmtTime(t: Float): String {
    val total = (t * 30f + 0.5f).toInt()
    return String.format("%02d:%02d:%02d", total / 1800, (total / 30) % 60, total % 30)
}

object Ease {
    val names = arrayOf("Linear", "Ease in", "Ease out", "Ease in-out", "Overshoot", "Bounce", "Custom")
    const val CUSTOM = 6

    /** Bezier handles (x1, y1, x2, y2) that draw a preset in the graph editor. */
    fun handles(i: Int): FloatArray = when (i) {
        1 -> floatArrayOf(0.55f, 0.055f, 0.675f, 0.19f)
        2 -> floatArrayOf(0.215f, 0.61f, 0.355f, 1f)
        3 -> floatArrayOf(0.645f, 0.045f, 0.355f, 1f)
        4 -> floatArrayOf(0.34f, 1.56f, 0.64f, 1f)
        else -> floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f)
    }

    /** y on a cubic bezier from (0,0) to (1,1) with handles (x1,y1) and (x2,y2), for a given x (0..1). */
    fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var s = x
        for (i in 0 until 18) {
            val u = 1f - s
            val sx = 3f * u * u * s * x1 + 3f * u * s * s * x2 + s * s * s
            if (sx < x) lo = s else hi = s
            s = (lo + hi) * 0.5f
        }
        val u = 1f - s
        return 3f * u * u * s * y1 + 3f * u * s * s * y2 + s * s * s
    }

    fun at(i: Int, t: Float): Float = when (i) {
        1 -> t * t * t
        2 -> 1f - (1f - t).pow(3)
        3 -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
        4 -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
        }
        5 -> bounce(t)
        else -> t
    }

    private fun bounce(x: Float): Float {
        var t = x
        val n = 7.5625f
        val d = 2.75f
        return if (t < 1f / d) {
            n * t * t
        } else if (t < 2f / d) {
            t -= 1.5f / d
            n * t * t + 0.75f
        } else if (t < 2.5f / d) {
            t -= 2.25f / d
            n * t * t + 0.9375f
        } else {
            t -= 2.625f / d
            n * t * t + 0.984375f
        }
    }
}

class Key(var t: Float, var v: Float, var ease: Int) {
    // graph editor handles, used when ease == Ease.CUSTOM
    var x1 = 0.25f
    var y1 = 0.25f
    var x2 = 0.75f
    var y2 = 0.75f
}

/** type: 0 text, 1 shape, 3 image, 4 video */
class Layer(val id: Int, val type: Int, var name: String) {
    var text = "Your text"
    var size = 96f
    var color = Color.WHITE
    var w = 420f
    var h = 260f
    var radius = 28f
    var shape = 0
    var start = 0f
    var end = 5f
    var trim = 0f      // video: offset into the source clip (seconds)
    var srcDur = 0f    // video: source clip length (seconds)
    var media: String? = null

    var fillType = 1          // 0 none, 1 solid, 2 gradient
    var color2 = Color.WHITE
    var gradType = 0          // 0 linear, 1 radial
    var strokeColor = Color.BLACK
    var shadowOn = false
    var shadowColor = 0x99000000.toInt()
    var blend = 0             // 0 normal, 1 multiply, 2 screen, 3 add
    var hidden = false
    var flipH = false
    var flipV = false
    var speed = 1f
    var tag = 0
    var align = 1             // 0 left, 1 centre, 2 right
    var fontIdx = 0
    var muted = false
    var locked = false
    var autoEnd = true        // true: the layer always ends where the composition ends

    var threeD = false        // Z, X/Y rotation and the camera only affect 3D layers
    val wigAmp = FloatArray(P_COUNT)   // wiggle amplitude per property
    var wigFreq = 3f
    var wigSeed = 0f
    var loopMode = 0          // 0 none, 1 cycle, 2 ping-pong (applies to keyframes)
    var pCount = 80           // particles
    var pLife = 2f
    var pAngle = -90f

    // x, y, scale, rotation Z, alpha, z, rotation X, rotation Y
    val base = floatArrayOf(
        0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f,        // x, y, scale, rot Z, alpha, z, rot X, rot Y
        0f, 0f,                                // skew X, skew Y
        0f, 12f, 6f, 6f,                       // stroke width, shadow blur, shadow dx, shadow dy
        0f,                                    // gradient angle
        300f, 40f, 200f, 360f                  // particles: speed, size, gravity, spread
    )
    val keys = Array(P_COUNT) { mutableListOf<Key>() }

    // these values can all be keyframed; the plain properties read and write the base value
    var skewX: Float
        get() = base[P_SKX]
        set(v) { base[P_SKX] = v }
    var skewY: Float
        get() = base[P_SKY]
        set(v) { base[P_SKY] = v }
    var strokeW: Float
        get() = base[P_STROKE]
        set(v) { base[P_STROKE] = v }
    var shadowBlur: Float
        get() = base[P_SHBLUR]
        set(v) { base[P_SHBLUR] = v }
    var shadowDx: Float
        get() = base[P_SHDX]
        set(v) { base[P_SHDX] = v }
    var shadowDy: Float
        get() = base[P_SHDY]
        set(v) { base[P_SHDY] = v }
    var gradAngle: Float
        get() = base[P_GRAD]
        set(v) { base[P_GRAD] = v }
    var pSpeed: Float
        get() = base[P_PSPEED]
        set(v) { base[P_PSPEED] = v }
    var pSize: Float
        get() = base[P_PSIZE]
        set(v) { base[P_PSIZE] = v }
    var pGravity: Float
        get() = base[P_PGRAV]
        set(v) { base[P_PGRAV] = v }
    var pSpread: Float
        get() = base[P_PSPREAD]
        set(v) { base[P_PSPREAD] = v }

    /** Smooth deterministic noise in about -1..1, used by wiggle. */
    private fun noise(x: Float): Float =
        (Math.sin(x.toDouble()) + 0.5 * Math.sin(x * 2.3 + 1.3) + 0.25 * Math.sin(x * 4.1 + 4.1)).toFloat() / 1.75f

    private fun loopTime(p: Int, t: Float): Float {
        val k = keys[p]
        if (loopMode == 0 || k.size < 2) return t
        val t0 = k.first().t
        val len = k.last().t - t0
        if (len <= 1e-3f || t <= t0 + len) return t
        val m = (t - t0) % (if (loopMode == 2) 2f * len else len)
        return t0 + if (m <= len) m else 2f * len - m
    }

    /** Value of a property at time t: keyframes (with optional loop) plus wiggle. */
    fun eval(p: Int, t: Float): Float {
        var v = evalKeys(p, loopTime(p, t))
        val a = wigAmp[p]
        if (a != 0f) v += a * noise(t * wigFreq + p * 31.7f + wigSeed)
        return v
    }

    private fun evalKeys(p: Int, t: Float): Float {
        val k = keys[p]
        if (k.isEmpty()) return base[p]
        if (t <= k.first().t) return k.first().v
        if (t >= k.last().t) return k.last().v
        for (i in 0 until k.size - 1) {
            val a = k[i]
            val b = k[i + 1]
            if (t >= a.t && t <= b.t) {
                val u = (t - a.t) / (b.t - a.t)
                val k = if (a.ease == Ease.CUSTOM) Ease.bezier(a.x1, a.y1, a.x2, a.y2, u) else Ease.at(a.ease, u)
                return a.v + (b.v - a.v) * k
            }
        }
        return k.last().v
    }

    fun keyAt(p: Int, t: Float): Key? = keys[p].firstOrNull { abs(it.t - t) < 0.017f }

    fun upsert(p: Int, t: Float, v: Float, ease: Int) {
        val ex = keyAt(p, t)
        if (ex != null) {
            ex.v = v
        } else {
            keys[p].add(Key(t, v, ease))
            keys[p].sortBy { it.t }
        }
    }

    fun removeKey(p: Int, t: Float) {
        val ex = keyAt(p, t) ?: return
        keys[p].remove(ex)
        if (keys[p].isEmpty()) base[p] = ex.v
    }

    /** Change a property at time t. If it has keyframes, a keyframe is added or updated. */
    fun set(p: Int, t: Float, v: Float, ease: Int) {
        if (keys[p].isEmpty()) base[p] = v else upsert(p, t, v, ease)
    }

    fun visible(t: Float) = t >= start - 1e-4f && t <= end + 1e-4f

    /** Visible in time and not hidden with the eye button. */
    fun shown(t: Float) = !hidden && visible(t)

    /** Used by "paste style": copies how the layer looks, not where it is. */
    fun copyStyleFrom(o: Layer) {
        color = o.color; fillType = o.fillType; color2 = o.color2
        gradType = o.gradType; gradAngle = o.gradAngle
        strokeW = o.strokeW; strokeColor = o.strokeColor
        shadowOn = o.shadowOn; shadowBlur = o.shadowBlur; shadowDx = o.shadowDx
        shadowDy = o.shadowDy; shadowColor = o.shadowColor
        blend = o.blend; base[P_ALPHA] = o.base[P_ALPHA]
    }

    fun duplicate(newId: Int): Layer {
        val c = Layer(newId, type, "$name copy")
        c.text = text; c.size = size; c.color = color; c.w = w; c.h = h
        c.radius = radius; c.shape = shape; c.start = start; c.end = end
        c.trim = trim; c.srcDur = srcDur; c.media = media
        c.copyStyleFrom(this)
        c.hidden = hidden; c.flipH = flipH; c.flipV = flipV; c.skewX = skewX; c.skewY = skewY
        c.speed = speed; c.tag = tag; c.align = align; c.fontIdx = fontIdx; c.muted = muted
        c.text = text; c.size = size
        c.threeD = threeD; c.wigFreq = wigFreq; c.wigSeed = wigSeed; c.loopMode = loopMode
        for (i in 0 until P_COUNT) c.wigAmp[i] = wigAmp[i]
        c.pCount = pCount; c.pLife = pLife; c.pSpeed = pSpeed; c.pSize = pSize
        c.pGravity = pGravity; c.pSpread = pSpread; c.pAngle = pAngle
        for (i in 0 until P_COUNT) {
            c.base[i] = base[i]
            for (k in keys[i]) {
                val nk = Key(k.t, k.v, k.ease)
                nk.x1 = k.x1; nk.y1 = k.y1; nk.x2 = k.x2; nk.y2 = k.y2
                c.keys[i].add(nk)
            }
        }
        c.base[P_X] += 30f
        c.base[P_Y] += 30f
        for (k in c.keys[P_X]) k.v += 30f
        for (k in c.keys[P_Y]) k.v += 30f
        return c
    }
}

class Project(var id: String = UUID.randomUUID().toString().replace("-", "").take(12)) {
    var name = "New project"
    var ratio = "9:16"
    var W = 720
    var H = 1280
    var duration = 5f
    var bg = Color.parseColor("#0D0E14")
    val layers = mutableListOf<Layer>()
    var sel: Layer? = null
    var time = 0f
    var easeIdx = 2
    var nextId = 1
    @Volatile var playing = false   // true while the preview is playing (the video decoder runs ahead)
    var shutter = 0f   // motion blur shutter angle in degrees, 0 = off
    val folders = mutableListOf<String>()   // imported folders shown in the Project panel

    companion object {
        val RATIOS = arrayOf(
            Triple("9:16", 720, 1280), Triple("16:9", 1280, 720),
            Triple("1:1", 900, 900), Triple("4:5", 864, 1080)
        )
    }

    fun resLabel() = "${minOf(W, H)}p"

    fun nextLayerId() = nextId++

    /** Sets the composition length. Layers that follow the composition end stretch or shrink with it. */
    @JvmName("updateDuration")
    fun setDuration(d: Float) {
        duration = d
        for (l in layers) {
            if (l.autoEnd || l.end > d) l.end = d
            if (l.start > d - 0.1f) l.start = maxOf(0f, d - 0.5f)
        }
        time = time.coerceIn(0f, d)
    }

    /** After a layer is deleted: fit the composition to the media that is left (back to 5 s when no media is left). */
    fun refitAfterDelete(removedWasMedia: Boolean) {
        val anchored = layers.filter { !it.autoEnd && it.type != T_CAMERA }
        if (anchored.isNotEmpty()) {
            val nd = anchored.maxOf { it.end }.coerceIn(1f, 300f)
            if (abs(nd - duration) > 0.01f) setDuration(nd)
        } else if (removedWasMedia) {
            setDuration(5f)
        }
    }

    fun newLayer(type: Int): Layer {
        val label = when (type) {
            T_TEXT -> "Text"; T_SHAPE -> "Shape"; T_IMAGE -> "Image"
            T_CAMERA -> "Camera"; T_PARTICLES -> "Particles"; else -> "Video"
        }
        val n = layers.count { it.type == type } + 1
        val l = Layer(nextId++, type, "$label $n")
        l.base[P_X] = W / 2f
        l.base[P_Y] = H / 2f
        l.end = duration
        if (type == T_SHAPE) l.color = Color.parseColor("#FF5A7A")
        if (type == T_PARTICLES) {
            l.w = 220f
            l.h = 220f
            l.blend = 3
            l.color = Color.parseColor("#FFC247")
        }
        return l
    }

    fun changeRatio(label: String) {
        val r = RATIOS.firstOrNull { it.first == label } ?: return
        val rx = r.second.toFloat() / W
        val ry = r.third.toFloat() / H
        for (l in layers) {
            l.base[P_X] *= rx
            l.base[P_Y] *= ry
            for (k in l.keys[P_X]) k.v *= rx
            for (k in l.keys[P_Y]) k.v *= ry
        }
        ratio = r.first
        W = r.second
        H = r.third
    }

    /** Replace the content of this project with another one (used by undo/redo). Name and id stay. */
    fun copyFrom(o: Project) {
        ratio = o.ratio; W = o.W; H = o.H; duration = o.duration; bg = o.bg
        easeIdx = o.easeIdx; nextId = o.nextId; shutter = o.shutter
        layers.clear()
        layers.addAll(o.layers)
    }
}
