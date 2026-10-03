package com.notify.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.notify.download.stream.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val TAG = "ProfileAndKeyRepo"
private const val PREFS_NAME = "notify_entitlement_prefs"
private const val KEY_NICKNAME = "cached_nickname"
private const val KEY_STATUS = "cached_status"
private const val KEY_EXPIRES_AT = "cached_expires_at"
private const val KEY_LAST_VERIFIED = "cached_last_verified_at"
private const val KEY_LAST_CHECK_TIME = "cached_last_check_epoch"

// 72 hours offline grace period in milliseconds
const val OFFLINE_GRACE_PERIOD_MS = 72L * 60L * 60L * 1000L

data class EntitlementInfo(
    val status: String, // 'active', 'none', 'expired', 'revoked'
    val expiresAtEpochMs: Long?,
    val serverTimeEpochMs: Long,
    val isFromOfflineCache: Boolean = false
) {
    val isActive: Boolean
        get() = status == "active"
}

data class RedeemResult(
    val code: String, // 'ok', 'invalid', 'already_used', 'revoked', 'too_many_attempts'
    val message: String,
    val expiresAtEpochMs: Long?,
    val serverTimeEpochMs: Long?
)

class SupabaseProfileAndKeyRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── Profile Methods ───────────────────────────────────────────────────────

    /**
     * Fetches nickname from profiles table for the authenticated user.
     * Returns:
     * - Result.success(nickname): row exists and has nickname
     * - Result.success(null): row does not exist (new user needs nickname)
     * - Result.failure(e): network or server error (must show retry, NEVER overwrite)
     */
    suspend fun fetchProfileNickname(userId: String, accessToken: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.REST_URL}/profiles?id=eq.$userId&select=nickname"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "fetchProfileNickname failed with HTTP ${response.code}: $body")
                        return@withContext Result.failure(IOException("Server error ${response.code}"))
                    }

                    val jsonArray = JSONArray(body)
                    if (jsonArray.length() == 0) {
                        return@withContext Result.success(null)
                    }

                    val row = jsonArray.getJSONObject(0)
                    val nickname = row.optString("nickname", "").trim()
                    if (nickname.isNotEmpty()) {
                        saveCachedNickname(nickname)
                        return@withContext Result.success(nickname)
                    } else {
                        return@withContext Result.success(null)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error fetching profile nickname", e)
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching profile", e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * Saves user nickname to Supabase profiles table using merge-duplicates upsert.
     */
    suspend fun saveProfileNickname(
        userId: String,
        nickname: String,
        accessToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = nickname.trim()
        try {
            val url = "${SupabaseConfig.REST_URL}/profiles"
            val json = JSONObject().apply {
                put("id", userId)
                put("nickname", trimmed)
                put("updated_at", currentIsoTimestamp())
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "saveProfileNickname failed: HTTP ${response.code} $body")
                    return@withContext Result.failure(Exception("Nickname save fail ho gaya (${response.code})"))
                }

                saveCachedNickname(trimmed)
                return@withContext Result.success(Unit)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error saving nickname", e)
            return@withContext Result.failure(Exception("Internet connection check karein"))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving nickname", e)
            return@withContext Result.failure(e)
        }
    }

    fun getCachedNickname(): String? {
        return prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() }
    }

    fun saveCachedNickname(nickname: String) {
        prefs.edit().putString(KEY_NICKNAME, nickname.trim()).apply()
    }

    // ── Entitlement & Key Methods ─────────────────────────────────────────────

    /**
     * Calls get_entitlement() RPC.
     * Evaluates offline grace period (72 hours) if network is unavailable.
     */
    suspend fun checkEntitlement(
        accessToken: String,
        forceRefresh: Boolean = false
    ): Result<EntitlementInfo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)

        // Throttle check on resume: max once per 15 minutes unless forceRefresh is true
        if (!forceRefresh && (now - lastCheck < 15 * 60 * 1000L)) {
            val cached = getCachedEntitlement()
            if (cached != null) {
                return@withContext Result.success(cached)
            }
        }

        try {
            val url = "${SupabaseConfig.REST_URL}/rpc/get_entitlement"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "get_entitlement failed: HTTP ${response.code} $body")
                    return@withContext evaluateOfflineGraceFallback()
                }

                val json = JSONObject(body)
                val status = json.optString("status", "none")
                val expiresAtStr = if (!json.isNull("expires_at")) json.optString("expires_at") else null
                val serverTimeStr = json.optString("server_time", "")

                val expiresAtMs = parseIsoToMillis(expiresAtStr)
                val serverTimeMs = parseIsoToMillis(serverTimeStr) ?: System.currentTimeMillis()

                saveCachedEntitlement(status, expiresAtMs, serverTimeMs)
                prefs.edit().putLong(KEY_LAST_CHECK_TIME, now).apply()

                val info = EntitlementInfo(
                    status = status,
                    expiresAtEpochMs = expiresAtMs,
                    serverTimeEpochMs = serverTimeMs,
                    isFromOfflineCache = false
                )
                return@withContext Result.success(info)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error checking entitlement, evaluating offline grace", e)
            return@withContext evaluateOfflineGraceFallback()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error checking entitlement", e)
            return@withContext evaluateOfflineGraceFallback()
        }
    }

    /**
     * Calls redeem_key(p_code) RPC atomically.
     */
    suspend fun redeemKey(code: String, accessToken: String): Result<RedeemResult> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.REST_URL}/rpc/redeem_key"
                val json = JSONObject().apply {
                    put("p_code", code.trim())
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "redeem_key failed HTTP ${response.code}: $body")
                        return@withContext Result.failure(Exception("Redeem failed with status ${response.code}"))
                    }

                    val jsonObj = JSONObject(body)
                    val resultCode = jsonObj.optString("code", "invalid")
                    val message = jsonObj.optString("message", "Key verification failed")
                    val expiresAtStr = if (!jsonObj.isNull("expires_at")) jsonObj.optString("expires_at") else null
                    val serverTimeStr = jsonObj.optString("server_time", "")

                    val expiresAtMs = parseIsoToMillis(expiresAtStr)
                    val serverTimeMs = parseIsoToMillis(serverTimeStr)

                    if (resultCode == "ok") {
                        saveCachedEntitlement("active", expiresAtMs, serverTimeMs ?: System.currentTimeMillis())
                    }

                    val result = RedeemResult(
                        code = resultCode,
                        message = message,
                        expiresAtEpochMs = expiresAtMs,
                        serverTimeEpochMs = serverTimeMs
                    )
                    return@withContext Result.success(result)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error during redeem_key", e)
                return@withContext Result.failure(Exception("Internet check karein aur dobara try karein"))
            } catch (e: Exception) {
                Log.e(TAG, "Error during redeem_key", e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * Evaluates offline grace rule:
     * If cached status is 'active' AND last_verified_at is within 72 hours
     * AND (expires_at is null OR now < expires_at): active via grace.
     */
    private fun evaluateOfflineGraceFallback(): Result<EntitlementInfo> {
        val cached = getCachedEntitlement()
        if (cached != null) {
            val now = System.currentTimeMillis()
            val lastVerified = prefs.getLong(KEY_LAST_VERIFIED, 0L)
            val isWithin72Hours = (now - lastVerified) <= OFFLINE_GRACE_PERIOD_MS
            val isNotExpired = cached.expiresAtEpochMs == null || now < cached.expiresAtEpochMs

            if (cached.status == "active" && isWithin72Hours && isNotExpired) {
                Log.i(TAG, "User active under offline grace period (${(now - lastVerified)/3600000}h since last verification)")
                return Result.success(cached.copy(isFromOfflineCache = true))
            }
        }
        return Result.failure(IOException("No active offline entitlement grace available"))
    }

    fun getCachedEntitlement(): EntitlementInfo? {
        val status = prefs.getString(KEY_STATUS, null) ?: return null
        val expiresAt = if (prefs.contains(KEY_EXPIRES_AT)) prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0L } else null
        val lastVerified = prefs.getLong(KEY_LAST_VERIFIED, System.currentTimeMillis())

        return EntitlementInfo(
            status = status,
            expiresAtEpochMs = expiresAt,
            serverTimeEpochMs = lastVerified,
            isFromOfflineCache = true
        )
    }

    private fun saveCachedEntitlement(status: String, expiresAtMs: Long?, serverTimeMs: Long) {
        val editor = prefs.edit()
            .putString(KEY_STATUS, status)
            .putLong(KEY_LAST_VERIFIED, serverTimeMs)

        if (expiresAtMs != null) {
            editor.putLong(KEY_EXPIRES_AT, expiresAtMs)
        } else {
            editor.remove(KEY_EXPIRES_AT)
        }
        editor.apply()
    }

    fun clearLocalData() {
        prefs.edit().clear().apply()
    }

    private fun parseIsoToMillis(isoString: String?): Long? {
        if (isoString.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(isoString)?.time
        } catch (_: Exception) {
            try {
                val sdf2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                sdf2.timeZone = TimeZone.getTimeZone("UTC")
                sdf2.parse(isoString)?.time
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse ISO timestamp: $isoString", e)
                null
            }
        }
    }

    private fun currentIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(System.currentTimeMillis())
    }
}
