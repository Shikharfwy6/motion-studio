package com.motionstudio.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Graph editor for one keyframe segment: x = time (0..1), y = value (0..1, can overshoot).
 * Drag the two handles to shape the curve exactly like in Alight Motion / After Effects.
 */
class CurveEditorView(context: Context) : View(context) {
    var x1 = 0.42f
    var y1 = 0f
    var x2 = 0.58f
    var y2 = 1f
    var onChange: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val pad = 26f * d
    private var active = 0 // 0 none, 1 first handle, 2 second handle

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E2E2E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4DA3FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }
    private val stick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 10f * d
    }
    private val path = Path()

    // the graph shows y from -0.5 to 1.5 so the curve can overshoot
    private fun gx(x: Float) = pad + x * (width - 2 * pad)
    private fun gy(y: Float) = height - pad - ((y + 0.5f) / 2f) * (height - 2 * pad)
    private fun fx(px: Float) = ((px - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
    private fun fy(py: Float) = (((height - pad - py) / (height - 2 * pad)) * 2f - 0.5f).coerceIn(-0.5f, 1.5f)

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1A1A1A.toInt())
        for (i in 0..4) {
            val x = gx(i / 4f)
            c.drawLine(x, gy(-0.5f), x, gy(1.5f), grid)
        }
        for (v in floatArrayOf(-0.5f, 0f, 0.5f, 1f, 1.5f)) c.drawLine(gx(0f), gy(v), gx(1f), gy(v), if (v == 0f || v == 1f) axis else grid)
        c.drawText("0", gx(0f) - 12f * d, gy(0f) + 4f * d, text)
        c.drawText("1", gx(0f) - 12f * d, gy(1f) + 4f * d, text)
        c.drawText("time", gx(0.5f) - 10f * d, height - 6f * d, text)
        c.drawLine(gx(0f), gy(0f), gx(x1), gy(y1), stick)
        c.drawLine(gx(1f), gy(1f), gx(x2), gy(y2), stick)
        path.reset()
        path.moveTo(gx(0f), gy(0f))
        path.cubicTo(gx(x1), gy(y1), gx(x2), gy(y2), gx(1f), gy(1f))
        c.drawPath(path, curve)
        dot.color = Color.WHITE
        c.drawCircle(gx(0f), gy(0f), 4f * d, dot)
        c.drawCircle(gx(1f), gy(1f), 4f * d, dot)
        dot.color = if (active == 1) 0xFFFFFFFF.toInt() else C_KEY
        c.drawCircle(gx(x1), gy(y1), 10f * d, dot)
        dot.color = if (active == 2) 0xFFFFFFFF.toInt() else C_ACCENT
        c.drawCircle(gx(x2), gy(y2), 10f * d, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val d1 = hypot(e.x - gx(x1), e.y - gy(y1))
                val d2 = hypot(e.x - gx(x2), e.y - gy(y2))
                active = if (d1 <= d2) 1 else 2
                move(e)
            }
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = 0
                invalidate()
            }
        }
        return true
    }

    private fun move(e: MotionEvent) {
        if (active == 1) {
            x1 = fx(e.x)
            y1 = fy(e.y)
        } else if (active == 2) {
            x2 = fx(e.x)
            y2 = fy(e.y)
        }
        invalidate()
        onChange?.invoke()
    }

    fun set(h: FloatArray) {
        x1 = h[0]; y1 = h[1]; x2 = h[2]; y2 = h[3]
        invalidate()
    }
}

object CurveDialog {
    /**
     * Opens the graph editor. [start] are the bezier handles, [startEase] the current ease index.
     * [onApply] gets the handles and the ease to store (a preset index, or Ease.CUSTOM after dragging).
     */
    fun show(ctx: Context, title: String, start: FloatArray, startEase: Int, onApply: (FloatArray, Int) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        val graph = CurveEditorView(ctx)
        graph.set(start)
        var chosen = startEase
        val info = TextView(ctx)
        info.setTextColor(0xFFAAAAAA.toInt())
        info.textSize = 11f
        info.gravity = Gravity.CENTER
        fun updateInfo() {
            info.text = if (chosen == 5) "Bounce" else String.format("x1 %.2f   y1 %.2f   x2 %.2f   y2 %.2f", graph.x1, graph.y1, graph.x2, graph.y2)
        }
        updateInfo()
        graph.onChange = {
            chosen = Ease.CUSTOM
            updateInfo()
        }
        col.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (260 * d).toInt()))
        col.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, (8 * d).toInt(), 0, 0)
        val names = arrayOf("Linear", "In", "Out", "In-Out", "Over", "Bounce")
        for (i in names.indices) {
            val b = TextView(ctx)
            b.text = names[i]
            b.textSize = 11f
            b.gravity = Gravity.CENTER
            b.setTextColor(0xFFDDDDDD.toInt())
            b.setBackgroundColor(0xFF333333.toInt())
            b.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            b.setOnClickListener {
                chosen = i
                if (i != 5) graph.set(Ease.handles(i))
                updateInfo()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins((2 * d).toInt(), 0, (2 * d).toInt(), 0)
            row.addView(b, lp)
        }
        col.addView(row)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(col)
            .setPositiveButton("Apply") { _, _ ->
                onApply(floatArrayOf(graph.x1, graph.y1, graph.x2, graph.y2), chosen)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
