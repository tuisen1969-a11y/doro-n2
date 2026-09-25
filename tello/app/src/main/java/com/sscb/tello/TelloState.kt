package com.sscb.tello

/**
 * Tello state telemetry parsed from the UDP 8890 stream.
 * Raw format example: "pitch:0;roll:0;yaw:0;vgx:0;vgy:0;vgz:0;templ:52;temph:56;
 * tof:10;h:30;bat:80;baro:140.29;time:0;agx:...;wifi:90;"
 */
data class TelloState(
    val pitch: Int = 0,
    val roll: Int = 0,
    val yaw: Int = 0,
    val speedX: Int = 0,
    val speedY: Int = 0,
    val speedZ: Int = 0,
    val temperatureLow: Int = 0,
    val temperatureHigh: Int = 0,
    val tof: Int = 0,
    val heightDm: Int = 0,
    val battery: Int = -1,
    val barometer: Double = 0.0,
    val flightTimeSec: Int = 0,
    val wifiSnr: Int = 0,
) {
    companion object {
        fun parse(raw: String): TelloState {
            var s = TelloState()
            for (pair in raw.split(";")) {
                val kv = pair.split(":", limit = 2)
                if (kv.size != 2) continue
                val key = kv[0].trim()
                val value = kv[1].trim()
                val intVal = value.toIntOrNull()
                when (key) {
                    "pitch" -> intVal?.let { s = s.copy(pitch = it) }
                    "roll" -> intVal?.let { s = s.copy(roll = it) }
                    "yaw" -> intVal?.let { s = s.copy(yaw = it) }
                    "vgx" -> intVal?.let { s = s.copy(speedX = it) }
                    "vgy" -> intVal?.let { s = s.copy(speedY = it) }
                    "vgz" -> intVal?.let { s = s.copy(speedZ = it) }
                    "templ" -> intVal?.let { s = s.copy(temperatureLow = it) }
                    "temph" -> intVal?.let { s = s.copy(temperatureHigh = it) }
                    "tof" -> intVal?.let { s = s.copy(tof = it) }
                    "h" -> intVal?.let { s = s.copy(heightDm = it) }
                    "bat" -> intVal?.let { s = s.copy(battery = it) }
                    "baro" -> value.toDoubleOrNull()?.let { s = s.copy(barometer = it) }
                    "time" -> intVal?.let { s = s.copy(flightTimeSec = it) }
                    "wifi" -> intVal?.let { s = s.copy(wifiSnr = it) }
                }
            }
            return s
        }
    }
}
