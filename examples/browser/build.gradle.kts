plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Browser host of the shared example: SDL3 owns the canvas, imgui-kmp draws
// the UI, camera-kmp provides the camera frames. The Emscripten runtimes of
// all three libraries are npm packages; they are served next to the Kotlin
// module and initialized by index.html before it runs.
kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "browser.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain {
            dependencies {
                implementation(project(":examples:common"))
                implementation(libs.kotlinx.browser)
                implementation(npm("@enaium/sdl-kmp", "3.4.18-2"))
                implementation(npm("@enaium/imgui-kmp", "1.93.0-2"))
                implementation(npm("@enaium/camera-kmp", "1.0.0"))
            }
        }
    }
}

val wasmNodeModules = rootProject.layout.buildDirectory.dir("wasm/node_modules/@enaium")

val copyWasmAssets = tasks.register<Copy>("copyWasmAssets") {
    description = "Copies the sdl/imgui/camera Emscripten runtimes into the web resources."
    dependsOn(":kotlinWasmNpmInstall")
    from(wasmNodeModules.map { it.dir("sdl-kmp") }) {
        include("sdl_kmp_glue.js", "sdl_wasm.js", "sdl_wasm.wasm")
    }
    from(wasmNodeModules.map { it.dir("imgui-kmp") }) {
        include("imgui_kmp_glue.js", "imgui_kmp_exports.js", "imgui_wasm.js", "imgui_wasm.wasm")
    }
    from(wasmNodeModules.map { it.dir("camera-kmp") }) {
        include("camera-kmp-web.js")
    }
    into(layout.buildDirectory.dir("generated/wasmResources"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("wasmJsProcessResources") {
    dependsOn(copyWasmAssets)
    from(copyWasmAssets)
}
