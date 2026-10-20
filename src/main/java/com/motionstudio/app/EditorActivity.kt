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
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity(), PanelHost {
    private lateinit var p: Project
    private lateinit var editor: EditorView
    private lateinit var gl: GLEditorView
    private lateinit var stage: FrameLayout
    private lateinit var timeline: TimelineView
    private lateinit var panel: LayerPanel
    private lateinit var panelBox: FrameLayout
    private lateinit var timelineBox: FrameLayout
    private lateinit var projectBar: LinearLayout
    private lateinit var layerBar: LinearLayout
    private lateinit var titleBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var nameEdit: EditText
    private lateinit var layerName: EditText
    private lateinit var playIcon: IconView
    private lateinit var undoIcon: IconView
    private lateinit var redoIcon: IconView
    private lateinit var viewTools: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var sheetBody: FrameLayout
    private val tabBtns = ArrayList<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var lastNs = 0L
    private var sheetTab = 0
    private var glW = 0
    private var glH = 0
    private var lastSelId = -1
    private var clip: Layer? = null
    private val hist = ArrayList<String>()
    private var hi = -1

    private val bgPalette = intArrayOf(
        Color.parseColor("#0D0E14"), Color.BLACK, Color.WHITE,
        Color.parseColor("#1E293B"), Color.parseColor("#7F1D1D"), Color.parseColor("#14532D")
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

    private fun icon(kind: Int, onClick: (View) -> Unit): IconView {
        val v = IconView(this, kind)
        v.setOnClickListener { onClick(it) }
        return v
    }

    private fun bar(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setBackgroundColor(C_PANEL)
        b.setPadding(dp(4), dp(4), dp(4), dp(4))
        return b
    }

    private fun nameField(text: String): EditText {
        val e = EditText(this)
        e.setText(text)
        e.setTextColor(C_TEXT)
        e.textSize = 17f
        e.setSingleLine(true)
        e.setBackgroundColor(Color.TRANSPARENT)
        return e
    }

    /* ---------------- PanelHost ---------------- */

    override fun changed() {
        editor.invalidate()
    }

    override fun title(t: String?) {
        if (!::projectBar.isInitialized) return
        if (t == null) {
            showBar(if (p.sel != null) 1 else 0)
        } else {
            titleText.text = t
            showBar(2)
        }
    }

    override fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun timelineChanged() {
        timeline.invalidate()
    }

    private fun showBar(i: Int) {
        projectBar.visibility = if (i == 0) View.VISIBLE else View.GONE
        layerBar.visibility = if (i == 1) View.VISIBLE else View.GONE
        titleBar.visibility = if (i == 2) View.VISIBLE else View.GONE
    }

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
        panel = LayerPanel(this, p, this)
        editor.onSelect = { refreshProps(); timeline.invalidate() }
        editor.onEdit = { panel.refresh() }
        editor.onCommit = { timeline.invalidate(); panel.refresh(); commit() }
        timeline.onSeek = { stopPlay(); setTime(it) }
        timeline.onSelect = { editor.invalidate(); refreshProps() }
        timeline.onChanged = { editor.invalidate() }
        timeline.onCommit = { commit() }
        timeline.onToggleEye = { l -> l.hidden = !l.hidden; editor.invalidate(); commit() }

        val root = FrameLayout(this)
        root.setBackgroundColor(C_INK)
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL

        // three top bars, only one is visible at a time
        val topStack = FrameLayout(this)
        projectBar = bar()
        projectBar.addView(icon(IconView.BACK) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        nameEdit = nameField(p.name)
        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { p.name = s.toString() }
        })
        projectBar.addView(nameEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        projectBar.addView(icon(IconView.TUNE) { settings() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        projectBar.addView(icon(IconView.EXPORT) { doExport() }, LinearLayout.LayoutParams(dp(44), dp(44)))

        layerBar = bar()
        layerBar.addView(icon(IconView.BACK) { p.sel = null; refreshProps(); editor.invalidate() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        layerName = nameField("")
        layerName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val l = p.sel
                if (l != null) { l.name = s.toString(); timeline.invalidate() }
            }
        })
        layerBar.addView(layerName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layerBar.addView(icon(IconView.DUP) { duplicate() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        layerBar.addView(icon(IconView.TRASH) { deleteLayer() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        layerBar.addView(icon(IconView.DOTS) { moreMenu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        layerBar.visibility = View.GONE

        titleBar = bar()
        titleBar.addView(icon(IconView.BACK) { panel.back() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleText = TextView(this)
        titleText.setTextColor(C_TEXT)
        titleText.textSize = 17f
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.visibility = View.GONE

        for (b in arrayOf(projectBar, layerBar, titleBar)) {
            topStack.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        main.addView(topStack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // preview: GPU view with the touch overlay on top, plus the preview tools column
        stage = FrameLayout(this)
        stage.setBackgroundColor(0xFF0A0B10.toInt())
        gl = GLEditorView(this, p)
        editor.gl = gl
        stage.addView(gl, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        stage.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        viewTools = LinearLayout(this)
        viewTools.orientation = LinearLayout.VERTICAL
        viewTools.setBackgroundColor(0xDD202330.toInt())
        viewTools.setPadding(dp(4), dp(4), dp(4), dp(4))
        val gridBtn = btn("Grid") {}
        gridBtn.setOnClickListener {
            editor.showGrid = !editor.showGrid
            gridBtn.setTextColor(if (editor.showGrid) C_GREEN else C_TEXT)
            editor.invalidate()
        }
        val guideBtn = btn("Guides") {}
        guideBtn.setOnClickListener {
            editor.showGuides = !editor.showGuides
            guideBtn.setTextColor(if (editor.showGuides) C_GREEN else C_TEXT)
            editor.invalidate()
        }
        viewTools.addView(gridBtn)
        viewTools.addView(guideBtn)
        viewTools.visibility = View.GONE
        val vlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP)
        vlp.setMargins(0, dp(8), dp(8), 0)
        stage.addView(viewTools, vlp)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutGl() }
        main.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // toolbar: undo, redo, start, play, end, layer menu, preview tools
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
            icon(IconView.DUP) { layerMenu(it) },
            icon(IconView.TUNE) {
                viewTools.visibility = if (viewTools.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        )
        for (v in items) tb.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
        main.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // timeline with the green plus button
        timelineBox = FrameLayout(this)
        timelineBox.addView(timeline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        timelineBox.addView(fab, flp)
        main.addView(timelineBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))

        // layer panel (only while a layer is selected)
        panelBox = FrameLayout(this)
        panelBox.addView(panel.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panelBox.visibility = View.GONE
        main.addView(panelBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(236)))

        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        sheet = buildSheet()
        sheet.visibility = View.GONE
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM))

        setContentView(root)
        hist.add(Store.toJson(p))
        hi = 0
        setTime(0f)
        updateUndoUI()
        refreshProps()
    }

    /* ---------------- selection ---------------- */

    private fun refreshProps() {
        val id = p.sel?.id ?: -1
        if (id != lastSelId) {
            lastSelId = id
            onSelectionChanged()
        } else {
            panel.refresh()
        }
    }

    private fun onSelectionChanged() {
        val l = p.sel
        val tl = timelineBox.layoutParams as LinearLayout.LayoutParams
        if (l == null) {
            panelBox.visibility = View.GONE
            showBar(0)
            tl.height = dp(200)
        } else {
            panelBox.visibility = View.VISIBLE
            if (layerName.text.toString() != l.name) layerName.setText(l.name)
            tl.height = dp(96)
            panel.showGrid()
        }
        timelineBox.layoutParams = tl
        timeline.invalidate()
        editor.invalidate()
    }

    /* ---------------- menus ---------------- */

    private fun moreMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        for (n in arrayOf(
            "Save to my elements", "Flip horizontal", "Flip vertical", "Fit to canvas",
            "Fill canvas", "Stretch to canvas", "Create clipping mask", "Colour tag..."
        )) pm.menu.add(n)
        pm.setOnMenuItemClickListener { item ->
            val l = p.sel
            if (l != null) {
                when (item.title.toString()) {
                    "Flip horizontal" -> { l.flipH = !l.flipH; editor.invalidate(); commit() }
                    "Flip vertical" -> { l.flipV = !l.flipV; editor.invalidate(); commit() }
                    "Fit to canvas" -> fitMode(0)
                    "Fill canvas" -> fitMode(1)
                    "Stretch to canvas" -> fitMode(2)
                    "Colour tag..." -> tagDialog()
                    else -> toast("Coming in the next update")
                }
            }
            true
        }
        pm.show()
    }

    private fun fitMode(mode: Int) {
        val l = p.sel ?: return
        val sz = Renderer.sizeOf(l)
        val sx = p.W / sz[0]
        val sy = p.H / sz[1]
        if (mode == 2 && l.type != T_TEXT) {
            l.w = p.W.toFloat()
            l.h = p.H.toFloat()
            l.set(P_SCALE, p.time, 1f, p.easeIdx)
        } else {
            l.set(P_SCALE, p.time, if (mode == 1) max(sx, sy) else min(sx, sy), p.easeIdx)
        }
        l.set(P_X, p.time, p.W / 2f, p.easeIdx)
        l.set(P_Y, p.time, p.H / 2f, p.easeIdx)
        editor.invalidate()
        panel.refresh()
        commit()
    }

    private fun tagDialog() {
        val l = p.sel ?: return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(12), dp(12), dp(12), dp(4))
        val dlg = AlertDialog.Builder(this).setTitle("Colour tag").setView(row).setNegativeButton("Close", null).create()
        for (i in TAG_COLORS.indices) {
            val v = View(this)
            val g = GradientDrawable()
            g.setColor(if (i == 0) 0xFF3A3F55.toInt() else TAG_COLORS[i])
            g.cornerRadius = dp(8).toFloat()
            g.setStroke(dp(if (l.tag == i) 3 else 1), if (l.tag == i) Color.WHITE else 0xFF666666.toInt())
            v.background = g
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            lp.setMargins(dp(4), 0, dp(4), 0)
            v.setOnClickListener {
                l.tag = i
                timeline.invalidate()
                commit()
                dlg.dismiss()
            }
            row.addView(v, lp)
        }
        dlg.show()
    }

    private fun layerMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        for (n in arrayOf(
            "Select all layers", "Duplicate layer", "Save preset", "Copy layer", "Paste layer", "Paste style"
        )) pm.menu.add(n)
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Duplicate layer" -> duplicate()
                "Copy layer" -> {
                    val l = p.sel
                    if (l == null) toast("Select a layer first") else { clip = l.duplicate(0); toast("Layer copied") }
                }
                "Paste layer" -> {
                    val c = clip
                    if (c == null) {
                        toast("Nothing copied yet")
                    } else {
                        val n = c.duplicate(p.nextLayerId())
                        n.name = c.name
                        p.layers.add(n)
                        p.sel = n
                        afterStructure()
                        commit()
                    }
                }
                "Paste style" -> {
                    val c = clip
                    val l = p.sel
                    if (c == null || l == null) {
                        toast("Copy a layer and select another one first")
                    } else {
                        l.copyStyleFrom(c)
                        editor.invalidate()
                        commit()
                    }
                }
                else -> toast("Coming in the next update")
            }
            true
        }
        pm.show()
    }

    /* ---------------- add sheet ---------------- */

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
        for ((i, n) in arrayOf("Shapes", "Media", "Text").withIndex()) {
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
        sheetBody.addView(when (i) { 0 -> shapesView(); 1 -> mediaView(); else -> textTab() })
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

    private fun textTab(): View {
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

    override fun commit() {
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

    /** Sizes the GPU preview to the project's aspect ratio, centred in the stage (same maths as the overlay). */
    private fun layoutGl() {
        if (!::stage.isInitialized || !::gl.isInitialized) return
        val w = stage.width
        val h = stage.height
        if (w <= 0 || h <= 0) return
        val m = 16f
        val sc = min((w - 2 * m) / p.W, (h - 2 * m) / p.H)
        val gw = (p.W * sc).toInt().coerceAtLeast(2)
        val gh = (p.H * sc).toInt().coerceAtLeast(2)
        if (gw == glW && gh == glH) return
        glW = gw
        glH = gh
        gl.layoutParams = FrameLayout.LayoutParams(gw, gh, Gravity.CENTER)
    }

    private fun afterStructure() {
        layoutGl()
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
        val mw: Int
        val mh: Int
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

    private fun deleteLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        p.layers.remove(l)
        p.sel = null
        afterStructure()
        commit()
    }

    private fun settings() {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(16), dp(8), dp(16), dp(4))
        val ratioBtn = btn("Canvas size: ${p.ratio}") {}
        ratioBtn.setOnClickListener {
            val idx = Project.RATIOS.indexOfFirst { it.first == p.ratio }
            p.changeRatio(Project.RATIOS[(idx + 1) % Project.RATIOS.size].first)
            ratioBtn.text = "Canvas size: ${p.ratio}"
            layoutGl()
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

    private fun setTime(t: Float, light: Boolean = false) {
        p.time = t.coerceIn(0f, p.duration)
        editor.invalidate()
        timeline.invalidate()
        if (!light) panel.refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = System.nanoTime()
            var t = p.time + (now - lastNs) / 1e9f
            lastNs = now
            if (t >= p.duration) t = 0f
            setTime(t, true)
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
        if (::panel.isInitialized) panel.refresh()
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
        if (sheet.visibility == View.VISIBLE) { hideSheet(); return }
        if (panel.back()) return
        if (p.sel != null) { p.sel = null; refreshProps(); editor.invalidate(); return }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::gl.isInitialized) gl.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::gl.isInitialized) gl.onPause()
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
        VideoCache.release()
    }
}
