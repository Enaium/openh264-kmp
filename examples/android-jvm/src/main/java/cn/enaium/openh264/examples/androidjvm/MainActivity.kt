/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.openh264.examples.androidjvm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.enaium.openh264.OpenH264
import kotlin.math.max

/**
 * CameraX -> MediaCodec AVC encoder -> OpenH264 decoder -> RGBA view.
 *
 * The example keeps the whole loop in memory: CameraX delivers I420 frames,
 * MediaCodec encodes them to Annex-B AVC access units, and the OpenH264
 * Kotlin Multiplatform binding decodes each access unit back to I420, which
 * libyuv converts to RGBA for [FrameView].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch the binding before the first frame so the native library is
        // loaded on the UI thread and failures surface early.
        runCatching { OpenH264.libraryVersion }
        setContent {
            MaterialTheme {
                CameraScreen()
            }
        }
    }
}

/** Draws the newest decoded frame, letterboxed. */
class FrameView(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var bitmap: Bitmap? = null

    fun setFrame(frame: Bitmap) {
        bitmap = frame
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = bitmap ?: return
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val drawWidth = frame.width * scale
        val drawHeight = frame.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(frame, null, destination, paint)
    }
}

@Composable
private fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var running by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<PipelineStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rotationDegrees by remember { mutableStateOf(0) }
    var frameView by remember { mutableStateOf<FrameView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameView(ctx).also { frameView = it }
                },
            )
            if (!permissionGranted) {
                Text(
                    text = "Camera permission is required",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = permissionGranted && !running,
                    onClick = {
                        error = null
                        stats = null
                        running = true
                    },
                ) { Text("Start") }
                Button(
                    enabled = running,
                    onClick = { running = false },
                ) { Text("Stop") }
            }

            StatsCard(stats = stats, error = error, rotationDegrees = rotationDegrees)
        }
    }

    // The pipeline only exists while the Start toggle is live.
    DisposableEffect(running, permissionGranted) {
        if (!running || !permissionGranted) {
            return@DisposableEffect onDispose { }
        }
        val pipeline = H264Pipeline(
            onFrame = { bitmap -> frameView?.setFrame(bitmap) },
            onStats = { updated -> stats = updated },
            onError = { throwable ->
                error = throwable.message ?: throwable.toString()
                running = false
            },
        )
        val capture = CameraCapture(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrame = { frame ->
                rotationDegrees = frame.rotationDegrees
                pipeline.submit(frame)
            },
            onError = { throwable ->
                error = throwable.message ?: throwable.toString()
                running = false
            },
        )
        pipeline.start()
        capture.start()
        onDispose {
            capture.stop()
            pipeline.stop()
        }
    }

    // CameraX keeps the process camera provider alive; releasing it here avoids
    // leaking a camera session when the activity is recreated.
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
}

@Composable
private fun StatsCard(stats: PipelineStats?, error: String?, rotationDegrees: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "OpenH264 ${OpenH264.libraryVersion} (api ${OpenH264.apiBuild})",
                style = MaterialTheme.typography.titleMedium,
            )
            if (error != null) {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
            val current = stats
            if (current == null) {
                Text("Idle", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${current.width}x${current.height}, rotation $rotationDegrees deg",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "encoded ${current.encodedFrames} / decoded ${current.decodedFrames} " +
                        "(${current.droppedFrames} dropped)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "%.1f fps, %.0f kbit/s".format(current.fps, bitrateKbps(current)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun bitrateKbps(stats: PipelineStats): Double {
    if (stats.encodedFrames == 0L) return 0.0
    val bytesPerSecond = stats.bytes.toDouble() / max(stats.encodedFrames, 1)
    return bytesPerSecond * 30.0 * 8.0 / 1000.0
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
