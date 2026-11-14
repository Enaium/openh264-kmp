plugins {
    alias(libs.plugins.android.application)
}

// Android (JVM/ART) host of the shared SDL3 + ImGui example. MainActivity
// extends SDLActivity from sdl-kmp's Android variant and calls the commonMain
// runExample(), so this module carries no UI or camera code of its own.
android {
    namespace = "cn.enaium.openh264.examples.androidjvm"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.enaium.openh264.examples.androidjvm"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":examples:common"))
    // SDL's Java layer (SDLActivity) and libsdl_jni.so.
    implementation(libs.sdl.kmp)
}
