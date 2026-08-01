# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# Compose
-dontwarn androidx.compose.**

# AWS SDK
-keep class aws.sdk.kotlin.** { *; }
-keep class aws.smithy.kotlin.** { *; }
-dontwarn aws.sdk.kotlin.**
-dontwarn aws.smithy.kotlin.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin Serialization
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.* <methods>;
}

# Coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Keep data classes for JSON serialization
-keep class com.imaviso.stash.data.model.** { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil
-dontwarn coil.**

# Strip debug/verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public *** d(...);
    public *** v(...);
}
