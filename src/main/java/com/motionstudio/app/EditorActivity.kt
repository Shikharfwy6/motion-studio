package com.motionstudio.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.min

class EditorActivity : Activity() {
    private lateinit var p: Project
    private lateinit var editor: EditorView
    private lateinit var timeline: TimelineView
    private lateinit var nameEdit: EditText
    private lateinit var playIcon: IconView
    private lateinit var undoIcon: IconView
    private lateinit var redoIcon: IconView
    private lateinit var inspector: ScrollView
    private lateinit var sheet: LinearLayout
    private lateinit var sheetBody: FrameLayout
    private lateinit var sbScale: SeekBar
    private lateinit var sbRot: SeekBar
    private lateinit var sbAlpha: SeekBar
    private lateinit var easeBtn: Button
    private val tabBtns = ArrayList<Button>()
    private val keyBtns = HashMap<String, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var lastNs = 0L
    private var syncing = false
    private var sheetTab = 0
    private val hist = ArrayList<String>()
    private var hi = -1

    private val palette = intArrayOf(
        Color.WHITE, Color.parseColor("#FF5A7A"), Color.parseColor("#FFC247"),
        Color.parseColor("#4ADE80"), Color.parseColor("#38BDF8"),
        Color.parseColor("#A78BFA"), Color.BLACK
    )
    private val bgPalette = intArrayOf(
        Color.parseColor("#0D0E14"), Color.BLACK, Color.WHITE,
        Color.parseColor("#1E293B"), Color.parseColor("#7F1D1D"), Color.parseColor("#14532D")
    )

