// stateskeletonlayout 根构建脚本：只声明插件版本，不放业务逻辑。
plugins {
    alias(libs.plugins.agp.library) apply false
    alias(libs.plugins.agp.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    // API 文档插件：只在库模块应用，此处仅声明版本。
    alias(libs.plugins.dokka) apply false
    // 公开 API 兼容性基线。在根工程应用，由插件自行配置各子模块的
    // apiDump / apiCheck 任务。
    alias(libs.plugins.binary.compat)
    // 截图回归插件；只在库模块应用（示例没有需要像素比对的骨架）。
    alias(libs.plugins.roborazzi) apply false
}

/**
 * 公开 API 基线配置。
 *
 * 库的公开接口已有 100+ 个成员，此前没有任何机制在新增、删除或改签名时提醒——
 * 一次无意的参数类型变更会静默破坏宿主工程的编译。
 *
 * 基线文件：`api/stateskeletonlayout.api`（由 `apiDump` 生成，需入库）。
 * 校验任务：`apiCheck`（已接入 CI 的验证任务列表）。
 *
 * `:sample` 是示例应用、不是发布制品，不参与基线比对。
 */
apiValidation {
    ignoredProjects += "sample"
}
