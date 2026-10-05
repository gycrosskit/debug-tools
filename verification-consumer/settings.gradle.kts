import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") { content { includeVersionByRegex(".*", ".*", ".*-(1\\.0\\.0|1\\.1\\.0-04)") } }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        exclusiveContent {
            forRepository { maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
            filter { includeGroup("com.tencent.kuikly-open") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") { content { includeVersionByRegex(".*", ".*", ".*-(1\\.0\\.0|1\\.1\\.0-04)") } }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io") { content { includeGroup("com.github.gycrosskit.debug-tools") } }
        exclusiveContent {
            forRepository { maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
            filter { includeGroup("com.tencent.kuikly-open") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "debug-tools-consumer"
