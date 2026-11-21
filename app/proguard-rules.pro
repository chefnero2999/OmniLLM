# Keep the JNI bridge names intact: the native side looks them up by exact signature.
-keepclasseswithmembernames,includedescriptorclasses class com.omni.llm.core.LlamaBridge { *; }
-keep class com.omni.llm.core.LlamaBridge { *; }
-keep class com.omni.llm.core.LlamaBridge$TokenCallback { *; }
-keep class com.omni.llm.core.LlamaBridge$LoadCallback { *; }

# kotlinx.serialization: keep generated serializers for the manifest model
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.omni.llm.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.omni.llm.**$$serializer { *; }

# OkHttp ships optional Conscrypt/BouncyCastle hooks we never use
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
