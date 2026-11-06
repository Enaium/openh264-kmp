import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

// The native module builds libmain.so (with an exported SDL_main) for every
// androidNative ABI; copy those into jniLibs and depend on the link tasks so
// the APK is assembled after them. libc++_shared.so is packaged next to it
// because libmain.so links the shared C++ runtime that stock Android images
// do not ship.
val allAndroidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

/**
 * ABIs the APK links and packages: all four by default. Narrow it with
 * `-PandroidAbis=arm64-v8a` when the host cannot link all of them - an Apple
 * Silicon Mac without Rosetta cannot run the x86_64 clang the Kotlin/Native
 * Android toolchain ships, so the 32 bit ARM and x86 ABIs are out of reach
 * there. CI and release builds leave it unset.
 */
val androidAbis: Map<String, String> = providers.gradleProperty("androidAbis").orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.let { requested ->
        allAndroidAbis.filterValues { it in requested }.also {
            require(it.isNotEmpty()) {
                "androidAbis=${requested.joinToString(",")} matches none of ${allAndroidAbis.values}"
            }
        }
    }
    ?: allAndroidAbis

val androidCxxTriples = mapOf(
    "androidNativeArm64" to "aarch64-linux-android",
    "androidNativeArm32" to "arm-linux-androideabi",
    "androidNativeX64" to "x86_64-linux-android",
    "androidNativeX86" to "i686-linux-android",
)

abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val cxxSharedTriples: MapProperty<String, String>

    @TaskAction
    fun run() {
        val bin = project.layout.projectDirectory.dir("../build/bin").asFile
        outputDir.get().asFile.deleteRecursively()

        /** NDK's libc++_shared.so, or the Kotlin/Native toolchain fallback. */
        fun cxxShared(target: String): File? {
            val triple = cxxSharedTriples.get()[target] ?: return null
            val sysroot = project.rootProject.extra["androidNdkSysroot"] as String?
            if (sysroot != null) {
                val fromNdk = File(sysroot, "usr/lib/$triple/libc++_shared.so")
                if (fromNdk.isFile) return fromNdk
            }

            val konanData = System.getenv("KONAN_DATA_DIR")
                ?: System.getProperty("user.home")?.let { File(it, ".konan") }?.absolutePath
            return konanData?.let { data ->
                File(data, "dependencies").listFiles()
                    ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
                    ?.resolve("sysroot/usr/lib/$triple/libc++_shared.so")
            }?.takeIf { it.isFile }
        }

        abis.get().forEach { (target, abi) ->
            val source = File(bin, "$target/mainDebugShared/libmain.so")
            if (!source.isFile) {
                throw GradleException(
                    "Expected $source - did linkMainDebugShared${target.replaceFirstChar { it.uppercase() }} fail?",
                )
            }
            val abiDir = File(outputDir.get().asFile, abi).apply { mkdirs() }
            source.copyTo(File(abiDir, "libmain.so"), overwrite = true)

            val shared = cxxShared(target)
            if (shared == null) {
                logger.warn("No libc++_shared.so for $abi; libmain.so may fail to load at runtime.")
            } else {
                shared.copyTo(File(abiDir, "libc++_shared.so"), overwrite = true)
            }
        }
    }
}

val prepareJniLibs = tasks.register<PrepareJniLibsTask>("prepareJniLibs") {
    group = "build"
    description = "Collects the linked libmain.so of every Android ABI into jniLibs."
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    abis.set(androidAbis)
    cxxSharedTriples.set(androidCxxTriples)
}

androidAbis.keys.forEach { target ->
    val linkTask = project(":examples:android-native").tasks.named(
        "linkMainDebugShared${target.replaceFirstChar { it.uppercase() }}",
    )
    prepareJniLibs.configure {
        dependsOn(linkTask)
        // Re-package whenever a linked libmain.so changes; without this the task
        // stays UP-TO-DATE and a rebuilt library never reaches the APK.
        inputs.files(linkTask.flatMap { (it as KotlinNativeLink).outputFile })
    }
}

android {
    namespace = "cn.enaium.openh264.examples.androidnative"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.enaium.openh264.examples.androidnative"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Keeps the AAR-shipped libsdl_jni.so down to the ABIs libmain.so was
        // built for.
        ndk {
            abiFilters += androidAbis.values
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Register the collected libmain.so directory with AGP's variant API.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { it.outputDir }
    }
}

dependencies {
    // sdl-kmp's Android variant carries SDL's Java layer (org.libsdl.app:
    // SDLActivity, SDLSurface, ...) and its own JNI library; libmain.so links
    // SDL3 statically and exports the JNI entry points SDLActivity calls.
    implementation(libs.sdl.kmp)
}
