// :sample —— 控件的可运行说明书，仅依赖库模块，不被库模块反向依赖。
// Lottie 只允许出现在本模块，用于演示自定义加载布局。
plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sfyc.demo.ssl"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.sfyc.demo.ssl"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("libraryVersion").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // sample 同时充当真实压缩消费端，验证库的 XML 构造器与公开 API 可被 R8 正确分析。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":stateskeletonlayout"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.activity.ktx)
    // 仅示例使用：自定义加载布局示例的依赖严禁进入库模块。
    implementation(libs.lottie)
    // 网络多类型示例的依赖只进入 sample。
    implementation(libs.smartrefresh.kernel)
    implementation(libs.smartrefresh.header.classics)
    implementation(libs.smartrefresh.footer.classics)
    implementation(libs.epoxy)
    ksp(libs.epoxy.processor)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.ext.junit)
}
