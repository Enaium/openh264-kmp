pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "openh264-kmp"

include(":openh264-kmp")
include(":examples:common")
include(":examples:android-jvm")
include(":examples:android-native")
include(":examples:browser")

// Per-OS/arch JNI artifacts that ship the prebuilt libopenh264_jni shared library as
// a classpath resource. NativeLoader extracts the matching one at runtime.
listOf(
    "linux-x86_64",
    "linux-aarch64",
    "darwin-x86_64",
    "darwin-aarch64",
    "windows-x86_64",
).forEach { classifier ->
    val name = ":jni-jvm-$classifier"
    include(name)
    project(name).projectDir = file("jni/jvm/$classifier")
}
