# openh264-kmp

Kotlin Multiplatform bindings for [Cisco OpenH264](https://github.com/cisco/openh264):
H.264 decoding on JVM, Android, Kotlin/Native and the web.

`openh264` is included as a Git submodule and compiled from source for every
target. One C ABI ([`jni/include/openh264_kmp.h`](jni/include/openh264_kmp.h))
drives the JNI bridge, the Kotlin/Native cinterop and the WebAssembly build, so
every platform shares the same access-unit handling and result format.
OpenH264's assembly kernels are disabled: the portable C/C++ implementation
runs everywhere, which keeps a single code path across x86, ARM, Android,
MinGW and WebAssembly.

Binding version: **2.7.0.1** (OpenH264 `2.7.0`, decoder API `20700`).

## Supported targets

| Platform | Kotlin target | Native artifact |
|---|---|---|
| JVM | `jvm` | `openh264-kmp-jni-jvm-{linux,darwin}-{x86_64,aarch64}`, `openh264-kmp-jni-jvm-windows-x86_64` |
| Android | `android` | `openh264-kmp` AAR with `libopenh264_jni.so` for every ABI |
| Android Native | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | static archive embedded in the cinterop klib |
| Linux | `linuxX64`, `linuxArm64` | static archive embedded in the cinterop klib |
| Windows | `mingwX64` | static archive embedded in the cinterop klib |
| macOS | `macosX64`, `macosArm64` | static archive embedded in the cinterop klib |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | static archive embedded in the cinterop klib |
| tvOS | `tvosArm64`, `tvosSimulatorArm64` | static archive embedded in the cinterop klib |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | static archive embedded in the cinterop klib |
| Kotlin/JS | `js` | embedded WebAssembly module |
| Kotlin/Wasm | `wasmJs` | embedded WebAssembly module |

The web targets compile the decoder and the shim to a standalone `.wasm`
module with Emscripten, embed the binary into the published klib and
instantiate it synchronously through a small JS bridge. No resource lookup or
async initialization is required at runtime. OpenH264 only spawns decoder
worker threads when `DECODER_OPTION_NUM_OF_THREADS` is set, so the WebAssembly
build needs neither pthreads nor a patched copy; every other target uses the
portable single-thread decoder path as well.

The standalone module, the JS glue and the licence are also published as the
`@enaium/openh264-kmp` npm package (`:openh264-kmp:assembleWasmNpm` stages it in
`build/npm`, `:openh264-kmp:publishWasmNpm` publishes it). npm only accepts
three-component semver, so `2.7.0.1` is published as `2.7.0-1` under
the `latest` tag. Plain JavaScript apps instantiate it without Kotlin:

```js
import { initOpenH264Kmp } from "@enaium/openh264-kmp";

const module = await initOpenH264Kmp();
console.log(module.versionString());
```

The npm token is read from `npm.publish.key` in `~/.gradle/gradle.properties`
(or `-Pnpm.publish.key=<token>`); CI reads the `NPM_PUBLISH_KEY` secret.

This build is decoder-only and 8-bit. It outputs 4:2:0 (I420) pictures.

## Using the library

```kotlin
dependencies {
    implementation("cn.enaium.openh264:openh264-kmp:2.7.0.1")
}
```

The Android AAR and the JVM artifacts contain the native libraries. JVM
consumers automatically receive the per-OS/arch JNI artifacts and
`NativeLoader` extracts the matching one from the classpath.

### Decoding

```kotlin
import cn.enaium.openh264.*

OpenH264Decoder.create().use { decoder ->
    for (accessUnit in accessUnits) {
        // Annex-B access unit: one or more NAL units with start codes, exactly
        // what MediaCodec, WebCodecs (`avc: { format: "annexb" }`) and muxers
        // produce.
        decoder.decode(accessUnit, pts = presentationTimeUs)?.let { frame ->
            render(frame.width, frame.height, frame.y, frame.u, frame.v)
        }
    }

    // Pictures still held in the reorder buffer, if any.
    decoder.flush().forEach { frame -> render(frame.width, frame.height, frame.y, frame.u, frame.v) }
}
```

`decode` returns `null` when the access unit did not complete a picture (for
example a delta frame that arrived before its reference), and a frame when it
did. The SPS/PPS may be part of the first access unit or repeated before key
frames; both are supported.

`OpenH264Frame` carries the picture size, the timestamps echoed back and the
three tightly packed planes:

```kotlin
class OpenH264Frame(
    val width: Int,
    val height: Int,
    val chromaFormat: OpenH264ChromaFormat, // I420
    val pts: Long,
    val y: ByteArray, // width * height
    val u: ByteArray, // (width / 2) * (height / 2)
    val v: ByteArray, // (width / 2) * (height / 2)
)
```

The planes are copies of OpenH264's output, so a frame stays valid after the
next `decode` call. `toI420()` concatenates them in I420 order, which is what
libyuv, MediaCodec and most upload paths expect.

`OpenH264.libraryVersion`, `libraryVersionPacked` and `apiBuild` report the
native release (`2.7.0`, `20700`, `20700`); `OpenH264.VERSION` is the binding
version (`2.7.0.1`).

## Examples

The examples share one SDL3 + Dear ImGui app in `examples/common` (targets:
`jvm`, macOS/Linux/Windows native, Android JVM, Android Native and `wasmJs`).
Camera frames come from [camera-kmp](https://github.com/Enaium/camera-kmp):
Camera2 on Android, Camera2 NDK and V4L2 on Android Native,
AVFoundation/V4L2/Media Foundation on the desktops and `getUserMedia` in the
browser, so the same code runs on every target instead of an Android-only
camera implementation.

The UI shows the camera-kmp capture next to an embedded single-frame H.264
Annex-B stream decoded by `OpenH264Decoder`; the decoder runs even when no
camera is available (CI, denied permission) by using a deterministic test
pattern.

`examples/common` is also the wasmJs browser host: its Emscripten runtimes
(sdl-kmp, imgui-kmp and camera-kmp) are installed as npm packages and served
next to the Kotlin module, and `requestAnimationFrame` drives the shared loop.

The two Android host modules are thin wrappers around the shared code:

- `examples/android-jvm` - an Android app whose `SDLActivity.main()` calls the
  shared `runExample()`.
- `examples/android-native` - an Android app that packages the `libmain.so`
  built by `examples/common`; it carries no camera or UI code of its own.

```bash
# desktop entry of the shared module
./gradlew :examples:common:linkDebugExecutableMacosArm64
./examples/common/build/bin/macosArm64/debugExecutable/common.kexe

./gradlew :examples:android-jvm:assembleDebug
./gradlew -PandroidAbis=arm64-v8a :examples:android-native:assembleDebug

./gradlew :examples:common:wasmJsBrowserDistribution
python3 -m http.server 8080 --directory examples/common/build/dist/wasmJs/productionExecutable
# open http://localhost:8080/ - serve over HTTPS or localhost for the camera
```

The SDL3 and imgui-kmp APIs are published on Maven Central
(`cn.enaium.sdl:sdl-kmp:3.4.18.2`, `cn.enaium.imgui:imgui-kmp:1.93.0.2`); the
browser runtimes are `@enaium/sdl-kmp@3.4.18-2`, `@enaium/imgui-kmp@1.93.0-2`
and `@enaium/camera-kmp@1.0.0`.

## Building from source

```bash
git submodule update --init --recursive
./gradlew :openh264-kmp:jvmTest                 # JNI + tests
./gradlew :openh264-kmp:macosArm64Test          # cinterop + static archive
./gradlew :openh264-kmp:jsNodeTest              # Emscripten module in Node
./gradlew :openh264-kmp:wasmJsNodeTest
./gradlew :openh264-kmp:testAndroidHostTest
./gradlew :examples:common:wasmJsBrowserDistribution
./gradlew :examples:android-jvm:assembleDebug
```

Native targets are built through CMake, which lists the sources from
OpenH264's `codec/common/targets.mk` and `codec/decoder/targets.mk`
(`jni/CMakeLists.txt`). No NASM, no per-architecture assembler and no external
package manager are needed. `version_gen.h` is generated from the pinned
version, because OpenH264's Makefile normally produces it from git. The web
build compiles the submodule in place and embeds `openh264_wasm.wasm` into the
published klib.

Tests decode an embedded six-access-unit Annex-B baseline stream (64×48,
generated with ffmpeg/libx264) and assert the picture size, plane geometry,
non-blank luma and timestamp round-tripping. The same test runs on JVM, Apple
and Linux Kotlin/Native targets, Node (js) and Node (wasmJs).

## Licence

OpenH264 is licensed under the BSD 2-Clause licence; see [`LICENSE`](LICENSE),
which is the upstream licence. The bindings are released under the same
BSD-2-Clause terms.
