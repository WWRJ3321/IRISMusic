# IRIS Music ProGuard Rules
#
# 原则：不要整包 -keep，否则 R8 无法裁剪未用代码，安装体积暴涨。
# 只保留反射/运行时真正需要、R8 无法静态推断的部分，其余交给 R8 shrink。

# ---- Media3 / ExoPlayer ----
# Media3 自带 consumer proguard 规则，会保留必要部分；仅关闭无关警告。
-dontwarn androidx.media3.**

# ---- Kotlin 协程/元数据 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

# ---- App：只保留带 @Keep 的成员，其余允许裁剪/混淆 ----
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}
-keep @androidx.annotation.Keep class * { *; }