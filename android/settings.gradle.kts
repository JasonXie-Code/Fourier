pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 本地依赖缓存（优先使用，如果存在）
        val localCache = file("${rootProject.projectDir}/local_dependencies/gradle_cache")
        if (localCache.exists() && localCache.listFiles()?.isNotEmpty() == true) {
            maven {
                url = localCache.toURI()
                isAllowInsecureProtocol = true
            }
        }
        // 远程仓库（回退）
        google()
        mavenCentral()
    }
}

rootProject.name = "FourierAudioAnalyzer"
include(":app")
