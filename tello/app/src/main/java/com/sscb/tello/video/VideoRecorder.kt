package com.sscb.tello.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer

/**
 * Records decoder output into an MP4 file via MediaMuxer.
 * The track is configured with SPS/PPS (csd-0/csd-1) captured from the stream,
 * then decoder output samples are written as-is.
 */
class VideoRecorder {

    companion object {
        private const val TAG = "VideoRecorder"
    }

    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var track = -1

    @Volatile
    var isRecording = false
        private set

    fun start(sps: ByteArray, pps: ByteArray, pfd: ParcelFileDescriptor): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, VideoDecoder.WIDTH, VideoDecoder.HEIGHT
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            val m = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            track = m.addTrack(format)
            m.start()
            muxer = m
            this.pfd = pfd
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "record start failed", e)
            try { pfd.close() } catch (_: Exception) {}
            false
        }
    }

    fun stop() {
        isRecording = false
        try {
            muxer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "muxer stop failed", e)
        }
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    /** Must be called before the decoder buffer is released (copies the data). */
    fun writeSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        if (!isRecording || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        try {
            val copy = ByteArray(info.size)
            val savedPosition = buffer.position()
            val savedLimit = buffer.limit()
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.get(copy)
            buffer.position(savedPosition)
            buffer.limit(savedLimit)
            val out = MediaCodec.BufferInfo()
            out.set(0, info.size, info.presentationTimeUs, info.flags)
            m.writeSampleData(track, ByteBuffer.wrap(copy), out)
        } catch (e: Exception) {
            Log.e(TAG, "write sample failed", e)
        }
    }
}
