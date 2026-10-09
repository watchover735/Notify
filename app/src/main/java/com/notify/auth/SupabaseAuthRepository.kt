package com.notify.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.notify.download.stream.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
private const val KEY_AUTH_PROVIDER = "auth_provider"

sealed interface SignUpResult {
    data class Success(val session: SupabaseAuthSession) : SignUpResult
    data class NeedOtp(val email: String) : SignUpResult
    data class Error(val message: String) : SignUpResult
}

sealed interface SignInResult {
    data class Success(val session: SupabaseAuthSession) : SignInResult
    data class NeedOtp(val email: String) : SignInResult
    data class Error(val message: String) : SignInResult
}

sealed interface VerifyOtpResult {
    data class Success(val session: SupabaseAuthSession) : VerifyOtpResult
    data object OtpInvalidOrExpired : VerifyOtpResult
    data object RateLimited : VerifyOtpResult
    data object Offline : VerifyOtpResult
    data object Timeout : VerifyOtpResult
    data class Server5xx(val code: Int) : VerifyOtpResult
    data class Unknown(val message: String) : VerifyOtpResult
}

sealed interface ResendOtpResult {
    data object Success : ResendOtpResult
    data object RateLimited : ResendOtpResult
    data object Offline : ResendOtpResult
    data object Timeout : ResendOtpResult
    data class Server5xx(val code: Int) : ResendOtpResult
    data class Unknown(val message: String) : ResendOtpResult
}

internal fun maskEmail(email: String): String {
    val atIndex = email.indexOf('@')
    if (atIndex <= 0) return "***@***"
    val user = email.substring(0, atIndex)
    val domain = email.substring(atIndex)
    val maskedUser = when {
        user.length == 1 -> "*"
        user.length == 2 -> "${user.first()}*"
        else -> "${user.first()}${"*".repeat(user.length - 2)}${user.last()}"
    }
    return "$maskedUser$domain"
}

private fun extractJsonString(jsonStr: String, key: String): String {
    try {
        val root = JSONObject(jsonStr)
        val value = root.optString(key)
        if (value.isNotEmpty()) return value
    } catch (_: Exception) {}

    val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
    return regex.find(jsonStr)?.groupValues?.get(1) ?: ""
}

private fun extractNestedJsonString(jsonStr: String, parent: String, key: String): String {
    val parentRegex = Regex("\"$parent\"\\s*:\\s*\\{([^}]*)\\}")
    val parentContent = parentRegex.find(jsonStr)?.groupValues?.get(1) ?: return ""
    val childRegex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
    return childRegex.find(parentContent)?.groupValues?.get(1) ?: ""
}

private fun extractJsonLong(jsonStr: String, key: String, fallback: Long): Long {
    try {
        val root = JSONObject(jsonStr)
        val value = root.optLong(key, -1L)
        if (value != -1L) return value
    } catch (_: Exception) {}

    val regex = Regex("\"$key\"\\s*:\\s*([0-9]+)")
    return regex.find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: fallback
}

internal fun parseSessionJson(jsonStr: String): SupabaseAuthSession {
    val accessToken = extractJsonString(jsonStr, "access_token")
    val refreshToken = extractJsonString(jsonStr, "refresh_token")
    val expiresIn = extractJsonLong(jsonStr, "expires_in", 3600L)
    val nowSec = System.currentTimeMillis() / 1000L
    val expiresAt = nowSec + expiresIn

    var userId = ""
    var email = ""

    try {
        val root = JSONObject(jsonStr)
        val userObj = root.optJSONObject("user")
        if (userObj != null) {
            userId = userObj.optString("id")
            email = userObj.optString("email")
        }
    } catch (_: Exception) {}

    if (userId.isEmpty()) {
        userId = extractNestedJsonString(jsonStr, "user", "id").ifEmpty {
            extractJsonString(jsonStr, "user_id").ifEmpty {
                extractJsonString(jsonStr, "id")
            }
        }
    }
    if (email.isEmpty()) {
        email = extractNestedJsonString(jsonStr, "user", "email").ifEmpty {
            extractJsonString(jsonStr, "email")
        }
    }

    if (accessToken.isEmpty() || refreshToken.isEmpty()) {
        throw IllegalArgumentException("Invalid session JSON: missing access_token or refresh_token")
    }

    return SupabaseAuthSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSec = expiresAt,
        userId = userId,
        email = email
    )
}

