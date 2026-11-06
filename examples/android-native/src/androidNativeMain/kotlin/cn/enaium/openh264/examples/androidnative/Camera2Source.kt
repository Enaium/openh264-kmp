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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.openh264.examples.androidnative

import android.camera2.ACameraCaptureSession_setRepeatingRequest
import android.camera2.ACameraCaptureSession_stateCallbacks
import android.camera2.ACameraDevice_StateCallbacks
import android.camera2.ACameraDevice_close
import android.camera2.ACameraDevice_createCaptureRequest
import android.camera2.ACameraDevice_createCaptureSession
import android.camera2.ACameraManager_create
import android.camera2.ACameraManager_delete
import android.camera2.ACameraManager_deleteCameraIdList
import android.camera2.ACameraManager_getCameraIdList
import android.camera2.ACameraManager_openCamera
import android.camera2.ACameraOutputTarget_create
import android.camera2.ACameraOutputTarget_free
import android.camera2.ACaptureRequest_addTarget
import android.camera2.ACaptureRequest_free
import android.camera2.ACaptureRequest_setEntry_u8
import android.camera2.ACAMERA_CONTROL_AWB_MODE
import android.camera2.ACAMERA_CONTROL_AWB_MODE_AUTO
import android.camera2.ACAMERA_CONTROL_MODE
import android.camera2.ACAMERA_CONTROL_MODE_AUTO
import android.camera2.ACaptureSessionOutputContainer_add
import android.camera2.ACaptureSessionOutputContainer_create
import android.camera2.ACaptureSessionOutputContainer_free
import android.camera2.ACaptureSessionOutput_create
import android.camera2.ACaptureSessionOutput_free
import android.camera2.AIMAGE_FORMAT_YUV_420_888
import android.camera2.AImageReader_acquireLatestImage
import android.camera2.AImageReader_delete
import android.camera2.AImageReader_getWindow
import android.camera2.AImageReader_new
import android.camera2.AImage_delete
import android.camera2.AImage_getHeight
import android.camera2.AImage_getNumberOfPlanes
import android.camera2.AImage_getPlaneData
import android.camera2.AImage_getPlanePixelStride
import android.camera2.AImage_getPlaneRowStride
import android.camera2.AImage_getWidth
import android.camera2.TEMPLATE_RECORD
import cnames.structs.ACameraCaptureSession
import cnames.structs.ACameraDevice
import cnames.structs.ACameraManager
import android.camera2.ACameraIdList
import cnames.structs.ACaptureRequest
import cnames.structs.ACaptureSessionOutput
import cnames.structs.ACaptureSessionOutputContainer
import cnames.structs.ACameraOutputTarget
import cn.enaium.libyuv.LibYuv
import cnames.structs.AImage
import cnames.structs.AImageReader
import cnames.structs.ANativeWindow
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.memcpy

/**
 * Camera2 NDK frame source.
 *
 * SDL's Android camera backend labels `YUV_420_888` as NV12 and copies only the
 * first chroma plane. On devices that expose planar chroma that loses the V
 * plane and leaves a magenta cast. Camera2 plus [AImageReader] keeps all three
 * planes; libyuv's `Android420ToI420` then handles both planar and interleaved
 * layouts.
 */
