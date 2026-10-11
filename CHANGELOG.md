# 修改日志

记录库模块、示例应用和公开文档的变更。未发布记录不代表已发布新的 JitPack 版本。

## 未发布 — 2026-10-12

本次记录基于上次推送的 `main` 提交 `219e9af`（`build: preserve JitPack source artifact downloads`）。

### 库模块 `:stateskeletonlayout`

- 本次未修改库源码、公共 API、资源或库发布配置，`libraryVersion` 保持 `0.0.1`。
- SVGA 播放器及 Wire 等依赖仅用于示例应用。库仍仅显式依赖 AndroidX Annotation，Kotlin 标准库由插件自动添加。
- SVGA 示例复用现有自定义 Loading 布局和状态 View 创建、加载可见性、释放回调，展示第三方动画的接入方式。

### 示例应用 `:sample`

#### 添加 SVGA 动画示例

- 添加「SVGA 加载」入口、`SvgaLoadingFragment` 和配套加载布局，示例页总数由 15 个增加到 16 个，保留原有 Lottie 示例。
- 保留根目录 `loading.svga` 素材，并将同一素材打包到 `sample/src/main/assets/loading.svga`；运行时从本地解析，无需下载动画。
- 进入页面自动模拟 7 秒加载；支持持续 Loading、手动切换 Content / Empty / Error 和错误页重试，手动操作取消旧模拟任务。
- 使用加载可见性和 View 生命周期控制播放：后台或隐藏时停止，重新可见时从首帧播放；隐藏时复用解析结果，释放布局或销毁 View 时清理资源。
- 使用弱引用、解析代次和目标 View 身份检查处理迟到回调；校验播放参数，解析失败保留加载反馈，系统动画关闭时显示静态文案。

#### 添加沉浸式系统栏适配

- `MainActivity` 启用 `enableEdgeToEdge()`，系统栏图标随日夜主题调整，并允许横屏绘制进入刘海区。
- 添加 `applyDemoSystemBarPadding()`，在首页内容区与各 Fragment 根 View 上应用系统栏和刘海安全区域。
- 保留 XML 原始 padding，以初始值叠加 Insets，避免重复分发时累加；首页与详情分别处理，内部子 View 不重复添加。
- 返回原始 Insets 供后代继续接收；示例无输入框，本次未处理 IME Insets。

#### 修复返回目录后的遮挡

- 预测性返回提交或取消后，在下一轮主线程同步详情容器可见性；回退栈为空时设为 `GONE`，避免空详情背景遮挡目录。
- 补充普通返回、预测性返回、取消返回、前后台切换和重建后再次进入的回归覆盖。

### 依赖与构建

- 为示例固定添加 `com.github.yyued:SVGAPlayer-Android:2.6.1`，JitPack 仓库仅允许 `com.github.yyued` 依赖组。
- 排除 SVGA 的 `kotlin-android-extensions-runtime`，避免与现有 Parcelize 运行库产生重复类。
- 添加 Manifest 合并规则，保留示例应用现有的备份配置和名称；添加 Wire / SVGA Proto 的 R8 保留规则。
- 补充 SVGA、Wire 和相关 Kotlin 依赖附件的 SHA-256 校验记录。

### 文档

- 添加 `README_EN.md`，提供库接入、状态与骨架配置、自定义动画、测试和发布流程的英文说明。
- 在中英文 README 中添加语言切换、修改日志入口和沉浸式系统栏说明，补充 SVGA 接入与首页导航回归说明。
- 添加本文件，分别记录库模块、示例应用、构建与文档的修改范围。

### 验证与边界

- 添加 `SvgaLoadingFragmentTest`，覆盖模拟任务、解析失败、无效播放参数、迟到回调、生命周期、系统动画关闭及真实素材的 Native Canvas 帧像素检查。
- 添加 `DemoInsetsTest` 和 `MainActivityNavigationTest`，覆盖 padding 与方向开关、普通返回和预测性返回。
- 添加 `SvgaLoadingSmokeTest` 并扩展 `DemoSmokeTest`，供连接设备后检查入口、播放、返回、取消返回和重建。
- 本次提交验证（2026-10-12）：在隔离工作树中关闭构建缓存并重跑测试，库 127 项、示例 60 项，共 187 项通过，零失败、错误或跳过。
- 两模块 Debug / Release、AndroidTest APK 组装通过，示例 Release 完成 R8 压缩；库本地 Maven 发布的 AAR、源码包和 POM 均已生成。
- 库 Release Lint 为 0 Error / 16 Warning；示例 Debug / Release Lint 均为 0 Error / 58 Warning，未整体屏蔽警告。
- Debug / Release APK 内的 `assets/loading.svga` 与源码素材 SHA-256 一致；库 Release AAR 不包含示例动画或 Debug 测试资源，发布 POM 不包含 SVGA / Wire 依赖。
- 本次设备清单为空；设备冒烟、系统栏在横竖屏及不同导航模式下的表现、TalkBack 和 R8 Release 实际运行仍需设备验收。
