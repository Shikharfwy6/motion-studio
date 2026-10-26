package com.motionstudio.app

import android.app.Dialog
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
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
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
        },
        "Fly in 3D" to { l: Layer, t: Float ->
            if (l.type != T_CAMERA) l.threeD = true
            l.upsert(P_Z, t, 900f, 2); l.upsert(P_Z, t + 1f, 0f, 2)
            l.upsert(P_ALPHA, t, 0f, 2); l.upsert(P_ALPHA, t + 0.6f, 1f, 2)
        },
        "Flip 3D" to { l: Layer, t: Float ->
            if (l.type != T_CAMERA) l.threeD = true
            l.upsert(P_RY, t, 90f, 4); l.upsert(P_RY, t + 0.8f, 0f, 4)
        },
        "Orbit 360" to { l: Layer, t: Float ->
            if (l.type != T_CAMERA) l.threeD = true
            l.upsert(P_RY, t, 0f, 0); l.upsert(P_RY, t + 3f, 360f, 0)
        },
        "Dolly in" to { l: Layer, t: Float ->
            l.upsert(P_Z, t, 0f, 3); l.upsert(P_Z, t + 2f, if (l.type == T_CAMERA) 500f else -400f, 3)
        },
        "Camera shake" to { l: Layer, t: Float ->
            l.wigAmp[P_X] = 14f; l.wigAmp[P_Y] = 14f; l.wigAmp[P_ROT] = 1.5f; l.wigFreq = 7f
        }
    )
}

/** The bottom panel that opens when a layer is selected (same layout as Alight Motion). */
class LayerPanel(private val ctx: Context, private val p: Project, private val host: PanelHost) {
    val view = FrameLayout(ctx)
    var inSub = false
        private set

    private val syncFns = ArrayList<() -> Unit>()
    private val gridBtns = HashMap<String, View>()
    private val gridIcons = HashMap<String, IconView>()
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

    private fun en(b: View?, on: Boolean) {
        b?.isEnabled = on
        b?.alpha = if (on) 1f else 0.35f
    }

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

