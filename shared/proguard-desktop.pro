# OkHttp's optional TLS providers and GraalVM support; not on the classpath, OkHttp checks for them at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.nativeimage.**

# Ktor finds its engine through ServiceLoader (META-INF/services), which ProGuard can't see.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# Room loads the generated <Database>_Impl by name.
-keep class * extends androidx.room3.RoomDatabase { <init>(); }
