package com.motionstudio.app

import android.content.Context
import kotlin.math.min
import android.widget.SeekBar
import android.widget.HorizontalScrollView
import android.text.InputType
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot

/* ---------------- drag and drop ---------------- */

/** Full-screen transparent layer that shows the dragged item and reports where it was dropped. */
class DragLayer(context: Context) : FrameLayout(context) {
    private val ghost = TextView(context)
    private val d = resources.displayMetrics.density
    private val a = IntArray(2)
    private val b = IntArray(2)
    private var payload: Any? = null
    var active = false
        private set
    var onDrop: ((Any, Float, Float) -> Unit)? = null

    init {
        ghost.setTextColor(Color.WHITE)
        ghost.textSize = 11f
        ghost.setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
        val g = GradientDrawable()
        g.setColor(0xEE1473E6.toInt())
        g.cornerRadius = 8 * d
        ghost.background = g
        ghost.visibility = View.GONE
        addView(ghost, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Position of a touch inside [v], in this layer's coordinates. */
    fun pos(v: View, e: MotionEvent): FloatArray {
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        return floatArrayOf(a[0] - b[0] + e.x, a[1] - b[1] + e.y)
    }

    /** Rectangle of [v] in this layer's coordinates: left, top, right, bottom. */
    fun rectOf(v: View): FloatArray {
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return floatArrayOf(l, t, l + v.width, t + v.height)
    }

    fun inside(v: View, x: Float, y: Float): Boolean {
        val r = rectOf(v)
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]
    }

    fun begin(label: String, data: Any, x: Float, y: Float) {
        payload = data
        ghost.text = label
        ghost.visibility = View.VISIBLE
        active = true
        move(x, y)
    }

    fun move(x: Float, y: Float) {
        ghost.x = x - ghost.width / 2f
        ghost.y = y - ghost.height - 14 * d
    }

    fun end(x: Float, y: Float) {
        if (!active) return
        active = false
        ghost.visibility = View.GONE
        val pl = payload
        payload = null
        if (pl != null) onDrop?.invoke(pl, x, y)
    }

    fun cancel() {
        active = false
        payload = null
        ghost.visibility = View.GONE
    }
}

/** Tap = [onClick], press and hold then move = drag [data] to a drop target. Works with finger and with the virtual cursor. */
fun attachDrag(v: View, drag: DragLayer, label: String, data: Any, onClick: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
    val last = FloatArray(2)
    var dragging = false
    var moved = false
    var downX = 0f
    var downY = 0f
    val start = Runnable {
        dragging = true
        v.parent?.requestDisallowInterceptTouchEvent(true)
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        drag.begin(label, data, last[0], last[1])
    }
    v.setOnTouchListener { view, e ->
        val p = drag.pos(view, e)
        last[0] = p[0]
        last[1] = p[1]
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                dragging = false
                h.postDelayed(start, 350)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    drag.move(p[0], p[1])
                } else if (hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    h.removeCallbacks(start)
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(start)
                if (dragging) drag.end(p[0], p[1]) else if (!moved) onClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(start)
                if (dragging) drag.cancel()
                dragging = false
            }
        }
        true
    }
}

/* ---------------- panel resizer ---------------- */

/** Thin bar between panels. [horizontalBar] true = it is a horizontal bar (drag up/down). */
class Resizer(context: Context, private val horizontalBar: Boolean, private val onDrag: (Float) -> Unit) : View(context) {
    private val loc = IntArray(2)
    private var last = 0f

    init {
        setBackgroundColor(0xFF141414.toInt())
    }

    private fun pos(e: MotionEvent): Float {
        getLocationInWindow(loc)
        return if (horizontalBar) loc[1] + e.y else loc[0] + e.x
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                last = pos(e)
                setBackgroundColor(0xFF1473E6.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                val p = pos(e)
                onDrag(p - last)
                last = p
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setBackgroundColor(0xFF141414.toInt())
        }
        return true
    }
}

/* ---------------- Project panel: whole folders, drag into the timeline ---------------- */

class DocItem(val name: String, val uri: Uri, val mime: String, val isDir: Boolean, val depth: Int) {
    val isMedia get() = mime.startsWith("image/") || mime.startsWith("video/")
}