internal fun parseVerifyResponse(code: Int, body: String): VerifyOtpResult {
    if (code in 200..299) {
        return try {
            val session = parseSessionJson(body)
            VerifyOtpResult.Success(session)
        } catch (e: Exception) {
            VerifyOtpResult.Unknown("Failed to parse session: ${e.message}")
        }
    }
    if (code == 429) {
        return VerifyOtpResult.RateLimited
    }
    if (code in 500..599) {
        return VerifyOtpResult.Server5xx(code)
    }

    val errorCode = extractJsonString(body, "error_code").ifEmpty {
        extractJsonString(body, "code")
    }
    val msg = extractJsonString(body, "msg").ifEmpty {
        extractJsonString(body, "message").ifEmpty {
            extractJsonString(body, "error_description")
        }
    }

    return if (errorCode == "over_email_send_rate_limit" || errorCode == "rate_limited") {
        VerifyOtpResult.RateLimited
    } else if (msg.contains("type", ignoreCase = true) &&
        (msg.contains("invalid", ignoreCase = true) || msg.contains("unsupported", ignoreCase = true))
    ) {
        Log.e(TAG, "Server rejected verify type: $msg")
        VerifyOtpResult.Unknown("Server type error: $msg")
    } else {
        VerifyOtpResult.OtpInvalidOrExpired
    }
}

internal fun parseResendResponse(code: Int, body: String): ResendOtpResult {
    if (code in 200..299) {
        return ResendOtpResult.Success
    }
    if (code == 429) {
        return ResendOtpResult.RateLimited
    }
    if (code in 500..599) {
        return ResendOtpResult.Server5xx(code)
    }

    val errorCode = extractJsonString(body, "error_code").ifEmpty {
        extractJsonString(body, "code")
    }
    return if (errorCode == "over_email_send_rate_limit" || errorCode == "rate_limited") {
        ResendOtpResult.RateLimited
    } else {
        val msg = extractJsonString(body, "msg").ifEmpty {
            extractJsonString(body, "message").ifEmpty {
                extractJsonString(body, "error_description")
            }
        }
        ResendOtpResult.Unknown(msg.ifEmpty { "Failed to send OTP" })
    }
}

internal fun mapOtpExceptionToVerifyResult(e: Throwable): VerifyOtpResult = when (e) {
    is java.net.SocketTimeoutException -> VerifyOtpResult.Timeout
    is IOException -> VerifyOtpResult.Offline
    else -> VerifyOtpResult.Unknown(e.message ?: "Unknown error")
}

internal fun mapOtpExceptionToResendResult(e: Throwable): ResendOtpResult = when (e) {
    is java.net.SocketTimeoutException -> ResendOtpResult.Timeout
    is IOException -> ResendOtpResult.Offline
    else -> ResendOtpResult.Unknown(e.message ?: "Unknown error")
}

class SupabaseAuthRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : AuthRepository {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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

        if (!cachedSession.isExpired(bufferSeconds = 60L)) {
            return@withContext cachedSession
        }

        try {
            val refreshed = refreshAccessToken(refreshToken)
            if (refreshed != null) {
                saveSession(refreshed)
                return@withContext refreshed
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error during session restore; falling back to cached offline session", e)
            return@withContext cachedSession.copy(isOffline = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during session restore", e)
        }

        clearLocalSession()
        return@withContext null
    }

    suspend fun signInWithEmail(email: String, password: String): SignInResult =
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
                        val isEmailNotConfirmed = try {
                            val root = JSONObject(body)
                            val errCode = root.optString("error_code")
                            val errMsg = root.optString("error_description").ifEmpty {
                                root.optString("msg").ifEmpty { root.optString("message") }
                            }
                            errCode == "email_not_confirmed" || errMsg.contains("Email not confirmed", ignoreCase = true)
                        } catch (_: Exception) {
                            false
                        }

                        if (isEmailNotConfirmed) {
                            Log.i(TAG, "Sign in required email confirmation for ${maskEmail(email)}")
                            return@withContext SignInResult.NeedOtp(email.trim())
                        }

                        val errorMsg = parseErrorMessage(body, "Invalid email or password")
                        return@withContext SignInResult.Error(errorMsg)
                    }

