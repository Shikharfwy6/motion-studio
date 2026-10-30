package com.motionstudio.app

import android.content.Context
import android.graphics.Color
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class EffectPreset(
    val id: String,
    val name: String,
    val description: String,
    val format: String, // "xml" or "ffx"
    val effectIds: List<String>,
    val baseValues: Map<Int, Float>,
    val keyframes: Map<Int, List<Key>>,
    val tintColor: Int? = null,
    val threeD: Boolean = false,
    val loopMode: Int = 0
)

object EffectPresetManager {

    private val PROP_NAME_TO_ID = mapOf(
        "x" to P_X, "y" to P_Y, "scale" to P_SCALE, "rot" to P_ROT, "alpha" to P_ALPHA,
        "z" to P_Z, "rx" to P_RX, "ry" to P_RY, "skx" to P_SKX, "sky" to P_SKY,
        "stroke" to P_STROKE, "shblur" to P_SHBLUR, "shdx" to P_SHDX, "shdy" to P_SHDY,
        "grad" to P_GRAD, "pspeed" to P_PSPEED, "psize" to P_PSIZE, "pgrav" to P_PGRAV,
        "pspread" to P_PSPREAD, "pangle" to P_PANGLE, "blur" to P_BLUR, "mblur" to P_MBLUR,
        "mbang" to P_MBANG, "rblur" to P_RBLUR, "glow" to P_GLOW, "glowr" to P_GLOWR,
        "bright" to P_BRIGHT, "contrast" to P_CONTRAST, "hue" to P_HUE, "sat" to P_SAT,
        "tint" to P_TINT, "pix" to P_PIX, "rgb" to P_RGB, "vig" to P_VIG,
        "grain" to P_GRAIN, "inv" to P_INV
    )

    private val PROP_ID_TO_NAME = PROP_NAME_TO_ID.entries.associate { (k, v) -> v to k }

