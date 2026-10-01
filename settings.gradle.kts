rootProject.name = "stateskeletonlayout"

include(":stateskeletonlayout")
include(":sample")

project(":stateskeletonlayout").projectDir = file("stateskeletonlayout")
project(":sample").projectDir = file("sample")

pluginManagement {
    repositories {
        // 官方仓库优先，避免远端构建被镜像的缺失制品或 502 响应阻断。
        google()
        mavenCentral()
        gradlePluginPortal()

        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 与插件解析保持相同顺序，已有依赖校验继续校验制品内容。
        google()
        mavenCentral()
        gradlePluginPortal()

        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
    }
}
