package com.motionstudio.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.max

object Exporter {
    /** Renders the project to an H.264 MP4 file (no audio). Call from a background thread. */
    fun export(p: Project, out: File, fps: Int = 30, onProgress: (Int) -> Unit) {
        val w = p.W - p.W % 2
        val h = p.H - p.H % 2
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false
        var outCount = 0L

        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end) return
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && buf != null) {
                        info.presentationTimeUs = outCount * 1_000_000L / fps
                        outCount++
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        try {
            val total = max(1, (p.duration * fps).toInt())
            for (i in 0 until total) {
                val c = surface.lockHardwareCanvas()
                try {
                    Renderer.draw(c, p, i / fps.toFloat(), true)
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                for (b in Renderer.toRecycle) b.recycle()
                Renderer.toRecycle.clear()
                drain(false)
                onProgress((i + 1) * 100 / total)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            surface.release()
            try { if (started) muxer.stop() } catch (e: Exception) {}
            muxer.release()
        }
    }
}
