@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.openh264.examples.browser

import cn.enaium.openh264.OpenH264
import cn.enaium.openh264.OpenH264Decoder
import cn.enaium.openh264.OpenH264Frame
import kotlin.io.encoding.Base64
import org.khronos.webgl.Uint8ClampedArray

/**
 * Browser loopback:
 *
 * 1. `navigator.mediaDevices.getUserMedia()` provides the camera stream.
 * 2. WebCodecs `VideoEncoder` encodes the camera canvas as AVC with
 *    `avc: { format: "annexb" }`, so the chunks are Annex-B access units.
 * 3. `OpenH264Decoder` (the same embedded WebAssembly module the published js
 *    artifact uses) decodes each chunk.
 * 4. The decoded I420 picture is converted to RGBA and painted on the output
 *    canvas.
 *
 * The demo intentionally uses plain `js()` interop: WebCodecs, `getUserMedia`
 * and `ImageData` are not covered by the Kotlin/JS DOM declarations yet.
 */
private val document: dynamic = js("document")
private val window: dynamic = js("window")
private val navigator: dynamic = js("navigator")
private val console: dynamic = js("console")

private var video: dynamic = null
private var capture: dynamic = null
private var outputCanvas: dynamic = null
private var status: dynamic = null
private var stats: dynamic = null

private var decoder: OpenH264Decoder? = null
private var encoder: dynamic = null
private var imageData: dynamic = null
private var imageDataPixels: Uint8ClampedArray? = null
private var imageWidth = 0
private var imageHeight = 0

private var lastFrameTime = -1.0
private var lastReportTime = 0.0
private var encodedFrames = 0L
private var decodedFrames = 0L
private var lastFrameSize = 0

fun main() {
    buildDom()
    startCamera()
}

private fun buildDom() {
    status = document.getElementById("status")
    stats = document.getElementById("stats")

    video = document.createElement("video")
    video.autoplay = true
    video.muted = true
    video.playsInline = true
    document.getElementById("camera").appendChild(video)

    outputCanvas = document.createElement("canvas")
    outputCanvas.width = 640
    outputCanvas.height = 480
    document.getElementById("decoded").appendChild(outputCanvas)

    // Off-screen canvas used only to hand camera frames to WebCodecs.
    capture = document.createElement("canvas")
    capture.width = 640
    capture.height = 480

    status.textContent = "Requesting camera…"
}

private fun startCamera() {
    val constraints = js(
        """({
            audio: false,
            video: {
                width: { ideal: 640 },
                height: { ideal: 480 },
                frameRate: { ideal: 30 }
            }
        })""",
    )
    if ((js("typeof navigator.mediaDevices === 'undefined'") as Boolean)) {
        status.textContent = "navigator.mediaDevices is not available (serve the page over HTTPS or localhost)."
        return
    }
    navigator.mediaDevices.getUserMedia(constraints).then({ stream: dynamic ->
        video.srcObject = stream
        video.play()
        status.textContent = "Camera ready, opening encoder…"
        requestFrame()
    }).catch({ error: dynamic ->
        status.textContent = "Camera error: $error"
        console.error(error)
    })
}

private fun requestFrame() {
    window.requestAnimationFrame({ frameLoop() })
}

private fun frameLoop() {
    try {
        if ((video.readyState as Double) >= 2.0 && (video.videoWidth as Double) > 0.0) {
            encodeNewFrame()
        }
        val now = window.performance.now() as Double
        if (now - lastReportTime > 500.0) {
            lastReportTime = now
            report()
        }
    } catch (error: Throwable) {
        status.textContent = "Pipeline error: ${error.message}"
        console.error((error.asDynamic().stack as? String) ?: error.toString())
    }
    requestFrame()
}

private fun encodeNewFrame() {
    val width = (video.videoWidth as Double).toInt()
    val height = (video.videoHeight as Double).toInt()
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return

    val time = video.currentTime as Double
    if (time == lastFrameTime) return
    lastFrameTime = time

    if ((capture.width as Double).toInt() != width || (capture.height as Double).toInt() != height) {
        capture.width = width
        capture.height = height
    }
    val context = capture.getContext("2d")
    context.drawImage(video, 0, 0, width, height)

    val active = encoder ?: openEncoder(width, height)
    if ((active.queueSize() as Double) > 4.0) return
    val timestamp = ((window.performance.now() as Double) * 1000.0).toLong()
    if (active.encode(capture, timestamp, encodedFrames % 30L == 0L) as Boolean) {
        encodedFrames++
    }

    val chunks = active.drain() as Array<String>
    for (chunk in chunks) {
        val first = chunk.indexOf(':')
        val second = chunk.indexOf(':', first + 1)
        if (first < 0 || second < 0) continue
        val timestampUs = chunk.substring(first + 1, second).toLongOrNull() ?: 0L
        val data = Base64.decode(chunk.substring(second + 1))
        lastFrameSize = data.size
        decoder?.decode(data, timestampUs)?.let(::render)
    }
}

private fun openEncoder(width: Int, height: Int): dynamic {
    val created = createEncoder(width, height)
    encoder = created
    decoder = OpenH264Decoder.create()
    status.textContent = "OpenH264 ${OpenH264.libraryVersion}, ${width}x$height"
    if (!(created.supported() as Boolean)) {
        status.textContent = "This browser does not expose a usable WebCodecs AVC VideoEncoder."
    }
    return created
}

