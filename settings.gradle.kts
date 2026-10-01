rootProject.name = "stateskeletonlayout"

include(":stateskeletonlayout")
include(":sample")

project(":stateskeletonlayout").projectDir = file("stateskeletonlayout")
project(":sample").projectDir = file("sample")

pluginManagement {
    repositories {

        // 按声明顺序优先解析镜像，官方仓库在后；保持现有网络访问策略。
        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))

        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {

        // 镜像优先；依赖校验元数据会拒绝坐标相同但内容不同的制品。
        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
