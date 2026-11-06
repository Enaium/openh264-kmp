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

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cn.enaium.libyuv.I420Frame
import cn.enaium.libyuv.toI420
import java.util.concurrent.Executors

/**
 * CameraX `ImageAnalysis` source for [H264Pipeline].
 *
 * Frames are delivered on a single background thread and dropped by the camera
 * when the encoder cannot keep up (`STRATEGY_KEEP_ONLY_LATEST`).
 */
internal class CameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (I420Frame) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openh264-camera")
    }
    private var provider: ProcessCameraProvider? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                bind(future.get())
            } catch (t: Throwable) {
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        if (!running) return
        provider = cameraProvider

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(CAPTURE_WIDTH, CAPTURE_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()

        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                if (running) onFrame(image.toI420())
            } catch (t: Throwable) {
                onError(t)
            } finally {
                image.close()
            }
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysis,
        )
    }

    fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        provider = null
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "openh264-camera"
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480

        init {
            Log.i(TAG, "capture target ${CAPTURE_WIDTH}x$CAPTURE_HEIGHT")
        }
    }
}
