/*
 * Per-OS/arch JNI artifact: linux-x86_64.
 * Ships libopenh264_jni.so as a classpath resource at
 * /cn/enaium/openh264/native/linux-x86_64/, which NativeLoader
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

val jniOs = "linux"
val jniArch = "x86_64"
val classifier = "$jniOs-$jniArch"
val libFile = "libopenh264_jni.so"
val resourceDir = "cn/enaium/openh264/native/$classifier"

val canBuildHere = OperatingSystem.current().isLinux

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
    // A runner that is not itself x86_64 needs the cross toolchain CI installs;
    // without the flags CMake would write a host-arch binary into an artifact
    // that promises another architecture.
    val crossFlags = if (System.getProperty("os.arch").lowercase() in listOf("x86_64", "amd64")) {
        emptyList()
    } else {
        listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
            "-DCMAKE_C_COMPILER=x86_64-linux-gnu-gcc",
            "-DCMAKE_CXX_COMPILER=x86_64-linux-gnu-g++",
        )
    }
    commandLine(
        listOf(
            cmakeExecutable,
            rootProject.file("jni").absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_JNI=ON",
            "-DJNI_INCLUDE_DIR=$jniInclude",
            "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/linux",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
            "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        ) + crossFlags,
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
