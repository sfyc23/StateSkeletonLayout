// :stateskeletonlayout —— 纯 UI 状态库。
// 最少依赖原则：仅依赖 AndroidX Annotation，不引入 Core、Lottie、Material、Navigation、Lifecycle。
plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotlin.android)
    // 生成发布制品的 javadoc jar。0.0.1 已发布且无法追溯补发，
    // 从下一个版本起制品会带上 API 文档。
    alias(libs.plugins.dokka)
    // 截图回归：骨架是纯视觉功能，用 Robolectric 渲染比对像素。
    alias(libs.plugins.roborazzi)
    `maven-publish`
}

group = providers.gradleProperty("libraryGroup").get()
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
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.ext.junit)
}

/**
 * 把 Dokka 的 HTML 输出打成 javadoc jar。
 *
 * 不用 `withJavadocJar()`：AGP 的该 API 期望真正的 Javadoc 格式产物，而
 * Dokka 2.x 的 javadoc 格式插件（`org.jetbrains.dokka.javadoc`）在本工程的
 * 仓库列表里解析不到。HTML 包同样满足"带 API 文档的 doc 制品"这一目的。
 *
 * **必须声明在 `publishing {}` 之前**：`publishing` 块在配置期就会读取这个属性，
 * 若用 `by tasks.registering` 且定义在其后，读到的委托尚未赋值，会抛
 * "Parameter specified as non-null is null"。
 */
val dokkaJavadocJar: TaskProvider<Jar> = tasks.register<Jar>("dokkaJavadocJar") {
    group = "documentation"
    description = "把 Dokka HTML 输出打包为发布用的 -javadoc.jar"
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("dokka/html"))
    // 声明任务依赖保证顺序，避免发布出空 doc 包。
    dependsOn("dokkaGeneratePublicationHtml")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = providers.gradleProperty("libraryArtifactId").get()
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
                // developers 与 scm 是 Maven Central 的必填项。当前走 JitPack，
                // 缺失不会导致发布失败，但补上零成本，为将来可能的 Central 发布留路。
                // 同时让 IDE 与制品浏览页能正确显示来源与仓库地址。
                developers {
                    developer {
                        id.set("sfyc23")
                        name.set("sfyc23")
                        email.set("sfyc23@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/sfyc23/StateSkeletonLayout")
                    connection.set("scm:git:https://github.com/sfyc23/StateSkeletonLayout.git")
                    developerConnection.set("scm:git:git@github.com:sfyc23/StateSkeletonLayout.git")
                }
            }

            // AGP 在项目评估完成后才创建 Release 发布组件。
            afterEvaluate {
                from(components["release"])
            }

            // Dokka 2.x 把 javadoc 输出格式拆成独立插件，该插件在本地仓库不可解析；
            // 这里把 Dokka 生成的 HTML 打成标准的 -javadoc.jar 附到制品上。
            // 消费端（IDE、制品浏览页）要的就是一个带 classifier 的 doc 包，
            // HTML 内容完全可用；0.0.1 已发布且无法追溯补发，从下个版本起生效。
            artifact(dokkaJavadocJar)
        }
    }
}


// JitPack 重写 Gradle 元数据时会丢失源码包 classifier，改用 Maven POM 与标准源码包发布。
tasks.withType<org.gradle.api.publish.tasks.GenerateModuleMetadata>().configureEach {
    enabled = false
}
