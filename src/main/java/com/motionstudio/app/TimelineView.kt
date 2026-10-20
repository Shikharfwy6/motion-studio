package com.motionstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Alight-Motion style timeline: the playhead stays in the centre and the layers scroll under it. */
class TimelineView(context: Context, private val p: Project) : View(context) {
    var onSeek: ((Float) -> Unit)? = null
    var onSelect: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onToggleEye: ((Layer) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val rulerH = dp(34f)
    private val rowH = dp(42f)
    private val pps = dp(80f)
    private var scrollY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = C_LINE
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f) }
    private val path = Path()
    private val rf = RectF()

    private var mode = 0 // 1 scrub, 2 move, 3 trim start, 4 trim end
    private var lx = 0f
    private var ly = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var hitLayer: Layer? = null
    private var o0 = 0f
    private var o1 = 0f
    private var o2 = 0f
    private var barChanged = false

    private fun cx() = width / 2f
    private fun xOf(t: Float) = cx() + (t - p.time) * pps

    /** While a layer is selected only that layer's row is shown, like Alight Motion. */
    private fun rows(): List<Layer> {
        val s = p.sel
        return if (s != null && p.layers.contains(s)) listOf(s) else p.layers.asReversed()
    }

    private fun typeColor(l: Layer): Int = if (l.tag != 0) TAG_COLORS[l.tag] else when (l.type) {
        T_TEXT -> 0xFF3B82F6.toInt()
        T_SHAPE -> 0xFFEC4899.toInt()
        T_IMAGE -> 0xFF22C55E.toInt()
        else -> 0xFF8B5CF6.toInt()
    }

    private fun clampScroll() {
        val maxY = max(0f, p.layers.size * rowH - (height - rulerH) + dp(60f))
        scrollY = scrollY.coerceIn(0f, maxY)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C_PANEL)
        clampScroll()
        val rows = rows()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = cx()

        // rows
        c.save()
        c.clipRect(0f, rulerH, w, h)
        for ((i, l) in rows.withIndex()) {
            val top = rulerH + i * rowH - scrollY
            if (top > h || top + rowH < rulerH) continue
            val x0 = xOf(l.start)
            val x1 = xOf(l.end)
            val isSel = l === p.sel
            val col = typeColor(l)
            fill.color = col
            fill.alpha = if (isSel) 255 else 150
            rf.set(x0, top + dp(5f), max(x1, x0 + dp(6f)), top + rowH - dp(5f))
            c.drawRoundRect(rf, dp(8f), dp(8f), fill)
            if (isSel) {
                stroke.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(rf, dp(8f), dp(8f), stroke)
                fill.color = 0xFFFFFFFF.toInt()
                fill.alpha = 255
                val hy = (rf.top + rf.bottom) / 2f
                c.drawRoundRect(rf.left + dp(3f), hy - dp(8f), rf.left + dp(6f), hy + dp(8f), dp(2f), dp(2f), fill)
                c.drawRoundRect(rf.right - dp(6f), hy - dp(8f), rf.right - dp(3f), hy + dp(8f), dp(2f), dp(2f), fill)
            }
            // eye button (hide / show) and collapse chevron
            val ey = top + rowH / 2f
            fill.color = 0xFF2B2F42.toInt()
            fill.alpha = 255
            c.drawRoundRect(dp(4f), top + dp(6f), dp(40f), top + rowH - dp(6f), dp(10f), dp(10f), fill)
            stroke.color = if (l.hidden) C_DIM else C_TEXT
            stroke.strokeWidth = dp(1.6f)
            rf.set(dp(12f), ey - dp(5f), dp(32f), ey + dp(5f))
            c.drawOval(rf, stroke)
            fill.color = stroke.color
            c.drawCircle(dp(22f), ey, dp(2.5f), fill)
            if (l.hidden) c.drawLine(dp(12f), ey + dp(7f), dp(32f), ey - dp(7f), stroke)
            if (isSel) {
                path.reset()
                path.moveTo(dp(58f), ey - dp(7f))
                path.lineTo(dp(51f), ey)
                path.lineTo(dp(58f), ey + dp(7f))
                c.drawPath(path, stroke)
            }
            stroke.strokeWidth = dp(2f)
            rf.set(max(x0, dp(44f)), top + dp(5f), max(x1, x0 + dp(6f)), top + rowH - dp(5f))
            // name
            c.save()
            c.clipRect(rf)
            txt.color = 0xFFFFFFFF.toInt()
            c.drawText(l.name, max(rf.left, 0f) + dp(12f), top + rowH / 2f + dp(4f), txt)
            c.restore()
            // keyframes
            val times = HashSet<Float>()
            for (ks in l.keys) for (k in ks) times.add(k.t)
            fill.color = C_KEY
            fill.alpha = 255
            for (t in times) {
                val kx = xOf(t)
                val ky = rf.bottom - dp(5f)
                val r = dp(4.5f)
                path.reset()
                path.moveTo(kx, ky - r)
                path.lineTo(kx + r, ky)
                path.lineTo(kx, ky + r)
                path.lineTo(kx - r, ky)
                path.close()
                c.drawPath(path, fill)
            }
        }
        c.restore()

        // outside of the project range
        fill.color = 0x99000000.toInt()
        fill.alpha = 255
        val z0 = xOf(0f)
        val z1 = xOf(p.duration)
        if (z0 > 0f) c.drawRect(0f, rulerH, min(z0, w), h, fill)
        if (z1 < w) c.drawRect(max(z1, 0f), rulerH, w, h, fill)

        // ruler
        fill.color = C_PANEL2
        fill.alpha = 255
        c.drawRect(0f, 0f, w, rulerH, fill)
        txt.color = C_DIM
        val first = max(0, floor(p.time - cx / pps).toInt())
        val last = min(ceil(p.duration).toInt(), ceil(p.time + cx / pps).toInt())
        for (s in first..last) {
            val x = xOf(s.toFloat())
            c.drawLine(x, rulerH * 0.62f, x, rulerH, line)
            c.drawText("${s}s", x + dp(3f), rulerH - dp(4f), txt)
        }

        // playhead and time chip
        stroke.color = 0xFFFFFFFF.toInt()
        c.drawLine(cx, 0f, cx, h, stroke)
        val label = fmtTime(p.time)
        txt.color = C_TEXT
        val tw = txt.measureText(label)
        fill.color = 0xFF2B2F42.toInt()
        c.drawRoundRect(cx - tw / 2f - dp(8f), dp(3f), cx + tw / 2f + dp(8f), dp(25f), dp(8f), dp(8f), fill)
        c.drawText(label, cx - tw / 2f, dp(18f), txt)
    }

    private fun rowAt(y: Float): Layer? {
        val rows = rows()
        val i = ((y - rulerH + scrollY) / rowH).toInt()
        return if (y >= rulerH && i >= 0 && i < rows.size) rows[i] else null
    }

    private fun tap(x: Float, l: Layer?) {
        if (l == null) {
            if (p.sel != null) {
                p.sel = null
                onSelect?.invoke()
            }
            return
        }
        if (x < dp(42f)) {
            onToggleEye?.invoke(l)
            invalidate()
            return
        }
        if (x < dp(70f) && l === p.sel) {
            p.sel = null
            onSelect?.invoke()
            return
        }
        var best = -1f
        var bd = dp(14f)
        for (ks in l.keys) for (k in ks) {
            val dist = abs(xOf(k.t) - x)
            if (dist < bd) {
                bd = dist
                best = k.t
            }
        }
        if (p.sel !== l) {
            p.sel = l
            onSelect?.invoke()
        }
        if (best >= 0f) onSeek?.invoke(best)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downY = e.y
                lx = e.x
                ly = e.y
                moved = false
                barChanged = false
                mode = 1
                val l = if (e.y >= rulerH) rowAt(e.y) else null
                hitLayer = l
                if (l != null && l === p.sel) {
                    val x0 = xOf(l.start)
                    val x1 = xOf(l.end)
                    val edge = dp(28f)
                    if (e.x >= x0 && e.x <= max(x1, x0 + dp(6f))) {
                        mode = if (e.x < x0 + edge) 3 else if (e.x > x1 - edge) 4 else 2
                        o0 = l.start
                        o1 = l.end
                        o2 = l.trim
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > dp(6f) || abs(e.y - downY) > dp(6f)) moved = true
                val l = hitLayer
                val dt = (e.x - downX) / pps
                when (mode) {
                    1 -> {
                        onSeek?.invoke((p.time - (e.x - lx) / pps).coerceIn(0f, p.duration))
                        scrollY -= e.y - ly
                        clampScroll()
                        invalidate()
                    }
                    2 -> if (moved && l != null) {
                        val shift = dt.coerceIn(-o0, p.duration - o1)
                        l.start = o0 + shift
                        l.end = o1 + shift
                        barChanged = true
                        onChanged?.invoke()
                        invalidate()
                    }
                    3 -> if (moved && l != null) {
                        var ns = (o0 + dt).coerceIn(0f, o1 - 0.2f)
                        if (l.type == T_VIDEO) ns = max(ns, o0 - o2)
                        l.start = ns
                        if (l.type == T_VIDEO) l.trim = o2 + (ns - o0)
                        barChanged = true
                        onChanged?.invoke()
                        invalidate()
                    }
                    4 -> if (moved && l != null) {
                        var ne = (o1 + dt).coerceIn(o0 + 0.2f, p.duration)
                        if (l.type == T_VIDEO && l.srcDur > 0f) ne = min(ne, l.start + (l.srcDur - l.trim))
                        l.end = ne
                        barChanged = true
                        onChanged?.invoke()
                        invalidate()
                    }
                }
                lx = e.x
                ly = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) tap(e.x, hitLayer)
                if (barChanged) onCommit?.invoke()
                barChanged = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (barChanged) onCommit?.invoke()
                barChanged = false
            }
        }
        return true
    }
}