    private val presets = linkedMapOf<String, (Layer, Float) -> Unit>(
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(C_TEXT)
        b.setBackgroundColor(C_PANEL2)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(12), 0, dp(12), 0)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun icon(kind: Int, onClick: () -> Unit): IconView {
        val v = IconView(this, kind)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun row(vararg views: View): HorizontalScrollView {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.setPadding(dp(5), 0, dp(5), 0)
        for (v in views) ll.addView(v)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(ll)
        return hs
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(C_DIM)
        t.textSize = 12f
        t.setPadding(dp(10), dp(10), dp(10), dp(2))
        return t
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /* ---------------- build UI ---------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        val id = intent.getStringExtra("id")
        val loaded = if (id != null) Store.load(id) else null
        if (loaded == null) {
            finish()
            return
        }
        p = loaded
        window.statusBarColor = C_INK
        window.navigationBarColor = C_INK

        editor = EditorView(this, p)
        timeline = TimelineView(this, p)
        editor.onSelect = { refreshProps(); timeline.invalidate() }
        editor.onEdit = { refreshProps() }
        editor.onCommit = { timeline.invalidate(); refreshProps(); commit() }
        timeline.onSeek = { stopPlay(); setTime(it) }
        timeline.onSelect = { editor.invalidate(); refreshProps() }
        timeline.onChanged = { editor.invalidate() }
        timeline.onCommit = { commit() }
        VideoCache.onFrame = { editor.invalidate() }

        val root = FrameLayout(this)
        root.setBackgroundColor(C_INK)
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(C_PANEL)
        top.setPadding(dp(4), dp(4), dp(4), dp(4))
        top.addView(icon(IconView.BACK) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        nameEdit = EditText(this)
        nameEdit.setText(p.name)
        nameEdit.setTextColor(C_TEXT)
        nameEdit.textSize = 17f
        nameEdit.setSingleLine(true)
        nameEdit.setBackgroundColor(Color.TRANSPARENT)
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { p.name = s.toString() }
        })
        top.addView(nameEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(icon(IconView.TUNE) { settings() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(icon(IconView.EXPORT) { doExport() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        main.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        main.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // toolbar
        val tb = LinearLayout(this)
        tb.orientation = LinearLayout.HORIZONTAL
        tb.setBackgroundColor(C_PANEL)
        undoIcon = icon(IconView.UNDO) { undo() }
        redoIcon = icon(IconView.REDO) { redo() }
        playIcon = icon(IconView.PLAY) { if (playing) stopPlay() else startPlay() }
        val items = listOf(
            undoIcon, redoIcon,
            icon(IconView.START) { stopPlay(); setTime(0f) },
            playIcon,
            icon(IconView.END) { stopPlay(); setTime(p.duration) },
            icon(IconView.DUP) { duplicate() },
            icon(IconView.TRASH) { deleteLayer() }
        )
        for (v in items) tb.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
        main.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // timeline + plus button
        val tf = FrameLayout(this)
        tf.addView(timeline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val fab = IconView(this, IconView.PLUS)
        fab.tint = C_GREEN
        val ring = GradientDrawable()
        ring.shape = GradientDrawable.OVAL
        ring.setColor(0xFF1B2A2A.toInt())
        ring.setStroke(dp(2), C_GREEN)
        fab.background = ring
        fab.setPadding(dp(11), dp(11), dp(11), dp(11))
        fab.setOnClickListener { showSheet() }
        val flp = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
        flp.setMargins(0, 0, dp(14), dp(14))
        tf.addView(fab, flp)
        main.addView(tf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))

        // inspector for the selected layer
        inspector = buildInspector()
        inspector.visibility = View.GONE
        main.addView(inspector, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))

        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // bottom sheet for adding things
        sheet = buildSheet()
        sheet.visibility = View.GONE
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM))

        setContentView(root)
        hist.add(Store.toJson(p))
        hi = 0
        setTime(0f)
        updateUndoUI()
    }

    private fun keyRow(name: String, props: IntArray, sb: SeekBar?): LinearLayout {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER_VERTICAL
        ll.setPadding(dp(10), 0, dp(10), 0)
        val tv = TextView(this)
        tv.text = name
        tv.setTextColor(C_TEXT)
        tv.textSize = 13f
        ll.addView(tv, LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (sb != null) {
            ll.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            val hint = TextView(this)
            hint.text = "Drag on the canvas"
            hint.setTextColor(C_DIM)
            hint.textSize = 12f
            ll.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val kb = btn("\u25C6") {
            val l = p.sel
            if (l == null) {
                toast("Select a layer first")
            } else {
                val has = props.all { l.keyAt(it, p.time) != null }
                for (q in props) {
                    if (has) l.removeKey(q, p.time) else l.upsert(q, p.time, l.eval(q, p.time), p.easeIdx)
                }
                refreshProps()
                timeline.invalidate()
                commit()
            }
        }
        kb.layoutParams = LinearLayout.LayoutParams(dp(46), dp(40))
        keyBtns[name] = kb
        ll.addView(kb)
        return ll
    }

    private fun slider(max: Int, onChange: (Layer, Int) -> Unit): SeekBar {
        val sb = SeekBar(this)
        sb.max = max
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                val l = p.sel
                if (fromUser && !syncing && l != null) {
                    onChange(l, v)
                    editor.invalidate()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                refreshProps()
                timeline.invalidate()
                commit()
            }
        })
        return sb
    }

    private fun buildInspector(): ScrollView {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(label("Tap the diamond to set a keyframe at the playhead."))
        panel.addView(keyRow("Position", intArrayOf(P_X, P_Y), null))
        sbScale = slider(500) { l, v -> l.set(P_SCALE, p.time, v / 100f, p.easeIdx) }
        panel.addView(keyRow("Scale", intArrayOf(P_SCALE), sbScale))
        sbRot = slider(720) { l, v -> l.set(P_ROT, p.time, (v - 360).toFloat(), p.easeIdx) }
        panel.addView(keyRow("Rotation", intArrayOf(P_ROT), sbRot))
        sbAlpha = slider(100) { l, v -> l.set(P_ALPHA, p.time, v / 100f, p.easeIdx) }
        panel.addView(keyRow("Opacity", intArrayOf(P_ALPHA), sbAlpha))
        easeBtn = btn("Easing: ${Ease.names[p.easeIdx]}") {
            p.easeIdx = (p.easeIdx + 1) % Ease.names.size
            easeBtn.text = "Easing: ${Ease.names[p.easeIdx]}"
            val l = p.sel
            if (l != null) for (q in 0 until 5) l.keyAt(q, p.time)?.ease = p.easeIdx
            commit()
        }
        panel.addView(row(easeBtn))

        panel.addView(label("Animation presets (start at the playhead)"))
        val presetBtns = presets.map { (name, fn) ->
            btn(name) {
                val l = p.sel
                if (l == null) {
                    toast("Select a layer first")
                } else {
                    fn(l, p.time)
                    refreshProps(); timeline.invalidate(); editor.invalidate(); commit()
                }
            }
        }
        panel.addView(row(*presetBtns.toTypedArray()))

        panel.addView(label("Layer"))
        panel.addView(row(
            btn("Edit text") { editText() }, btn("Colour") { cycleColour() },
            btn("Bigger") { resize(1.15f) }, btn("Smaller") { resize(1f / 1.15f) }
        ))
        panel.addView(row(
            btn("Move up") { move(1) }, btn("Move down") { move(-1) }
        ))
        val pad = View(this)
        panel.addView(pad, LinearLayout.LayoutParams(1, dp(20)))
        val sv = ScrollView(this)
        sv.setBackgroundColor(C_PANEL)
        sv.addView(panel)
        return sv
    }

    private fun buildSheet(): LinearLayout {
        val s = LinearLayout(this)
        s.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(C_PANEL)
        bg.cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
        s.background = bg
        s.elevation = dp(12).toFloat()
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setPadding(dp(8), dp(6), dp(4), dp(2))
        val names = arrayOf("Shapes", "Media", "Text")
        for ((i, n) in names.withIndex()) {
            val b = btn(n) { selectTab(i) }
            b.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            tabBtns.add(b)
            tabs.addView(b)
        }
        tabs.addView(icon(IconView.CLOSE) { hideSheet() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        s.addView(tabs)
        sheetBody = FrameLayout(this)
        s.addView(sheetBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return s
    }

    private fun showSheet() {
        sheet.visibility = View.VISIBLE
        selectTab(sheetTab)
    }

    private fun hideSheet() {
        sheet.visibility = View.GONE
    }

    private fun selectTab(i: Int) {
        sheetTab = i
        for ((k, b) in tabBtns.withIndex()) {
            b.setBackgroundColor(if (k == i) 0xFF2B2F42.toInt() else C_PANEL)
            b.setTextColor(if (k == i) C_GREEN else C_DIM)
        }
        sheetBody.removeAllViews()
        sheetBody.addView(when (i) { 0 -> shapesView(); 1 -> mediaView(); else -> textView() })
    }

    private fun shapesView(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), dp(8), dp(10), dp(8))
        var kind = 0
        for (r in 0 until 2) {
            val rowv = LinearLayout(this)
            rowv.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 4) {
                val k = kind++
                val tile = ShapeTile(this, k)
                tile.setOnClickListener { addShape(k) }
                val lp = LinearLayout.LayoutParams(0, dp(84), 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                tile.background = GradientDrawable().apply { setColor(C_PANEL2); cornerRadius = dp(12).toFloat() }
                rowv.addView(tile, lp)
            }
            col.addView(rowv)
        }
        val sv = ScrollView(this)
        sv.addView(col)
        return sv
    }

    private fun mediaView(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(10), dp(14), dp(10))
        val hint = TextView(this)
        hint.text = "Photos and videos are copied into your project. A video starts at the playhead."
        hint.setTextColor(C_DIM)
        hint.textSize = 13f
        col.addView(hint)
        fun big(label: String, type: String, code: Int): Button {
            val b = btn(label) { pick(type, code) }
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
            return b
        }
        col.addView(big("Add photo", "image/*", 21))
        col.addView(big("Add video", "video/*", 22))
        col.addView(big("Browse all files", "*/*", 23))
        val sv = ScrollView(this)
        sv.addView(col)
        return sv
    }

    private fun textView(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(10), dp(14), dp(10))
        fun big(label: String, size: Float, text: String): Button {
            val b = btn(label) { addText(text, size) }
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
            return b
        }
        col.addView(big("Title", 120f, "Title"))
        col.addView(big("Subtitle", 72f, "Subtitle"))
        col.addView(big("Caption", 44f, "Caption text"))
        val sv = ScrollView(this)
        sv.addView(col)
        return sv
    }

