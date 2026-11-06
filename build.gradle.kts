import java.io.File
import java.util.Properties
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

// ==================== CMake resolution ====================
// An IDE starts the Gradle daemon with the PATH it inherited from the desktop
// session, which on macOS and Linux does not include Homebrew, MacPorts or
// /usr/local/bin: a bare `cmake` lookup then fails before any compilation
// happens. Every module that drives CMake reads the absolute path resolved
// here instead.

/** Android SDK root from the environment or `local.properties`, or `null`. */
fun androidSdkDir(): File? {
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val dir = File(it)
            if (dir.isDirectory) return dir
        }
    }
    val localProperties = file("local.properties")
    if (localProperties.isFile) {
        val properties = Properties().apply { localProperties.inputStream().use { load(it) } }
        properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let {
            val dir = File(it)
            if (dir.isDirectory) return dir
        }
    }
    return null
}

fun resolveCmakeExecutable(): String {
    val executable = if (OperatingSystem.current().isWindows) "cmake.exe" else "cmake"

    val candidates = buildList {
        // The PATH of this process, which is what a terminal build has.
        System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { add(File(it, executable)) }
        // Where the package managers put it when the PATH does not have it.
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/opt/local/bin").forEach {
            add(File(it, executable))
        }
        // Last resort: the copy the Android SDK ships, for SDK-only machines.
        androidSdkDir()?.resolve("cmake")
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?.let { add(it.resolve("bin/$executable")) }
    }

    return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: executable
}

val cmakeExecutable: String = resolveCmakeExecutable()

/** Android NDK prebuilt host tag: the NDK names its directory after the build host it ships. */
fun androidNdkHostTag(): String? = when {
    OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    OperatingSystem.current().isLinux -> "linux-x86_64"
    else -> null
}

/** Android NDK root: `/ndk/<pinned version>` when present, the newest one otherwise. */
fun androidNdkDir(): File? {
    val sdk = androidSdkDir() ?: return null
    val ndkParent = sdk.resolve("ndk")
    if (!ndkParent.isDirectory) return null
    val pinned = ndkParent.resolve(PINNED_ANDROID_NDK_VERSION)
    if (pinned.isDirectory) return pinned
    return ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

/** Android NDK sysroot, or `null` without one. */
fun androidNdkSysroot(): File? {
    val host = androidNdkHostTag() ?: return null
    val ndk = androidNdkDir() ?: return null
    return ndk.resolve("toolchains/llvm/prebuilt/$host/sysroot")
}

/** Kept in step with `android.ndkVersion` in gradle.properties. */
val PINNED_ANDROID_NDK_VERSION = "27.0.12077973"

val androidNdkSysrootPath: String? = androidNdkSysroot()?.absolutePath

val hasSigningKey = providers.gradleProperty("signing.keyId").isPresent ||
    providers.gradleProperty("signing.secretKeyRingFile").isPresent

allprojects {
    group = "cn.enaium.openh264"
    version = "2.7.0.1"

    extra["hasSigningKey"] = hasSigningKey

    // CMake-driven modules take this instead of looking `cmake` up on PATH.
    extra["cmakeExecutable"] = cmakeExecutable

    // Android NDK sysroot for modules that link NDK-built C++ (may be absent).
    extra["androidNdkSysroot"] = androidNdkSysrootPath
}
