package com.sscb.tello

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tello SDK command channel (UDP 8889) and state channel (UDP 8890).
 *
 * Compatible with standard Tello (SDK 1.3/2.0), Tello EDU and RoboMaster TT (SDK 3.0):
 * after entering SDK mode with "command", "streamon" is always sent; older models
 * may answer with an error which is ignored.
 */
class TelloClient {

    companion object {
        const val DRONE_ADDRESS = "192.168.10.1"
        const val CMD_PORT = 8889
        const val STATE_PORT = 8890
        const val VIDEO_PORT = 11111
        private const val CMD_TIMEOUT_MS = 7000
    }

    private var cmdSocket: DatagramSocket? = null
    private var stateSocket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val rc = AtomicReference(intArrayOf(0, 0, 0, 0)) // lr, fb, ud, yaw
    private var stateThread: Thread? = null
    private var rcThread: Thread? = null

    @Volatile
    var connected = false
        private set

    /** Called from the state listener thread. */
    var onState: ((TelloState) -> Unit)? = null

    /** Binds sockets, enters SDK mode and enables the video stream. Must not run on the main thread. */
    @Throws(IOException::class)
    fun connect() {
        disconnect()
        val addr = InetAddress.getByName(DRONE_ADDRESS)

        // Bind the state socket early so telemetry is not missed.
        val sSock = DatagramSocket(STATE_PORT)
        stateSocket = sSock

        val sock = DatagramSocket()
        sock.soTimeout = CMD_TIMEOUT_MS
        cmdSocket = sock
        try {
            sendPacket(sock, addr, "command")
            val resp = receiveResponse(sock)
            if (!resp.equals("ok", ignoreCase = true)) {
                throw IOException("Telloがcommandを受け付けませんでした: $resp")
            }
            // streamon is required for EDU/TT; standard Tello may answer "error" — ignore.
            sendPacket(sock, addr, "streamon")
            try {
                receiveResponse(sock)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            cmdSocket = null
            try { sSock.close() } catch (_: Exception) {}
            stateSocket = null
            throw e
        }

        connected = true
        running.set(true)

        stateThread = Thread { stateLoop(sSock) }.apply { isDaemon = true; start() }
        rcThread = Thread { rcLoop() }.apply { isDaemon = true; start() }
    }

    fun disconnect() {
        running.set(false)
        connected = false
        rcThread?.interrupt()
        rcThread = null
        stateThread?.interrupt()
        stateThread = null
        try { stateSocket?.close() } catch (_: Exception) {}
        stateSocket = null
        try { cmdSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
    }

    /**
     * Sends an SDK command on a background thread.
     * [onResult] receives true when the drone answered "ok" (or when no response is awaited).
     */
    fun sendCommand(cmd: String, awaitResponse: Boolean = true, onResult: (Boolean) -> Unit = {}) {
        executor.execute {
            val ok = doSend(cmd, awaitResponse)
            onResult(ok)
        }
    }

    private fun doSend(cmd: String, awaitResponse: Boolean): Boolean {
        val sock = cmdSocket ?: return false
        return try {
            sendPacket(sock, InetAddress.getByName(DRONE_ADDRESS), cmd)
            if (!awaitResponse) {
                true
            } else {
                try {
                    receiveResponse(sock).equals("ok", ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendPacket(sock: DatagramSocket, addr: InetAddress, cmd: String) {
        val data = cmd.toByteArray(StandardCharsets.UTF_8)
        sock.send(DatagramPacket(data, data.size, addr, CMD_PORT))
    }

    private fun receiveResponse(sock: DatagramSocket): String {
        val buf = ByteArray(1024)
        val pkt = DatagramPacket(buf, buf.size)
        sock.receive(pkt)
        return String(buf, 0, pkt.length, StandardCharsets.UTF_8).trim()
    }

    /** Updates one RC channel: 0=left/right, 1=forward/back, 2=up/down, 3=yaw. Values are -100..100. */
    fun setRcChannel(index: Int, value: Int) {
        require(index in 0..3)
        while (true) {
            val cur = rc.get()
            val next = cur.copyOf()
            next[index] = value.coerceIn(-100, 100)
            if (rc.compareAndSet(cur, next)) return
        }
    }

    private fun rcLoop() {
        val addr = try {
            InetAddress.getByName(DRONE_ADDRESS)
        } catch (_: Exception) {
            return
        }
        while (running.get()) {
            val v = rc.get()
            try {
                val sock = cmdSocket ?: break
                sendPacket(sock, addr, "rc ${v[0]} ${v[1]} ${v[2]} ${v[3]}")
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun stateLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val state = TelloState.parse(String(buf, 0, pkt.length, StandardCharsets.UTF_8))
                onState?.invoke(state)
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }
}