    /* ---------------- history / save ---------------- */

    private fun commit() {
        val s = Store.toJson(p)
        if (hi >= 0 && hi < hist.size && hist[hi] == s) return
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(s)
        if (hist.size > 60) hist.removeAt(0)
        hi = hist.size - 1
        Store.saveRaw(p.id, s)
        updateUndoUI()
    }

    private fun restoreFrom(s: String) {
        val q = Store.fromJson(s)
        val selId = p.sel?.id
        p.copyFrom(q)
        p.sel = p.layers.firstOrNull { it.id == selId }
        p.time = p.time.coerceIn(0f, p.duration)
        Store.saveRaw(p.id, s)
        afterStructure()
        setTime(p.time)
    }

    private fun undo() {
        if (hi > 0) { hi--; restoreFrom(hist[hi]); updateUndoUI() }
    }

    private fun redo() {
        if (hi < hist.size - 1) { hi++; restoreFrom(hist[hi]); updateUndoUI() }
    }

    private fun updateUndoUI() {
        undoIcon.isEnabled = hi > 0
        redoIcon.isEnabled = hi < hist.size - 1
    }

    private fun afterStructure() {
        timeline.invalidate()
        editor.invalidate()
        refreshProps()
    }

    /* ---------------- adding / editing layers ---------------- */