    private fun field(name: String, box: NumBox, w: Int = 92): LinearLayout {
        val t = TextView(ctx)
        t.text = name
        t.setTextColor(C_DIM)
        t.textSize = 12f
        t.setPadding(dp(4), 0, dp(6), 0)
        val r = hbox(t, box.e)
        box.e.layoutParams = lp(dp(w), dp(42))
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

    private fun styleKey(b: IconView, props: IntArray) {
        val l = p.sel
        val on = l != null && props.all { l.keyAt(it, p.time) != null }
        val has = l != null && props.any { l.keys[it].isNotEmpty() }
        b.filled = on
        b.tint = if (on || has) C_KEY else C_DIM
        b.invalidate()
    }

    /** Graph editor: edit the curve of the keyframe at the playhead (the segment that leaves this keyframe). */
    private fun openCurve(props: IntArray) {
        val l = p.sel ?: return
        val ks = props.mapNotNull { l.keyAt(it, p.time) }
        if (ks.isEmpty()) {
            host.toast("Add a keyframe here first (tap the diamond), then edit its curve")
            return
        }
        val k0 = ks[0]
        val start = if (k0.ease == Ease.CUSTOM) floatArrayOf(k0.x1, k0.y1, k0.x2, k0.y2) else Ease.handles(k0.ease)
        CurveDialog.show(ctx, "Curve after this keyframe", start, k0.ease) { h, ease ->
            for (k in ks) {
                k.ease = ease
                k.x1 = h[0]
                k.y1 = h[1]
                k.x2 = h[2]
                k.y2 = h[3]
            }
            if (ease != Ease.CUSTOM) p.easeIdx = ease
            host.changed()
            host.timelineChanged()
            host.commit()
            refresh()
        }
    }

    private fun easeShort() = arrayOf("Linear", "In", "Out", "In-Out", "Over", "Bounce")[p.easeIdx % 6]

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
            val kv = IconView(ctx, IconView.KEY)
            kv.background = tileBg(C_PANEL2)
            kv.setOnClickListener { toggleKeys(props) }
            val klp = lp(dp(46), dp(42))
            klp.topMargin = dp(6)
            left.addView(kv, klp)
            syncFns.add { styleKey(kv, props) }
            val ev = IconView(ctx, IconView.EASE)
            ev.background = tileBg(C_PANEL2)
            val et = TextView(ctx)
            et.text = easeShort()
            et.textSize = 9f
            et.setTextColor(C_DIM)
            et.gravity = Gravity.CENTER
            ev.setOnClickListener { openCurve(props) }
            syncFns.add { et.text = easeShort() }
            val elp = lp(dp(46), dp(40))
            elp.topMargin = dp(6)
            left.addView(ev, elp)
            left.addView(et, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

    fun open(name: String) {
        if (p.sel == null) {
            host.toast("Select a layer first")
            return
        }
        when (name) {
            "move" -> transform(0)
            "rotate" -> transform(1)
            "scale" -> transform(2)
            "skew" -> transform(3)
            "fill" -> fill()
            "border" -> { borderTab = 0; border() }
            "shadow" -> { borderTab = 1; border() }
            "blend" -> blend()
            "speed" -> speed()
            "wiggle" -> expressions()
            "particles" -> particles()
            "text" -> editText()
            "presets" -> presets()
            "effects" -> effectsSubPanel()
            "3d" -> toggle3d()
            else -> showGrid()
        }
    }

    fun back(): Boolean {
        if (inSub) {
            showGrid()
            return true
        }
        return false
    }

    /* ---------------- Main Alight Motion 3-Row Grid ---------------- */

    private fun tileBg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(10).toFloat()
        return g
    }

    private fun tile(kind: Int, label: String, onClick: () -> Unit): LinearLayout {
        val t = LinearLayout(ctx)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        t.background = tileBg(C_PANEL2)
        val ic = IconView(ctx, kind)
        t.addView(ic, lp(dp(26), dp(26)))
        if (label.isNotEmpty()) {
            val tv = TextView(ctx)
            tv.text = label
            tv.textSize = 10f
            tv.setTextColor(C_DIM)
            tv.gravity = Gravity.CENTER
            tv.maxLines = 2
            t.addView(tv, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        t.setOnClickListener { onClick() }
        gridIcons[label] = ic
        return t
    }

    private fun modeBtn(kind: Int, on: Boolean, onClick: () -> Unit): IconView {
        val v = IconView(ctx, kind)
        v.tint = if (on) C_GREEN else C_TEXT
        v.background = tileBg(if (on) 0xFF2B2F42.toInt() else C_PANEL2)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun sl(c: LinearLayout, title: String, max: Int, get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String) {
        val tv = lbl(title)
        val sb = seek(max, { v -> set(v); tv.text = title + "  " + fmt(v); host.changed() }, { host.commit() })
        c.addView(tv)
        c.addView(sb)
        syncFns.add {
            val v = get()
            tv.text = title + "  " + fmt(v)
            setProg(sb, v)
        }
    }

    fun showGrid() {
        host.commit()
        inSub = false
        host.title(null)
        syncFns.clear()
        gridBtns.clear()
        gridIcons.clear()
        view.removeAllViews()

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(6), dp(8), dp(8))

        fun g(key: String, kind: Int, label: String, onClick: () -> Unit): View {
            val t = tile(kind, label, onClick)
            gridBtns[key] = t
            return t
        }

        fun row(heightDp: Int, vararg bs: View): LinearLayout {
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.HORIZONTAL
            for (b in bs) {
                val m = lp(0, dp(heightDp), 1f)
                m.setMargins(dp(4), dp(4), dp(4), dp(4))
                r.addView(b, m)
            }
            return r
        }

        // Row 1 (Quick Bar like Alight Motion Video 0:10): Speed | Trim Left | Trim Right | Mute
        col.addView(row(
            44,
            g("Speed", IconView.SPEED, "") { speed() },
            g("TrimIn", IconView.TRIM_START, "") { trim(true) },
            g("TrimOut", IconView.TRIM_END, "") { trim(false) },
            g("Mute", IconView.MUTE, "") { mute() }
        ))

        // Row 2 (3 Cards like Alight Motion Video 0:10): Colour & Fill | Border & Shadow | Blending & Opacity
        col.addView(row(
            68,
            g("Fill", IconView.FILL, "Colour & Fill") { fill() },
            g("Border", IconView.BORDER, "Border & Shadow") { border() },
            g("Blend", IconView.BLEND, "Blending & Opacity") { blend() }
        ))

        // Row 3 (4 Cards like Alight Motion Video 0:10): Move & Transform | Edit Text | Presets | Effects
        col.addView(row(
            68,
            g("Transform", IconView.MOVE, "Move & Transform") { transform(0) },
            g("Text", IconView.TEXT, "Edit Text") { editText() },
            g("Presets", IconView.STAR, "Presets") { presets() },
            g("Effects", IconView.SPARK, "Effects") { effectsSubPanel() }
        ))

        val sv = ScrollView(ctx)
        sv.addView(col)
        view.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        syncFns.add {
            val l = p.sel
            if (l != null) {
                val cam = l.type == T_CAMERA
                val part = l.type == T_PARTICLES
                val styled = l.type == T_SHAPE || l.type == T_TEXT
                en(gridBtns["Speed"], l.type == T_VIDEO)
                en(gridBtns["Mute"], l.type == T_VIDEO)
                en(gridBtns["Text"], l.type == T_TEXT)
                en(gridBtns["Fill"], styled || part)
                en(gridBtns["Border"], styled)
                en(gridBtns["Blend"], !cam)
                en(gridBtns["Effects"], true)
            }
        }
        refresh()
    }

    /* ---------------- Effects Sub-Panel (+ Add Effect & Effect Browser like Video 0:56 - 0:59) ---------------- */

    private fun effectsSubPanel() {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()

        // Active effects / properties summary
        if (l.wigAmp.any { it != 0f }) {
            val wBtn = btn("✦ Wiggle / Oscillate (Active - Tap to edit)", 13f) { expressions() }
            c.addView(wBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        if (l.threeD) {
            val dBtn = btn("✦ 3D Layer Perspective (Active)", 13f) { transform(1) }
            c.addView(dBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        if (l.shadowOn) {
            val sBtn = btn("✦ Drop Shadow (Active - Tap to edit)", 13f) { borderTab = 1; border() }
            c.addView(sBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        if (l.strokeW > 0f) {
            val bBtn = btn("✦ Stroke / Border (Active - Tap to edit)", 13f) { borderTab = 0; border() }
            c.addView(bBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        if (l.type == T_PARTICLES) {
            val pBtn = btn("✦ Particle Emitter Settings", 13f) { particles() }
            c.addView(pBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

        // "+ प्रभाव जोड़ें / + Add Effect" Button (Exact match with Video 0:56)
        val addEffectBtn = Button(ctx).apply {
            text = "+ प्रभाव जोड़ें  (+ Add Effect)"
            isAllCaps = false
            textSize = 14f
            setTextColor(C_TEXT)
            background = tileBg(C_PANEL2)
            setOnClickListener { showEffectBrowserDialog() }
        }
        val addLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        addLp.topMargin = dp(6)
        c.addView(addEffectBtn, addLp)

        scaffold("Effects (प्रभाव)", intArrayOf(P_X, P_Y, P_SCALE, P_ROT, P_ALPHA), scroll(c))
    }

    private data class BrowserEffect(val title: String, val subtitle: String, val action: () -> Unit)

    private fun showEffectBrowserDialog() {
        val l = p.sel ?: return
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_INK)
        }

        // Header Bar: Close '✕', Title 'प्रभाव ब्राउज़र', Search Box
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C_PANEL)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            setTextColor(C_TEXT)
            setPadding(dp(10), dp(6), dp(14), dp(6))
            setOnClickListener { dialog.dismiss() }
        }
        val titleTv = TextView(ctx).apply {
            text = "प्रभाव ब्राउज़र (Effect Browser)"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(C_TEXT)
            layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(closeBtn)
        header.addView(titleTv)
        root.addView(header)

        // Search input
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search effects..."
            setHintTextColor(C_DIM)
            setTextColor(C_TEXT)
            textSize = 13f
            setSingleLine(true)
            background = tileBg(C_PANEL2)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val sLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sLp.setMargins(dp(12), dp(8), dp(12), dp(4))
        root.addView(searchBox, sLp)

        val contentBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(24))
        }
        val sv = ScrollView(ctx)
        sv.addView(contentBox)
        root.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val allEffects = listOf(
            BrowserEffect("Wiggle / Oscillate (झुलाना)", "Move & Transform") { expressions() },
            BrowserEffect("Drop Shadow (छाया)", "Border & Shadow") {
                l.shadowOn = true; host.changed(); host.commit(); borderTab = 1; border()
            },
            BrowserEffect("Stroke / Outline (सीमा)", "Border & Shadow") {
                if (l.strokeW <= 0f) l.strokeW = 6f
                host.changed(); host.commit(); borderTab = 0; border()
            },
            BrowserEffect("Gradient Ramp (रंग ढाल)", "Colour & Light") {
                l.fillType = 2; host.changed(); host.commit(); fill()
            },
            BrowserEffect("3D Flip / Perspective (3 डी)", "3D") {
                l.threeD = true; host.changed(); host.commit(); transform(1)
            },
            BrowserEffect("Skew / Warp (विरूपण/रैप)", "Distortion / Warp") { transform(3) },
            BrowserEffect("Fade In / Out (फ़ेड इन/आउट)", "Opacity / Visibility") {
                Presets.all["Fade in"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Fade In applied")
            },
            BrowserEffect("Pop In (पॉप इन)", "Move & Transform") {
                Presets.all["Pop in"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Pop In applied")
            },
            BrowserEffect("Slide Up (स्लाइड अप)", "Move & Transform") {
                Presets.all["Slide up"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Slide Up applied")
            },
            BrowserEffect("Spin In (स्पिन इन)", "Move & Transform") {
                Presets.all["Spin in"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Spin In applied")
            },
            BrowserEffect("Bounce Drop (बाउंस)", "Move & Transform") {
                Presets.all["Bounce drop"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Bounce Drop applied")
            },
            BrowserEffect("Fly In 3D (3 डी फ्लाई)", "3D") {
                Presets.all["Fly in 3D"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Fly in 3D applied")
            },
            BrowserEffect("Flip 3D (3 डी फ्लिप)", "3D") {
                Presets.all["Flip 3D"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Flip 3D applied")
            },
            BrowserEffect("Orbit 360° (360° परिक्रमा)", "3D") {
                Presets.all["Orbit 360"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Orbit 360 applied")
            },
            BrowserEffect("Camera Shake (कैमरा शेक)", "Move & Transform") {
                Presets.all["Camera shake"]?.invoke(l, p.time)
                host.changed(); settled(); host.toast("Camera shake applied")
            },
            BrowserEffect("Blending & Opacity (अपारदर्शिता)", "Opacity / Visibility") { blend() }
        )

        val categories = listOf(
            "रंग और प्रकाश (Color & Light)" to { fill() },
            "ड्राइंग और एज (Drawing & Edge)" to { borderTab = 0; border() },
            "सीमा और छाया (Border & Shadow)" to { borderTab = 1; border() },
            "विरूपण / रैप (Distortion/Warp)" to { transform(3) },
            "प्रक्रियात्मक (Wiggle & Loop)" to { expressions() },
            "3 डी (3D Transform)" to { l.threeD = true; transform(1) },
            "ले जाएँ / रूपांतरण (Move/Transform)" to { transform(0) },
            "अपारदर्शिता / दृश्यता (Opacity)" to { blend() },
            "प्रीसेट्स (Animation Presets)" to { presets() },
            "टेक्स्ट (Text Formatting)" to { editText() }
        )

        fun renderBrowser(q: String) {
            contentBox.removeAllViews()
            val shown = if (q.isEmpty()) allEffects else allEffects.filter {
                it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
            }

            // Popular Effects Grid (2 columns like Video 0:57)
            val grid = GridLayout(ctx).apply {
                columnCount = 2
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            for (fx in shown) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = tileBg(C_PANEL2)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(78)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        fx.action()
                    }
                }
                val t1 = TextView(ctx).apply {
                    text = "✦ " + fx.title
                    setTextColor(C_TEXT)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
                val t2 = TextView(ctx).apply {
                    text = fx.subtitle
                    setTextColor(C_DIM)
                    textSize = 10f
                    gravity = Gravity.CENTER
                }
                card.addView(t1)
                card.addView(t2)
                grid.addView(card)
            }
            contentBox.addView(grid)

            if (q.isEmpty()) {
                val catHeader = lbl("श्रेणियाँ (Effect Categories)")
                catHeader.setPadding(dp(6), dp(14), dp(6), dp(6))
                contentBox.addView(catHeader)

                val catGrid = GridLayout(ctx).apply {
                    columnCount = 2
                    layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                for ((catName, catAction) in categories) {
                    val cBtn = Button(ctx).apply {
                        text = catName
                        isAllCaps = false
                        setTextColor(C_TEXT)
                        textSize = 12f
                        background = tileBg(0xFF282D42.toInt())
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = 0
                            height = dp(64)
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            setMargins(dp(5), dp(5), dp(5), dp(5))
                        }
                        setOnClickListener {
                            dialog.dismiss()
                            catAction()
                        }
                    }
                    catGrid.addView(cBtn)
                }
                contentBox.addView(catGrid)
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderBrowser(s.toString().trim().lowercase())
            }
        })

        renderBrowser("")
        dialog.setContentView(root)
        dialog.show()
    }

    private fun toggle3d() {
        val l = p.sel ?: return
        l.threeD = !l.threeD
        host.toast(if (l.threeD) "3D layer ON: Z, X/Y rotation and camera apply" else "3D layer OFF")
        host.changed()
        host.commit()
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
        host.toast(if (l.muted) "Muted" else "Unmuted")
        host.commit()
    }

    /* ---------------- Move & Transform ---------------- */

    private fun setZ(l: Layer, v: Float) {
        l.set(P_Z, p.time, v, p.easeIdx)
        if (v != 0f && !l.threeD && l.type != T_CAMERA) {
            l.threeD = true
            host.toast("3D layer turned on")
        }
    }

    private fun setAxis(l: Layer, prop: Int, deg: Float) {
        l.set(prop, p.time, deg, p.easeIdx)
        if (deg != 0f && !l.threeD && l.type != T_CAMERA) {
            l.threeD = true
            host.toast("3D layer turned on")
        }
    }

    private fun transform(m: Int) {
        val l = p.sel ?: return
        syncFns.clear()
        val cam = l.type == T_CAMERA
        val c = vbox()
        var props: IntArray? = null
        when (m) {
            0 -> {
                props = intArrayOf(P_X, P_Y, P_Z)
                val fx = NumBox(ctx) { v -> l.set(P_X, p.time, v, p.easeIdx); host.changed(); settled() }
                val fy = NumBox(ctx) { v -> l.set(P_Y, p.time, v, p.easeIdx); host.changed(); settled() }
                val fz = NumBox(ctx) { v -> setZ(l, v); host.changed(); settled(); refresh() }
                c.addView(hbox(
                    field(if (cam) "Pan X" else "X", fx, 62),
                    field(if (cam) "Pan Y" else "Y", fy, 62),
                    field(if (cam) "Dolly" else "Z", fz, 62)
                ))
                syncFns.add { fx.set(l.eval(P_X, p.time)); fy.set(l.eval(P_Y, p.time)); fz.set(l.eval(P_Z, p.time)) }
                sl(c, if (cam) "Dolly (forward / back)" else "Depth Z (away / towards)", 3000,
                    { (l.eval(P_Z, p.time) + 1500f).toInt() },
                    { v -> setZ(l, (v - 1500).toFloat()) },
                    { v -> (v - 1500).toString() })
                val pad = TextView(ctx)
                pad.text = "Swipe here to move layer"
                pad.setTextColor(C_DIM)
                pad.gravity = Gravity.CENTER
                pad.background = tileBg(C_PANEL2)
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
                c.addView(pad, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            }
            1 -> {
                props = intArrayOf(P_ROT, P_RX, P_RY)
                val names = if (cam) arrayOf("Roll", "Tilt", "Orbit") else arrayOf("Rotate Z", "Rotate X (tilt)", "Rotate Y (turn)")
                sl(c, names[0], 720, { (l.eval(P_ROT, p.time) + 360f).toInt() },
                    { v -> l.set(P_ROT, p.time, (v - 360).toFloat(), p.easeIdx) }, { v -> (v - 360).toString() + "°" })
                sl(c, names[1], 720, { (l.eval(P_RX, p.time) + 360f).toInt() },
                    { v -> setAxis(l, P_RX, (v - 360).toFloat()) }, { v -> (v - 360).toString() + "°" })
                sl(c, names[2], 720, { (l.eval(P_RY, p.time) + 360f).toInt() },
                    { v -> setAxis(l, P_RY, (v - 360).toFloat()) }, { v -> (v - 360).toString() + "°" })
            }
            2 -> {
                props = intArrayOf(P_SCALE)
                if (!cam) {
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
                        val lk = IconView(ctx, IconView.SIZE)
                        lk.tint = if (link) C_GREEN else C_DIM
                        lk.setOnClickListener {
                            link = !link
                            lk.tint = if (link) C_GREEN else C_DIM
                            lk.invalidate()
                            host.toast(if (link) "Width and height linked" else "Width and height free")
                        }
                        lk.layoutParams = lp(dp(40), dp(40))
                        c.addView(hbox(field("W", fw, 70), lk, field("H", fh, 70)))
                        syncFns.add { fw.set(l.w); fh.set(l.h) }
                    }
                }
                sl(c, if (cam) "Zoom" else "Scale", 500, { (l.eval(P_SCALE, p.time) * 100f).toInt() },
                    { v -> l.set(P_SCALE, p.time, v / 100f, p.easeIdx) }, { v -> "$v%" })
            }
            else -> {
                if (cam) {
                    c.addView(lbl("Skew and flip do not apply to the camera"))
                } else {
                    props = intArrayOf(P_SKX, P_SKY)
                    sl(c, "Skew X", 120, { (l.eval(P_SKX, p.time) + 60f).toInt() }, { v -> l.set(P_SKX, p.time, (v - 60).toFloat(), p.easeIdx) }, { v -> (v - 60).toString() + "°" })
                    sl(c, "Skew Y", 120, { (l.eval(P_SKY, p.time) + 60f).toInt() }, { v -> l.set(P_SKY, p.time, (v - 60).toFloat(), p.easeIdx) }, { v -> (v - 60).toString() + "°" })
                    c.addView(hbox(
                        btn("Flip horizontal", 12f) { l.flipH = !l.flipH; host.changed(); settled() },
                        btn("Flip vertical", 12f) { l.flipV = !l.flipV; host.changed(); settled() }
                    ))
                }
            }
        }
        val modes = LinearLayout(ctx)
        modes.orientation = LinearLayout.VERTICAL
        modes.setPadding(0, dp(4), dp(4), 0)
        val kinds = intArrayOf(IconView.MOVE, IconView.ROTATE, IconView.SIZE, IconView.SKEW)
        for ((i, k) in kinds.withIndex()) {
            val mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            mp.setMargins(0, dp(2), 0, dp(2))
            modes.addView(modeBtn(k, i == m) { transform(i) }, mp)
        }
        if (!cam) {
            val mp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            mp.setMargins(0, dp(2), 0, dp(2))
            modes.addView(modeBtn(IconView.CUBE, l.threeD) {
                l.threeD = !l.threeD
                host.toast(if (l.threeD) "3D layer ON" else "3D layer OFF")
                host.changed()
                host.commit()
                transform(m)
            }, mp)
        }
        scaffold(if (cam) "Camera" else "Move & Transform", props, scroll(c), modes)
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
            val sa = seek(360, { v -> l.set(P_GRAD, p.time, v.toFloat(), p.easeIdx); host.changed(); ta.text = "Angle  $v°" }, { host.commit() })
            c.addView(ta)
            c.addView(sa)
            syncFns.add { ta.text = "Angle  " + l.eval(P_GRAD, p.time).toInt() + "°"; setProg(sa, l.eval(P_GRAD, p.time).toInt()) }
        }
        if (l.fillType == 1 || l.fillType == 2) colourEditor(c, l)
        scaffold("Colour & Fill", if (l.fillType == 2) intArrayOf(P_GRAD) else null, scroll(c))
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
            val sw = seek(40, { v -> l.set(P_STROKE, p.time, v.toFloat(), p.easeIdx); host.changed(); tw.text = "Width  $v" }, { host.commit() })
            c.addView(tw)
            c.addView(sw)
            c.addView(lbl("Colour"))
            c.addView(swatchRow({ l.strokeColor }, { col -> l.strokeColor = col; host.changed(); host.commit() }))
            syncFns.add { tw.text = "Width  " + l.eval(P_STROKE, p.time).toInt(); setProg(sw, l.eval(P_STROKE, p.time).toInt()) }
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
            val sb = seek(40, { v -> l.set(P_SHBLUR, p.time, v.toFloat(), p.easeIdx); host.changed(); tb.text = "Blur  $v" }, { host.commit() })
            val tx = lbl("Offset X")
            val sx = seek(80, { v -> l.set(P_SHDX, p.time, (v - 40).toFloat(), p.easeIdx); host.changed(); tx.text = "Offset X  " + (v - 40) }, { host.commit() })
            val ty = lbl("Offset Y")
            val sy = seek(80, { v -> l.set(P_SHDY, p.time, (v - 40).toFloat(), p.easeIdx); host.changed(); ty.text = "Offset Y  " + (v - 40) }, { host.commit() })
            c.addView(tb); c.addView(sb); c.addView(tx); c.addView(sx); c.addView(ty); c.addView(sy)
            c.addView(lbl("Colour"))
            c.addView(swatchRow({ l.shadowColor }, { col -> l.shadowColor = (col and 0xFFFFFF) or 0xAA000000.toInt(); host.changed(); host.commit() }))
            syncFns.add {
                tb.text = "Blur  " + l.eval(P_SHBLUR, p.time).toInt(); setProg(sb, l.eval(P_SHBLUR, p.time).toInt())
                tx.text = "Offset X  " + l.eval(P_SHDX, p.time).toInt(); setProg(sx, l.eval(P_SHDX, p.time).toInt() + 40)
                ty.text = "Offset Y  " + l.eval(P_SHDY, p.time).toInt(); setProg(sy, l.eval(P_SHDY, p.time).toInt() + 40)
            }
        }
        scaffold("Border & Shadow", if (borderTab == 0) intArrayOf(P_STROKE) else intArrayOf(P_SHBLUR, P_SHDX, P_SHDY), scroll(c))
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

    /* ---------------- Wiggle & Loop (expressions) ---------------- */

    private fun expressions() {
        val l = p.sel ?: return
        syncFns.clear()
        val c = vbox()
        c.addView(lbl("Wiggle adds smooth random motion on top of your keyframes"))
        fun amp(title: String, max: Int, unit: Float, props: IntArray, fmt: String) {
            sl(c, title, max, { (l.wigAmp[props[0]] / unit + 0.5f).toInt() },
                { v -> for (q in props) l.wigAmp[q] = v * unit }, { v -> String.format(fmt, v * unit) })
        }
        amp("Position", 200, 1f, intArrayOf(P_X, P_Y), "%.0f px")
        amp("Depth (Z)", 150, 2f, intArrayOf(P_Z), "%.0f")
        amp("Rotation", 90, 0.5f, intArrayOf(P_ROT), "%.1f°")
        amp("Scale", 50, 0.01f, intArrayOf(P_SCALE), "%.2f")
        amp("Opacity", 50, 0.01f, intArrayOf(P_ALPHA), "%.2f")
        sl(c, "Speed", 195, { ((l.wigFreq - 0.5f) * 10f).toInt() }, { v -> l.wigFreq = 0.5f + v / 10f },
            { v -> String.format("%.1f", 0.5f + v / 10f) })
        c.addView(btn("New random seed", 12f) {
            l.wigSeed = (Math.random() * 100.0).toFloat()
            host.changed()
            host.commit()
        })
        c.addView(lbl("Loop keyframes after the last one"))
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for ((i, n) in arrayOf("Off", "Cycle", "Ping-pong").withIndex()) {
            val b = btn(n, 12f) { l.loopMode = i; host.changed(); host.commit(); expressions() }
            b.setBackgroundColor(if (l.loopMode == i) 0xFF2B2F42.toInt() else C_PANEL2)
            b.setTextColor(if (l.loopMode == i) C_GREEN else C_TEXT)
            r.addView(b, lp(0, dp(40), 1f))
        }
        c.addView(r)
        scaffold("Wiggle & Loop", null, scroll(c))
    }

    /* ---------------- Particles ---------------- */

    private fun particles() {
        val l = p.sel ?: return
        if (l.type != T_PARTICLES) {
            host.toast("Select a Particles layer")
            return
        }
        syncFns.clear()
        val c = vbox()
        sl(c, "Amount", 395, { l.pCount - 5 }, { v -> l.pCount = v + 5 }, { v -> (v + 5).toString() })
        sl(c, "Life", 55, { ((l.pLife - 0.5f) * 10f).toInt() }, { v -> l.pLife = 0.5f + v / 10f },
            { v -> String.format("%.1fs", 0.5f + v / 10f) })
        sl(c, "Speed", 1000, { l.eval(P_PSPEED, p.time).toInt() }, { v -> l.set(P_PSPEED, p.time, v.toFloat(), p.easeIdx) }, { v -> v.toString() })
        sl(c, "Size", 195, { l.eval(P_PSIZE, p.time).toInt() - 5 }, { v -> l.set(P_PSIZE, p.time, (v + 5).toFloat(), p.easeIdx) }, { v -> (v + 5).toString() })
        sl(c, "Gravity", 2000, { l.eval(P_PGRAV, p.time).toInt() + 500 }, { v -> l.set(P_PGRAV, p.time, (v - 500).toFloat(), p.easeIdx) }, { v -> (v - 500).toString() })
        sl(c, "Spread", 360, { l.eval(P_PSPREAD, p.time).toInt() }, { v -> l.set(P_PSPREAD, p.time, v.toFloat(), p.easeIdx) }, { v -> v.toString() + "°" })
        sl(c, "Direction", 360, { (l.pAngle + 180f).toInt() }, { v -> l.pAngle = (v - 180).toFloat() }, { v -> (v - 180).toString() + "°" })
        c.addView(btn("Colour", 12f) { fill() })
        scaffold("Particles", intArrayOf(P_PSPEED, P_PSIZE, P_PGRAV, P_PSPREAD), scroll(c))
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
