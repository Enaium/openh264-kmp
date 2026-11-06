/*
 * Per-OS/arch JNI artifact: darwin-x86_64.
 * Ships libopenh264_jni.dylib as a classpath resource at
 * /cn/enaium/openh264/native/darwin-x86_64/, which NativeLoader
 * (in :openh264-kmp's jvmMain) extracts and System.load()s at runtime.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val jniOs = "darwin"
val jniArch = "x86_64"
val classifier = "$jniOs-$jniArch"
val libFile = "libopenh264_jni.dylib"
val resourceDir = "cn/enaium/openh264/native/$classifier"

val canBuildHere = OperatingSystem.current().isMacOsX

val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary = tasks.register<Exec>("configureJniLibrary") {
    group = "build"
    description = "cmake-configures openh264_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    commandLine(
        cmakeExecutable,
        rootProject.file("jni").absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_JNI=ON",
        "-DCMAKE_OSX_ARCHITECTURES=x86_64",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/darwin",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        // DLLs are RUNTIME artifacts in CMake; without this the Windows build
        // writes openh264_jni.dll into the CMake build tree.
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",

    )
}

val buildJniLibrary = tasks.register<Exec>("buildJniLibrary") {
    group = "build"
    description = "Builds $libFile for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--target", "openh264_jni", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/openh264_kmp.cpp"),
        rootProject.file("jni/include/openh264_kmp.h"),
        rootProject.file("jni/openh264_jni.cpp"),
    )
    inputs.dir(rootProject.file("openh264/codec/common"))
    inputs.dir(rootProject.file("openh264/codec/decoder"))
    inputs.dir(rootProject.file("openh264/codec/api"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    // Copy the archive through its provider instead of the task's output
    // collection: the latter is filtered by an include pattern and was empty
    // on the Windows runner, which silently produced a jar without the DLL.
    from(nativeOutputDir.map { it.file(libFile) }) {
        into(resourceDir)
    }
}

val hasSigningKey = rootProject.extra["hasSigningKey"] as Boolean

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasSigningKey) signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "openh264-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("openh264-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for openh264-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; not intended to be depended on directly.",
        )
        url.set("https://github.com/Enaium/openh264-kmp")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("BSD-2-Clause")
                url.set("https://opensource.org/licenses/BSD-2-Clause")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Enaium")
            }
        }
        scm {
            url.set("https://github.com/Enaium/openh264-kmp")
            connection.set("scm:git:git@github.com:Enaium/openh264-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/openh264-kmp.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/openh264-kmp/issues")
        }
    }
}
