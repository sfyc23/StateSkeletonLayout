// stateskeletonlayout 根构建脚本：只声明插件版本，不放业务逻辑。
plugins {
    alias(libs.plugins.agp.library) apply false
    alias(libs.plugins.agp.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
