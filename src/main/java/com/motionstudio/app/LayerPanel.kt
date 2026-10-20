package com.motionstudio.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min

interface PanelHost {
    fun changed()                 // redraw the preview (no history entry)
    fun commit()                  // save to history and disk
    fun title(t: String?)         // sub-panel title in the top bar, null = back to the layer bar
    fun toast(s: String)
    fun timelineChanged()
}

object Presets {
    val all = linkedMapOf<String, (Layer, Float) -> Unit>(
        "Fade in" to { l: Layer, t: Float ->
            l.upsert(P_ALPHA, t, 0f, 2); l.upsert(P_ALPHA, t + 0.6f, 1f, 2)
        },
        "Fade out" to { l: Layer, t: Float ->
            l.upsert(P_ALPHA, t, 1f, 1); l.upsert(P_ALPHA, t + 0.6f, 0f, 1)
        },
        "Slide up" to { l: Layer, t: Float ->
            val y = l.eval(P_Y, t)
            l.upsert(P_Y, t, y + 220f, 2); l.upsert(P_Y, t + 0.7f, y, 2)
            l.upsert(P_ALPHA, t, 0f, 2); l.upsert(P_ALPHA, t + 0.5f, 1f, 2)
        },
        "Pop in" to { l: Layer, t: Float ->
            l.upsert(P_SCALE, t, 0f, 4); l.upsert(P_SCALE, t + 0.6f, 1f, 4)
        },
        "Spin in" to { l: Layer, t: Float ->
            l.upsert(P_ROT, t, -180f, 2); l.upsert(P_ROT, t + 0.8f, 0f, 2)
            l.upsert(P_SCALE, t, 0f, 2); l.upsert(P_SCALE, t + 0.8f, 1f, 2)
        },
        "Bounce drop" to { l: Layer, t: Float ->
            val y = l.eval(P_Y, t)
            l.upsert(P_Y, t, y - 500f, 5); l.upsert(P_Y, t + 1f, y, 5)
        }
    )
}

/** The bottom panel that opens when a layer is selected (same layout as Alight Motion). */
class LayerPanel(private val ctx: Context, private val p: Project, private val host: PanelHost) {
    val view = FrameLayout(ctx)
    var inSub = false
        private set

