import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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

/** Where the Android links look for the stubs. */
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

// Shared by every Android ABI:
//  - compiler-rt builtins embedded in the sdl-kmp/imgui-kmp klibs overlap with
//    K/N's bundled libgcc on some ABIs (e.g. __sync_* on armv7), so the first
//    definition wins;
//  - binding the linked archives' globals locally keeps symbol resolution
//    deterministic when SDL, ImGui, libyuv and OpenH264 are all in libmain.so;
//  - Android devices come with either 4 KB or 16 KB memory pages, so every
//    PT_LOAD segment of libmain.so is aligned for both.
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

kotlin {
    // A desktop executable is not the main target of this module, but it runs
    // the exact same SDL/ImGui/libyuv/OpenH264 UI on the build host. That is what
    // makes the example verifiable without an Android device.
    macosArm64 {
        binaries.executable()
    }

    // Android native: SDLActivity (from the SDL3 Android archive the wrapper
    // depends on) loads libmain.so and calls its exported SDL_main.
    listOf(
        "androidNativeArm64" to "arm64-v8a",
        "androidNativeArm32" to "armeabi-v7a",
        "androidNativeX64" to "x86_64",
        "androidNativeX86" to "x86",
    ).forEach { (targetName, abi) ->
        val target = when (targetName) {
            "androidNativeArm64" -> androidNativeArm64()
            "androidNativeArm32" -> androidNativeArm32()
            "androidNativeX64" -> androidNativeX64()
            else -> androidNativeX86()
        }
        target.compilations.getByName("main").cinterops.create("androidCamera2") {
            defFile(file("src/nativeInterop/cinterop/android_camera2.def"))
            includeDirs(file("src/nativeInterop/cinterop"))
        }
        target.binaries.sharedLib("main") {
            linkerOpts("-L" + androidStubLinkDir(abi))
            // OpenH264 is built with the SDK NDK, so libmain.so links that NDK's
            // shared C++ runtime (packaged into the APK next to it) instead of
            // the much older one bundled with Kotlin/Native.
            androidNdkLibDir(abi)?.let { linkerOpts("$it/libc++_shared.so") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    // The repository disables the automatic default hierarchy template
    // (gradle.properties), so materialize it here: nativeMain holds the shared
    // desktop entry point and platform actuals, androidNativeMain the SDL_main.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":openh264-kmp"))
                implementation(libs.libyuv.kmp)
                implementation(libs.imgui.kmp)
                implementation(libs.sdl.kmp)
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
