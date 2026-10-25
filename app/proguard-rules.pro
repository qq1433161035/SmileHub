# SmileHub release keep rules.
#
# The app bundles apktool, smali, apksig, jadx, BouncyCastle, Conscrypt and
# libadb; several of them resolve classes by name or through ServiceLoader.
# AIDL and manifest components are also kept explicitly because they are
# instantiated by the framework / the other process.

# ---- app entry points -------------------------------------------------------
-keep class com.wbhub.app.MainActivity { *; }
-keep class com.wbhub.app.agent.AgentRunService { *; }
-keep class com.wbhub.app.bridge.BridgeService { *; }
-keep class com.wbhub.app.tools.worker.ToolWorkerService { *; }
-keep class com.wbhub.app.tools.adb.AdbPairReceiver { *; }
-keep class com.wbhub.app.access.** { *; }

# AIDL: both processes in this APK share the generated classes, but the
# framework resolves the binder interface by these names.
-keep interface com.wbhub.app.tools.IToolWorker { *; }
-keep interface com.wbhub.app.tools.IToolCallback { *; }
-keep class com.wbhub.app.tools.IToolWorker$Stub { *; }
-keep class com.wbhub.app.tools.IToolCallback$Stub { *; }
-keep class com.wbhub.app.tools.IToolWorker$Stub$Proxy { *; }
-keep class com.wbhub.app.tools.IToolCallback$Stub$Proxy { *; }

# ---- engine: apktool / smali / apksig / jadx -------------------------------
-keep class brut.** { *; }
-keep class org.jf.** { *; }
-keep class com.android.apksig.** { *; }
-keep class com.android.tools.** { *; }
-keep class jadx.** { *; }
-keep class org.apache.** { *; }
-keep class org.xmlpull.** { *; }
-dontwarn brut.**
-dontwarn org.jf.**
-dontwarn com.android.apksig.**
-dontwarn com.android.tools.**
-dontwarn jadx.**
-dontwarn org.apache.**
-dontwarn org.xmlpull.**

# ---- crypto / TLS -----------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn io.github.muntashirakon.adb.**
-dontwarn com.google.gson.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**

# BouncyCastle / Conscrypt are found through provider names and service files.
-keep class * extends java.security.Provider { *; }

# ---- Kotlin / coroutines ----------------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# ---- Chaquopy ---------------------------------------------------------------
-keep class com.chaquo.python.** { *; }
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.jvm.internal.FunctionBase { *; }
-keep class kotlin.reflect.KAnnotatedElement { *; }
-dontwarn org.jetbrains.annotations.NotNull

# Keep annotations used by libraries at runtime.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
