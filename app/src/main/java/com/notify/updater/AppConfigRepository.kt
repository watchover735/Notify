package com.notify.updater

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.notify.BuildConfig
import com.notify.download.stream.SupabaseConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Data model for remote app versioning and update requirements.
 */
data class AppConfig(
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int,
    val maxSkips: Int = 3,
    val forceMessage: String,
    val downloadUrl: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis()
)

/**
 * Repository responsible for fetching, caching, and evaluating remote app update configuration.
 */
class AppConfigRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AppConfigRepository"
        private const val PREFS_NAME = "notify_app_config_prefs"

        private const val KEY_LATEST_VERSION_CODE = "latest_version_code"
        private const val KEY_MIN_SUPPORTED_VERSION_CODE = "min_supported_version_code"
        private const val KEY_MAX_SKIPS = "max_skips"
        private const val KEY_FORCE_MESSAGE = "force_message"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_FETCHED_AT_MS = "fetched_at_ms"

        private const val PREFIX_SKIPS_COUNT = "skips_count_"

        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Retrieves the last-known-good cached configuration, or null if never fetched.
     */
    fun getCachedConfig(): AppConfig? {
        if (!prefs.contains(KEY_LATEST_VERSION_CODE) || !prefs.contains(KEY_MIN_SUPPORTED_VERSION_CODE)) {
            return null
        }
        val latest = prefs.getInt(KEY_LATEST_VERSION_CODE, BuildConfig.VERSION_CODE)
        val minSupported = prefs.getInt(KEY_MIN_SUPPORTED_VERSION_CODE, BuildConfig.VERSION_CODE)
        val maxSkips = prefs.getInt(KEY_MAX_SKIPS, 3)
        val forceMessage = prefs.getString(KEY_FORCE_MESSAGE, null)
            ?: "A critical update is required to continue using NotiFy. Please update to the latest version."
        val downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, null)
        val fetchedAtMs = prefs.getLong(KEY_FETCHED_AT_MS, 0L)

        return AppConfig(
            latestVersionCode = latest,
            minSupportedVersionCode = minSupported,
            maxSkips = maxSkips,
            forceMessage = forceMessage,
            downloadUrl = downloadUrl,
            fetchedAtMs = fetchedAtMs
        )
    }

    /**
     * Saves the config to SharedPreferences as last-known-good cache.
     */
    fun saveCachedConfig(config: AppConfig) {
        prefs.edit()
            .putInt(KEY_LATEST_VERSION_CODE, config.latestVersionCode)
            .putInt(KEY_MIN_SUPPORTED_VERSION_CODE, config.minSupportedVersionCode)
            .putInt(KEY_MAX_SKIPS, config.maxSkips)
            .putString(KEY_FORCE_MESSAGE, config.forceMessage)
            .putString(KEY_DOWNLOAD_URL, config.downloadUrl)
            .putLong(KEY_FETCHED_AT_MS, config.fetchedAtMs)
            .apply()
    }

    /**
     * Number of times the user has clicked "Later" for this specific target versionCode.
     * When a new version is released on the server, the target changes and skips reset to 0 automatically.
     */
    fun getSkipsUsed(targetVersionCode: Int): Int {
        return prefs.getInt("$PREFIX_SKIPS_COUNT$targetVersionCode", 0)
    }

    /**
     * Increments the "Later" click counter for a specific target versionCode.
     */
    fun incrementSkips(targetVersionCode: Int): Int {
        val current = getSkipsUsed(targetVersionCode)
        val next = current + 1
        prefs.edit().putInt("$PREFIX_SKIPS_COUNT$targetVersionCode", next).apply()
        return next
    }

    /**
     * Fetches current [AppConfig] from Supabase via `get_app_config` RPC using the anon key.
     * On success, updates the local SharedPreferences cache.
     * On failure (network error, timeout, 5xx), returns [Result.failure] without modifying cache.
     */
    suspend fun fetchAppConfig(): Result<AppConfig> = withContext(ioDispatcher) {
        try {
            val url = "${SupabaseConfig.REST_URL}/rpc/get_app_config"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
                .addHeader("Content-Type", "application/json")
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "get_app_config RPC failed: HTTP ${response.code} $bodyStr")
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $bodyStr"))
                }

                val json = JSONObject(bodyStr)
                val latest = json.optInt("latest_version_code", BuildConfig.VERSION_CODE)
                val minSupported = json.optInt("min_supported_version_code", BuildConfig.VERSION_CODE)
                val maxSkips = json.optInt("max_skips", 3)
                val forceMessage = json.optString(
                    "force_message",
                    "A critical update is required to continue using NotiFy. Please update to the latest version."
                )
                val downloadUrl = if (!json.isNull("download_url")) json.optString("download_url") else null

                val config = AppConfig(
                    latestVersionCode = latest,
                    minSupportedVersionCode = minSupported,
                    maxSkips = maxSkips,
                    forceMessage = forceMessage,
                    downloadUrl = downloadUrl,
                    fetchedAtMs = System.currentTimeMillis()
                )

                saveCachedConfig(config)
                return@withContext Result.success(config)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAppConfig network or parse exception: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Evaluates current update policy decision using provided config (or fallback to cached config).
     * If no config exists anywhere (offline on first run), returns [UpdatePolicyDecision.None].
     */
    fun evaluatePolicy(
        config: AppConfig?,
        currentCode: Int = BuildConfig.VERSION_CODE
    ): Pair<UpdatePolicyDecision, AppConfig?> {
        val effectiveConfig = config ?: getCachedConfig()
        if (effectiveConfig == null) {
            // First time launch offline or cache empty: never hard block
            return Pair(UpdatePolicyDecision.None, null)
        }

        val skipsUsed = getSkipsUsed(effectiveConfig.latestVersionCode)
        val decision = UpdatePolicy.evaluateUpdatePolicy(
            currentCode = currentCode,
            latestCode = effectiveConfig.latestVersionCode,
            minSupportedCode = effectiveConfig.minSupportedVersionCode,
            skipsUsed = skipsUsed,
            maxSkips = effectiveConfig.maxSkips
        )

        return Pair(decision, effectiveConfig)
    }
}