object FolderScan {
    /** Lists a picked folder (and sub-folders, 4 levels deep). The folder itself is the first entry. */
    fun list(ctx: Context, tree: Uri): List<DocItem> {
        val out = ArrayList<DocItem>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Folder" }
        out.add(DocItem(rootName, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, true, 0))

        fun walk(docId: String, depth: Int) {
            val kids = ArrayList<DocItem>()
            val ids = HashMap<DocItem, String>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: "?"
                    val mime = c.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val item = DocItem(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, isDir, depth)
                    kids.add(item)
                    ids[item] = id
                }
            }
            kids.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            for (k in kids) {
                out.add(k)
                if (k.isDir && depth < 4) walk(ids[k] ?: continue, depth + 1)
            }
        }
        walk(rootId, 1)
        return out
    }
}

class ProjectPanel(
    context: Context,
    private val drag: DragLayer,
    private val onImportFolder: () -> Unit,
    private val onPick: (DocItem) -> Unit
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val list = LinearLayout(context)
    private var items: List<DocItem> = emptyList()
    private val collapsed = HashSet<String>()
    private var selected: DocItem? = null

    init {
        orientation = VERTICAL
        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setBackgroundColor(0xFF2D2D2D.toInt())
        head.setPadding((8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        val t = TextView(context)
        t.text = "PROJECT"
        t.setTextColor(0xFFAAAAAA.toInt())
        t.textSize = 9f
        t.maxLines = 1
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        head.addView(t, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val imp = TextView(context)
        imp.text = "\uD83D\uDCC2 Import"
        imp.setTextColor(0xFF4DA3FF.toInt())
        imp.textSize = 9f
        imp.maxLines = 1
        imp.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        imp.setOnClickListener { onImportFolder() }
        head.addView(imp)
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.orientation = VERTICAL
        list.setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
        val sv = ScrollView(context)
        sv.addView(list)
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render()
    }

    fun setItems(newItems: List<DocItem>) {
        items = newItems
        render()
    }

    private fun icon(i: DocItem): String = when {
        i.isDir -> "\uD83D\uDCC1"
        i.mime.startsWith("image/") -> "\uD83D\uDDBC\uFE0F"
        i.mime.startsWith("video/") -> "\uD83C\uDFAC"
        i.mime.startsWith("audio/") -> "\uD83C\uDFB5"
        else -> "\uD83D\uDCC4"
    }

    private fun render() {
        list.removeAllViews()
        if (items.isEmpty()) {
            val t = TextView(context)
            t.text = "Tap \"Import\" and pick a folder. Then press and hold a file and drag it onto the timeline."
            t.setTextColor(0xFF777777.toInt())
            t.textSize = 10f
            t.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), 0)
            list.addView(t)
            return
        }
        var skipBelow = -1
        for (it in items) {
            if (skipBelow >= 0) {
                if (it.depth > skipBelow) continue
                skipBelow = -1
            }
            if (it.isDir && collapsed.contains(it.uri.toString())) skipBelow = it.depth
            val row = TextView(context)
            val arrow = if (it.isDir) (if (collapsed.contains(it.uri.toString())) "\u25B8 " else "\u25BE ") else ""
            row.text = arrow + icon(it) + " " + it.name
            row.setTextColor(if (it === selected) Color.WHITE else if (it.isDir || it.isMedia) 0xFFCCCCCC.toInt() else 0xFF777777.toInt())
            row.textSize = 11f
            row.maxLines = 1
            row.ellipsize = android.text.TextUtils.TruncateAt.END
            row.setPadding(((6 + it.depth * 10) * d).toInt(), (5 * d).toInt(), (4 * d).toInt(), (5 * d).toInt())
            if (it === selected) row.setBackgroundColor(0xFF2F4F7F.toInt())
            val item = it
            if (item.isDir) {
                row.setOnClickListener {
                    val k = item.uri.toString()
                    if (!collapsed.remove(k)) collapsed.add(k)
                    render()
                }
            } else if (item.isMedia) {
                attachDrag(row, drag, icon(item) + " " + item.name, item) {
                    selected = item
                    onPick(item)
                    render()
                }
            } else {
                row.setOnClickListener { selected = item; render() }
            }
            list.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}

/* ---------------- Effects & Presets ---------------- */

class Effect(val id: String, val label: String, val enabled: Boolean = true)

object EffectCatalog {
    val cats: List<Pair<String, List<Effect>>> = listOf(
        "3D Channel" to listOf(
            Effect("move", "Position X / Y / Z"),
            Effect("rotate", "Rotation X / Y / Z"),
            Effect("3d", "Make 3D layer (on / off)"),
            Effect("camera", "3D Camera"),
            Effect("preset:Orbit 360", "Orbit 360"),
            Effect("preset:Fly in 3D", "Fly in 3D"),
            Effect("preset:Flip 3D", "Flip 3D"),
            Effect("preset:Dolly in", "Dolly in (Z)")
        ),
        "Transform" to listOf(
            Effect("scale", "Scale / Size"),
            Effect("skew", "Skew & Flip"),
            Effect("blend", "Opacity & Blend mode")
        ),
        "Color Correction" to listOf(
            Effect("fill", "Fill, Gradient, Colour"),
            Effect("border", "Stroke"),
            Effect("shadow", "Drop Shadow")
        ),
        "Generate" to listOf(
            Effect("particles", "Particles"),
            Effect("text", "Text")
        ),
        "Time" to listOf(Effect("speed", "Speed (video)")),
        "Expression" to listOf(
            Effect("wiggle", "Wiggle & Loop"),
            Effect("preset:Camera shake", "Camera shake")
        ),
        "Animation Presets" to Presets.all.keys.map { Effect("preset:$it", it) },
        "Blur & Sharpen" to listOf(Effect("none", "Gaussian Blur (coming soon)", false))
    )
}

/** What the Effects panel needs from the editor screen. */
interface EffectsHost {
    fun changed()                 // redraw the preview
    fun commit()                  // save to history and disk
    fun toast(s: String)
    fun timelineChanged()
    fun create(kind: String)      // "camera", "particles" or "text": add a new layer
}

/**
 * Effects & Presets. Tap an effect and its controls open right below it inside this panel:
 * a slider for every value and a keyframe diamond next to it. Nothing opens over the timeline.
 */
class EffectsPanel(
    context: Context,
    private val p: Project,
    private val drag: DragLayer,
    private val host: EffectsHost
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val list = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val openCats = HashSet<String>()
    private val openFx = HashSet<String>()
    private val syncs = ArrayList<() -> Unit>()
    private val rowViews = HashMap<String, View>()
    private var query = ""
    private var quiet = false
    private var renderedSelId = Int.MIN_VALUE
    private var renderedType = -2
    private val selLabel = TextView(context)

    private val swatches = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF404040.toInt(), 0xFF000000.toInt(),
        0xFFFF4D4D.toInt(), 0xFFFF7A3D.toInt(), 0xFFFFC247.toInt(), 0xFFFFE94D.toInt(),
        0xFF4ADE80.toInt(), 0xFF2DD4BF.toInt(), 0xFF38BDF8.toInt(), 0xFF3B82F6.toInt(),
        0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(), 0xFF7F1D1D.toInt(), 0xFF14532D.toInt(),
        0xFF1E3A8A.toInt(), 0xFFF5E6C8.toInt()
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF212121.toInt())
        val head = TextView(context)
        head.text = "EFFECTS & PRESETS"
        head.setTextColor(0xFFAAAAAA.toInt())
        head.textSize = 9f
        head.maxLines = 1
        head.setTypeface(null, android.graphics.Typeface.BOLD)
        head.setBackgroundColor(0xFF2D2D2D.toInt())
        head.setPadding(dp(8), dp(5), dp(8), dp(5))
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        selLabel.textSize = 10f
        selLabel.maxLines = 1
        selLabel.setPadding(dp(8), dp(3), dp(8), dp(3))
        selLabel.setBackgroundColor(0xFF1A1A1A.toInt())
        addView(selLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val box = EditText(context)
        box.hint = "\uD83D\uDD0D Search effects..."
        box.setHintTextColor(0xFF666666.toInt())
        box.setTextColor(0xFFCCCCCC.toInt())
        box.textSize = 11f
        box.setSingleLine(true)
        box.setBackgroundColor(0xFF141414.toInt())
        box.setPadding(dp(8), dp(4), dp(8), dp(4))
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString().trim().lowercase()
                render()
            }
        })
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(6), dp(5), dp(6), dp(4))
        addView(box, lp)
        list.orientation = VERTICAL
        list.setPadding(0, 0, 0, dp(40))
        scroll.addView(list)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        openCats.add("3D Channel")
        render()
    }

    /* ---------------- public ---------------- */

    /** Expands one effect (and its category) and scrolls to it. */
    fun open(id: String) {
        for ((cat, effects) in EffectCatalog.cats) {
            if (effects.any { it.id == id }) openCats.add(cat)
        }
        openFx.add(id)
        render()
        scroll.post { rowViews[id]?.let { scroll.smoothScrollTo(0, it.top) } }
    }

    /** Call when the time, a value or the selection changed. */
    fun refresh() {
        val l = p.sel
        if ((l?.id ?: -1) != renderedSelId || (l?.type ?: -1) != renderedType) {
            render()
            return
        }
        for (f in ArrayList(syncs)) f()
    }

    /* ---------------- row helpers ---------------- */

    private fun sel(): Layer? = p.sel

    private fun lbl(text: String): TextView {
        val t = TextView(context)
        t.text = text
        t.setTextColor(0xFFAAAAAA.toInt())
        t.textSize = 10f
        t.maxLines = 1
        return t
    }

    private fun hint(parent: LinearLayout, text: String) {
        val t = lbl(text)
        t.maxLines = 3
        t.setPadding(dp(24), dp(4), dp(8), dp(6))
        parent.addView(t)
    }

    private fun roundBg(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun afterEdit() {
        host.changed()
        host.timelineChanged()
        host.commit()
        refresh()
    }

    private fun keyIcon(prop: Int): IconView {
        val k = IconView(context, IconView.KEY)
        k.setOnClickListener {
            val l = sel() ?: return@setOnClickListener
            if (l.keyAt(prop, p.time) != null) l.removeKey(prop, p.time) else l.upsert(prop, p.time, l.eval(prop, p.time), p.easeIdx)
            afterEdit()
        }
        syncs.add {
            val l = sel()
            val on = l != null && l.keyAt(prop, p.time) != null
            val has = l != null && l.keys[prop].isNotEmpty()
            k.filled = on
            k.tint = if (on || has) C_KEY else 0xFF777777.toInt()
            k.invalidate()
        }
        return k
    }

    private fun fmt(v: Float, range: Float) = if (range > 30f) String.format("%.0f", v) else String.format("%.2f", v)

    private fun typeValue(title: String, get: Float, onValue: (Float) -> Unit) {
        val et = EditText(context)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        et.setText(String.format("%.2f", get))
        et.setSelection(et.text.length)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().toFloatOrNull()
                if (v != null) onValue(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** label ........ value (tap to type)  keyframe diamond / full width slider below. */
    private fun numRow(
        parent: LinearLayout, label: String, min: Float, max: Float, prop: Int?,
        get: (Layer) -> Float, set: (Layer, Float) -> Unit
    ) {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        box.setPadding(dp(22), dp(2), dp(4), dp(2))
        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val name = lbl(label)
        val value = TextView(context)
        value.textSize = 10f
        value.setTextColor(0xFF7FC4FF.toInt())
        value.setPadding(dp(6), dp(2), dp(6), dp(2))
        top.addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(value)
        if (prop != null) top.addView(keyIcon(prop), LayoutParams(dp(26), dp(26)))
        box.addView(top)
        val sb = SeekBar(context)
        sb.max = 1000
        sb.setPadding(dp(8), 0, dp(8), 0)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (!fromUser || quiet) return
                val l = sel() ?: return
                set(l, min + v / 1000f * (max - min))
                value.text = fmt(get(l), max - min)
                host.changed()
                for (f in ArrayList(syncs)) f()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                host.timelineChanged()
                host.commit()
                refresh()
            }
        })
        value.setOnClickListener {
            val l = sel() ?: return@setOnClickListener
            typeValue(label, get(l)) { v -> set(l, v); afterEdit() }
        }
        box.addView(sb, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(box)
        syncs.add {
            val l = sel()
            if (l == null) {
                value.text = "-"
                sb.isEnabled = false
            } else {
                val v = get(l)
                value.text = fmt(v, max - min)
                quiet = true
                sb.progress = (((v - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
                quiet = false
                sb.isEnabled = true
            }
        }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 10f
        t.maxLines = 1
        t.gravity = Gravity.CENTER
        t.setTextColor(if (on) C_GREEN else 0xFFCCCCCC.toInt())
        t.background = roundBg(if (on) 0xFF2B2F42.toInt() else 0xFF333333.toInt(), 4)
        t.setPadding(dp(4), dp(6), dp(4), dp(6))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chipRow(parent: LinearLayout, options: List<String>, get: (Layer) -> Int, set: (Layer, Int) -> Unit) {
        val l = sel() ?: return
        val cur = get(l)
        var idx = 0
        while (idx < options.size) {
            val r = LinearLayout(context)
            r.orientation = HORIZONTAL
            r.setPadding(dp(22), dp(2), dp(4), dp(2))
            for (k in idx until min(idx + 3, options.size)) {
                val lp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(2), 0, dp(2), 0)
                r.addView(chip(options[k], k == cur) {
                    val ly = sel() ?: return@chip
                    set(ly, k)
                    host.changed()
                    host.timelineChanged()
                    host.commit()
                    render()
                }, lp)
            }
            parent.addView(r)
            idx += 3
        }
    }

    private fun toggleRow(parent: LinearLayout, label: String, get: (Layer) -> Boolean, set: (Layer, Boolean) -> Unit) {
        val r = LinearLayout(context)
        r.orientation = HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(22), dp(3), dp(6), dp(3))
        val name = lbl(label)
        val sw = TextView(context)
        sw.textSize = 10f
        sw.gravity = Gravity.CENTER
        sw.setPadding(dp(10), dp(5), dp(10), dp(5))
        sw.setOnClickListener {
            val l = sel() ?: return@setOnClickListener
            set(l, !get(l))
            afterEdit()
        }
        r.addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(sw)
        parent.addView(r)
        syncs.add {
            val l = sel()
            val on = l != null && get(l)
            sw.text = if (on) "ON" else "OFF"
            sw.setTextColor(if (on) C_GREEN else 0xFF999999.toInt())
            sw.background = roundBg(if (on) 0xFF2B2F42.toInt() else 0xFF333333.toInt(), 4)
        }
    }

    private fun buttonRow(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val b = chip(label, false, onClick)
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(22), dp(3), dp(6), dp(3))
        parent.addView(b, lp)
    }

    private fun swatchRow(parent: LinearLayout, label: String, get: (Layer) -> Int, set: (Layer, Int) -> Unit) {
        parent.addView(lbl(label).also { it.setPadding(dp(22), dp(4), 0, 0) })
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        val cur = sel()?.let { get(it) }
        for (col in swatches) {
            val v = View(context)
            val g = GradientDrawable()
            g.setColor(col)
            g.cornerRadius = dp(5).toFloat()
            g.setStroke(dp(if (cur == col) 2 else 1), if (cur == col) Color.WHITE else 0xFF555555.toInt())
            v.background = g
            val lp = LayoutParams(dp(24), dp(24))
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            v.setOnClickListener {
                val l = sel() ?: return@setOnClickListener
                set(l, col)
                host.changed()
                host.commit()
                render()
            }
            row.addView(v, lp)
        }
        val hs = HorizontalScrollView(context)
        hs.isHorizontalScrollBarEnabled = false
        hs.setPadding(dp(20), 0, 0, 0)
        hs.addView(row)
        parent.addView(hs)
    }

    /* ---------------- the controls of each effect ---------------- */

    private fun setZ(l: Layer, v: Float) {
        l.set(P_Z, p.time, v, p.easeIdx)
        if (v != 0f && !l.threeD && l.type != T_CAMERA) {
            l.threeD = true
            host.toast("3D layer turned on")
        }
    }

    private fun setAxis(l: Layer, prop: Int, v: Float) {
        l.set(prop, p.time, v, p.easeIdx)
        if (v != 0f && !l.threeD && l.type != T_CAMERA) {
            l.threeD = true
            host.toast("3D layer turned on")
        }
    }

    private fun keyed(prop: Int) = { l: Layer -> l.eval(prop, p.time) }

    private fun controls(e: Effect, box: LinearLayout) {
        val l = sel()
        val creates = e.id == "camera" || e.id == "particles" || e.id == "text"
        if (l == null && !creates) {
            hint(box, "Select a layer in the timeline first.")
            return
        }
        val cam = l?.type == T_CAMERA
        val w = p.W.toFloat()
        val h = p.H.toFloat()
        when (e.id) {
            "move" -> {
                numRow(box, if (cam) "Pan X" else "X", -w, w * 2f, P_X, keyed(P_X)) { ly, v -> ly.set(P_X, p.time, v, p.easeIdx) }
                numRow(box, if (cam) "Pan Y" else "Y", -h, h * 2f, P_Y, keyed(P_Y)) { ly, v -> ly.set(P_Y, p.time, v, p.easeIdx) }
                numRow(box, if (cam) "Dolly (Z)" else "Z (depth)", -1500f, 1500f, P_Z, keyed(P_Z)) { ly, v -> setZ(ly, v) }
            }
            "rotate" -> {
                numRow(box, if (cam) "Roll" else "Rotation Z", -360f, 360f, P_ROT, keyed(P_ROT)) { ly, v -> ly.set(P_ROT, p.time, v, p.easeIdx) }
                numRow(box, if (cam) "Tilt" else "Rotation X", -360f, 360f, P_RX, keyed(P_RX)) { ly, v -> setAxis(ly, P_RX, v) }
                numRow(box, if (cam) "Orbit" else "Rotation Y", -360f, 360f, P_RY, keyed(P_RY)) { ly, v -> setAxis(ly, P_RY, v) }
            }
            "3d" -> toggleRow(box, "3D layer", { ly -> ly.threeD }, { ly, v -> ly.threeD = v })
            "scale" -> {
                numRow(box, if (cam) "Zoom" else "Scale", 0f, 5f, P_SCALE, keyed(P_SCALE)) { ly, v -> ly.set(P_SCALE, p.time, v, p.easeIdx) }
                if (!cam) {
                    if (l?.type == T_TEXT) {
                        numRow(box, "Font size", 10f, 400f, null, { ly -> ly.size }) { ly, v -> ly.size = v }
                    } else {
                        numRow(box, "Width", 10f, 2400f, null, { ly -> ly.w }) { ly, v -> ly.w = v }
                        numRow(box, "Height", 10f, 2400f, null, { ly -> ly.h }) { ly, v -> ly.h = v }
                    }
                }
            }
            "skew" -> {
                numRow(box, "Skew X", -60f, 60f, null, { ly -> ly.skewX }) { ly, v -> ly.skewX = v }
                numRow(box, "Skew Y", -60f, 60f, null, { ly -> ly.skewY }) { ly, v -> ly.skewY = v }
                toggleRow(box, "Flip horizontal", { ly -> ly.flipH }, { ly, v -> ly.flipH = v })
                toggleRow(box, "Flip vertical", { ly -> ly.flipV }, { ly, v -> ly.flipV = v })
            }
            "blend" -> {
                numRow(box, "Opacity", 0f, 1f, P_ALPHA, keyed(P_ALPHA)) { ly, v -> ly.set(P_ALPHA, p.time, v, p.easeIdx) }
                chipRow(box, listOf("Normal", "Multiply", "Screen", "Add"), { ly -> ly.blend }, { ly, v -> ly.blend = v })
            }
            "fill" -> {
                chipRow(box, listOf("None", "Solid", "Gradient"), { ly -> ly.fillType }, { ly, v -> ly.fillType = v })
                if (l != null && (l.fillType == 1 || l.fillType == 2)) {
                    swatchRow(box, if (l.fillType == 2) "Colour 1" else "Colour", { ly -> ly.color }, { ly, v -> ly.color = v })
                }
                if (l != null && l.fillType == 2) {
                    swatchRow(box, "Colour 2", { ly -> ly.color2 }, { ly, v -> ly.color2 = v })
                    chipRow(box, listOf("Linear", "Radial"), { ly -> ly.gradType }, { ly, v -> ly.gradType = v })
                    numRow(box, "Angle", 0f, 360f, null, { ly -> ly.gradAngle }) { ly, v -> ly.gradAngle = v }
                }
            }
            "border" -> {
                numRow(box, "Stroke width", 0f, 40f, null, { ly -> ly.strokeW }) { ly, v -> ly.strokeW = v }
                swatchRow(box, "Stroke colour", { ly -> ly.strokeColor }, { ly, v -> ly.strokeColor = v })
            }
            "shadow" -> {
                toggleRow(box, "Drop shadow", { ly -> ly.shadowOn }, { ly, v -> ly.shadowOn = v })
                numRow(box, "Blur", 0f, 40f, null, { ly -> ly.shadowBlur }) { ly, v -> ly.shadowBlur = v }
                numRow(box, "Offset X", -40f, 40f, null, { ly -> ly.shadowDx }) { ly, v -> ly.shadowDx = v }
                numRow(box, "Offset Y", -40f, 40f, null, { ly -> ly.shadowDy }) { ly, v -> ly.shadowDy = v }
                swatchRow(box, "Shadow colour", { ly -> ly.shadowColor }, { ly, v -> ly.shadowColor = (v and 0xFFFFFF) or 0xAA000000.toInt() })
            }
            "speed" -> {
                if (l?.type != T_VIDEO) {
                    hint(box, "Speed works on video layers.")
                } else {
                    numRow(box, "Speed", 0.25f, 4f, null, { ly -> ly.speed }) { ly, v ->
                        ly.speed = v
                        ly.end = min(p.duration, ly.start + (ly.srcDur - ly.trim) / v)
                        ly.autoEnd = false
                    }
                }
            }
            "wiggle" -> {
                numRow(box, "Position shake", 0f, 200f, null, { ly -> ly.wigAmp[P_X] }) { ly, v -> ly.wigAmp[P_X] = v; ly.wigAmp[P_Y] = v }
                numRow(box, "Depth (Z) shake", 0f, 300f, null, { ly -> ly.wigAmp[P_Z] }) { ly, v -> ly.wigAmp[P_Z] = v }
                numRow(box, "Rotation shake", 0f, 45f, null, { ly -> ly.wigAmp[P_ROT] }) { ly, v -> ly.wigAmp[P_ROT] = v }
                numRow(box, "Scale shake", 0f, 0.5f, null, { ly -> ly.wigAmp[P_SCALE] }) { ly, v -> ly.wigAmp[P_SCALE] = v }
                numRow(box, "Opacity shake", 0f, 0.5f, null, { ly -> ly.wigAmp[P_ALPHA] }) { ly, v -> ly.wigAmp[P_ALPHA] = v }
                numRow(box, "Wiggle speed", 0.5f, 20f, null, { ly -> ly.wigFreq }) { ly, v -> ly.wigFreq = v }
                chipRow(box, listOf("Loop off", "Cycle", "Ping-pong"), { ly -> ly.loopMode }, { ly, v -> ly.loopMode = v })
                buttonRow(box, "New random seed") {
                    val ly = sel()
                    if (ly != null) {
                        ly.wigSeed = (Math.random() * 100.0).toFloat()
                        afterEdit()
                    }
                }
            }
            "particles" -> {
                buttonRow(box, "+ Add particle emitter") { host.create("particles") }
                if (l?.type == T_PARTICLES) {
                    numRow(box, "Amount", 5f, 400f, null, { ly -> ly.pCount.toFloat() }) { ly, v -> ly.pCount = v.toInt() }
                    numRow(box, "Life (s)", 0.5f, 6f, null, { ly -> ly.pLife }) { ly, v -> ly.pLife = v }
                    numRow(box, "Speed", 0f, 1000f, null, { ly -> ly.pSpeed }) { ly, v -> ly.pSpeed = v }
                    numRow(box, "Size", 5f, 200f, null, { ly -> ly.pSize }) { ly, v -> ly.pSize = v }
                    numRow(box, "Gravity", -500f, 1500f, null, { ly -> ly.pGravity }) { ly, v -> ly.pGravity = v }
                    numRow(box, "Spread", 0f, 360f, null, { ly -> ly.pSpread }) { ly, v -> ly.pSpread = v }
                    numRow(box, "Direction", -180f, 180f, null, { ly -> ly.pAngle }) { ly, v -> ly.pAngle = v }
                    swatchRow(box, "Colour", { ly -> ly.color }, { ly, v -> ly.color = v })
                }
            }
            "camera" -> {
                buttonRow(box, "+ Add 3D camera") { host.create("camera") }
                if (cam) {
                    numRow(box, "Pan X", -w, w * 2f, P_X, keyed(P_X)) { ly, v -> ly.set(P_X, p.time, v, p.easeIdx) }
                    numRow(box, "Pan Y", -h, h * 2f, P_Y, keyed(P_Y)) { ly, v -> ly.set(P_Y, p.time, v, p.easeIdx) }
                    numRow(box, "Dolly (Z)", -1500f, 1500f, P_Z, keyed(P_Z)) { ly, v -> ly.set(P_Z, p.time, v, p.easeIdx) }
                    numRow(box, "Roll", -360f, 360f, P_ROT, keyed(P_ROT)) { ly, v -> ly.set(P_ROT, p.time, v, p.easeIdx) }
                    numRow(box, "Tilt", -360f, 360f, P_RX, keyed(P_RX)) { ly, v -> ly.set(P_RX, p.time, v, p.easeIdx) }
                    numRow(box, "Orbit", -360f, 360f, P_RY, keyed(P_RY)) { ly, v -> ly.set(P_RY, p.time, v, p.easeIdx) }
                    numRow(box, "Zoom", 0.1f, 5f, P_SCALE, keyed(P_SCALE)) { ly, v -> ly.set(P_SCALE, p.time, v, p.easeIdx) }
                } else {
                    hint(box, "Select the camera layer to see its controls.")
                }
            }
            "text" -> {
                buttonRow(box, "+ Add text layer") { host.create("text") }
                if (l?.type == T_TEXT) {
                    val et = EditText(context)
                    et.setText(l.text)
                    et.setTextColor(0xFFEEEEEE.toInt())
                    et.textSize = 12f
                    et.setBackgroundColor(0xFF141414.toInt())
                    et.setPadding(dp(8), dp(6), dp(8), dp(6))
                    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    et.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val ly = sel()
                            if (ly != null && ly.type == T_TEXT) {
                                ly.text = s.toString().ifEmpty { " " }
                                host.changed()
                            }
                        }
                    })
                    et.setOnFocusChangeListener { _, has -> if (!has) host.commit() }
                    val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(dp(22), dp(3), dp(6), dp(3))
                    box.addView(et, lp)
                    chipRow(box, Fonts.names.toList(), { ly -> ly.fontIdx }, { ly, v -> ly.fontIdx = v })
                    chipRow(box, listOf("Left", "Centre", "Right"), { ly -> ly.align }, { ly, v -> ly.align = v })
                    numRow(box, "Size", 10f, 400f, null, { ly -> ly.size }) { ly, v -> ly.size = v }
                    swatchRow(box, "Colour", { ly -> ly.color }, { ly, v -> ly.color = v; ly.fillType = 1 })
                }
            }
        }
    }

    /* ---------------- list ---------------- */

    private fun onEffectClick(e: Effect) {
        if (e.id.startsWith("preset:")) {
            val l = sel()
            if (l == null) {
                host.toast("Select a layer first")
                return
            }
            val name = e.id.removePrefix("preset:")
            Presets.all[name]?.invoke(l, p.time)
            afterEdit()
            host.toast("$name applied to ${l.name}")
            return
        }
        if (!openFx.remove(e.id)) openFx.add(e.id)
        render()
    }

    private fun render() {
        syncs.clear()
        rowViews.clear()
        list.removeAllViews()
        val l = sel()
        renderedSelId = l?.id ?: -1
        renderedType = l?.type ?: -1
        selLabel.text = if (l == null) "No layer selected" else "\u25B6 " + l.name
        selLabel.setTextColor(if (l == null) 0xFF777777.toInt() else 0xFF4DA3FF.toInt())
        for ((cat, effects) in EffectCatalog.cats) {
            val shown = if (query.isEmpty()) effects else effects.filter { it.label.lowercase().contains(query) || cat.lowercase().contains(query) }
            if (shown.isEmpty()) continue
            val expanded = query.isNotEmpty() || openCats.contains(cat)
            val h = TextView(context)
            h.text = (if (expanded) "\u25BE " else "\u25B6 ") + cat
            h.setTextColor(0xFFAAAAAA.toInt())
            h.textSize = 11f
            h.maxLines = 1
            h.setPadding(dp(8), dp(6), dp(6), dp(6))
            h.setOnClickListener {
                if (!openCats.remove(cat)) openCats.add(cat)
                render()
            }
            list.addView(h)
            if (!expanded) continue
            for (e in shown) {
                val isPreset = e.id.startsWith("preset:")
                val isOpen = openFx.contains(e.id)
                val row = TextView(context)
                row.text = (if (isPreset) "\u2726 " else if (isOpen) "\u25BE " else "\u25B8 ") + e.label
                row.textSize = 11f
                row.maxLines = 1
                row.ellipsize = android.text.TextUtils.TruncateAt.END
                row.setTextColor(if (!e.enabled) 0xFF666666.toInt() else if (isOpen) Color.WHITE else 0xFFE0E0E0.toInt())
                if (isOpen) row.setBackgroundColor(0xFF2F4F7F.toInt())
                row.setPadding(dp(16), dp(6), dp(6), dp(6))
                if (e.enabled) attachDrag(row, drag, "\u2728 " + e.label, e) { onEffectClick(e) }
                list.addView(row)
                rowViews[e.id] = row
                if (isOpen && !isPreset && e.enabled) {
                    val box = LinearLayout(context)
                    box.orientation = VERTICAL
                    box.setBackgroundColor(0xFF1B1B1B.toInt())
                    controls(e, box)
                    list.addView(box)
                }
            }
        }
        for (f in ArrayList(syncs)) f()
    }
}
