# NotiFy Proguard Rules

# Credential Manager & Google ID
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# OkHttp & Coroutines
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**

# NotiFy Models & Config
-keep class com.notify.DeveloperConfig { *; }
-keep class com.notify.download.stream.SupabaseConfig { *; }
-keep class com.notify.auth.** { *; }