    fun userPresetsDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "custom_effects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Load all bundled effect presets from assets/effects/ */
    fun loadBundledPresets(ctx: Context): List<EffectPreset> {
        val list = mutableListOf<EffectPreset>()
        try {
            val files = ctx.assets.list("effects") ?: emptyArray()
            for (f in files) {
                try {
                    val stream = ctx.assets.open("effects/$f")
                    val bytes = stream.readBytes()
                    stream.close()
                    val p = if (f.endsWith(".ffx", ignoreCase = true)) {
                        parseFfx(bytes, f)
                    } else if (f.endsWith(".xml", ignoreCase = true)) {
                        parseXml(String(bytes, StandardCharsets.UTF_8), f)
                    } else null
                    if (p != null) list.add(p)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return list
    }

    /** Load custom user-imported/saved effect presets from filesDir/custom_effects/ */
    fun loadUserPresets(ctx: Context): List<EffectPreset> {
        val list = mutableListOf<EffectPreset>()
        val dir = userPresetsDir(ctx)
        val files = dir.listFiles() ?: return list
        for (f in files) {
            try {
                val bytes = f.readBytes()
                val p = if (f.name.endsWith(".ffx", ignoreCase = true)) {
                    parseFfx(bytes, f.name)
                } else if (f.name.endsWith(".xml", ignoreCase = true)) {
                    parseXml(String(bytes, StandardCharsets.UTF_8), f.name)
                } else null
                if (p != null) list.add(p)
            } catch (e: Exception) {}
        }
        return list
    }

    fun saveUserPreset(ctx: Context, preset: EffectPreset, asFfx: Boolean = false): File {
        val dir = userPresetsDir(ctx)
        val cleanName = preset.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
        val ext = if (asFfx) ".ffx" else ".xml"
        val file = File(dir, "$cleanName$ext")
        if (asFfx) {
            file.writeBytes(exportFfx(preset))
        } else {
            file.writeText(exportXml(preset))
        }
        return file
    }

    fun deleteUserPreset(ctx: Context, preset: EffectPreset): Boolean {
        val dir = userPresetsDir(ctx)
        val f1 = File(dir, "${preset.id}.xml")
        val f2 = File(dir, "${preset.id}.ffx")
        val f3 = File(dir, "${preset.name}.xml")
        val f4 = File(dir, "${preset.name}.ffx")
        return f1.delete() || f2.delete() || f3.delete() || f4.delete()
    }

    /**
     * Import a custom preset (.xml or .ffx) from an input stream (e.g. from user file picker or download)
     */
    fun importPresetFromStream(ctx: Context, stream: InputStream, fileName: String): EffectPreset? {
        return try {
            val bytes = stream.readBytes()
            val isFfx = fileName.endsWith(".ffx", ignoreCase = true) ||
                    (bytes.size >= 8 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte())
            val preset = if (isFfx) {
                parseFfx(bytes, fileName)
            } else {
                parseXml(String(bytes, StandardCharsets.UTF_8), fileName)
            }
            if (preset != null) {
                saveUserPreset(ctx, preset, isFfx)
            }
            preset
        } catch (e: Exception) {
            null
        } finally {
            try { stream.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Import preset from Android Storage Access Framework content URI
     */
    fun importPresetFromUri(ctx: Context, uri: Uri): EffectPreset? {
        var fileName = "imported_preset.xml"
        try {
            val cursor = ctx.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        fileName = it.getString(nameIdx) ?: fileName
                    }
                }
            }
        } catch (e: Exception) {
            val seg = uri.lastPathSegment
            if (!seg.isNullOrEmpty()) fileName = seg
        }

        val inputStream = ctx.contentResolver.openInputStream(uri) ?: return null
        return importPresetFromStream(ctx, inputStream, fileName)
    }

    /* ---------------- XML Parser & Exporter ---------------- */

    fun parseXml(xmlString: String, filename: String = ""): EffectPreset? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlString))

            var name = filename.substringBeforeLast(".")
            var desc = ""
            var format = "xml"
            val fxIds = mutableListOf<String>()
            val baseVals = mutableMapOf<Int, Float>()
            val keyMap = mutableMapOf<Int, MutableList<Key>>()
            var tintCol: Int? = null
            var threeD = false
            var loopMode = 0

            var event = parser.eventType
            var curProp: Int? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (tag.lowercase()) {
                            "motionstudiopreset", "preset", "aftereffectspreset" -> {
                                name = parser.getAttributeValue(null, "name") ?: name
                                desc = parser.getAttributeValue(null, "description") ?: desc
                                val t3d = parser.getAttributeValue(null, "threeD")
                                if (t3d != null) threeD = t3d.toBoolean()
                            }
                            "description" -> {
                                desc = parser.nextText()
                            }
                            "effect" -> {
                                val fxId = parser.getAttributeValue(null, "id") ?: ""
                                if (fxId.isNotEmpty() && !fxIds.contains(fxId)) fxIds.add(fxId)
                            }
                            "property" -> {
                                val pidName = parser.getAttributeValue(null, "id")
                                    ?: parser.getAttributeValue(null, "name") ?: ""
                                val propId = PROP_NAME_TO_ID[pidName.lowercase()]
                                val valStr = parser.getAttributeValue(null, "value")
                                if (propId != null && valStr != null) {
                                    val fl = valStr.toFloatOrNull()
                                    if (fl != null) baseVals[propId] = fl
                                }
                                val colStr = parser.getAttributeValue(null, "color")
                                if (colStr != null) {
                                    try { tintCol = Color.parseColor(colStr) } catch (e: Exception) {}
                                }
                                curProp = propId
                            }
                            "key", "keyframe" -> {
                                val propId = curProp
                                if (propId != null) {
                                    val t = parser.getAttributeValue(null, "time")?.toFloatOrNull() ?: 0f
                                    val v = parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                                    val ease = parser.getAttributeValue(null, "ease")?.toIntOrNull() ?: 2
                                    val k = Key(t, v, ease)
                                    val x1 = parser.getAttributeValue(null, "x1")?.toFloatOrNull()
                                    val y1 = parser.getAttributeValue(null, "y1")?.toFloatOrNull()
                                    val x2 = parser.getAttributeValue(null, "x2")?.toFloatOrNull()
                                    val y2 = parser.getAttributeValue(null, "y2")?.toFloatOrNull()
                                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                                        k.x1 = x1; k.y1 = y1; k.x2 = x2; k.y2 = y2
                                    }
                                    keyMap.getOrPut(propId) { mutableListOf() }.add(k)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag.equals("property", ignoreCase = true)) curProp = null
                    }
                }
                event = parser.next()
            }

            for (ks in keyMap.values) ks.sortBy { it.t }

            return EffectPreset(
                id = filename.ifEmpty { name.replace(" ", "_").lowercase() },
                name = name,
                description = desc,
                format = format,
                effectIds = fxIds,
                baseValues = baseVals,
                keyframes = keyMap,
                tintColor = tintCol,
                threeD = threeD,
                loopMode = loopMode
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun exportXml(preset: EffectPreset): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<MotionStudioPreset version=\"1.0\" name=\"").append(escapeXml(preset.name))
        sb.append("\" description=\"").append(escapeXml(preset.description))
        sb.append("\" threeD=\"").append(preset.threeD).append("\">\n")

        for (fxId in preset.effectIds) {
            sb.append("    <effect id=\"").append(fxId).append("\">\n")
            sb.append("    </effect>\n")
        }

        for ((propId, v) in preset.baseValues) {
            val pName = PROP_ID_TO_NAME[propId] ?: continue
            val keys = preset.keyframes[propId]
            if (keys.isNullOrEmpty()) {
                sb.append("    <property id=\"").append(pName).append("\" value=\"").append(v).append("\"/>\n")
            } else {
                sb.append("    <property id=\"").append(pName).append("\" value=\"").append(v).append("\">\n")
                for (k in keys) {
                    sb.append("        <key time=\"").append(k.t).append("\" value=\"").append(k.v)
                    sb.append("\" ease=\"").append(k.ease).append("\"")
                    if (k.ease == Ease.CUSTOM) {
                        sb.append(" x1=\"").append(k.x1).append("\" y1=\"").append(k.y1)
                        sb.append("\" x2=\"").append(k.x2).append("\" y2=\"").append(k.y2).append("\"")
                    }
                    sb.append("/>\n")
                }
                sb.append("    </property>\n")
            }
        }

        if (preset.tintColor != null) {
            val hex = String.format("#%08X", preset.tintColor)
            sb.append("    <property id=\"tintColor\" color=\"").append(hex).append("\"/>\n")
        }

        sb.append("</MotionStudioPreset>\n")
        return sb.toString()
    }

    /* ---------------- .FFX (After Effects Preset Container) ---------------- */

    /**
     * Parses an Adobe After Effects .ffx preset container.
     * After Effects .ffx files either start with 'RIFF....FXST' or wrap an XML / UTF-8 property stream.
     */
    fun parseFfx(bytes: ByteArray, filename: String = ""): EffectPreset? {
        try {
            if (bytes.size < 8) return null
            val text = String(bytes, StandardCharsets.UTF_8)
            // If the .ffx embeds XML or text properties
            if (text.contains("<MotionStudioPreset") || text.contains("<AfterEffectsPreset") || text.contains("<?xml")) {
                val xmlStart = text.indexOf("<?xml").takeIf { it >= 0 } ?: text.indexOf("<")
                if (xmlStart >= 0) return parseXml(text.substring(xmlStart), filename)
            }

            // Binary/RIFF parser: look for property names and float values
            val name = filename.substringBeforeLast(".")
            val fxIds = mutableListOf<String>()
            val baseVals = mutableMapOf<Int, Float>()
            val keyMap = mutableMapOf<Int, MutableList<Key>>()

            // Scan through string tokens inside the .ffx binary
            for ((propName, propId) in PROP_NAME_TO_ID) {
                val idx = indexOfBytes(bytes, propName.toByteArray(StandardCharsets.US_ASCII))
                if (idx >= 0) {
                    // Try to read adjacent float value
                    if (idx + propName.length + 4 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, idx + propName.length, 4).order(ByteOrder.LITTLE_ENDIAN)
                        val fl = bb.float
                        if (!fl.isNaN() && fl > -10000f && fl < 10000f) {
                            baseVals[propId] = fl
                        }
                    }
                }
            }

            return EffectPreset(
                id = filename.ifEmpty { name.replace(" ", "_").lowercase() },
                name = name,
                description = "After Effects .ffx preset: $name",
                format = "ffx",
                effectIds = fxIds,
                baseValues = baseVals,
                keyframes = keyMap
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun exportFfx(preset: EffectPreset): ByteArray {
        val out = ByteArrayOutputStream()
        // Write standard After Effects .ffx header (RIFF container with 'FXST' and embedded XML preset stream)
        val xmlContent = exportXml(preset).toByteArray(StandardCharsets.UTF_8)
        val riffHeader = "RIFF".toByteArray(StandardCharsets.US_ASCII)
        val fxstType = "FXST".toByteArray(StandardCharsets.US_ASCII)
        val dataTag = "data".toByteArray(StandardCharsets.US_ASCII)

        val totalSize = 4 + 8 + xmlContent.size
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(totalSize)

        out.write(riffHeader)
        out.write(bb.array(), 0, 4)
        out.write(fxstType)
        out.write(dataTag)

        val dataLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        dataLen.putInt(xmlContent.size)
        out.write(dataLen.array())
        out.write(xmlContent)

        return out.toByteArray()
    }

    /* ---------------- Apply Preset to Layer ---------------- */

    fun applyPreset(preset: EffectPreset, layer: Layer, time: Float, easeIdx: Int) {
        if (preset.threeD) layer.threeD = true
        if (preset.tintColor != null) layer.tintColor = preset.tintColor
        if (preset.loopMode != 0) layer.loopMode = preset.loopMode

        for ((propId, baseVal) in preset.baseValues) {
            if (propId in 0 until P_COUNT) {
                layer.base[propId] = baseVal
            }
        }

        for ((propId, keys) in preset.keyframes) {
            if (propId in 0 until P_COUNT) {
                layer.keys[propId].clear()
                for (k in keys) {
                    val nk = Key(time + k.t, k.v, k.ease)
                    nk.x1 = k.x1; nk.y1 = k.y1; nk.x2 = k.x2; nk.y2 = k.y2
                    layer.keys[propId].add(nk)
                }
                layer.keys[propId].sortBy { it.t }
            }
        }
    }

    fun createPresetFromLayer(layer: Layer, name: String, desc: String = ""): EffectPreset {
        val applied = layer.getAppliedEffects(0f)
        val fxIds = applied.map { it.id }
        val baseVals = mutableMapOf<Int, Float>()
        val keyMap = mutableMapOf<Int, List<Key>>()

        val relevantProps = applied.flatMap { it.props.toList() }.distinct()
        for (p in relevantProps) {
            baseVals[p] = layer.base[p]
            if (layer.keys[p].isNotEmpty()) {
                val t0 = layer.keys[p].first().t
                keyMap[p] = layer.keys[p].map { k ->
                    val nk = Key(k.t - t0, k.v, k.ease)
                    nk.x1 = k.x1; nk.y1 = k.y1; nk.x2 = k.x2; nk.y2 = k.y2
                    nk
                }
            }
        }

        return EffectPreset(
            id = name.replace(" ", "_").lowercase(),
            name = name,
            description = desc.ifEmpty { "Custom effect preset for ${layer.name}" },
            format = "xml",
            effectIds = fxIds,
            baseValues = baseVals,
            keyframes = keyMap,
            tintColor = layer.tintColor,
            threeD = layer.threeD,
            loopMode = layer.loopMode
        )
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun indexOfBytes(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        for (i in 0..source.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
