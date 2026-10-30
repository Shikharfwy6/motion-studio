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

    /** The GPU preview that sits under this overlay; it is redrawn whenever this view is invalidated. */
    var gl: GLEditorView? = null

    var showGrid = false
    var showGuides = false
    var tool = 0   // 0 V select, 3 W rotate, 4 C camera orbit, 5 Q add shape, 7 T add text
    var onTap: ((Int, Float, Float) -> Unit)? = null
    private var camRx0 = 0f
    private var camRy0 = 0f
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    override fun invalidate() {
        super.invalidate()
        gl?.requestRender()
    }

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
        val m = 16f
        sc = min((width - 2 * m) / p.W, (height - 2 * m) / p.H)
        ox = (width - p.W * sc) / 2f
        oy = (height - p.H * sc) / 2f
        if (showGrid) {
            val gw = p.W * sc
            val gh = p.H * sc
            for (i in 1..2) {
                c.drawLine(ox + gw * i / 3f, oy, ox + gw * i / 3f, oy + gh, guide)
                c.drawLine(ox, oy + gh * i / 3f, ox + gw, oy + gh * i / 3f, guide)
            }
        }
        if (showGuides) {
            val gw = p.W * sc
            val gh = p.H * sc
            c.drawRect(ox + gw * 0.05f, oy + gh * 0.05f, ox + gw * 0.95f, oy + gh * 0.95f, guide)
            c.drawRect(ox + gw * 0.10f, oy + gh * 0.10f, ox + gw * 0.90f, oy + gh * 0.90f, guide)
            c.drawLine(ox + gw / 2f - 12f, oy + gh / 2f, ox + gw / 2f + 12f, oy + gh / 2f, guide)
            c.drawLine(ox + gw / 2f, oy + gh / 2f - 12f, ox + gw / 2f, oy + gh / 2f + 12f, guide)
        }

        val l = p.sel
        if (l != null && l.shown(p.time) && l.type != T_CAMERA) {
            val s = l.eval(P_SCALE, p.time)
            val sz = Renderer.sizeOf(l)
            val hw = sz[0] * s * sc / 2f
            val hh = sz[1] * s * sc / 2f
            val lx = ox + l.eval(P_X, p.time) * sc
            val ly = oy + l.eval(P_Y, p.time) * sc

            if (!p.view3d) {
                c.save()
                c.translate(lx, ly)
                c.rotate(l.eval(P_ROT, p.time))
                c.drawRect(-hw, -hh, hw, hh, outline)
                c.restore()
            } else {
                // After Effects 3D Transform Gizmo (Manipulator)
                draw3dGizmo(c, lx, ly, l)
            }
        }

        if (p.view3d) {
            draw3dViewHud(c)
        }
    }

    private val gizmoX = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3344.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val gizmoY = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val gizmoZ = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2979FF.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val gizmoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD171923.toInt(); style = Paint.Style.FILL }
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER }
    private val rfHud = android.graphics.RectF()
    private var gizmoMode = 0 // 1 X, 2 Y, 3 Z, 4 RotX, 5 RotY, 6 RotZ
    private var gizmoStartVal = 0f
    private var gizmoDownX = 0f
    private var gizmoDownY = 0f

    private fun draw3dGizmo(c: Canvas, gx: Float, gy: Float, l: Layer) {
        val len = 110f
        // Center ring
        gizmoFill.color = Color.WHITE
        c.drawCircle(gx, gy, 8f, gizmoFill)

        // X Arrow (Red) -> Right
        c.drawLine(gx, gy, gx + len, gy, gizmoX)
        val pX = android.graphics.Path()
        pX.moveTo(gx + len + 18f, gy)
        pX.lineTo(gx + len, gy - 8f)
        pX.lineTo(gx + len, gy + 8f)
        pX.close()
        gizmoFill.color = 0xFFFF3344.toInt()
        c.drawPath(pX, gizmoFill)
        hudText.color = 0xFFFF3344.toInt()
        hudText.textSize = 22f
        c.drawText("X", gx + len + 30f, gy + 7f, hudText)

        // Y Arrow (Green) -> Down
        c.drawLine(gx, gy, gx, gy + len, gizmoY)
        val pY = android.graphics.Path()
        pY.moveTo(gx, gy + len + 18f)
        pY.lineTo(gx - 8f, gy + len)
        pY.lineTo(gx + 8f, gy + len)
        pY.close()
        gizmoFill.color = 0xFF00E676.toInt()
        c.drawPath(pY, gizmoFill)
        hudText.color = 0xFF00E676.toInt()
        c.drawText("Y", gx, gy + len + 34f, hudText)

        // Z Arrow (Blue) -> Depth Up-Left
        val zx = gx - len * 0.7f
        val zy = gy - len * 0.7f
        c.drawLine(gx, gy, zx, zy, gizmoZ)
        val pZ = android.graphics.Path()
        pZ.moveTo(zx - 12f, zy - 12f)
        pZ.lineTo(zx - 2f, zy + 10f)
        pZ.lineTo(zx + 10f, zy - 2f)
        pZ.close()
        gizmoFill.color = 0xFF2979FF.toInt()
        c.drawPath(pZ, gizmoFill)
        hudText.color = 0xFF2979FF.toInt()
        c.drawText("Z", zx - 18f, zy - 14f, hudText)

        // Rotation Arc Rings
        val arcRf = android.graphics.RectF(gx - 55f, gy - 55f, gx + 55f, gy + 55f)
        c.drawArc(arcRf, -90f, 90f, false, gizmoX)
        c.drawArc(arcRf, 0f, 90f, false, gizmoY)
        c.drawArc(arcRf, 90f, 90f, false, gizmoZ)
    }

    private fun draw3dViewHud(c: Canvas) {
        val names = arrayOf("Orbit", "Front", "Top", "Right", "Reset")
        val btnW = 100f
        val startX = (width - names.size * (btnW + 10f)) / 2f
        val topY = 16f
        for ((idx, name) in names.withIndex()) {
            val bx = startX + idx * (btnW + 10f)
            rfHud.set(bx, topY, bx + btnW, topY + 44f)
            c.drawRoundRect(rfHud, 14f, 14f, hudBg)
            hudText.color = Color.WHITE
            hudText.textSize = 20f
            c.drawText(name, bx + btnW / 2f, topY + 30f, hudText)
        }
    }

    private fun toProj(x: Float, y: Float) = floatArrayOf((x - ox) / sc, (y - oy) / sc)

    private fun hit(x: Float, y: Float): Layer? {
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            if (!l.shown(p.time) || l.type == T_CAMERA || l.locked) continue
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

    private var oX = 0f
    private var oY = 0f
    private var oDist = 0f

    /** 3D view: 3D Transform Gizmo drag + camera orbit/pinch zoom + HUD view presets. */
    private fun orbitTouch(e: MotionEvent): Boolean {
        val l = p.sel
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                oX = e.x
                oY = e.y
                gizmoDownX = e.x
                gizmoDownY = e.y
                gizmoMode = 0

                // Check 3D HUD buttons tap
                if (e.y in 10f..70f) {
                    val names = arrayOf("Orbit", "Front", "Top", "Right", "Reset")
                    val btnW = 100f
                    val startX = (width - names.size * (btnW + 10f)) / 2f
                    for ((idx, _) in names.withIndex()) {
                        val bx = startX + idx * (btnW + 10f)
                        if (e.x in bx..(bx + btnW)) {
                            when (idx) {
                                0 -> { p.orbitRx = -22f; p.orbitRy = 28f }
                                1 -> { p.orbitRx = 0f; p.orbitRy = 0f }
                                2 -> { p.orbitRx = 89f; p.orbitRy = 0f }
                                3 -> { p.orbitRx = 0f; p.orbitRy = 90f }
                                4 -> { p.orbitRx = -22f; p.orbitRy = 28f; p.orbitZoom = 0.55f }
                            }
                            invalidate()
                            return true
                        }
                    }
                }

                // Check 3D Gizmo hit on selected layer
                if (l != null && l.shown(p.time) && l.type != T_CAMERA) {
                    val gx = ox + l.eval(P_X, p.time) * sc
                    val gy = oy + l.eval(P_Y, p.time) * sc
                    val dX = hypot(e.x - (gx + 110f), e.y - gy)
                    val dY = hypot(e.x - gx, e.y - (gy + 110f))
                    val dZ = hypot(e.x - (gx - 77f), e.y - (gy - 77f))
                    val dArc = hypot(e.x - gx, e.y - gy)

                    if (dX < 48f) {
                        gizmoMode = 1 // X drag
                        gizmoStartVal = l.eval(P_X, p.time)
                        return true
                    } else if (dY < 48f) {
                        gizmoMode = 2 // Y drag
                        gizmoStartVal = l.eval(P_Y, p.time)
                        return true
                    } else if (dZ < 48f) {
                        gizmoMode = 3 // Z drag
                        gizmoStartVal = l.eval(P_Z, p.time)
                        return true
                    } else if (abs(dArc - 55f) < 26f) {
                        val ang = Math.toDegrees(atan2(e.y - gy, e.x - gx).toDouble()).toFloat()
                        if (ang in -90f..0f) {
                            gizmoMode = 4 // Rot X
                            gizmoStartVal = l.eval(P_RX, p.time)
                        } else if (ang in 0f..90f) {
                            gizmoMode = 5 // Rot Y
                            gizmoStartVal = l.eval(P_RY, p.time)
                        } else {
                            gizmoMode = 6 // Rot Z
                            gizmoStartVal = l.eval(P_ROT, p.time)
                        }
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) oDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                if (i < e.pointerCount) {
                    oX = e.getX(i)
                    oY = e.getY(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gizmoMode != 0 && l != null) {
                    when (gizmoMode) {
                        1 -> l.set(P_X, p.time, gizmoStartVal + (e.x - gizmoDownX) / sc, p.easeIdx)
                        2 -> l.set(P_Y, p.time, gizmoStartVal + (e.y - gizmoDownY) / sc, p.easeIdx)
                        3 -> {
                            if (!l.threeD) l.threeD = true
                            l.set(P_Z, p.time, gizmoStartVal - (e.y - gizmoDownY) * 3f, p.easeIdx)
                        }
                        4 -> {
                            if (!l.threeD) l.threeD = true
                            l.set(P_RX, p.time, gizmoStartVal + (e.y - gizmoDownY) * 0.6f, p.easeIdx)
                        }
                        5 -> {
                            if (!l.threeD) l.threeD = true
                            l.set(P_RY, p.time, gizmoStartVal + (e.x - gizmoDownX) * 0.6f, p.easeIdx)
                        }
                        6 -> l.set(P_ROT, p.time, gizmoStartVal + (e.x - gizmoDownX) * 0.6f, p.easeIdx)
                    }
                    onEdit?.invoke()
                    invalidate()
                    return true
                }
                if (e.pointerCount >= 2) {
                    val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    if (oDist > 0f) p.orbitZoom = (p.orbitZoom * d / oDist).coerceIn(0.15f, 2.5f)
                    oDist = d
                } else {
                    p.orbitRy += (e.x - oX) * 0.35f
                    p.orbitRx = (p.orbitRx + (e.y - oY) * 0.35f).coerceIn(-89f, 89f)
                    oX = e.x
                    oY = e.y
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gizmoMode != 0) {
                    gizmoMode = 0
                    onCommit?.invoke()
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (p.view3d) return orbitTouch(e)
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
                val sl0 = p.sel
                if (tool == 3 && sl0 != null && sl0.type != T_CAMERA) {
                    mode = 3
                    a0 = atan2(pt[1] - sl0.eval(P_Y, p.time), pt[0] - sl0.eval(P_X, p.time))
                    r0 = sl0.eval(P_ROT, p.time)
                } else if (tool == 4) {
                    mode = 4
                    val cam = p.layers.lastOrNull { it.type == T_CAMERA }
                    camRx0 = cam?.eval(P_RX, p.time) ?: 0f
                    camRy0 = cam?.eval(P_RY, p.time) ?: 0f
                } else if (tool == 5 || tool == 7) {
                    mode = 0
                }
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
                if (mode == 4 && moved) {
                    val cam = p.layers.lastOrNull { it.type == T_CAMERA }
                    if (cam != null) {
                        cam.set(P_RY, p.time, camRy0 + (e.x - downRx) * 0.3f, p.easeIdx)
                        cam.set(P_RX, p.time, camRx0 - (e.y - downRy) * 0.3f, p.easeIdx)
                        changed = true
                        invalidate()
                        onEdit?.invoke()
                    }
                } else if (mode == 3 && l != null) {
                    val pt = toProj(e.x, e.y)
                    val ang = atan2(pt[1] - l.eval(P_Y, p.time), pt[0] - l.eval(P_X, p.time))
                    l.set(P_ROT, p.time, r0 + Math.toDegrees((ang - a0).toDouble()).toFloat(), p.easeIdx)
                    changed = true
                    invalidate()
                    onEdit?.invoke()
                } else if (l != null) {
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
                if (e.actionMasked == MotionEvent.ACTION_UP && !moved && (tool == 5 || tool == 7)) {
                    val pt = toProj(e.x, e.y)
                    onTap?.invoke(tool, pt[0], pt[1])
                } else if (e.actionMasked == MotionEvent.ACTION_UP && mode == 0 && !moved && p.sel != null) {
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
