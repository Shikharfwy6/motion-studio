package com.motionstudio.app

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder that renders straight into a GPU texture (SurfaceTexture / external OES).
 *
 * Preview (offline = false): a separate decode thread runs ahead of the playback clock, so the GL
 * thread never waits for the decoder. The GL thread only calls updateTexImage() when a new frame
 * is ready. Scrubbing and looping make the decode thread seek, without ever blocking the preview.
 *
 * Export (offline = true): frames are decoded one by one on the calling thread, as fast as the
 * hardware allows, and every frame is exact.
 */
class VideoDecoder(private val path: String, private val offline: Boolean = false) {
    var texId = 0
        private set
    val texMatrix = FloatArray(16)

    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private var ex: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var cbThread: HandlerThread? = null
    private var worker: Thread? = null
    private val info = MediaCodec.BufferInfo()
    private var frameUs = 33_333L
    private var opened = false
    private var failed = false

    @Volatile private var targetUs = 0L
    @Volatile private var playing = false
    @Volatile private var stopped = false
    @Volatile private var newFrame = false
    @Volatile private var pendingPts = 0L

    private var hasFrame = false
    private var lastPts = 0L

    // offline mode only
    private val sem = Semaphore(0)
    private var inputDone = false
    private var outputEos = false

    init {
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun open(): Boolean {
        try {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(target, texId)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val s = SurfaceTexture(texId)
            st = s
            // frame callbacks arrive on their own thread, never on the busy UI thread
            val ct = HandlerThread("video-frames")
            ct.start()
            cbThread = ct
            s.setOnFrameAvailableListener({
                if (offline) {
                    sem.release()
                } else {
                    newFrame = true
                }
            }, Handler(ct.looper))
            val sf = Surface(s)
            surface = sf

            val e = MediaExtractor()
            e.setDataSource(path)
            ex = e
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) return false
            e.selectTrack(track)
            try {
                if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val fr = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
                    if (fr > 0) frameUs = 1_000_000L / fr
                }
            } catch (x: Exception) {
            }
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            if (offline) {
                // let the decoder run faster than real time while exporting
                try { fmt.setInteger(MediaFormat.KEY_PRIORITY, 1) } catch (x: Exception) {}
                try { fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) } catch (x: Exception) {}
            }
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, sf, null, 0)
            c.start()
            opened = true
            if (!offline) {
                val t = Thread { decodeLoop() }
                t.name = "video-decode"
                t.isDaemon = true
                worker = t
                t.start()
            }
            return true
        } catch (x: Throwable) {
            return false
        }
    }

    /* ------------------------- preview (non-blocking) ------------------------- */

    /**
     * Tells the decode thread which source time is wanted and returns at once. Returns true when a
     * decoded frame is available in the texture.
     */
    fun frameAt(us: Long, isPlaying: Boolean): Boolean {
        if (offline) return frameAtSync(us)
        if (failed) return false
        if (!opened && !open()) {
            failed = true
            return false
        }
        targetUs = if (us < 0) 0L else us
        playing = isPlaying
        if (newFrame) {
            newFrame = false
            val s = st
            if (s != null) {
                try {
                    s.updateTexImage()
                    s.getTransformMatrix(texMatrix)
                    lastPts = pendingPts
                    hasFrame = true
                } catch (x: Exception) {
                }
            }
        }
        return hasFrame
    }

    private fun decodeLoop() {
        val c = codec ?: return
        val e = ex ?: return
        var inDone = false
        var outEos = false
        var held = -1
        var heldPts = 0L
        var lastRendered = Long.MIN_VALUE
        var needSeek = true
        try {
            while (!stopped) {
                val target = targetUs
                if (!needSeek && lastRendered != Long.MIN_VALUE) {
                    val d = target - lastRendered
                    // jumped backwards, or far forwards: seek instead of decoding everything in between
                    if (d < -frameUs * 2 || d > 1_500_000L) needSeek = true
                }
                if (needSeek) {
                    if (held >= 0) {
                        c.releaseOutputBuffer(held, false)
                        held = -1
                    }
                    e.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    c.flush()
                    inDone = false
                    outEos = false
                    lastRendered = Long.MIN_VALUE
                    needSeek = false
                }
                // keep the decoder fed
                var fed = 0
                while (!inDone && fed < 3) {
                    val ib = c.dequeueInputBuffer(0)
                    if (ib < 0) break
                    val buf = c.getInputBuffer(ib)
                    val n = if (buf == null) -1 else e.readSampleData(buf, 0)
                    if (n < 0) {
                        c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        c.queueInputBuffer(ib, 0, n, e.sampleTime, 0)
                        e.advance()
                    }
                    fed++
                }
                if (held < 0 && !outEos) {
                    val ob = c.dequeueOutputBuffer(info, 4_000)
                    if (ob >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size <= 0) {
                            c.releaseOutputBuffer(ob, false)
                            if (eos) outEos = true
                        } else {
                            held = ob
                            heldPts = info.presentationTimeUs
                        }
                    }
                }
                if (held >= 0) {
                    val t = targetUs
                    if (heldPts < t - frameUs && lastRendered != Long.MIN_VALUE) {
                        // we are behind the clock: drop frames to catch up
                        c.releaseOutputBuffer(held, false)
                        held = -1
                    } else if (heldPts < t - frameUs * 2) {
                        c.releaseOutputBuffer(held, false)
                        held = -1
                    } else if (lastRendered == Long.MIN_VALUE || heldPts - t <= 8_000L) {
                        pendingPts = heldPts
                        c.releaseOutputBuffer(held, true)
                        held = -1
                        lastRendered = heldPts
                    } else {
                        // the next frame is in the future: wait for the clock
                        Thread.sleep(if (playing) 2L else 8L)
                    }
                } else if (outEos || (inDone && held < 0)) {
                    Thread.sleep(8L)
                }
            }
        } catch (x: Throwable) {
            // decoder was released while we were running
        }
    }

    /* ------------------------- export (blocking, exact) ------------------------- */

    private fun feedInput() {
        val c = codec ?: return
        val e = ex ?: return
        var n = 0
        while (!inputDone && n < 3) {
            val ib = c.dequeueInputBuffer(0)
            if (ib < 0) return
            val buf = c.getInputBuffer(ib)
            val size = if (buf == null) -1 else e.readSampleData(buf, 0)
            if (size < 0) {
                c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                c.queueInputBuffer(ib, 0, size, e.sampleTime, 0)
                e.advance()
            }
            n++
        }
    }

    private fun seekSync(us: Long) {
        val c = codec ?: return
        val e = ex ?: return
        e.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        inputDone = false
        outputEos = false
        hasFrame = false
    }

    private fun frameAtSync(us: Long): Boolean {
        if (failed) return false
        if (!opened && !open()) {
            failed = true
            return false
        }
        val c = codec ?: return false
        val s = st ?: return false
        val target = if (us < 0) 0L else us
        if (hasFrame) {
            val d = target - lastPts
            if (d >= -20_000L && d < frameUs + 20_000L) return true
            if (d < -20_000L || d > 1_500_000L) seekSync(target)
        } else if (target > 500_000L) {
            seekSync(target)
        }
        if (outputEos && hasFrame) return true

        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            feedInput()
            val ob = c.dequeueOutputBuffer(info, 5_000)
            if (ob < 0) continue
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs
            if (info.size > 0 && pts >= target - 20_000L) {
                sem.drainPermits()
                c.releaseOutputBuffer(ob, true)
                if (sem.tryAcquire(300, TimeUnit.MILLISECONDS)) {
                    s.updateTexImage()
                    s.getTransformMatrix(texMatrix)
                    lastPts = pts
                    hasFrame = true
                    return true
                }
                return hasFrame
            }
            c.releaseOutputBuffer(ob, false)
            if (eos) {
                outputEos = true
                return hasFrame
            }
        }
        return hasFrame
    }

    fun release() {
        stopped = true
        try {
            worker?.interrupt()
            worker?.join(400)
        } catch (x: Exception) {
        }
        try { codec?.stop() } catch (x: Exception) {}
        try { codec?.release() } catch (x: Exception) {}
        try { ex?.release() } catch (x: Exception) {}
        try { surface?.release() } catch (x: Exception) {}
        try { st?.release() } catch (x: Exception) {}
        try { cbThread?.quitSafely() } catch (x: Exception) {}
        if (texId != 0) {
            try { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) } catch (x: Exception) {}
            texId = 0
        }
        codec = null
    }
}
