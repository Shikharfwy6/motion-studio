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
import android.view.Choreographer
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity(), PanelHost {
    private lateinit var p: Project
    private lateinit var root: FrameLayout
    private lateinit var editor: EditorView
    private lateinit var gl: GLEditorView
    private lateinit var stage: FrameLayout
    private lateinit var timeline: TimelineView
    private lateinit var layerPanel: LayerPanel

    private lateinit var topTitle: TextView
    private lateinit var timeLabel: TextView
    private lateinit var playIcon: IconView
    private lateinit var timelineWrap: FrameLayout
    private lateinit var panelWrap: FrameLayout
    private lateinit var addFab: TextView
    private lateinit var layerActionsBox: LinearLayout
    private lateinit var projectActionsBox: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var lastNs = 0L
    private var glW = 0
    private var glH = 0
    private var lastSelId = -1
    private var subTitle: String? = null
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
        b.setPadding(dp(12), 0, dp(12), 0)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun topIconBtn(symbol: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = symbol
        tv.textSize = 17f
        tv.setTextColor(C_TEXT)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(10), dp(4), dp(10), dp(4))
        tv.setOnClickListener { onClick() }
        return tv
    }

    /* ---------------- PanelHost ---------------- */

    override fun changed() {
        editor.invalidate()
    }

    override fun title(t: String?) {
        subTitle = t
        updateTopBar()
    }

    override fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun timelineChanged() {
        timeline.invalidate()
    }

    /* ---------------- Build Alight Motion Style UI ---------------- */

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
        layerPanel = LayerPanel(this, p, this)

        editor.onSelect = { onSelectionChanged() }
        editor.onEdit = { layerPanel.refresh() }
        editor.onCommit = { timeline.invalidate(); layerPanel.refresh(); commit() }
        editor.onTap = { t, x, y -> if (t == 7) addTextAt(x, y) else addShapeAt(x, y) }

        timeline.onSeek = { stopPlay(); setTime(it) }
        timeline.onSelect = { editor.invalidate(); onSelectionChanged() }
        timeline.onChanged = { editor.invalidate(); layerPanel.refresh() }
        timeline.onCommit = { commit() }
        timeline.onToggleEye = { l -> l.hidden = !l.hidden; editor.invalidate(); commit() }
        timeline.onToggleLock = { l -> l.locked = !l.locked; commit() }

        root = FrameLayout(this)
        root.setBackgroundColor(C_INK)

        val mainCol = LinearLayout(this)
        mainCol.orientation = LinearLayout.VERTICAL

        // 1. Top Header Bar
        mainCol.addView(
            buildTopBar(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )

        // 2. Center Preview Area
        mainCol.addView(
            buildCenterStage(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.05f)
        )

        // 3. Middle Playback & Timecode Bar
        mainCol.addView(
            buildPlaybackBar(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 4. Bottom Area (Timeline + LayerPanel + '+' Green FAB)
        mainCol.addView(
            buildBottomArea(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.25f)
        )

        root.addView(
            mainCol,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        hist.add(Store.toJson(p))
        hi = 0
        editor.tool = 0
        setTime(0f)
        onSelectionChanged()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(C_PANEL)
        bar.setPadding(dp(6), 0, dp(8), 0)

        val backBtn = IconView(this, IconView.BACK)
        backBtn.setOnClickListener {
            if (p.sel != null && layerPanel.back()) return@setOnClickListener
            if (p.sel != null) {
                p.sel = null
                onSelectionChanged()
                editor.invalidate()
                return@setOnClickListener
            }
            finish()
        }
        bar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(42)))

        topTitle = TextView(this)
        topTitle.setTextColor(C_TEXT)
        topTitle.textSize = 16f
        topTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        topTitle.maxLines = 1
        topTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        topTitle.setPadding(dp(6), 0, dp(6), 0)
        bar.addView(topTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Layer selected actions (Duplicate, Delete, 3-dots menu)
        layerActionsBox = LinearLayout(this)
        layerActionsBox.orientation = LinearLayout.HORIZONTAL
        layerActionsBox.gravity = Gravity.CENTER_VERTICAL
        layerActionsBox.addView(topIconBtn("❐") { duplicate() })
        layerActionsBox.addView(topIconBtn("🗑") { deleteLayer() })
        val moreBtn = topIconBtn("⋮") {}
        moreBtn.setOnClickListener { showLayerOverflowMenu(moreBtn) }
        layerActionsBox.addView(moreBtn)
        bar.addView(layerActionsBox)

        // Normal project actions (Settings, Export)
        projectActionsBox = LinearLayout(this)
        projectActionsBox.orientation = LinearLayout.HORIZONTAL
        projectActionsBox.gravity = Gravity.CENTER_VERTICAL
        projectActionsBox.addView(topIconBtn("⚙") { settings() })

        val ex = TextView(this)
        ex.text = "Export"
        ex.setTextColor(Color.BLACK)
        ex.textSize = 12f
        ex.setTypeface(null, android.graphics.Typeface.BOLD)
        ex.gravity = Gravity.CENTER
        ex.setPadding(dp(12), dp(6), dp(12), dp(6))
        val g = GradientDrawable()
        g.setColor(C_GREEN)
        g.cornerRadius = dp(6).toFloat()
        ex.background = g
        ex.setOnClickListener { doExport() }
        val exLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        exLp.leftMargin = dp(4)
        projectActionsBox.addView(ex, exLp)
        bar.addView(projectActionsBox)

        return bar
    }

    private fun buildCenterStage(): View {
        stage = FrameLayout(this)
        stage.setBackgroundColor(Color.parseColor("#090A0F"))

        gl = GLEditorView(this, p)
        editor.gl = gl
        stage.addView(gl, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        stage.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top-left Grid / Guides button (like zoom/view button in video)
        val viewBtn = TextView(this)
        viewBtn.text = "🔍"
        viewBtn.textSize = 14f
        viewBtn.gravity = Gravity.CENTER
        val vg = GradientDrawable()
        vg.setColor(0x99171923.toInt())
        vg.cornerRadius = dp(8).toFloat()
        viewBtn.background = vg
        viewBtn.setOnClickListener { showViewPopup(viewBtn) }
        val vlp = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.START)
        vlp.setMargins(dp(10), dp(10), 0, 0)
        stage.addView(viewBtn, vlp)
        val b3 = TextView(this)
        b3.text = "3D"
        b3.textSize = 12f
        b3.gravity = Gravity.CENTER
        b3.setTextColor(Color.WHITE)
        b3.setTypeface(null, android.graphics.Typeface.BOLD)
        b3.setOnClickListener { toggleView3d() }
        btn3d = b3
        style3d()
        val blp = FrameLayout.LayoutParams(dp(40), dp(36), Gravity.TOP or Gravity.END)
        blp.setMargins(0, dp(10), dp(10), 0)
        stage.addView(b3, blp)

        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutGl() }
        return stage
    }

    private fun buildPlaybackBar(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(C_PANEL)
        wrap.setPadding(dp(6), dp(4), dp(6), dp(4))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val undoBtn = topIconBtn("↶") { undo() }
        val redoBtn = topIconBtn("↷") { redo() }
        val prevKfBtn = IconView(this, IconView.START).apply {
            setOnClickListener { jumpKey(false) }
        }
        playIcon = IconView(this, IconView.PLAY).apply {
            setOnClickListener { if (playing) stopPlay() else startPlay() }
        }
        val nextKfBtn = IconView(this, IconView.END).apply {
            setOnClickListener { jumpKey(true) }
        }
        val clipMenuBtn = topIconBtn("❐") {}
        clipMenuBtn.setOnClickListener { showCopyPastePopup(clipMenuBtn) }

        row.addView(undoBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(redoBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(prevKfBtn, LinearLayout.LayoutParams(0, dp(34), 1f))
        row.addView(playIcon, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(nextKfBtn, LinearLayout.LayoutParams(0, dp(34), 1f))
        row.addView(clipMenuBtn, LinearLayout.LayoutParams(0, dp(36), 1f))

        val timeRow = LinearLayout(this)
        timeRow.orientation = LinearLayout.HORIZONTAL
        timeRow.gravity = Gravity.CENTER_VERTICAL
        timeRow.setPadding(dp(8), 0, dp(8), dp(2))

        val zoomOut = TextView(this).apply {
            text = "−"
            setTextColor(C_DIM)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { timeline.zoomBy(0.77f) }
        }
        timeLabel = TextView(this).apply {
            text = "00:00:00"
            setTextColor(C_TEXT)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val zoomIn = TextView(this).apply {
            text = "+"
            setTextColor(C_DIM)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { timeline.zoomBy(1.3f) }
        }

        timeRow.addView(zoomOut)
        timeRow.addView(timeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(zoomIn)

        wrap.addView(row)
        wrap.addView(timeRow)
        return wrap
    }

    private fun buildBottomArea(): View {
        val bottomRoot = FrameLayout(this)
        bottomRoot.setBackgroundColor(C_INK)

        val splitCol = LinearLayout(this)
        splitCol.orientation = LinearLayout.VERTICAL

        timelineWrap = FrameLayout(this)
        timelineWrap.addView(
            timeline,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        splitCol.addView(
            timelineWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        panelWrap = FrameLayout(this)
        panelWrap.visibility = View.GONE
        panelWrap.addView(
            layerPanel.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        splitCol.addView(
            panelWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.8f)
        )

        bottomRoot.addView(
            splitCol,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Green '+' Floating Button (Alight Motion style)
        addFab = TextView(this)
        addFab.text = "+"
        addFab.textSize = 26f
        addFab.setTextColor(Color.BLACK)
        addFab.gravity = Gravity.CENTER
        val fabBg = GradientDrawable()
        fabBg.shape = GradientDrawable.OVAL
        fabBg.setColor(C_GREEN)
        addFab.background = fabBg
        addFab.setOnClickListener { showAddLayerDialog() }
        val fabLp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
        fabLp.setMargins(0, 0, dp(18), dp(18))
        bottomRoot.addView(addFab, fabLp)

        return bottomRoot
    }

    /* ---------------- Selection & Layout State ---------------- */

    private fun onSelectionChanged() {
        val sel = p.sel
        val id = sel?.id ?: -1
        if (id != lastSelId) {
            lastSelId = id
            subTitle = null
            timeline.invalidate()
            editor.invalidate()
            if (sel != null) {
                // Show compact timeline strip + bottom LayerPanel (just like video 0:10)
                timelineWrap.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(76),
                    0f
                )
                panelWrap.visibility = View.VISIBLE
                addFab.visibility = View.GONE
                layerPanel.showGrid()
            } else {
                // Show full-height timeline + green '+' button
                timelineWrap.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                panelWrap.visibility = View.GONE
                addFab.visibility = View.VISIBLE
            }
        } else {
            layerPanel.refresh()
        }
        updateTopBar()
    }

    private fun updateTopBar() {
        if (!::topTitle.isInitialized) return
        val sel = p.sel
        if (sel != null) {
            topTitle.text = subTitle ?: sel.name
            layerActionsBox.visibility = View.VISIBLE
            projectActionsBox.visibility = View.GONE
        } else {
            topTitle.text = p.name
            layerActionsBox.visibility = View.GONE
            projectActionsBox.visibility = View.VISIBLE
        }
    }

    /* ---------------- Dialogs & Popups ---------------- */

    private fun showAddLayerDialog() {
        val options = arrayOf(
            "🖼  Media (Image / Video)",
            "Aa  Text",
            "▭  Rectangle Shape",
            "●  Circle Shape",
            "★  Star / Polygon Shape",
            "━  Line Shape",
            "✨  Particles",
            "🎥  3D Camera"
        )
        AlertDialog.Builder(this)
            .setTitle("Add Layer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pick("*/*", 23)
                    1 -> addText("Text", 96f)
                    2 -> addShape(0)
                    3 -> addShape(1)
                    4 -> addShape(3)
                    5 -> addShape(7)
                    6 -> addParticles()
                    7 -> addCamera()
                }
            }
            .show()
    }

    private fun showLayerOverflowMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val items = listOf(
            "Flip horizontal" to { editSel { it.flipH = !it.flipH } },
            "Flip vertical" to { editSel { it.flipV = !it.flipV } },
            "Fit to composition area" to { fitMode(0) },
            "Fill composition area" to { fitMode(1) },
            "Stretch to composition area" to { fitMode(2) },
            "3D layer ON / OFF" to { layerPanel.open("3d") },
            "Colour tag..." to { tagDialog() },
            "Hide / Show layer" to { editSel { it.hidden = !it.hidden } },
            "Lock / Unlock layer" to { editSel { it.locked = !it.locked } }
        )
        for ((i, pair) in items.withIndex()) pm.menu.add(0, i, i, pair.first)
        pm.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second()
            true
        }
        pm.show()
    }

    private fun showCopyPastePopup(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val items = listOf(
            "Copy layer" to { copyLayer() },
            "Paste layer" to { pasteLayer() },
            "Paste style" to { pasteStyle() },
            "Duplicate layer" to { duplicate() }
        )
        for ((i, pair) in items.withIndex()) pm.menu.add(0, i, i, pair.first)
        pm.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second()
            true
        }
        pm.show()
    }

    private fun showViewPopup(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val items = listOf(
            "3D view on / off" to { toggleView3d() },
            "Toggle Grid" to {
                editor.showGrid = !editor.showGrid
                editor.invalidate()
            },
            "Toggle Guides" to {
                editor.showGuides = !editor.showGuides
                editor.invalidate()
            },
            "Fit duration to media" to {
                p.refitAfterDelete(false)
                afterStructure()
                commit()
            }
        )
        for ((i, pair) in items.withIndex()) pm.menu.add(0, i, i, pair.first)
        pm.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second()
            true
        }
        pm.show()
    }

    private fun jumpKey(next: Boolean) {
        val l = p.sel ?: return
        var best = -1f
        for (ks in l.keys) for (k in ks) {
            if (next && k.t > p.time + 0.01f && (best < 0f || k.t < best)) best = k.t
            if (!next && k.t < p.time - 0.01f && (best < 0f || k.t > best)) best = k.t
        }
        if (best >= 0f) {
            stopPlay()
            setTime(best)
        } else {
            toast(if (next) "No next keyframe" else "No previous keyframe")
        }
    }

    /* ---------------- History & Save ---------------- */

    override fun commit() {
        val s = Store.toJson(p)
        if (hi >= 0 && hi < hist.size && hist[hi] == s) return
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(s)
        if (hist.size > 60) hist.removeAt(0)
        hi = hist.size - 1
        Store.saveRaw(p.id, s)
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
        if (hi > 0) { hi--; restoreFrom(hist[hi]) } else toast("Nothing to undo")
    }

    private fun redo() {
        if (hi < hist.size - 1) { hi++; restoreFrom(hist[hi]) } else toast("Nothing to redo")
    }

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
        lastSelId = -2 // Force UI sync
        onSelectionChanged()
        timeLabel.text = fmtTime(p.time)
    }

    /* ---------------- Adding / Editing Layers ---------------- */

    private fun defaultStart(): Float = p.time.coerceAtMost(p.duration - 0.5f).coerceAtLeast(0f)

    private fun finishAdd(l: Layer) {
        p.layers.add(l)
        p.sel = l
        afterStructure()
        commit()
    }

    private fun addShape(kind: Int) = addShapeAt(p.W / 2f, p.H / 2f, kind)

    private fun addShapeAt(x: Float, y: Float, kind: Int = 0) {
        val l = p.newLayer(T_SHAPE)
        l.shape = kind
        l.name = Renderer.shapeNames[kind]
        when (kind) {
            7 -> { l.w = 520f; l.h = 18f }
            0 -> { l.w = 340f; l.h = 340f; l.radius = 48f }
            else -> { l.w = 340f; l.h = 340f }
        }
        l.base[P_X] = x
        l.base[P_Y] = y
        l.start = defaultStart()
        finishAdd(l)
    }

    private fun addText(text: String, size: Float) = addTextAt(p.W / 2f, p.H / 2f, text, size)

    private fun addTextAt(x: Float, y: Float, text: String = "Text", size: Float = 96f) {
        val l = p.newLayer(T_TEXT)
        l.text = text
        l.size = size
        l.base[P_X] = x
        l.base[P_Y] = y
        l.start = defaultStart()
        finishAdd(l)
    }

    private fun addCamera() {
        if (p.layers.any { it.type == T_CAMERA }) {
            toast("This composition already has a camera")
            return
        }
        val l = p.newLayer(T_CAMERA)
        l.start = 0f
        l.end = p.duration
        l.w = 10f
        l.h = 10f
        finishAdd(l)
        toast("3D Camera added")
    }

    private fun addParticles() {
        val l = p.newLayer(T_PARTICLES)
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
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode < 21 || requestCode > 23) return
        toast("Importing...")
        Thread {
            val name = Store.importMedia(applicationContext, p.id, uri)
            runOnUiThread {
                if (name == null) toast("Could not import that file") else addMedia(name, defaultStart())
            }
        }.start()
    }

    private fun addMedia(name: String, startAt: Float) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isVideo = ext in setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
        val path = Store.mediaPath(p.id, name)
        val l = p.newLayer(if (isVideo) T_VIDEO else T_IMAGE)
        l.media = name
        val mw: Int
        val mh: Int
        val start = startAt.coerceIn(0f, max(0f, p.duration - 0.5f))
        if (isVideo) {
            val info = MediaCache.videoInfo(path)
            if (info == null || info.dur <= 0f) { toast("Could not read that video"); return }
            mw = info.w
            mh = info.h
            l.srcDur = info.dur
            l.start = start
            l.autoEnd = false
            if (start + info.dur > p.duration) p.setDuration(min(300f, ceil(start + info.dur)))
            l.end = min(p.duration, start + info.dur)
        } else {
            val bmp = MediaCache.image(path)
            if (bmp == null) { toast("Could not read that image"); return }
            mw = bmp.width
            mh = bmp.height
            l.start = start
        }
        val fit = minOf(1f, p.W * 0.9f / mw, p.H * 0.6f / mh)
        l.w = mw * fit
        l.h = mh * fit
        finishAdd(l)
        setTime(p.time)
    }

    private fun editSel(block: (Layer) -> Unit) {
        val l = p.sel ?: run { toast("Select a layer first"); return }
        block(l)
        editor.invalidate()
        timeline.invalidate()
        layerPanel.refresh()
        commit()
    }

    private fun duplicate() {
        val l = p.sel ?: return toast("Select a layer first")
        val c = l.duplicate(p.nextLayerId())
        p.layers.add(p.layers.indexOf(l) + 1, c)
        p.sel = c
        afterStructure()
        commit()
    }

    private fun copyLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        clip = l.duplicate(0)
        toast("Layer copied")
    }

    private fun pasteLayer() {
        val c = clip ?: return toast("Nothing copied yet")
        val n = c.duplicate(p.nextLayerId())
        n.name = c.name
        p.layers.add(n)
        p.sel = n
        afterStructure()
        commit()
    }

    private fun pasteStyle() {
        val c = clip
        val l = p.sel
        if (c == null || l == null) {
            toast("Copy a layer, then select another one")
            return
        }
        l.copyStyleFrom(c)
        editor.invalidate()
        commit()
    }

    private fun deleteLayer() {
        val l = p.sel ?: return toast("Select a layer first")
        if (l.locked) return toast("Layer is locked")
        val wasMedia = !l.autoEnd
        p.layers.remove(l)
        p.sel = null
        p.refitAfterDelete(wasMedia)
        afterStructure()
        commit()
    }

    private fun fitMode(mode: Int) {
        val l = p.sel ?: return toast("Select a layer first")
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
        layerPanel.refresh()
        commit()
    }

    private fun tagDialog() {
        val l = p.sel ?: return toast("Select a layer first")
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
            p.setDuration(if (p.duration >= 60f) 2f else p.duration + 1f)
            durBtn.text = "Duration: ${p.duration.toInt()}s  (tap to add 1s)"
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
        fun blurLabel() = when {
            p.shutter <= 0f -> "Motion blur: Off"
            p.shutter <= 180f -> "Motion blur: Normal (180°)"
            else -> "Motion blur: Strong (360°)"
        }
        val blurBtn = btn(blurLabel()) {}
        blurBtn.setOnClickListener {
            p.shutter = if (p.shutter <= 0f) 180f else if (p.shutter <= 180f) 360f else 0f
            blurBtn.text = blurLabel()
            editor.invalidate()
            commit()
        }
        for (b in arrayOf(ratioBtn, durBtn, bgBtn, blurBtn)) {
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
            ll.addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle("Composition settings")
            .setView(ll)
            .setPositiveButton("Done", null)
            .show()
    }

    /* ---------------- Time & Playback ---------------- */

    private var btn3d: TextView? = null

    private fun style3d() {
        val b = btn3d ?: return
        val g = GradientDrawable()
        g.setColor(if (p.view3d) 0xFF1473E6.toInt() else 0x99171923.toInt())
        g.cornerRadius = dp(8).toFloat()
        b.background = g
    }

    /** Shows the whole composition in 3D space (drag = orbit, pinch = zoom). It is never exported. */
    private fun toggleView3d() {
        p.view3d = !p.view3d
        style3d()
        if (p.view3d) toast("3D view: drag to orbit, pinch to zoom. The export is not affected.")
        editor.invalidate()
    }

    private fun setTime(t: Float, light: Boolean = false) {
        p.time = t.coerceIn(0f, p.duration)
        editor.invalidate()
        timeline.invalidate()
        val fr = (p.time * 30f).toInt()
        if (!light || fr != lastLabelFrame) {
            lastLabelFrame = fr
            timeLabel.text = fmtTime(p.time)
        }
        if (!light) layerPanel.refresh()
    }

    private var lastLabelFrame = -1

    // playback is driven by the display refresh (vsync), so it stays smooth and in step with the screen
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            var dt = (frameTimeNanos - lastNs) / 1e9f
            if (dt < 0f) dt = 0f
            if (dt > 0.1f) dt = 0.1f
            lastNs = frameTimeNanos
            var t = p.time + dt
            if (t >= p.duration) t = 0f
            setTime(t, true)
            timeline.follow()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startPlay() {
        if (playing) return
        playing = true
        playIcon.kind = IconView.PAUSE
        playIcon.invalidate()
        lastNs = System.nanoTime()
        p.playing = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun stopPlay() {
        playing = false
        p.playing = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        if (::playIcon.isInitialized) {
            playIcon.kind = IconView.PLAY
            playIcon.invalidate()
        }
        if (::layerPanel.isInitialized) layerPanel.refresh()
    }

    /* ---------------- Export ---------------- */

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

    /* ---------------- Lifecycle ---------------- */

    override fun onBackPressed() {
        if (::layerPanel.isInitialized && p.sel != null) {
            if (layerPanel.back()) return
            p.sel = null
            onSelectionChanged()
            editor.invalidate()
            return
        }
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