                    val session = parseSessionJson(body)
                    saveSession(session, provider = "email")
                    return@withContext SignInResult.Success(session)
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Timeout during sign in", e)
                return@withContext SignInResult.Error("Server is not responding. Please try again")
            } catch (e: IOException) {
                Log.w(TAG, "Network error during sign in", e)
                return@withContext SignInResult.Error("Please check your internet connection and try again")
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed", e)
                return@withContext SignInResult.Error(e.message ?: "Sign in failed")
            }
        }

    suspend fun signUpWithEmail(email: String, password: String): SignUpResult =
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
                        val errorMsg = parseErrorMessage(body, "Sign up failed. Please try again")
                        return@withContext SignUpResult.Error(errorMsg)
                    }

                    val jsonObj = JSONObject(body)
                    if (jsonObj.has("access_token")) {
                        val session = parseSessionJson(body)
                        saveSession(session)
                        return@withContext SignUpResult.Success(session)
                    }

                    val identitiesArray = jsonObj.optJSONArray("identities")
                        ?: jsonObj.optJSONObject("user")?.optJSONArray("identities")
                    if (identitiesArray != null && identitiesArray.length() == 0) {
                        Log.i(TAG, "Sign up email already registered (empty identities) for ${maskEmail(email)}")
                        return@withContext SignUpResult.Error("This email is already registered. Please sign in")
                    }

                    Log.i(TAG, "Sign up succeeded, awaiting email OTP for ${maskEmail(email)}")
                    return@withContext SignUpResult.NeedOtp(email.trim())
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Timeout during sign up", e)
                return@withContext SignUpResult.Error("Server is not responding. Please try again")
            } catch (e: IOException) {
                Log.w(TAG, "Network error during sign up", e)
                return@withContext SignUpResult.Error("Please check your internet connection and try again")
            } catch (e: Exception) {
                Log.e(TAG, "Sign up failed", e)
                return@withContext SignUpResult.Error(e.message ?: "Sign up failed")
            }
        }

    suspend fun verifyEmailOtp(email: String, token: String): VerifyOtpResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/verify"
                val json = JSONObject().apply {
                    put("type", "signup")
                    put("email", email.trim())
                    put("token", token.trim())
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val result = parseVerifyResponse(response.code, body)
                    if (result is VerifyOtpResult.Success) {
                        saveSession(result.session)
                    }
                    result
                }
            } catch (e: Exception) {
                mapOtpExceptionToVerifyResult(e)
            }
        }

    suspend fun resendEmailOtp(email: String): ResendOtpResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/resend"
                val json = JSONObject().apply {
                    put("type", "signup")
                    put("email", email.trim())
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    parseResendResponse(response.code, body)
                }
            } catch (e: Exception) {
                mapOtpExceptionToResendResult(e)
            }
        }

    suspend fun sendPasswordRecovery(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${SupabaseConfig.AUTH_URL}/recover"
            val json = JSONObject().apply {
                put("email", email.trim())
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 429) {
                    return@withContext Result.failure(Exception("Email limit reached. Please try again later"))
                }
                if (response.code in 500..599) {
                    return@withContext Result.failure(Exception("Server error. Please try again later"))
                }
                return@withContext Result.success(Unit)
            }
        } catch (e: java.net.SocketTimeoutException) {
            return@withContext Result.failure(Exception("Server is not responding. Please try again"))
        } catch (e: IOException) {
            return@withContext Result.failure(Exception("Please check your internet connection and try again"))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun verifyRecoveryOtp(email: String, token: String): VerifyOtpResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/verify"
                val json = JSONObject().apply {
                    put("type", "recovery")
                    put("email", email.trim())
                    put("token", token.trim())
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val result = parseVerifyResponse(response.code, body)
                    if (result is VerifyOtpResult.Success) {
                        saveSession(result.session, provider = "email")
                    }
                    result
                }
            } catch (e: Exception) {
                mapOtpExceptionToVerifyResult(e)
            }
        }

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
                    saveSession(session, provider = "google")
                    return@withContext Result.success(session)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network error during Google Sign-In", e)
                return@withContext Result.failure(Exception("Please check your internet connection and try again"))
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In error", e)
                return@withContext Result.failure(e)
            }
        }

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

    suspend fun updateUserPassword(newPassword: String, accessToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SupabaseConfig.AUTH_URL}/user"
                val json = JSONObject().apply {
                    put("password", newPassword)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .put(json.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = parseErrorMessage(body, "Failed to update password")
                        return@withContext Result.failure(Exception(errMsg))
                    }
                    return@withContext Result.success(Unit)
                }
            } catch (e: java.net.SocketTimeoutException) {
                return@withContext Result.failure(Exception("Server is not responding. Please try again"))
            } catch (e: IOException) {
                return@withContext Result.failure(Exception("Please check your internet connection and try again"))
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

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

    override fun getCurrentAccessToken(): String? {
        val session = getStoredSession() ?: return null
        return session.accessToken.ifBlank { null }
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

    fun saveSession(session: SupabaseAuthSession, provider: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USER_EMAIL, session.email)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSec)
        if (provider != null) {
            editor.putString(KEY_AUTH_PROVIDER, provider)
        }
        editor.apply()
    }

    fun isGoogleUser(): Boolean {
        val provider = prefs.getString(KEY_AUTH_PROVIDER, null)
        if (provider == "google") return true
        if (provider == "email") return false
        val session = getStoredSession() ?: return false
        return try {
            val parts = session.accessToken.split(".")
            if (parts.size >= 2) {
                val decoded = safeBase64UrlDecode(parts[1])
                val json = JSONObject(String(decoded, Charsets.UTF_8))
                val appMeta = json.optJSONObject("app_metadata")
                appMeta?.optString("provider") == "google"
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun safeBase64UrlDecode(input: String): ByteArray {
        return try {
            android.util.Base64.decode(input, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        } catch (_: Throwable) {
            java.util.Base64.getUrlDecoder().decode(input)
        }
    }

    fun clearLocalSession() {
        prefs.edit().clear().apply()
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
                "Invalid email or password"
            lower.contains("user already registered") || lower.contains("email already exists") ->
                "This email is already registered. Please sign in"
            lower.contains("password should be at least") || lower.contains("weak password") ->
                "Password must be at least 6 characters"
            lower.contains("email not confirmed") ->
                "Email has not been confirmed"
            lower.contains("invalid email") ->
                "Invalid email address format"
            else -> desc
        }
    }
}