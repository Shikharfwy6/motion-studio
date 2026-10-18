package com.motionstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class EditorView(context: Context, private val p: Project) : View(context) {
    var onSelect: (() -> Unit)? = null
    var onEdit: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    private var mode = 0 // 0 none, 1 drag, 2 pinch
    private var sx = 0f
    private var sy = 0f
    private var bx = 0f
    private var by = 0f
    private var downRx = 0f
    private var downRy = 0f
    private var moved = false
    private var changed = false
    private var d0 = 1f
    private var a0 = 0f
    private var s0 = 1f
    private var r0 = 0f

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF0A0B10.toInt())
        val m = 16f
        sc = min((width - 2 * m) / p.W, (height - 2 * m) / p.H)
        ox = (width - p.W * sc) / 2f
        oy = (height - p.H * sc) / 2f
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        c.clipRect(0f, 0f, p.W.toFloat(), p.H.toFloat())
        Renderer.draw(c, p, p.time, false)
        c.restore()

        val l = p.sel
        if (l != null && l.visible(p.time)) {
            val s = l.eval(P_SCALE, p.time)
            val sz = Renderer.sizeOf(l)
            val hw = sz[0] * s * sc / 2f
            val hh = sz[1] * s * sc / 2f
            c.save()
            c.translate(ox + l.eval(P_X, p.time) * sc, oy + l.eval(P_Y, p.time) * sc)
            c.rotate(l.eval(P_ROT, p.time))
            c.drawRect(-hw, -hh, hw, hh, outline)
            c.restore()
        }
    }

    private fun toProj(x: Float, y: Float) = floatArrayOf((x - ox) / sc, (y - oy) / sc)

    private fun hit(x: Float, y: Float): Layer? {
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            if (!l.visible(p.time)) continue
            var s = l.eval(P_SCALE, p.time)
            if (abs(s) < 1e-3f) s = 1e-3f
            val dx = x - l.eval(P_X, p.time)
            val dy = y - l.eval(P_Y, p.time)
            val a = -Math.toRadians(l.eval(P_ROT, p.time).toDouble()).toFloat()
            val rx = (dx * cos(a) - dy * sin(a)) / s
            val ry = (dx * sin(a) + dy * cos(a)) / s
            val sz = Renderer.sizeOf(l)
            if (abs(rx) <= max(sz[0] / 2f, 50f) && abs(ry) <= max(sz[1] / 2f, 50f)) return l
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val pt = toProj(e.x, e.y)
                val h = hit(pt[0], pt[1])
                if (h != null && h !== p.sel) {
                    p.sel = h
                    onSelect?.invoke()
                }
                mode = if (h != null) 1 else 0
                moved = false
                changed = false
                downRx = e.x
                downRy = e.y
                sx = pt[0]
                sy = pt[1]
                val l = p.sel
                if (h != null && l != null) {
                    bx = l.eval(P_X, p.time)
                    by = l.eval(P_Y, p.time)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val l = p.sel
                if (e.pointerCount == 2 && l != null) {
                    mode = 2
                    d0 = max(1f, hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)))
                    a0 = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                    s0 = l.eval(P_SCALE, p.time)
                    r0 = l.eval(P_ROT, p.time)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downRx, e.y - downRy) > 12f) moved = true
                val l = p.sel
                if (l != null) {
                    if (mode == 1 && e.pointerCount == 1 && moved) {
                        val pt = toProj(e.x, e.y)
                        l.set(P_X, p.time, bx + (pt[0] - sx), p.easeIdx)
                        l.set(P_Y, p.time, by + (pt[1] - sy), p.easeIdx)
                        changed = true
                        invalidate()
                        onEdit?.invoke()
                    } else if (mode == 2 && e.pointerCount >= 2) {
                        val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                        val ang = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                        l.set(P_SCALE, p.time, (s0 * d / d0).coerceIn(0f, 5f), p.easeIdx)
                        val deg = Math.toDegrees((ang - a0).toDouble()).toFloat()
                        l.set(P_ROT, p.time, r0 + deg, p.easeIdx)
                        changed = true
                        invalidate()
                        onEdit?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == 2) {
                    mode = 0
                    if (changed) onCommit?.invoke()
                    changed = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && mode == 0 && !moved && p.sel != null) {
                    p.sel = null
                    onSelect?.invoke()
                }
                if (changed) onCommit?.invoke()
                mode = 0
                changed = false
                invalidate()
            }
        }
        return true
    }
}
