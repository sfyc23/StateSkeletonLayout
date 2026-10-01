# 当前实现没有反射入口。XML 中的自定义 View 由 Android Gradle Plugin 生成精确 keep 规则，
# 直接调用的公开 API 由 R8 可达性分析保留；不再用 public * 掩盖真实压缩问题。
# :sample 的 minified Release 构建作为消费端回归验证。
