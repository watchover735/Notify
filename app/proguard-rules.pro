# ============================================================================
# NotiFy Production R8 / ProGuard Optimization & Obfuscation Rules
# ============================================================================

# ── General Optimization & Obfuscation Hardening ────────────────────────────
-repackageclasses ''
-allowaccessmodification

# ── Strip Debug & Info Logs (Leave w and e for production telemetry) ────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── Kotlin Attributes (Required for Compose, Coroutines & Room generics) ────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── Room Database & Entities ────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── WorkManager (Reflection-based Worker Instantiation) ──────────────────────
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── JNI & Native Methods (yt-dlp runtime & FFmpeg) ──────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.youtubedl_android.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# ── AndroidX Media3 / ExoPlayer Audio Engine ────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Credential Manager & Google ID (Google Sign-In) ─────────────────────────
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# ── OkHttp, Okio, Coroutines & Coil ─────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn coil.**
-dontwarn kotlinx.coroutines.**
