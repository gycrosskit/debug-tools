plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    ohosArm64()
    iosArm64()
    iosX64()
    iosSimulatorArm64 { binaries.framework { baseName = "DebugToolsConsumer" } }
    sourceSets {
      ohosArm64Main.dependencies {
        implementation("com.github.gycrosskit.debug-tools:debug-tools-kuikly:${providers.gradleProperty("debugToolsVersion").orElse("0.2.0-rc.2").get()}")
      }
    }
    sourceSets.commonMain.dependencies {
        implementation("com.github.gycrosskit.debug-tools:debug-tools:${providers.gradleProperty("debugToolsVersion").orElse("0.2.0-rc.2").get()}")
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
