// 配置使用本地依赖缓存
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
        repositories {
            // 优先使用本地缓存
            maven {
                url = uri("${rootProject.projectDir}/local_dependencies/gradle_cache")
            }
            // 回退到远程仓库
            google()
            mavenCentral()
        }
    }
}
