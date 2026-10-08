plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    kotlin("plugin.serialization") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
    `maven-publish`
}

group = providers.environmentVariable("GROUP").orElse("com.github.gycrosskit.debug-tools").get()
version = providers.environmentVariable("VERSION").orElse("0.2.0-rc.6").get()

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    jvm()
    ohosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        val androidIosMain by creating {
            dependsOn(commonMain.get())
            dependencies { api("androidx.lifecycle:lifecycle-viewmodel:2.10.0") }
        }
        androidMain.get().dependsOn(androidIosMain)
        iosMain.get().dependsOn(androidIosMain)
        val androidIosTest by creating { dependsOn(commonTest.get()) }
        androidUnitTest.get().dependsOn(androidIosTest)
        iosTest.get().dependsOn(androidIosTest)
        commonMain.dependencies {
            // 这些类型出现在公共构造参数、继承关系或生成的序列化 API 中，消费方必须能够解析。
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.1-1.0.0")
            api("io.ktor:ktor-client-core:3.3.3-1.1.0-04")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3-1.1.0-04")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3-1.1.0-04")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
            implementation("io.ktor:ktor-client-mock:3.3.3-1.1.0-04")
        }
    }
}
android {
    namespace = "com.dgtang.debugtools"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
publishing {
    repositories.maven {
        name = "staging"
        url = uri(layout.buildDirectory.dir("maven/${project.version}"))
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("GY CrossKit Debug Tools")
            url.set("https://github.com/gycrosskit/debug-tools")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            scm {
                url.set("https://github.com/gycrosskit/debug-tools")
                connection.set("scm:git:https://github.com/gycrosskit/debug-tools.git")
                developerConnection.set("scm:git:ssh://git@github.com/gycrosskit/debug-tools.git")
            }
            description.set("Android/iOS/HarmonyOS 共用 Bug 提交、附件和持久草稿；UI、证据与 TLS 由宿主提供。")
        }
    }
}
