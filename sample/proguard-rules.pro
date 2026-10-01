# Okio 的旧签名只把 javax.annotation.Nullable 用作编译期元数据，运行时不读取；
# JDK 17 不再内置该注解，因此允许 R8 忽略这个非运行时类型。
-dontwarn javax.annotation.Nullable
