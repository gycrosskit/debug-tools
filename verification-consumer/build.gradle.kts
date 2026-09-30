plugins {
    kotlin("multiplatform") version "2.2.21"
    id("com.android.library") version "8.10.1"
}
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    iosArm64()
    iosX64()
    iosSimulatorArm64 { binaries.framework { baseName = "DebugToolsConsumer" } }
    sourceSets.commonMain.dependencies {
        implementation("com.github.gycrosskit:debug-tools:0.1.0")
    }
}
android {
    namespace = "io.github.gycrosskit.debugtools.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
