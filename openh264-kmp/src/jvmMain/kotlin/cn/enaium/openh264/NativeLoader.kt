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

package cn.enaium.openh264

import java.io.File
import java.security.MessageDigest

/**
 * Resolves and loads the JNI shared library bundled in the matching
 * `openh264-kmp-jni-jvm-{os}-{arch}` artifact's resources.
 *
 * Falls back to `System.loadLibrary("openh264_jni")` if no bundled binary
 * matches the host: Android loads it from the AAR jniLibs, and a developer
 * workflow may place it on `java.library.path`.
 */
internal object NativeLoader {
    private const val LIB_NAME = "openh264_jni"
    private const val RESOURCE_BASE = "/cn/enaium/openh264/native"

    fun load() {
        // Android's os.name is "Linux", so detect it via android.os.Build which
        // only exists on the Android runtime. On Android the .so ships inside
        // the AAR's jniLibs and is loaded by name.
        val isAndroid = runCatching {
            Class.forName("android.os.Build")
            true
        }.getOrDefault(false)

        if (isAndroid) {
            System.loadLibrary(LIB_NAME)
            return
        }

        val classifier = detectClassifier()
        // Windows DLLs don't use the "lib" prefix (openh264_jni.dll), while
        // Unix shared libraries do (libopenh264_jni.so / .dylib).
        val prefix = if (classifier.startsWith("windows")) "" else "lib"
        val libFile = "$prefix$LIB_NAME.${libExtension()}"
        val resourcePath = "$RESOURCE_BASE/$classifier/$libFile"
        val stream = NativeLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw UnsatisfiedLinkError(
                "Resource $resourcePath not found. " +
                    "os=${System.getProperty("os.name")}, arch=${System.getProperty("os.arch")}, " +
                    "classifier=$classifier. Check that the matching openh264-kmp-jni-jvm artifact is on the classpath.",
            )
        val bytes = stream.use { it.readBytes() }
        val target = extractToTemp(bytes, libFile)
        try {
            System.load(target.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Failed to load $libFile from $resourcePath (os=${System.getProperty("os.name")}, " +
                    "arch=${System.getProperty("os.arch")}): ${e.message}",
            )
        }
    }

    private fun extractToTemp(bytes: ByteArray, libFile: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "openh264-kmp-$hex")
        tempDir.mkdirs()
        val target = File(tempDir, libFile)
        if (!target.isFile || target.length().toInt() != bytes.size) {
            val tmp = File.createTempFile(libFile, ".part", tempDir)
            tmp.writeBytes(bytes)
            // Avoid java.nio.file on Android (API < 26) where the class is
            // absent; the temporary file is in the same directory, so the
            // rename is atomic on the same filesystem.
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp.delete()
                }
            }
        }
        return target
    }

    private fun detectClassifier(): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        val os = when {
            osName.contains("linux") -> "linux"
            osName.contains("mac") || osName.contains("darwin") || osName.contains("osx") -> "darwin"
            osName.contains("win") -> "windows"
            else -> error("Unsupported OS for openh264-kmp JVM artifact: $osName")
        }
        val arch = when (osArch) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported CPU architecture for openh264-kmp JVM artifact: $osArch")
        }
        return "$os-$arch"
    }

    private fun libExtension(): String {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            osName.contains("win") -> "dll"
            osName.contains("mac") || osName.contains("darwin") || osName.contains("osx") -> "dylib"
            else -> "so"
        }
    }
}
