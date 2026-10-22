pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 无线调试配对（SPAKE2 + TLS）所需的 libadb-android 只在 JitPack 发布
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SmileHub"
include(":app")
include(":engine-api")
include(":engine")
include(":testapp")