    private fun defaultStart(): Float = p.time.coerceAtMost(p.duration - 0.5f).coerceAtLeast(0f)

    private fun finishAdd(l: Layer) {
        p.layers.add(l)
        p.sel = l
        hideSheet()
        afterStructure()
        commit()
    }

    private fun addShape(kind: Int) {
        val l = p.newLayer(T_SHAPE)
        l.shape = kind
        l.name = Renderer.shapeNames[kind]
        when (kind) {
            7 -> { l.w = 520f; l.h = 18f }
            0 -> { l.w = 340f; l.h = 340f; l.radius = 48f }
            else -> { l.w = 340f; l.h = 340f }
        }
        l.start = defaultStart()
        finishAdd(l)
    }

    private fun addText(text: String, size: Float) {
        val l = p.newLayer(T_TEXT)
        l.text = text
        l.size = size
        l.start = defaultStart()
        finishAdd(l)
    }

    @Suppress("DEPRECATION")
    private fun pick(type: String, code: Int) {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = type
        i.addCategory(Intent.CATEGORY_OPENABLE)
        if (type == "*/*") i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        startActivityForResult(Intent.createChooser(i, "Choose media"), code)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode < 21 || requestCode > 23 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Importing...")
        Thread {
            val name = Store.importMedia(applicationContext, p.id, uri)
            runOnUiThread {
                if (name == null) toast("Could not import that file") else addMedia(name)
            }
        }.start()
    }

    private fun addMedia(name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isVideo = ext in setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
        val path = Store.mediaPath(p.id, name)
        val l = p.newLayer(if (isVideo) T_VIDEO else T_IMAGE)
        l.media = name
        var mw: Int
        var mh: Int
        if (isVideo) {
            val info = MediaCache.videoInfo(path)
            if (info == null || info.dur <= 0f) { toast("Could not read that video"); return }
            mw = info.w
            mh = info.h
            l.srcDur = info.dur
            l.start = defaultStart()
            if (l.start + info.dur > p.duration) p.duration = min(300f, ceil(l.start + info.dur))
            l.end = min(p.duration, l.start + info.dur)
        } else {
            val bmp = MediaCache.image(path)
            if (bmp == null) { toast("Could not read that image"); return }
            mw = bmp.width
            mh = bmp.height
            l.start = defaultStart()
        }
        val fit = minOf(1f, p.W * 0.9f / mw, p.H * 0.6f / mh)
        l.w = mw * fit
        l.h = mh * fit
        finishAdd(l)
        setTime(p.time)
    }

    private fun duplicate() {
        val l = p.sel ?: return toast("Select a layer first")
        val c = l.duplicate(p.nextLayerId())
        p.layers.add(p.layers.indexOf(l) + 1, c)
        p.sel = c
        afterStructure()
        commit()
    }

    private fun move(dir: Int) {
        val l = p.sel ?: return toast("Select a layer first")
        val i = p.layers.indexOf(l)
        val j = i + dir
        if (j < 0 || j >= p.layers.size) return
        p.layers.removeAt(i)
        p.layers.add(j, l)
        afterStructure()
        commit()
    }

