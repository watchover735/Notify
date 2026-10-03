package com.notify.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.notify.download.stream.SupabaseConfig

private const val TAG = "GoogleAuthManager"

class GoogleAuthManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    /**
     * Triggers Google Sign-In sheet via Android Credential Manager and returns the ID token.
     */
    suspend fun getGoogleIdToken(): Result<String> {
        val serverClientId = SupabaseConfig.GOOGLE_WEB_CLIENT_ID
        if (serverClientId.isBlank() || serverClientId.startsWith("YOUR_GOOGLE_WEB_CLIENT_ID")) {
            return Result.failure(
                IllegalStateException("Google Web Client ID configure nahi hai. SETUP.md dekhein.")
            )
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(
                request = request,
                context = context
            )

            val credential = response.credential
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val idToken = googleIdTokenCredential.idToken

            if (idToken.isNotBlank()) {
                Result.success(idToken)
            } else {
                Result.failure(Exception("Google ID token khali mila"))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "User cancelled Google Sign-In dialog")
            Result.failure(Exception("Sign-In cancel ho gaya"))
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: ${e.type}", e)
            val userMsg = when {
                e.type.contains("NoCredentialException", ignoreCase = true) ->
                    "Device par koi Google account nahi mila"
                e.type.contains("Interrupted", ignoreCase = true) ->
                    "Sign-In interrupt ho gaya, dobara try karein"
                else -> "Google Sign-In failed (${e.message ?: e.type})"
            }
            Result.failure(Exception(userMsg))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google Sign-In", e)
            Result.failure(e)
        }
    }
}
