package com.sscb.tello

import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.sscb.tello.video.VideoDecoder
import com.sscb.tello.video.VideoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val client = TelloClient()
    private val recorder = VideoRecorder()
    private val decoder = VideoDecoder { bitmap -> latestFrame = bitmap }

    private var connected by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var telloState by mutableStateOf(TelloState())
    private var latestFrame by mutableStateOf<Bitmap?>(null)
    private var recording by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client.onState = { telloState = it }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ControlScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
        decoder.stopAll()
        recorder.stop()
    }

    // region actions

    private fun toggleConnection() {
        if (connected) {
            lifecycleScope.launch(Dispatchers.IO) {
                decoder.stopAll()
                client.sendCommand("streamoff", awaitResponse = false)
                client.disconnect()
                withContext(Dispatchers.Main) {
                    connected = false
                    latestFrame = null
                    message = "切断しました"
                }
            }
        } else {
            message = "接続中…"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    client.connect()
                    val videoOk = decoder.start()
                    withContext(Dispatchers.Main) {
                        connected = true
                        message = if (videoOk) "接続しました" else "接続しました(映像ポートを開けません)"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        message = "接続失敗: ${e.message}"
                    }
                }
            }
        }
    }

    private fun flyCommand(cmd: String) {
        client.sendCommand(cmd) { ok ->
            if (!ok) message = "コマンド失敗: $cmd"
        }
    }

    private fun takeScreenshot() {
        val frame = latestFrame
        if (frame == null) {
            message = "映像を受信していません"
            return
        }
        val uri = MediaSaver.saveScreenshot(this, frame)
        message = if (uri != null) "保存しました: Pictures/Tello" else "保存に失敗しました"
    }

    private fun toggleRecording() {
        if (recording) {
            decoder.recorder = null
            recorder.stop()
            recording = false
            message = "録画を保存しました: Movies/Tello"
        } else {
            val sps = decoder.sps
            val pps = decoder.pps
            if (sps == null || pps == null) {
                message = "映像を受信していません"
                return
            }
            val file = MediaSaver.createVideoFile(this)
            if (file == null) {
                message = "録画ファイルを作成できません"
                return
            }
            if (recorder.start(sps, pps, file.second)) {
                decoder.recorder = recorder
                recording = true
                message = "録画を開始しました"
            } else {
                message = "録画を開始できません"
            }
        }
    }

    // endregion

    // region UI

    @Composable
    private fun ControlScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderRow()
            VideoArea()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MovementPad(Modifier.weight(1f))
                AltitudeYawPad(Modifier.weight(1f))
            }
            FlightButtonsRow()
            MediaButtonsRow()
        }
    }

    @Composable
    private fun HeaderRow() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::toggleConnection) {
                Text(stringResource(if (connected) R.string.disconnect else R.string.connect))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (connected) {
                        "電池 ${telloState.battery}% | 高度 ${"%.1f".format(telloState.heightDm / 10.0)}m"
                    } else {
                        stringResource(R.string.disconnected)
                    },
                    fontSize = 13.sp
                )
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    @Composable
    private fun VideoArea() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val frame = latestFrame
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(stringResource(R.string.no_signal), color = Color.Gray)
            }
            if (recording) {
                Text(
                    text = "REC",
                    color = Color.Red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }

    @Composable
    private fun MovementPad(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HoldButton(stringResource(R.string.forward), Modifier.fillMaxWidth().height(52.dp)) { held ->
                client.setRcChannel(1, if (held) 100 else 0)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(stringResource(R.string.left), Modifier.weight(1f).height(52.dp)) { held ->
                    client.setRcChannel(0, if (held) -100 else 0)
                }
                HoldButton(stringResource(R.string.right), Modifier.weight(1f).height(52.dp)) { held ->
                    client.setRcChannel(0, if (held) 100 else 0)
                }
            }
            HoldButton(stringResource(R.string.back), Modifier.fillMaxWidth().height(52.dp)) { held ->
                client.setRcChannel(1, if (held) -100 else 0)
            }
        }
    }

    @Composable
    private fun AltitudeYawPad(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(stringResource(R.string.up), Modifier.weight(1f).height(52.dp)) { held ->
                    client.setRcChannel(2, if (held) 100 else 0)
                }
                HoldButton(stringResource(R.string.yaw_left), Modifier.weight(1f).height(52.dp)) { held ->
                    client.setRcChannel(3, if (held) -100 else 0)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(stringResource(R.string.down), Modifier.weight(1f).height(52.dp)) { held ->
                    client.setRcChannel(2, if (held) -100 else 0)
                }
                HoldButton(stringResource(R.string.yaw_right), Modifier.weight(1f).height(52.dp)) { held ->
                    client.setRcChannel(3, if (held) 100 else 0)
                }
            }
        }
    }

    @Composable
    private fun FlightButtonsRow() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { flyCommand("takeoff") },
                enabled = connected,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.takeoff)) }
            Button(
                onClick = { flyCommand("land") },
                enabled = connected,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.land)) }
            Button(
                onClick = { flyCommand("emergency") },
                enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.emergency)) }
        }
    }

    @Composable
    private fun MediaButtonsRow() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = ::takeScreenshot,
                enabled = connected,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.screenshot)) }
            OutlinedButton(
                onClick = ::toggleRecording,
                enabled = connected,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (recording) R.string.record_stop else R.string.record_start))
            }
        }
    }

    @Composable
    private fun HoldButton(
        text: String,
        modifier: Modifier = Modifier,
        onHoldChange: (Boolean) -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onHoldChange(true)
                        try {
                            awaitRelease()
                        } finally {
                            onHoldChange(false)
                        }
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // endregion
}
