plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Browser demo: getUserMedia -> WebCodecs AVC VideoEncoder -> openh264-kmp decode -> canvas.
// Written for the JS target because WebCodecs and the DOM expose plain
// JavaScript callbacks; the codec itself is the same embedded WebAssembly
// module the published js/wasmJs artifacts use.
// Kotlin 2.4 pins webpack 5.108.1 while webpack-dev-server 6 declares a
// ^5.112.0 peer; npm installs both copies and webpack then fails with
// "the 'compilation' argument must be an instance of Compilation".
// One override keeps a single webpack in the npm workspace.
(rootProject.extensions.getByName("kotlinNpm") as org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension)
    .override("webpack", "5.112.2")

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "browser-demo.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                implementation(project(":openh264-kmp"))
            }
        }
    }
}
