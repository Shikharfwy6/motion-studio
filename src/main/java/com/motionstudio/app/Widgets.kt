package com.motionstudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

class IconView(context: Context, var kind: Int) : View(context) {
    var tint = C_TEXT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    companion object {
        const val BACK = 0
        const val UNDO = 1
        const val REDO = 2
        const val START = 3
        const val END = 4
        const val PLAY = 5
        const val PAUSE = 6
        const val DUP = 7
        const val TRASH = 8
        const val PLUS = 9
        const val CLOSE = 10
        const val TUNE = 11
        const val EXPORT = 12
        const val DOTS = 13
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 24f
        c.save()
        c.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        c.scale(s, s)
        paint.color = tint
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        path.reset()
        if (kind == REDO || kind == END) {
            c.translate(24f, 0f)
            c.scale(-1f, 1f)
        }
        when (kind) {
            BACK -> {
                path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f)
                c.drawPath(path, paint)
            }
            UNDO, REDO -> {
                path.moveTo(4f, 10f); path.lineTo(14f, 10f)
                path.cubicTo(19f, 10f, 19f, 19f, 14f, 19f); path.lineTo(9f, 19f)
                c.drawPath(path, paint)
                path.reset()
                path.moveTo(9f, 5f); path.lineTo(4f, 10f); path.lineTo(9f, 15f)
                c.drawPath(path, paint)
            }
            START, END -> {
                c.drawLine(6f, 5f, 6f, 19f, paint)
                paint.style = Paint.Style.FILL
                path.moveTo(19f, 5f); path.lineTo(9f, 12f); path.lineTo(19f, 19f); path.close()
                c.drawPath(path, paint)
            }
            PLAY -> {
                paint.style = Paint.Style.FILL
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close()
                c.drawPath(path, paint)
            }
            PAUSE -> {
                paint.style = Paint.Style.FILL
                c.drawRect(7f, 5f, 10f, 19f, paint)
                c.drawRect(14f, 5f, 17f, 19f, paint)
            }
            DUP -> {
                c.drawRect(4f, 4f, 15f, 15f, paint)
                c.drawRect(9f, 9f, 20f, 20f, paint)
            }
            TRASH -> {
                c.drawLine(5f, 7f, 19f, 7f, paint)
                path.moveTo(9f, 7f); path.lineTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(15f, 7f)
                path.moveTo(7f, 7f); path.lineTo(8f, 20f); path.lineTo(16f, 20f); path.lineTo(17f, 7f)
                c.drawPath(path, paint)
            }
            PLUS -> {
                paint.strokeWidth = 2.6f
                c.drawLine(12f, 5f, 12f, 19f, paint)
                c.drawLine(5f, 12f, 19f, 12f, paint)
            }
            CLOSE -> {
                c.drawLine(6f, 6f, 18f, 18f, paint)
                c.drawLine(18f, 6f, 6f, 18f, paint)
            }
            TUNE -> {
                c.drawLine(4f, 7f, 20f, 7f, paint)
                c.drawLine(4f, 12f, 20f, 12f, paint)
                c.drawLine(4f, 17f, 20f, 17f, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(9f, 7f, 2.6f, paint)
                c.drawCircle(15f, 12f, 2.6f, paint)
                c.drawCircle(8f, 17f, 2.6f, paint)
            }
            EXPORT -> {
                path.moveTo(6f, 11f); path.lineTo(6f, 20f); path.lineTo(18f, 20f); path.lineTo(18f, 11f)
                c.drawPath(path, paint)
                path.reset()
                c.drawLine(12f, 4f, 12f, 15f, paint)
                path.moveTo(8f, 8f); path.lineTo(12f, 4f); path.lineTo(16f, 8f)
                c.drawPath(path, paint)
            }
            DOTS -> {
                paint.style = Paint.Style.FILL
                c.drawCircle(12f, 5f, 1.9f, paint)
                c.drawCircle(12f, 12f, 1.9f, paint)
                c.drawCircle(12f, 19f, 1.9f, paint)
            }
        }
        c.restore()
    }
}

class ShapeTile(context: Context, private val kind: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8BBC6.toInt() }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val s = min(width, height) * 0.62f
        val h = if (kind == 7) s * 0.12f else s
        Renderer.shapePath(kind, s, h, s * 0.15f, path)
        c.save()
        c.translate(width / 2f, height / 2f)
        c.drawPath(path, paint)
        c.restore()
    }
}
