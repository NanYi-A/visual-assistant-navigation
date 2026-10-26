# ProGuard / R8 规则
# 目标：release APK 体积最小化，同时保证运行时反射调用的组件不被裁剪。

# ---- ONNX Runtime：Java API 通过 JNI 反射调用原生库，必须保留 ----
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.microsoft.onnxruntime.**

# ---- Vosk 离线语音识别：Java 层通过反射实例化原生 Recognizer ----
-keep class org.vosk.** { *; }
-keep class net.java.dev.jna.** { *; }
-dontwarn org.vosk.**
-dontwarn net.java.dev.jna.**

# ---- ARCore：大量 JNI 回调与反射 ----
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# ---- 高德 / 腾讯定位 SDK ----
-keep class com.amap.api.** { *; }
-keep class com.tencent.map.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.tencent.map.**
# 高德定位 SDK 内部引用了以下可选类，本项目未使用，R8 只需忽略不需保留：
#   com.amap.ams.gnss.GnssSoftLocator —— 高德 GNSS 软定位（需另配 GNSS 硬件）
#   net.jafama.FastMath          —— 高德 A+ 协议数学库（自动纠偏算法，未走该链路）
-dontwarn com.amap.ams.gnss.**
-dontwarn net.jafama.**

# ---- 数据模型：Kotlin data class 被 Gson/Jackson 序列化，字段名不能改 ----
-keepclassmembers class cn.visualassistant.app.core.** { *; }
-keep class cn.visualassistant.app.core.** { *; }

# ---- 保留注解与行号，便于线上崩溃定位 ----
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable
