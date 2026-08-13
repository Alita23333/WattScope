# QPT Power Monitor - R8/ProGuard 规则

# ---------- libsu (com.github.topjohnwu.libsu) ----------
# libsu 通过反射实例化/访问 Shell 相关类，需保留
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# ---------- Room ----------
# Room 生成的实现类通过名称查找
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---------- Kotlin 协程 ----------
-dontwarn kotlinx.coroutines.**

# ---------- Gson/JSON（如引入） ----------
-dontwarn com.google.gson.**

# ---------- 通用 ----------
# 保留注解与元数据（反射需要）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn javax.annotation.**