    private val syncFns = ArrayList<() -> Unit>()
    private val gridBtns = HashMap<String, Button>()
    private var quiet = false
    private var link = true
    private var fillWhich = 0
    private var borderTab = 0
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val swatches = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF808080.toInt(), 0xFF404040.toInt(), 0xFF000000.toInt(),
        0xFFFF4D4D.toInt(), 0xFFFF7A3D.toInt(), 0xFFFFC247.toInt(), 0xFFFFE94D.toInt(), 0xFF4ADE80.toInt(),
        0xFF2DD4BF.toInt(), 0xFF38BDF8.toInt(), 0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(),
        0xFF7F1D1D.toInt(), 0xFF9A3412.toInt(), 0xFF854D0E.toInt(), 0xFF14532D.toInt(), 0xFF134E4A.toInt(),
        0xFF1E3A8A.toInt(), 0xFF4C1D95.toInt(), 0xFF831843.toInt(), 0xFFF5E6C8.toInt(), 0xFF6D6254.toInt(),
        0xFFFFD1DC.toInt(), 0xFFC7F9CC.toInt(), 0xFFBFDBFE.toInt()
    )

    init {
        view.setBackgroundColor(C_PANEL)
    }

    /* ---------------- small helpers ---------------- */

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun btn(label: String, size: Float = 13f, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.textSize = size
        b.setTextColor(C_TEXT)
        b.setBackgroundColor(C_PANEL2)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(6), 0, dp(6), 0)
        val m = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        m.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = m
        b.setOnClickListener { onClick() }
        return b
    }

    private fun lbl(s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextColor(C_DIM)
        t.textSize = 12f
        t.setPadding(dp(4), dp(6), dp(4), dp(2))
        return t
    }

    private fun scroll(c: View): ScrollView {
        val sv = ScrollView(ctx)
        sv.addView(c)
        return sv
    }

    private fun vbox(): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(8), dp(6), dp(8), dp(16))
        return c
    }

    private fun hbox(vararg views: View): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        for (v in views) r.addView(v)
        return r
    }

    private fun seek(max: Int, onChange: (Int) -> Unit, onStop: () -> Unit = {}): SeekBar {
        val sb = SeekBar(ctx)
        sb.max = max
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser && !quiet) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { onStop() }
        })
        return sb
    }

    private fun setProg(sb: SeekBar, v: Int) {
        quiet = true
        sb.progress = v.coerceIn(0, sb.max)
        quiet = false
    }

    private fun settled() {
        host.commit()
        host.timelineChanged()
    }

    private fun en(b: Button?, on: Boolean) {
        b?.isEnabled = on
        b?.alpha = if (on) 1f else 0.35f
    }

    /** Number box: applies the value when the keyboard "done" key is pressed or focus leaves. */
    private class NumBox(ctx: Context, private val onDone: (Float) -> Unit) {
        val e = EditText(ctx)
        private var dirty = false
        private var quiet = false

        init {
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            e.setSingleLine(true)
            e.textSize = 14f
            e.setTextColor(C_TEXT)
            e.setBackgroundColor(C_PANEL2)
            e.gravity = Gravity.CENTER
            e.imeOptions = EditorInfo.IME_ACTION_DONE
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!quiet) dirty = true }
            })
            e.setOnEditorActionListener { _, _, _ ->
                flush()
                e.clearFocus()
                false
            }
            e.setOnFocusChangeListener { _, has -> if (!has) flush() }
        }

        private fun flush() {
            if (!dirty) return
            dirty = false
            val v = e.text.toString().toFloatOrNull()
            if (v != null) onDone(v)
        }

        fun set(v: Float) {
            if (e.hasFocus()) return
            quiet = true
            e.setText(String.format("%.1f", v))
            quiet = false
            dirty = false
        }
    }

    private fun field(name: String, box: NumBox): LinearLayout {
        val t = TextView(ctx)
        t.text = name
        t.setTextColor(C_DIM)
        t.textSize = 12f
        t.setPadding(dp(4), 0, dp(6), 0)
        val r = hbox(t, box.e)
        box.e.layoutParams = lp(dp(92), dp(42))
        r.setPadding(0, dp(3), dp(8), dp(3))
        return r
    }

    private fun swatchRow(current: () -> Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for (col in swatches) {
            val v = View(ctx)
            val g = GradientDrawable()
            g.setColor(col)
            g.cornerRadius = dp(6).toFloat()
            g.setStroke(dp(1), 0xFF555555.toInt())
            v.background = g
            val m = lp(dp(34), dp(34))
            m.setMargins(dp(3), dp(3), dp(3), dp(3))
            v.setOnClickListener { onPick(col) }
            r.addView(v, m)
        }
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(r)
        return hs
    }

    /* ---------------- key frame / easing helpers ---------------- */

    private fun toggleKeys(props: IntArray) {
        val l = p.sel ?: return
        val has = props.all { l.keyAt(it, p.time) != null }
        for (q in props) {
            if (has) l.removeKey(q, p.time) else l.upsert(q, p.time, l.eval(q, p.time), p.easeIdx)
        }
        host.changed()
        refresh()
        settled()
    }

    private fun styleKey(b: Button, props: IntArray) {
        val l = p.sel
        val on = l != null && props.all { l.keyAt(it, p.time) != null }
        val has = l != null && props.any { l.keys[it].isNotEmpty() }
        b.setBackgroundColor(if (on) C_KEY else C_PANEL2)
        b.setTextColor(if (on) 0xFF2A1D00.toInt() else if (has) C_KEY else C_DIM)
    }

    private fun easeShort() = arrayOf("Linear", "In", "Out", "In-Out", "Over", "Bounce")[p.easeIdx % 6]

    /** Common frame for every sub-panel: back / keyframe / easing on the left, content in the middle. */
    private fun scaffold(title: String, props: IntArray?, content: View, right: View? = null) {
        inSub = true
        host.title(title)
        view.removeAllViews()
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(ctx)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.CENTER_HORIZONTAL
        left.setPadding(0, dp(4), 0, 0)
        val back = IconView(ctx, IconView.BACK)
        back.setOnClickListener { showGrid() }
        left.addView(back, lp(dp(56), dp(44)))
        if (props != null) {
            val kb = btn("\u25C6") { toggleKeys(props) }
            left.addView(kb, lp(dp(52), dp(40)))
            syncFns.add { styleKey(kb, props) }
            val eb = btn(easeShort(), 10f) {}
            eb.setOnClickListener {
                p.easeIdx = (p.easeIdx + 1) % 6
                val l = p.sel
                if (l != null) for (q in 0 until 5) l.keyAt(q, p.time)?.ease = p.easeIdx
                eb.text = easeShort()
                host.toast("Easing: " + Ease.names[p.easeIdx])
                host.commit()
            }
            left.addView(eb, lp(dp(52), dp(36)))
        }
        root.addView(left, lp(dp(60), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (right != null) root.addView(right, lp(dp(74), ViewGroup.LayoutParams.MATCH_PARENT))
        view.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refresh()
    }

    fun refresh() {
        for (f in ArrayList(syncFns)) f()
    }

    /** Returns true when it handled the back press by leaving a sub-panel. */
    fun back(): Boolean {
        if (inSub) {
            showGrid()
            return true
        }
        return false
    }

    /* ---------------- main grid ---------------- */

    fun showGrid() {
        host.commit()
        inSub = false
        host.title(null)
        syncFns.clear()
        gridBtns.clear()
        view.removeAllViews()
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(8), dp(8), dp(4))

        fun g(label: String, onClick: () -> Unit): Button {
            val b = btn(label, 11f, onClick)
            gridBtns[label] = b
            return b
        }
        fun row(vararg bs: Button): LinearLayout {
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.HORIZONTAL
            for (b in bs) r.addView(b, lp(0, dp(56), 1f))
            return r
        }
        col.addView(row(
            g("Speed") { speed() }, g("Trim start") { trim(true) },
            g("Trim end") { trim(false) }, g("Mute") { mute() }
        ))
        col.addView(row(
            g("Colour & Fill") { fill() }, g("Border & Shadow") { border() },
            g("Blend & Opacity") { blend() }
        ))
        col.addView(row(
            g("Move & Transform") { transform(0) }, g("Edit text") { editText() },
            g("Presets") { presets() }, g("Effects") { host.toast("Effects browser comes in the next update") }
        ))
        view.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        syncFns.add {
            val l = p.sel
            if (l != null) {
                en(gridBtns["Speed"], l.type == T_VIDEO)
                en(gridBtns["Edit text"], l.type == T_TEXT)
                val styled = l.type == T_SHAPE || l.type == T_TEXT
                en(gridBtns["Colour & Fill"], styled)
                en(gridBtns["Border & Shadow"], styled)
            }
        }
        refresh()
    }

    private fun trim(start: Boolean) {
        val l = p.sel ?: return
        if (start) {
            val ns = p.time.coerceIn(0f, l.end - 0.2f)
            if (l.type == T_VIDEO) l.trim += (ns - l.start) * l.speed
            l.start = ns
        } else {
            l.end = p.time.coerceIn(l.start + 0.2f, p.duration)
        }
        host.changed()
        settled()
    }

    private fun mute() {
        val l = p.sel ?: return
        l.muted = !l.muted
        host.toast(if (l.muted) "Muted (audio arrives in a later update)" else "Unmuted")
        host.commit()
    }

    /* ---------------- Move & Transform ---------------- */

    private fun transform(m: Int) {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()
        var props: IntArray? = null
        when (m) {
            0 -> {
                props = intArrayOf(P_X, P_Y)
                val fx = NumBox(ctx) { v -> l.set(P_X, p.time, v, p.easeIdx); host.changed(); settled() }
                val fy = NumBox(ctx) { v -> l.set(P_Y, p.time, v, p.easeIdx); host.changed(); settled() }
                c.addView(hbox(field("X", fx), field("Y", fy)))
                val pad = TextView(ctx)
                pad.text = "Swipe here to move the layer"
                pad.setTextColor(C_DIM)
                pad.gravity = Gravity.CENTER
                val bg = GradientDrawable()
                bg.setColor(C_PANEL2)
                bg.cornerRadius = dp(10).toFloat()
                pad.background = bg
                var lx = 0f
                var ly = 0f
                pad.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                        MotionEvent.ACTION_MOVE -> {
                            l.set(P_X, p.time, l.eval(P_X, p.time) + (e.x - lx) * 2f, p.easeIdx)
                            l.set(P_Y, p.time, l.eval(P_Y, p.time) + (e.y - ly) * 2f, p.easeIdx)
                            lx = e.x
                            ly = e.y
                            host.changed()
                            refresh()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> settled()
                    }
                    true
                }
                c.addView(pad, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
                syncFns.add { fx.set(l.eval(P_X, p.time)); fy.set(l.eval(P_Y, p.time)) }
            }
            1 -> {
                props = intArrayOf(P_ROT)
                val fr = NumBox(ctx) { v -> l.set(P_ROT, p.time, v, p.easeIdx); host.changed(); settled() }
                val sb = seek(720, { v -> l.set(P_ROT, p.time, (v - 360).toFloat(), p.easeIdx); host.changed(); refresh() }, { settled() })
                c.addView(field("Rotation (deg)", fr))
                c.addView(sb)
                syncFns.add {
                    val r = l.eval(P_ROT, p.time)
                    fr.set(r)
                    setProg(sb, (r + 360f).toInt())
                }
            }
            2 -> {
                props = intArrayOf(P_SCALE)
                if (l.type == T_TEXT) {
                    val fs = NumBox(ctx) { v -> l.size = v.coerceIn(10f, 500f); host.changed(); settled() }
                    c.addView(field("Font size", fs))
                    syncFns.add { fs.set(l.size) }
                } else {
                    val fw = NumBox(ctx) { v ->
                        val r = if (l.w != 0f) v / l.w else 1f
                        l.w = v.coerceAtLeast(2f)
                        if (link) l.h = (l.h * r).coerceAtLeast(2f)
                        host.changed(); settled(); refresh()
                    }
                    val fh = NumBox(ctx) { v ->
                        val r = if (l.h != 0f) v / l.h else 1f
                        l.h = v.coerceAtLeast(2f)
                        if (link) l.w = (l.w * r).coerceAtLeast(2f)
                        host.changed(); settled(); refresh()
                    }
                    val lk = btn(if (link) "Linked" else "Free", 11f) {}
                    lk.setOnClickListener {
                        link = !link
                        lk.text = if (link) "Linked" else "Free"
                    }
                    c.addView(hbox(field("W", fw), lk, field("H", fh)))
                    syncFns.add { fw.set(l.w); fh.set(l.h) }
                }
                val tv = lbl("Scale")
                val sb = seek(500, { v -> l.set(P_SCALE, p.time, v / 100f, p.easeIdx); host.changed(); refresh() }, { settled() })
                c.addView(tv)
                c.addView(sb)
                syncFns.add {
                    val s = l.eval(P_SCALE, p.time)
                    tv.text = "Scale  " + (s * 100f).toInt() + "%"
                    setProg(sb, (s * 100f).toInt())
                }
            }
            else -> {
                val tx = lbl("Skew X")
                val sx = seek(120, { v -> l.skewX = (v - 60).toFloat(); host.changed(); refresh() }, { settled() })
                val ty = lbl("Skew Y")
                val sy = seek(120, { v -> l.skewY = (v - 60).toFloat(); host.changed(); refresh() }, { settled() })
                c.addView(tx); c.addView(sx); c.addView(ty); c.addView(sy)
                c.addView(hbox(
                    btn("Flip horizontal", 12f) { l.flipH = !l.flipH; host.changed(); settled() },
                    btn("Flip vertical", 12f) { l.flipV = !l.flipV; host.changed(); settled() }
                ))
                syncFns.add {
                    tx.text = "Skew X  " + l.skewX.toInt() + "\u00B0"
                    ty.text = "Skew Y  " + l.skewY.toInt() + "\u00B0"
                    setProg(sx, (l.skewX + 60f).toInt())
                    setProg(sy, (l.skewY + 60f).toInt())
                }
            }
        }
        val modes = LinearLayout(ctx)
        modes.orientation = LinearLayout.VERTICAL
        modes.setPadding(0, dp(4), dp(4), 0)
        for ((i, n) in arrayOf("Move", "Rotate", "Size", "Skew").withIndex()) {
            val b = btn(n, 12f) { transform(i) }
            b.setBackgroundColor(if (i == m) 0xFF2B2F42.toInt() else C_PANEL2)
            b.setTextColor(if (i == m) C_GREEN else C_TEXT)
            modes.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        scaffold("Move & Transform", props, scroll(c), modes)
    }

    /* ---------------- Colour & Fill ---------------- */

    private fun target(l: Layer): Int = if (l.fillType == 2 && fillWhich == 1) l.color2 else l.color

    private fun setTarget(l: Layer, v: Int) {
        if (l.fillType == 2 && fillWhich == 1) l.color2 = v else l.color = v
        host.changed()
    }

    private fun fill() {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()
        val tabs = LinearLayout(ctx)
        tabs.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("None", "Solid", "Gradient", "Image")
        for ((i, n) in names.withIndex()) {
            val b = btn(n, 12f) {
                if (i == 3) {
                    host.toast("Image fill comes in the next update")
                } else {
                    l.fillType = i
                    host.changed()
                    host.commit()
                    fill()
                }
            }
            b.setBackgroundColor(if (l.fillType == i) 0xFF2B2F42.toInt() else C_PANEL2)
            b.setTextColor(if (l.fillType == i) C_GREEN else C_TEXT)
            tabs.addView(b, lp(0, dp(40), 1f))
        }
        c.addView(tabs)
        if (l.fillType == 2) {
            c.addView(hbox(
                btn("Colour 1", 12f) { fillWhich = 0; fill() },
                btn("Colour 2", 12f) { fillWhich = 1; fill() },
                btn("Linear", 12f) { l.gradType = 0; host.changed(); host.commit() },
                btn("Radial", 12f) { l.gradType = 1; host.changed(); host.commit() }
            ))
            val ta = lbl("Angle")
            val sa = seek(360, { v -> l.gradAngle = v.toFloat(); host.changed(); ta.text = "Angle  $v\u00B0" }, { host.commit() })
            c.addView(ta)
            c.addView(sa)
            syncFns.add { ta.text = "Angle  " + l.gradAngle.toInt() + "\u00B0"; setProg(sa, l.gradAngle.toInt()) }
        }
        if (l.fillType == 1 || l.fillType == 2) colourEditor(c, l)
        scaffold("Colour & Fill", null, scroll(c))
    }

    private fun colourEditor(c: LinearLayout, l: Layer) {
        val hex = EditText(ctx)
        hex.setSingleLine(true)
        hex.setTextColor(C_TEXT)
        hex.setBackgroundColor(C_PANEL2)
        hex.textSize = 14f
        hex.imeOptions = EditorInfo.IME_ACTION_DONE
        hex.setOnEditorActionListener { _, _, _ ->
            try {
                val col = Color.parseColor(hex.text.toString().trim())
                setTarget(l, col)
                host.commit()
                refresh()
            } catch (e: Exception) {
                host.toast("Use a colour like #FF5A7A")
            }
            hex.clearFocus()
            false
        }
        c.addView(lbl("Colour (hex)"))
        c.addView(hex, lp(dp(150), dp(42)))
        c.addView(swatchRow({ target(l) }, { col -> setTarget(l, col); host.commit(); refresh() }))

        fun apply() {
            val hsv = floatArrayOf(sbH.progress.toFloat(), sbS.progress / 100f, sbV.progress / 100f)
            setTarget(l, Color.HSVToColor(sbA.progress * 255 / 100, hsv))
        }
        val tH = lbl("Hue")
        val tS = lbl("Saturation")
        val tV = lbl("Brightness")
        val tA = lbl("Opacity")
        sbH = seek(360, { apply() }, { host.commit() })
        sbS = seek(100, { apply() }, { host.commit() })
        sbV = seek(100, { apply() }, { host.commit() })
        sbA = seek(100, { apply() }, { host.commit() })
        c.addView(tH); c.addView(sbH)
        c.addView(tS); c.addView(sbS)
        c.addView(tV); c.addView(sbV)
        c.addView(tA); c.addView(sbA)
        syncFns.add {
            val col = target(l)
            val hsv = FloatArray(3)
            Color.colorToHSV(col, hsv)
            setProg(sbH, hsv[0].toInt())
            setProg(sbS, (hsv[1] * 100f).toInt())
            setProg(sbV, (hsv[2] * 100f).toInt())
            setProg(sbA, Color.alpha(col) * 100 / 255)
            if (!hex.hasFocus()) hex.setText(String.format("#%06X", col and 0xFFFFFF))
        }
    }

    private lateinit var sbH: SeekBar
    private lateinit var sbS: SeekBar
    private lateinit var sbV: SeekBar
    private lateinit var sbA: SeekBar

    /* ---------------- Border & Shadow ---------------- */

    private fun border() {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()
        val tabs = LinearLayout(ctx)
        tabs.orientation = LinearLayout.HORIZONTAL
        for ((i, n) in arrayOf("Border", "Shadow").withIndex()) {
            val b = btn(n, 12f) { borderTab = i; border() }
            b.setBackgroundColor(if (borderTab == i) 0xFF2B2F42.toInt() else C_PANEL2)
            b.setTextColor(if (borderTab == i) C_GREEN else C_TEXT)
            tabs.addView(b, lp(0, dp(40), 1f))
        }
        c.addView(tabs)
        if (borderTab == 0) {
            val tw = lbl("Width")
            val sw = seek(40, { v -> l.strokeW = v.toFloat(); host.changed(); tw.text = "Width  $v" }, { host.commit() })
            c.addView(tw)
            c.addView(sw)
            c.addView(lbl("Colour"))
            c.addView(swatchRow({ l.strokeColor }, { col -> l.strokeColor = col; host.changed(); host.commit() }))
            syncFns.add { tw.text = "Width  " + l.strokeW.toInt(); setProg(sw, l.strokeW.toInt()) }
        } else {
            val tog = btn(if (l.shadowOn) "Shadow: ON" else "Shadow: OFF", 12f) {}
            tog.setOnClickListener {
                l.shadowOn = !l.shadowOn
                host.changed()
                host.commit()
                border()
            }
            c.addView(tog)
            val tb = lbl("Blur")
            val sb = seek(40, { v -> l.shadowBlur = v.toFloat(); host.changed(); tb.text = "Blur  $v" }, { host.commit() })
            val tx = lbl("Offset X")
            val sx = seek(80, { v -> l.shadowDx = (v - 40).toFloat(); host.changed(); tx.text = "Offset X  " + (v - 40) }, { host.commit() })
            val ty = lbl("Offset Y")
            val sy = seek(80, { v -> l.shadowDy = (v - 40).toFloat(); host.changed(); ty.text = "Offset Y  " + (v - 40) }, { host.commit() })
            c.addView(tb); c.addView(sb); c.addView(tx); c.addView(sx); c.addView(ty); c.addView(sy)
            c.addView(lbl("Colour"))
            c.addView(swatchRow({ l.shadowColor }, { col -> l.shadowColor = (col and 0xFFFFFF) or 0xAA000000.toInt(); host.changed(); host.commit() }))
            syncFns.add {
                tb.text = "Blur  " + l.shadowBlur.toInt(); setProg(sb, l.shadowBlur.toInt())
                tx.text = "Offset X  " + l.shadowDx.toInt(); setProg(sx, l.shadowDx.toInt() + 40)
                ty.text = "Offset Y  " + l.shadowDy.toInt(); setProg(sy, l.shadowDy.toInt() + 40)
            }
        }
        scaffold("Border & Shadow", null, scroll(c))
    }

    /* ---------------- Blending & Opacity ---------------- */

    private fun blend() {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()
        val to = lbl("Opacity")
        val so = seek(100, { v -> l.set(P_ALPHA, p.time, v / 100f, p.easeIdx); host.changed(); refresh() }, { settled() })
        c.addView(to)
        c.addView(so)
        syncFns.add {
            val a = l.eval(P_ALPHA, p.time)
            to.text = "Opacity  " + (a * 100f).toInt() + "%"
            setProg(so, (a * 100f).toInt())
        }
        c.addView(lbl("Blend mode"))
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for ((i, n) in arrayOf("Normal", "Multiply", "Screen", "Add").withIndex()) {
            val b = btn(n, 12f) { l.blend = i; host.changed(); host.commit(); blend() }
            b.setBackgroundColor(if (l.blend == i) 0xFF2B2F42.toInt() else C_PANEL2)
            b.setTextColor(if (l.blend == i) C_GREEN else C_TEXT)
            r.addView(b, lp(0, dp(40), 1f))
        }
        c.addView(r)
        scaffold("Blending & Opacity", intArrayOf(P_ALPHA), scroll(c))
    }

    /* ---------------- Edit text ---------------- */

    private fun editText() {
        val l = p.sel ?: return
        if (l.type != T_TEXT) {
            host.toast("Select a text layer")
            return
        }
        syncFns.clear()
        val c = vbox()
        val et = EditText(ctx)
        et.setText(l.text)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.setTextColor(C_TEXT)
        et.setBackgroundColor(C_PANEL2)
        et.minLines = 2
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun afterTextChanged(s: Editable?) {
                l.text = s.toString().ifEmpty { " " }
                host.changed()
            }
        })
        c.addView(et, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val alignNames = arrayOf("Left", "Centre", "Right")
        val ab = btn("Align: " + alignNames[l.align], 12f) {}
        ab.setOnClickListener {
            l.align = (l.align + 1) % 3
            ab.text = "Align: " + alignNames[l.align]
            host.changed()
            host.commit()
        }
        val fb = btn("Font: " + Fonts.names[l.fontIdx], 12f) {}
        fb.setOnClickListener {
            val pm = PopupMenu(ctx, fb)
            for ((i, n) in Fonts.names.withIndex()) pm.menu.add(0, i, i, n)
            pm.setOnMenuItemClickListener { item ->
                l.fontIdx = item.itemId
                fb.text = "Font: " + Fonts.names[l.fontIdx]
                host.changed()
                host.commit()
                true
            }
            pm.show()
        }
        val cb = btn("Colour", 12f) { fill() }
        c.addView(hbox(ab, fb, cb))
        val ts = lbl("Size")
        val ss = seek(300, { v -> l.size = (v + 10).toFloat(); host.changed(); ts.text = "Size  " + (v + 10) }, { host.commit() })
        c.addView(ts)
        c.addView(ss)
        syncFns.add { ts.text = "Size  " + l.size.toInt(); setProg(ss, l.size.toInt() - 10) }
        scaffold("Edit text", null, scroll(c))
    }

    /* ---------------- Speed ---------------- */

    private fun speed() {
        val l = p.sel ?: return
        if (l.type != T_VIDEO) {
            host.toast("Speed works on video layers")
            return
        }
        syncFns.clear()
        val c = vbox()
        val tv = lbl("")
        val sb = seek(375, { v -> l.speed = 0.25f + v / 100f; host.changed(); tv.text = String.format("Speed  %.2fx", l.speed) }, {
            val len = (l.srcDur - l.trim) / l.speed
            l.end = min(p.duration, l.start + len)
            settled()
        })
        c.addView(tv)
        c.addView(sb)
        c.addView(lbl("Slow  0.25x  <->  4.00x  Fast. The layer gets shorter or longer to match."))
        syncFns.add {
            tv.text = String.format("Speed  %.2fx", l.speed)
            setProg(sb, ((l.speed - 0.25f) * 100f).toInt())
        }
        scaffold("Speed", null, scroll(c))
    }

    /* ---------------- Presets ---------------- */

    private fun presets() {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()
        c.addView(lbl("Tap a preset to apply it at the playhead"))
        for (name in Presets.all.keys) {
            val b = btn(name, 14f) {
                Presets.all[name]?.invoke(l, p.time)
                host.changed()
                settled()
                host.toast("$name applied")
            }
            c.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }
        scaffold("Presets", null, scroll(c))
    }
}
