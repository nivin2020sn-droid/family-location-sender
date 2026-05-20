# Keep model classes used in JSON serialization
-keep class com.family.locationsender.data.** { *; }
-keepattributes Signature, *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
