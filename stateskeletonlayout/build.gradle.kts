// :stateskeletonlayout —— 纯 UI 状态库。
// 最少依赖原则：仅依赖 AndroidX Annotation，不引入 Core、Lottie、Material、Navigation、Lifecycle。
plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "com.github.sfyc23"
version = providers.gradleProperty("libraryVersion").get()

android {
    namespace = "com.sfyc.ssl"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // 库模块无混淆需求，保留默认；consumer-rules.pro 透传给宿主。
        }
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 强制所有公开资源使用 ssl_ 前缀，避免与宿主工程资源重名。
    resourcePrefix = "ssl_"

    buildFeatures {
        // 遮罩降级日志需要区分 Debug/Release。
        buildConfig = true
    }

    lint {
        // Lint Error 是发布门槛；版本升级类 Warning 进入独立维护任务，不在功能修复中混升。
        checkReleaseBuilds = true
        abortOnError = true
    }

    testOptions {
        unitTests {
            // Robolectric View 测试需要合并 Android 资源。
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.ext.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "StateSkeletonLayout"
            version = project.version.toString()

            pom {
                name.set("StateSkeletonLayout")
                description.set("Android View state layout with skeleton loading effects")
                url.set("https://github.com/sfyc23/StateSkeletonLayout")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }

            // AGP 在项目评估完成后才创建 Release 发布组件。
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// JitPack 重写 Gradle 元数据时会丢失源码包 classifier，改用 Maven POM 与标准源码包发布。
tasks.withType<org.gradle.api.publish.tasks.GenerateModuleMetadata>().configureEach {
    enabled = false
}