private fun render(frame: OpenH264Frame) {
    val width = frame.width
    val height = frame.height
    if (imageData == null || imageWidth != width || imageHeight != height) {
        imageWidth = width
        imageHeight = height
        outputCanvas.width = width
        outputCanvas.height = height
        imageData = js("(w, h) => new ImageData(new Uint8ClampedArray(w * h * 4), w, h)")(width, height)
        imageDataPixels = imageData.data as Uint8ClampedArray
    }
    val pixels = imageDataPixels ?: return
    val y = frame.y
    val u = frame.u
    val v = frame.v
    val chromaWidth = frame.chromaWidth
    val chromaHeight = frame.chromaHeight
    // `Uint8ClampedArray.set(index, Byte)` clamps the signed Byte to 0, which
    // turns every value above 127 (and the 255 alpha) black. Writing the
    // underlying JS array keeps the 0..255 range.
    val data = pixels.asDynamic()
    var target = 0
    for (row in 0 until height) {
        val chromaRow = row / 2
        for (column in 0 until width) {
            val luma = (y[row * width + column].toInt() and 0xFF) - 16
            val chromaIndex = chromaRow * chromaWidth + column / 2
            val cb = (u[chromaIndex].toInt() and 0xFF) - 128
            val cr = (v[chromaIndex].toInt() and 0xFF) - 128
            val c = if (luma > 0) luma else 0
            data[target] = clamp((298 * c + 409 * cr + 128) shr 8)
            data[target + 1] = clamp((298 * c - 100 * cb - 208 * cr + 128) shr 8)
            data[target + 2] = clamp((298 * c + 516 * cb + 128) shr 8)
            data[target + 3] = 255
            target += 4
        }
    }
    outputCanvas.getContext("2d").putImageData(imageData, 0, 0)
    decodedFrames++
}

private fun clamp(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value

private fun report() {
    val encoderState = encoder?.state()?.toString() ?: "unconfigured"
    val support = encoder?.support()?.toString() ?: "unknown"
    val error = encoder?.lastError()?.toString().orEmpty()
    stats.textContent = "encoded $encodedFrames frames · decoded $decodedFrames frames · " +
        "last $lastFrameSize bytes · encoder $encoderState ($support)" +
        if (error.isEmpty()) "" else " error=$error"
}

/**
 * Builds the WebCodecs AVC encoder. Kept in JavaScript because `VideoEncoder`,
 * `VideoFrame` and `EncodedVideoChunk` have no Kotlin declarations yet.
 */
private fun createEncoder(width: Int, height: Int): dynamic = js(
    """(function(width, height) {
        if (typeof VideoEncoder === 'undefined') {
            return {
                supported() { return false; },
                encode() { return false; },
                drain() { return []; },
                state() { return 'unconfigured'; },
                support() { return 'unavailable'; },
                queueSize() { return 0; },
                lastError() { return ''; },
                encodeQueueSize: 0
            };
        }
        const queue = [];
        let frames = 0;
        let lastError = null;
        let support = 'unknown';
        const config = {
            codec: 'avc1.42E01E',
            width: width,
            height: height,
            bitrate: 2000000,
            framerate: 30,
            latencyMode: 'realtime',
            avc: { format: 'annexb' }
        };
        let encoder = null;
        try {
            encoder = new VideoEncoder({
                output(chunk, metadata) {
                    const data = new Uint8Array(chunk.byteLength);
                    chunk.copyTo(data);
                    let text = '';
                    const chunkSize = 0x8000;
                    for (let i = 0; i < data.length; i += chunkSize) {
                        text += String.fromCharCode.apply(null, data.subarray(i, i + chunkSize));
                    }
                    queue.push((chunk.type === 'key' ? 'k' : 'd') + ':' +
                        chunk.timestamp + ':' + btoa(text));
                },
                error(error) {
                    lastError = String(error && error.message || error);
                    console.error('WebCodecs encode error', error);
                }
            });
            encoder.configure(config);
        } catch (error) {
            console.error('WebCodecs encoder configure failed', error);
            lastError = String(error && error.message || error);
            encoder = null;
        }
        VideoEncoder.isConfigSupported(config).then((result) => {
            support = result.supported ? 'supported' : 'unsupported';
            if (!result.supported) console.error('Unsupported encoder config', config, result);
        }).catch((error) => {
            support = 'error:' + error;
        });
        return {
            supported() { return encoder !== null; },
            encode(canvas, timestamp, keyFrame) {
                if (!encoder) return false;
                try {
                    encoder.encode(new VideoFrame(canvas, { timestamp: timestamp }),
                        { keyFrame: keyFrame });
                    frames++;
                    return true;
                } catch (error) {
                    lastError = String(error && error.message || error);
                    console.error('WebCodecs encode failed', error);
                    return false;
                }
            },
            drain() {
                return queue.splice(0, queue.length);
            },
            state() { return encoder ? encoder.state : 'unconfigured'; },
            support() { return support; },
            queueSize() { return encoder ? encoder.encodeQueueSize : 0; },
            lastError() { return lastError || ''; }
        };
    })""",
)(width, height)
