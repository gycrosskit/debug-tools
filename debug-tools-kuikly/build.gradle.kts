plugins { kotlin("multiplatform"); `maven-publish` }
group = rootProject.group
version = rootProject.version
kotlin {
    ohosArm64()
    sourceSets.commonMain.dependencies {
        api(project(":"))
        implementation("com.tencent.kuikly-open:core:2.28.0-2.0.21-ohos")
    }
}
publishing {
    repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven/${project.version}")) }
    publications.withType<MavenPublication>().configureEach { pom {
        name.set("GY CrossKit Debug Tools Kuikly")
        url.set("https://github.com/gycrosskit/debug-tools")
        description.set("Kuikly 页面 scope 的 OHOS 安全 Store 与传感器桥")
        licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt"); distribution.set("repo") } }
    } }
}
