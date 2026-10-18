package com.motionstudio.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class VideoInfo(val dur: Float, val w: Int, val h: Int)

object MediaCache {
    private val images = HashMap<String, Bitmap>()

    fun image(path: String): Bitmap? {
        val cached = synchronized(images) { images[path] }
        if (cached != null) return cached
        val b = decodeImage(path, 1600) ?: return null
        synchronized(images) { images[path] = b }
        return b
    }

    private fun decodeImage(path: String, maxDim: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > maxDim) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val b = BitmapFactory.decodeFile(path, o2) ?: return null
            val orient = try {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            } catch (e: Exception) {
                1
            }
            val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            if (deg != 0f) {
                val m = Matrix()
                m.postRotate(deg)
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            } else b
        } catch (e: Exception) {
            null
        }
    }

    fun videoInfo(path: String): VideoInfo? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r.release()
            if (rot == 90 || rot == 270) VideoInfo(d / 1000f, h, w) else VideoInfo(d / 1000f, w, h)
        } catch (e: Exception) {
            null
        }
    }
}

object VideoCache {
    private val lock = Any()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()
    private val frames = HashMap<String, Bitmap>()
    private val times = HashMap<String, Long>()
    private val pending = HashMap<String, Long>()
    private val busy = HashSet<String>()
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    var onFrame: (() -> Unit)? = null

    private fun retriever(path: String): MediaMetadataRetriever = synchronized(lock) {
        retrievers.getOrPut(path) {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            r
        }
    }

    /** Returns the latest decoded frame immediately and asks a background thread for a closer one. */
    fun previewFrame(path: String, us: Long): Bitmap? {
        val bmp = synchronized(lock) { frames[path] }
        val t = synchronized(lock) { times[path] }
        if (t == null || abs(t - us) > 45_000L) request(path, us)
        return bmp
    }

    private fun request(path: String, us: Long) {
        synchronized(lock) {
            pending[path] = us
            if (busy.contains(path)) return
            busy.add(path)
        }
        exec.execute {
            while (true) {
                val t = synchronized(lock) {
                    val v = pending.remove(path)
                    if (v == null) busy.remove(path)
                    v
                } ?: return@execute
                val bmp = grab(path, t, 960)
                if (bmp != null) synchronized(lock) { frames[path] = bmp; times[path] = t }
                ui.post { onFrame?.invoke() }
            }
        }
    }

    fun exactFrame(path: String, us: Long): Bitmap? = grab(path, us, 0)

    private fun grab(path: String, us: Long, maxDim: Int): Bitmap? {
        return try {
            val r = retriever(path)
            val f = synchronized(r) { r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) } ?: return null
            if (maxDim > 0 && max(f.width, f.height) > maxDim) {
                val s = maxDim.toFloat() / max(f.width, f.height)
                val sm = Bitmap.createScaledBitmap(
                    f, (f.width * s).toInt().coerceAtLeast(1), (f.height * s).toInt().coerceAtLeast(1), true
                )
                f.recycle()
                sm
            } else f
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        synchronized(lock) {
            for (r in retrievers.values) try { r.release() } catch (e: Exception) {}
            retrievers.clear()
            frames.clear()
            times.clear()
            pending.clear()
        }
    }
}