internal class Camera2Source(
    val width: Int = 640,
    val height: Int = 480,
) : AutoCloseable {
    private var manager: CPointer<ACameraManager>? = null
    private var device: CPointer<ACameraDevice>? = null
    private var session: CPointer<ACameraCaptureSession>? = null
    private var request: CPointer<ACaptureRequest>? = null
    private var target: CPointer<ACameraOutputTarget>? = null
    private var output: CPointer<ACaptureSessionOutput>? = null
    private var container: CPointer<ACaptureSessionOutputContainer>? = null
    private var reader: CPointer<AImageReader>? = null
    private var window: CPointer<ANativeWindow>? = null
    private var idList: CPointer<ACameraIdList>? = null
    private var deviceCallbacks: ACameraDevice_StateCallbacks? = null
    private var sessionCallbacks: ACameraCaptureSession_stateCallbacks? = null
    private var planeY = ByteArray(0)
    private var planeU = ByteArray(0)
    private var planeV = ByteArray(0)

    fun open() {
        val manager = ACameraManager_create()
            ?: throw IllegalStateException("ACameraManager_create failed")
        this.manager = manager

        val deviceCallbacks = nativeHeap.alloc<ACameraDevice_StateCallbacks>()
        val sessionCallbacks = nativeHeap.alloc<ACameraCaptureSession_stateCallbacks>()
        this.deviceCallbacks = deviceCallbacks
        this.sessionCallbacks = sessionCallbacks

        memScoped {
            val listPtr = alloc<CPointerVar<ACameraIdList>>()
            checkStatus(
                ACameraManager_getCameraIdList(manager, listPtr.ptr),
                "ACameraManager_getCameraIdList",
            )
            val list = listPtr.value ?: throw IllegalStateException("camera list is null")
            idList = list
            val cameraId = list.pointed.cameraIds?.get(0)?.toKString()
                ?: throw IllegalStateException("no camera id")

            val devicePtr = alloc<CPointerVar<ACameraDevice>>()
            checkStatus(
                ACameraManager_openCamera(manager, cameraId, deviceCallbacks.ptr, devicePtr.ptr),
                "ACameraManager_openCamera",
            )
            val device = devicePtr.value ?: throw IllegalStateException("camera device is null")
            this@Camera2Source.device = device

            val readerPtr = alloc<CPointerVar<AImageReader>>()
            checkStatus(
                AImageReader_new(
                    width,
                    height,
                    AIMAGE_FORMAT_YUV_420_888.toInt(),
                    4,
                    readerPtr.ptr,
                ),
                "AImageReader_new",
            )
            val reader = readerPtr.value ?: throw IllegalStateException("image reader is null")
            this@Camera2Source.reader = reader

            val windowPtr = alloc<CPointerVar<ANativeWindow>>()
            checkStatus(AImageReader_getWindow(reader, windowPtr.ptr), "AImageReader_getWindow")
            val window = windowPtr.value ?: throw IllegalStateException("reader window is null")
            this@Camera2Source.window = window

            val outputPtr = alloc<CPointerVar<ACaptureSessionOutput>>()
            checkStatus(ACaptureSessionOutput_create(window, outputPtr.ptr), "output create")
            val output = outputPtr.value ?: throw IllegalStateException("session output is null")
            this@Camera2Source.output = output

            val containerPtr = alloc<CPointerVar<ACaptureSessionOutputContainer>>()
            checkStatus(
                ACaptureSessionOutputContainer_create(containerPtr.ptr),
                "output container create",
            )
            val container = containerPtr.value
                ?: throw IllegalStateException("session output container is null")
            this@Camera2Source.container = container
            checkStatus(
                ACaptureSessionOutputContainer_add(container, output),
                "output container add",
            )

            val sessionPtr = alloc<CPointerVar<ACameraCaptureSession>>()
            checkStatus(
                ACameraDevice_createCaptureSession(
                    device,
                    container,
                    sessionCallbacks.ptr,
                    sessionPtr.ptr,
                ),
                "createCaptureSession",
            )
            session = sessionPtr.value ?: throw IllegalStateException("capture session is null")

            val requestPtr = alloc<CPointerVar<ACaptureRequest>>()
            checkStatus(
                ACameraDevice_createCaptureRequest(device, TEMPLATE_RECORD, requestPtr.ptr),
                "createCaptureRequest",
            )
            val request = requestPtr.value
                ?: throw IllegalStateException("capture request is null")
            this@Camera2Source.request = request

            val targetPtr = alloc<CPointerVar<ACameraOutputTarget>>()
            checkStatus(ACameraOutputTarget_create(window, targetPtr.ptr), "output target create")
            val target = targetPtr.value ?: throw IllegalStateException("output target is null")
            this@Camera2Source.target = target
            checkStatus(ACaptureRequest_addTarget(request, target), "addTarget")
            memScoped {
                val awb = alloc<UByteVar>()
                awb.value = ACAMERA_CONTROL_AWB_MODE_AUTO.toUByte()
                checkStatus(
                    ACaptureRequest_setEntry_u8(
                        request,
                        ACAMERA_CONTROL_AWB_MODE,
                        1u,
                        awb.ptr,
                    ),
                    "set AWB auto",
                )
                val mode = alloc<UByteVar>()
                mode.value = ACAMERA_CONTROL_MODE_AUTO.toUByte()
                checkStatus(
                    ACaptureRequest_setEntry_u8(
                        request,
                        ACAMERA_CONTROL_MODE,
                        1u,
                        mode.ptr,
                    ),
                    "set control mode auto",
                )
            }

            val requests = alloc<CPointerVar<ACaptureRequest>>()
            requests.value = request
            checkStatus(
                ACameraCaptureSession_setRepeatingRequest(session, null, 1, requests.ptr, null),
                "setRepeatingRequest",
            )
        }
    }

    /** Copies the newest frame into [dstY]/[dstU]/[dstV]; returns false when none is ready. */
    fun acquire(dstY: ByteArray, dstU: ByteArray, dstV: ByteArray): Boolean {
        val reader = reader ?: return false
        val imagePtr = memScoped { alloc<CPointerVar<AImage>>() }
        if (AImageReader_acquireLatestImage(reader, imagePtr.ptr) != 0) return false
        val image = imagePtr.value ?: return false
        try {
            val w = readInt { AImage_getWidth(image, it.ptr) }
            val h = readInt { AImage_getHeight(image, it.ptr) }
            if (w != width || h != height) return false
            val planes = readInt { AImage_getNumberOfPlanes(image, it.ptr) }
            if (planes < 3) return false

            val yData = planeData(image, 0)
            val uData = planeData(image, 1)
            val vData = planeData(image, 2)
            planeY = copyPlane(yData, yData.rowStride * h)
            planeU = copyPlane(uData, uData.rowStride * ((h + 1) / 2))
            planeV = copyPlane(vData, vData.rowStride * ((h + 1) / 2))

            LibYuv.android420ToI420(
                planeY, planeU, planeV,
                dstY, dstU, dstV,
                w, h,
                yData.rowStride, uData.rowStride, vData.rowStride, uData.pixelStride,
            )
            return true
        } finally {
            AImage_delete(image)
        }
    }

    override fun close() {
        session?.let { runCatching { android.camera2.ACameraCaptureSession_close(it) } }
        request?.let { ACaptureRequest_free(it) }
        target?.let { ACameraOutputTarget_free(it) }
        output?.let { ACaptureSessionOutput_free(it) }
        container?.let { ACaptureSessionOutputContainer_free(it) }
        device?.let { runCatching { ACameraDevice_close(it) } }
        reader?.let { AImageReader_delete(it) }
        manager?.let { ACameraManager_delete(it) }
        idList?.let { ACameraManager_deleteCameraIdList(it) }
        deviceCallbacks?.let { nativeHeap.free(it.rawPtr) }
        sessionCallbacks?.let { nativeHeap.free(it.rawPtr) }

        manager = null
        device = null
        session = null
        request = null
        target = null
        output = null
        container = null
        reader = null
        window = null
        idList = null
    }

    private data class PlaneData(
        val data: CPointer<UByteVar>?,
        val length: Int,
        val rowStride: Int,
        val pixelStride: Int,
    )

    private fun planeData(image: CPointer<AImage>, index: Int): PlaneData = memScoped {
        val data = alloc<CPointerVar<UByteVar>>()
        val length = alloc<IntVar>()
        checkStatus(
            AImage_getPlaneData(image, index, data.ptr, length.ptr),
            "AImage_getPlaneData($index)",
        )
        val rowStride = readInt { AImage_getPlaneRowStride(image, index, it.ptr) }
        val pixelStride = readInt { AImage_getPlanePixelStride(image, index, it.ptr) }
        PlaneData(data.value, length.value, rowStride, pixelStride)
    }

    private fun copyPlane(plane: PlaneData, size: Int): ByteArray {
        val out = ByteArray(size)
        val data = plane.data?.reinterpret<ByteVar>() ?: return out
        val count = minOf(size, plane.length)
        if (count > 0) {
            out.usePinned { memcpy(it.addressOf(0), data, count.convert()) }
        }
        return out
    }

    private inline fun readInt(block: (IntVar) -> Int): Int = memScoped {
        val value = alloc<IntVar>()
        block(value)
        value.value
    }

    private fun checkStatus(status: Int, operation: String) {
        if (status != 0) throw IllegalStateException("$operation failed: $status")
    }
}
