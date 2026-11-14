import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// ---------------------------------------------------------------------------
// Android stub libraries
//
// SDL3's Android drivers reference the platform libraries (libEGL, libGLESv2,
// libOpenSLES, libaaudio, ...) at link time, and Kotlin/Native ships stubs for
// them in its Android toolchain sysroot. The NDK's own stubs cannot stand in:
// the toolchain's linker cannot read their compressed debug sections. That
// sysroot only exists once the toolchain has been downloaded, which happens
// while a build runs, so every Android link is pointed at a directory this
// build fills first (running the target's compile downloads the toolchain).
// ---------------------------------------------------------------------------
val androidStubDir = layout.buildDirectory.dir("androidStubs")

/** Android API level the stubs are taken for (AAudio needs 26). */
val ANDROID_API_LEVEL = 26

/** ABI to the NDK triple its stubs live under. */
val androidTriples = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "arm-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

fun androidStubLinkDir(abi: String): String =
    androidStubDir.get().asFile.resolve(abi).absolutePath

/** The stubs inside the Kotlin/Native Android sysroot, or `null` before it is downloaded. */
fun konanAndroidStubDir(abi: String): File? {
    val triple = androidTriples[abi] ?: return null
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    return File(konanData, "dependencies").listFiles()
        ?.filter { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?.maxByOrNull { it.name }
        ?.resolve("sysroot/usr/lib/$triple/$ANDROID_API_LEVEL")
        ?.takeIf { it.isDirectory }
}

val androidMainLinkerOpts = listOf(
    "-Wl,--allow-multiple-definition",
    "-Wl,-Bsymbolic",
    "-Wl,-z,max-page-size=16384",
    "-Wl,-z,common-page-size=16384",
)

/** NDK sysroot library directory for [abi], or `null` when no NDK is installed. */
fun androidNdkLibDir(abi: String): String? {
    val sysroot = rootProject.extra["androidNdkSysroot"] as String? ?: return null
    val triple = androidTriples[abi] ?: return null
    return "$sysroot/usr/lib/$triple"
}

fun konanAndroidLibDir(abi: String): String? {
    val triple = androidTriples[abi] ?: return null
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: providers.gradleProperty("konan.data.dir")
            .getOrElse("${System.getProperty("user.home")}/.konan")
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    return "$toolchain/sysroot/usr/lib/$triple/26"
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
        mainRun {
            mainClass = "cn.enaium.openh264.examples.common.Main_jvmKt"
        }
    }

    android {
        namespace = "cn.enaium.openh264.examples.common"
        compileSdk = 37
        minSdk = 26

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    macosArm64 { binaries.executable() }
    macosX64 { binaries.executable() }

    linuxX64 {
        binaries.executable {
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    linuxArm64 {
        binaries.executable {
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    mingwX64 {
        binaries.executable {
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    androidNativeArm64 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("arm64-v8a"))
            androidNdkLibDir("arm64-v8a")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }
    androidNativeArm32 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("armeabi-v7a"))
            androidNdkLibDir("armeabi-v7a")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }
    androidNativeX64 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("x86_64"))
            androidNdkLibDir("x86_64")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }
    androidNativeX86 {
        binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir("x86"))
            androidNdkLibDir("x86")?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    wasmJs {
        browser()
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val compilation = compilations.getByName("main")
        if (name.startsWith("androidNative")) {
            compilation.defaultSourceSet.kotlin.srcDir("src/androidNativeMain/kotlin")
        } else {
            compilation.defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":openh264-kmp"))
                implementation(libs.libyuv.kmp)
                implementation(libs.sdl.kmp)
                implementation(libs.imgui.kmp)
                implementation(libs.camera.kmp)
            }
        }
    }
}

// Fill the stub directories the Android links above point at. Depending on the
// compile is what guarantees the toolchain - and with it the stubs - has been
// downloaded by the time they are copied.
mapOf(
    "AndroidNativeArm64" to "arm64-v8a",
    "AndroidNativeArm32" to "armeabi-v7a",
    "AndroidNativeX64" to "x86_64",
    "AndroidNativeX86" to "x86",
).forEach { (targetName, abi) ->
    val copyStubs = tasks.register<Copy>("copyAndroidStubs$targetName") {
        description = "Copies the Android stub libraries for $abi into the link directory."
        dependsOn("compileKotlin$targetName")
        from(provider { konanAndroidStubDir(abi)?.let { listOf(it) } ?: emptyList<File>() })
        into(androidStubDir.map { it.dir(abi) })
    }
    listOf("linkMainDebugShared$targetName", "linkMainReleaseShared$targetName").forEach { linkName ->
        tasks.named(linkName) { dependsOn(copyStubs) }
    }
}

// SDL3 must create its window on the first thread on macOS.
tasks.withType<JavaExec>().configureEach {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}

// Use the Node/npm toolchain installed on the machine instead of downloading
// the Kotlin-pinned distribution.
plugins.withType<NodeJsPlugin> {
    the<NodeJsEnvSpec>().download = false
}
