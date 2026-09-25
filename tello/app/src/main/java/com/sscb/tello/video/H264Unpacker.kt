package com.sscb.tello.video

import java.io.ByteArrayOutputStream

/**
 * Splits the Tello UDP byte stream into Annex-B NAL units.
 * Each emitted NAL is prefixed with a canonical 4-byte start code (00 00 00 01).
 * SPS/PPS are extracted and kept for decoder configuration and MP4 muxing.
 */
class H264Unpacker {

    companion object {
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        private const val MAX_BUFFER = 512 * 1024
    }

    private val buffer = ByteArrayOutputStream()
    private val sync = Any()

    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    /** NAL type is encoded in the low 5 bits of the first payload byte (after the 4-byte start code). */
    fun nalType(nal: ByteArray): Int = (nal[4].toInt() and 0x1F)

    fun feed(data: ByteArray, length: Int): List<ByteArray> = synchronized(sync) {
        buffer.write(data, 0, length)
        val all = buffer.toByteArray()
        if (all.size > MAX_BUFFER) {
            // Corrupted stream without start codes; drop everything.
            buffer.reset()
            return emptyList()
        }

        // Collect start code positions (handles both 3-byte and 4-byte codes).
        val starts = ArrayList<Int>(8)
        var i = 0
        while (i + 2 < all.size) {
            if (all[i] == 0.toByte() && all[i + 1] == 0.toByte() && all[i + 2] == 1.toByte()) {
                val scStart = if (i > 0 && all[i - 1] == 0.toByte()) i - 1 else i
                if (starts.isEmpty() || starts.last() < scStart) starts.add(scStart)
                i += 3
            } else {
                i++
            }
        }

        val result = ArrayList<ByteArray>(starts.size)
        if (starts.size >= 2) {
            for (j in 0 until starts.size - 1) {
                val nal = all.copyOfRange(starts[j], starts[j + 1])
                trackParameterSets(nal)
                result.add(nal)
            }
            // Keep the tail (from the last start code) for the next feed.
            buffer.reset()
            buffer.write(all, starts.last(), all.size - starts.last())
        }
        result
    }

    private fun trackParameterSets(nal: ByteArray) {
        if (nal.size < 5) return
        when (nalType(nal)) {
            NAL_SPS -> sps = nal
            NAL_PPS -> pps = nal
        }
    }
}