    private fun deleteLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        p.layers.remove(l)
        p.sel = null
        afterStructure()
        commit()
    }

    private fun resize(f: Float) {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.type == T_TEXT) l.size = (l.size * f).coerceIn(20f, 400f) else { l.w *= f; l.h *= f }
        editor.invalidate()
        commit()
    }

    private fun cycleColour() {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.type == T_IMAGE || l.type == T_VIDEO) return toast("Photos and videos have no colour")
        val i = palette.indexOf(l.color)
        l.color = palette[(i + 1) % palette.size]
        editor.invalidate()
        commit()
    }

    private fun editText() {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.type != T_TEXT) return toast("Select a text layer")
        val et = EditText(this)
        et.setText(l.text)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this)
            .setTitle("Edit text")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                l.text = et.text.toString().ifEmpty { " " }
                editor.invalidate()
                commit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun settings() {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(16), dp(8), dp(16), dp(4))
        val ratioBtn = btn("Canvas size: ${p.ratio}") {}
        ratioBtn.setOnClickListener {
            val idx = Project.RATIOS.indexOfFirst { it.first == p.ratio }
            p.setRatio(Project.RATIOS[(idx + 1) % Project.RATIOS.size].first)
            ratioBtn.text = "Canvas size: ${p.ratio}"
            editor.invalidate()
            commit()
        }
        val durBtn = btn("Duration: ${p.duration.toInt()}s  (tap to add 1s)") {}
        durBtn.setOnClickListener {
            p.duration = if (p.duration >= 60f) 2f else p.duration + 1f
            durBtn.text = "Duration: ${p.duration.toInt()}s  (tap to add 1s)"
            for (l in p.layers) if (l.end > p.duration) l.end = p.duration
            setTime(p.time)
            commit()
        }
        val bgBtn = btn("Background colour (tap to change)") {}
        bgBtn.setOnClickListener {
            val i = bgPalette.indexOf(p.bg)
            p.bg = bgPalette[(i + 1) % bgPalette.size]
            editor.invalidate()
            commit()
        }
        for (b in arrayOf(ratioBtn, durBtn, bgBtn)) {
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
            ll.addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle("Project settings")
            .setView(ll)
            .setPositiveButton("Done", null)
            .show()
    }

    /* ---------------- time / playback ---------------- */

    private fun setTime(t: Float) {
        p.time = t.coerceIn(0f, p.duration)
        editor.invalidate()
        timeline.invalidate()
        refreshProps()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = System.nanoTime()
            var t = p.time + (now - lastNs) / 1e9f
            lastNs = now
            if (t >= p.duration) t = 0f
            setTime(t)
            handler.postDelayed(this, 16)
        }
    }

    private fun startPlay() {
        if (playing) return
        playing = true
        playIcon.kind = IconView.PAUSE
        playIcon.invalidate()
        lastNs = System.nanoTime()
        handler.post(tick)
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacks(tick)
        playIcon.kind = IconView.PLAY
        playIcon.invalidate()
    }

    private fun refreshProps() {
        val l = p.sel
        inspector.visibility = if (l == null) View.GONE else View.VISIBLE
        syncing = true
        sbScale.progress = ((l?.eval(P_SCALE, p.time) ?: 1f) * 100f).toInt().coerceIn(0, 500)
        sbRot.progress = ((l?.eval(P_ROT, p.time) ?: 0f) + 360f).toInt().coerceIn(0, 720)
        sbAlpha.progress = ((l?.eval(P_ALPHA, p.time) ?: 1f) * 100f).toInt().coerceIn(0, 100)
        syncing = false
        val groups = mapOf(
            "Position" to intArrayOf(P_X, P_Y), "Scale" to intArrayOf(P_SCALE),
            "Rotation" to intArrayOf(P_ROT), "Opacity" to intArrayOf(P_ALPHA)
        )
        for ((name, props) in groups) {
            val b = keyBtns[name] ?: continue
            val on = l != null && props.all { l.keyAt(it, p.time) != null }
            val has = l != null && props.any { l.keys[it].isNotEmpty() }
            b.setBackgroundColor(if (on) C_KEY else C_PANEL2)
            b.setTextColor(if (on) 0xFF2A1D00.toInt() else if (has) C_KEY else C_DIM)
        }
    }

    /* ---------------- export ---------------- */

    private fun doExport() {
        stopPlay()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Rendering video")
            .setMessage("0%")
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            var error: String? = null
            val tmp = File(cacheDir, "export.mp4")
            try {
                Exporter.export(p, tmp, 30) { pct ->
                    runOnUiThread { dlg.setMessage("$pct%") }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            val where = if (error == null) saveToGallery(tmp) else null
            runOnUiThread {
                dlg.dismiss()
                if (error != null) toast("Export failed: $error")
                else toast(if (where != null) "Saved to $where" else "Could not save the video")
            }
        }.start()
    }

    private fun saveToGallery(src: File): String? {
        return try {
            val name = "motion-${System.currentTimeMillis()}.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MotionStudio")
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { it.copyTo(out) }
                }
                "Movies/MotionStudio"
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
                dir.mkdirs()
                val dst = File(dir, name)
                src.copyTo(dst, true)
                dst.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    /* ---------------- lifecycle ---------------- */

    override fun onBackPressed() {
        if (!::sheet.isInitialized) { super.onBackPressed(); return }
        if (sheet.visibility == View.VISIBLE) hideSheet() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (!::p.isInitialized || !::playIcon.isInitialized) return
        stopPlay()
        Store.save(p)
    }

    override fun onStop() {
        super.onStop()
        if (!::p.isInitialized) return
        Store.saveThumb(p)
    }

    override fun onDestroy() {
        super.onDestroy()
        VideoCache.onFrame = null
        VideoCache.release()
    }
}
