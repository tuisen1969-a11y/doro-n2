package com.sscb.tello.video

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives the raw H264 UDP stream on port 11111, decodes it with MediaCodec and
 * delivers decoded frames as Bitmaps through [onFrame]. Optional MP4 recording is
 * delegated to a [VideoRecorder] attached via [recorder].
 */
class VideoDecoder(private val onFrame: (Bitmap) -> Unit) {

    companion object {
        private const val TAG = "VideoDecoder"
        const val VIDEO_PORT = 11111
        const val WIDTH = 960
        const val HEIGHT = 720
    }

    /** Parameter sets (4-byte start code prefixed) needed to configure the recorder. */
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    @Volatile
    var recorder: VideoRecorder? = null

    private val unpacker = H264Unpacker()
    private var decoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var videoSocket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var codecThread: HandlerThread? = null
    private var handler: Handler? = null

    private val running = AtomicBoolean(false)
    private var configured = false
    private var pts = 0L

    private val lock = Any()
    private val freeInputIndices = ArrayDeque<Int>()
    private val pendingNals = ArrayDeque<ByteArray>()

    /** Binds UDP port 11111 and starts the receive loop. Returns false if the port cannot be opened. */
    fun start(): Boolean {
        if (running.get()) return true
        val socket = try {
            DatagramSocket(VIDEO_PORT)
        } catch (e: Exception) {
            Log.e(TAG, "failed to bind video port", e)
            return false
        }
        videoSocket = socket
        running.set(true)
        receiveThread = Thread {
            val buf = ByteArray(65536)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    onDatagram(buf, packet.length)
                } catch (e: Exception) {
                    if (!running.get()) break
                }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    fun stopAll() {
        running.set(false)
        try { videoSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        receiveThread = null
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        configured = false
        synchronized(lock) {
            freeInputIndices.clear()
            pendingNals.clear()
        }
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        handler = null
        codecThread?.quitSafely()
        codecThread = null
    }

    private fun onDatagram(data: ByteArray, length: Int) {
        val nals = unpacker.feed(data, length)
        if (!configured) {
            sps = unpacker.sps
            pps = unpacker.pps
            val s = sps
            val p = pps
            if (s != null && p != null && setupDecoder(s, p)) {
                configured = true
            }
        }
        if (!configured) return
        for (nal in nals) {
            feed(nal)
        }
    }

    private fun setupDecoder(spsBytes: ByteArray, ppsBytes: ByteArray): Boolean {
        return try {
            val thread = HandlerThread("TelloVideo").apply { start() }
            codecThread = thread
            val h = Handler(thread.looper)
            handler = h

            val reader = ImageReader.newInstance(WIDTH, HEIGHT, android.graphics.ImageFormat.YUV_420_888, 4)
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    onFrame(imageToBitmap(image))
                } catch (e: Exception) {
                    Log.e(TAG, "frame conversion failed", e)
                } finally {
                    image.close()
                }
            }, h)
            imageReader = reader

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))
                setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, reader.surface, null, 0)
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    synchronized(lock) { freeInputIndices.addLast(index) }
                    feedPending()
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    recorder?.let { r ->
                        try {
                            codec.getOutputBuffer(index)?.let { r.writeSample(it, info) }
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        codec.releaseOutputBuffer(index, true)
                    } catch (_: Exception) {
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "decoder error", e)
                }
            }, h)
            codec.start()
            decoder = codec
            true
        } catch (e: Exception) {
            Log.e(TAG, "decoder setup failed", e)
            false
        }
    }

    fun feed(nal: ByteArray) {
        synchronized(lock) {
            pendingNals.addLast(nal)
            while (pendingNals.size > 120) pendingNals.removeFirst()
        }
        feedPending()
    }

    private fun feedPending() {
        val codec = decoder ?: return
        while (true) {
            val index: Int
            val nal: ByteArray
            synchronized(lock) {
                if (pendingNals.isEmpty() || freeInputIndices.isEmpty()) return
                index = freeInputIndices.removeFirst()
                nal = pendingNals.removeFirst()
            }
            try {
                val buf = codec.getInputBuffer(index) ?: throw IllegalStateException("no input buffer")
                if (nal.size > buf.capacity()) continue // frame too large; drop
                buf.clear()
                buf.put(nal)
                pts += 33333
                codec.queueInputBuffer(index, 0, nal.size, pts, 0)
            } catch (e: Exception) {
                Log.e(TAG, "feed failed", e)
                synchronized(lock) { freeInputIndices.addLast(index) }
                return
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val y = readPlane(image.planes[0], width, height)
        val u = readPlane(image.planes[1], width / 2, height / 2)
        val v = readPlane(image.planes[2], width / 2, height / 2)

        val pixels = IntArray(width * height)
        var p = 0
        for (row in 0 until height) {
            val yRow = row * width
            val uvRow = (row / 2) * (width / 2)
            for (col in 0 until width) {
                val yy = (y[yRow + col].toInt() and 0xFF) - 16
                val uu = (u[uvRow + col / 2].toInt() and 0xFF) - 128
                val vv = (v[uvRow + col / 2].toInt() and 0xFF) - 128
                var r = (1192 * yy + 1634 * vv) shr 10
                var g = (1192 * yy - 400 * uu - 833 * vv) shr 10
                var b = (1192 * yy + 2066 * uu) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                pixels[p++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun readPlane(plane: Image.Plane, width: Int, height: Int): ByteArray {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = ByteArray(width * height)
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(out)
            return out
        }
        for (row in 0 until height) {
            val base = row * rowStride
            if (pixelStride == 1) {
                buffer.position(base)
                buffer.get(out, row * width, width)
            } else {
                for (col in 0 until width) {
                    out[row * width + col] = buffer.get(base + col * pixelStride)
                }
            }
        }
        return out
    }
}
