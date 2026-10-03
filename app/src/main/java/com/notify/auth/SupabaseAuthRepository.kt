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
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "SupabaseAuthRepo"
private const val PREFS_NAME = "notify_auth_prefs"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at_epoch_sec"
private const val KEY_USER_ID = "user_id"
private const val KEY_USER_EMAIL = "user_email"

class SupabaseAuthRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Attempts to restore any saved session.
     * Guaranteed to NOT log out the user on network errors.
     */
    suspend fun restoreSession(): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (refreshToken.isNullOrBlank() || userId.isNullOrBlank()) {
            return@withContext null
        }

        val cachedSession = SupabaseAuthSession(
            accessToken = accessToken.orEmpty(),
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            email = email,
            isOffline = false
        )

        // If access token is still fresh (has more than 60s validity), reuse it immediately
        if (!cachedSession.isExpired(bufferSeconds = 60L)) {
            return@withContext cachedSession
        }

        // Try to refresh token
        try {
            val refreshed = refreshAccessToken(refreshToken)
            if (refreshed != null) {
                saveSession(refreshed)
                return@withContext refreshed
            }
        } catch (e: IOException) {
            // Network failure: do NOT log out! Return cached session with offline fallback
            Log.w(TAG, "Network error during session restore; falling back to cached offline session", e)
            return@withContext cachedSession.copy(isOffline = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during session restore", e)
        }

        // If refresh failed explicitly (e.g. invalid refresh token / revoked)
        clearLocalSession()
        return@withContext null
    }

    /**
     * Sign in using Email and Password.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<SupabaseAuthSession> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/token?grant_type=password"
                val json = JSONObject().apply {
                    put("email", email.trim())
                    put("password", password)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorMsg = parseErrorMessage(body, "Email ya password galat hai")
                        return@withContext Result.failure(Exception(errorMsg))
                    }

                    val session = parseSessionJson(body)
                    saveSession(session)
                    return@withContext Result.success(session)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error during sign in", e)
                return@withContext Result.failure(Exception("Internet check karein aur dobara try karein"))
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed", e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * Sign up using Email and Password.
     */
    suspend fun signUpWithEmail(email: String, password: String): Result<SupabaseAuthSession> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/signup"
                val json = JSONObject().apply {
                    put("email", email.trim())
                    put("password", password)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorMsg = parseErrorMessage(body, "Sign up fail ho gaya. Kripya dobara koshish karein")
                        return@withContext Result.failure(Exception(errorMsg))
                    }

                    val jsonObj = JSONObject(body)
                    // If email confirm is off, session is returned in the response
                    if (jsonObj.has("access_token")) {
                        val session = parseSessionJson(body)
                        saveSession(session)
                        return@withContext Result.success(session)
                    }

                    // Otherwise attempt immediate sign-in
                    return@withContext signInWithEmail(email, password)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error during sign up", e)
                return@withContext Result.failure(Exception("Internet check karein aur dobara try karein"))
            } catch (e: Exception) {
                Log.e(TAG, "Sign up failed", e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * Sign in with Google ID Token obtained from Credential Manager.
     */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<SupabaseAuthSession> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/token?grant_type=id_token"
                val json = JSONObject().apply {
                    put("provider", "google")
                    put("id_token", idToken)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorMsg = parseErrorMessage(body, "Google Sign-In failed")
                        return@withContext Result.failure(Exception(errorMsg))
                    }

                    val session = parseSessionJson(body)
                    saveSession(session)
                    return@withContext Result.success(session)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error during Google Sign-In", e)
                return@withContext Result.failure(Exception("Internet check karein aur dobara try karein"))
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In error", e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * Refreshes the session using the given refresh token.
     * Throws IOException on connectivity issues so callers can distinguish network faults.
     */
    private fun refreshAccessToken(refreshToken: String): SupabaseAuthSession? {
        val url = "${SupabaseConfig.AUTH_URL}/token?grant_type=refresh_token"
        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "Refresh token rejected: code=${response.code}, body=$body")
                return null
            }
            return parseSessionJson(body)
        }
    }

    /**
     * Signs out the current user, invalidating tokens locally and calling Supabase logout.
     */
    suspend fun signOut(accessToken: String?) = withContext(Dispatchers.IO) {
        if (!accessToken.isNullOrBlank()) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/logout"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post("{}".toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Sign out network request failed, proceeding with local clean up", e)
            }
        }
        clearLocalSession()
    }

    fun getStoredSession(): SupabaseAuthSession? {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
        val userId = prefs.getString(KEY_USER_ID, "") ?: ""
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        return SupabaseAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            email = email
        )
    }

    fun saveSession(session: SupabaseAuthSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USER_EMAIL, session.email)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSec)
            .apply()
    }

    fun clearLocalSession() {
        prefs.edit().clear().apply()
    }

    private fun parseSessionJson(jsonStr: String): SupabaseAuthSession {
        val root = JSONObject(jsonStr)
        val accessToken = root.getString("access_token")
        val refreshToken = root.getString("refresh_token")
        val expiresIn = root.optLong("expires_in", 3600L)
        val nowSec = System.currentTimeMillis() / 1000L
        val expiresAt = nowSec + expiresIn

        val userObj = root.optJSONObject("user")
        val userId = userObj?.optString("id") ?: root.optString("user_id", "")
        val email = userObj?.optString("email") ?: root.optString("email", "")

        return SupabaseAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            email = email
        )
    }

    private fun parseErrorMessage(jsonStr: String, fallback: String): String {
        return try {
            val root = JSONObject(jsonStr)
            when {
                root.has("error_description") -> {
                    val desc = root.getString("error_description")
                    translateError(desc)
                }
                root.has("msg") -> translateError(root.getString("msg"))
                root.has("message") -> translateError(root.getString("message"))
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun translateError(desc: String): String {
        val lower = desc.lowercase()
        return when {
            lower.contains("invalid login credentials") || lower.contains("invalid grant") ->
                "Email ya password galat hai"
            lower.contains("user already registered") || lower.contains("email already exists") ->
                "Ye email pehle se registered hai. Sign In karein"
            lower.contains("password should be at least") || lower.contains("weak password") ->
                "Password kam se kam 6 characters ka hona chahiye"
            lower.contains("email not confirmed") ->
                "Email confirm nahi hua hai"
            lower.contains("invalid email") ->
                "Email address galat format me hai"
            else -> desc
        }
    }
}
