package com.notify.auth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notify.core.preferences.UserProfilePreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AuthGateViewModel"

sealed interface AuthGateState {
    data object Loading : AuthGateState
    data class NeedLogin(val error: String? = null, val isLoading: Boolean = false) : AuthGateState
    data class NeedEmailOtp(
        val email: String,
        val error: String? = null,
        val message: String? = null,
        val isLoading: Boolean = false
    ) : AuthGateState
    data class NeedNickname(val error: String? = null, val isLoading: Boolean = false) : AuthGateState
    data class NeedKey(
        val message: String? = null,
        val isError: Boolean = false,
        val isLoading: Boolean = false
    ) : AuthGateState
    data object Ready : AuthGateState
    data class Error(val message: String, val onRetry: () -> Unit) : AuthGateState
}

class AuthGateViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = SupabaseAuthRepository(application)
    private val profileAndKeyRepository = SupabaseProfileAndKeyRepository(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val userProfilePrefs = UserProfilePreferences.getInstance(application)

    private val _gateState = MutableStateFlow<AuthGateState>(AuthGateState.Loading)
    val gateState: StateFlow<AuthGateState> = _gateState.asStateFlow()

    private val _resendCooldownSeconds = MutableStateFlow(0)
    val resendCooldownSeconds: StateFlow<Int> = _resendCooldownSeconds.asStateFlow()
    private var cooldownJob: Job? = null

    // Flag to prevent double-tap submissions
    private var isSubmitting = false

    init {
        checkInitialAuthState()
    }

    private fun startResendCooldown(seconds: Int = 60) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (i in seconds downTo 0) {
                _resendCooldownSeconds.value = i
                if (i > 0) {
                    delay(1000L)
                }
            }
        }
    }

    fun checkInitialAuthState() {
        viewModelScope.launch {
            _gateState.value = AuthGateState.Loading
            val session = authRepository.restoreSession()
            if (session == null) {
                _gateState.value = AuthGateState.NeedLogin()
                return@launch
            }

            // User has a session, verify profile & nickname
            resolveProfileAndEntitlement(session)
        }
    }

    private suspend fun resolveProfileAndEntitlement(session: SupabaseAuthSession) {
        val cachedNickname = profileAndKeyRepository.getCachedNickname()

        // Source of truth is server, but local cache allows offline continuity
        val profileResult = profileAndKeyRepository.fetchProfileNickname(session.userId, session.accessToken)

        val resolvedNickname: String? = if (profileResult.isSuccess) {
            val serverNick = profileResult.getOrNull()
            if (serverNick.isNullOrBlank()) {
                // Server explicitly says no nickname row exists
                _gateState.value = AuthGateState.NeedNickname()
                return
            } else {
                profileAndKeyRepository.saveCachedNickname(serverNick)
                userProfilePrefs.updateDisplayName(serverNick)
                serverNick
            }
        } else {
            // Network error fetching profile: fallback to cached nickname if available
            if (!cachedNickname.isNullOrBlank()) {
                userProfilePrefs.updateDisplayName(cachedNickname)
                cachedNickname
            } else {
                // No cached nickname AND network failure -> show error with retry
                _gateState.value = AuthGateState.Error(
                    message = "Internet connection check karein",
                    onRetry = { checkInitialAuthState() }
                )
                return
            }
        }

        // Now verify license entitlement
        val entitlementResult = profileAndKeyRepository.checkEntitlement(session.accessToken, forceRefresh = false)
        if (entitlementResult.isSuccess) {
            val info = entitlementResult.getOrThrow()
            when (info.status) {
                "active" -> {
                    _gateState.value = AuthGateState.Ready
                }
                "expired" -> {
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Key expire ho gayi",
                        isError = true
                    )
                }
                "revoked" -> {
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Key revoke kar di gayi. Admin se contact karo",
                        isError = true
                    )
                }
                else -> {
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Apna access key enter karein",
                        isError = false
                    )
                }
            }
        } else {
            // Network error and offline grace failed
            val cached = profileAndKeyRepository.getCachedEntitlement()
            if (cached != null && cached.status == "active") {
                // If offline cache still valid
                _gateState.value = AuthGateState.Ready
            } else {
                _gateState.value = AuthGateState.Error(
                    message = "Access verify nahi ho saka. Internet check karein.",
                    onRetry = { checkInitialAuthState() }
                )
            }
        }
    }

    /**
     * Called on App RESUME. Max once per 15 minutes throttled inside repository.
     * onRevokedOrExpired callback allows pausing playback.
     */
    fun onAppResume(onRevokedOrExpired: () -> Unit) {
        if (_gateState.value !is AuthGateState.Ready) return

        val session = authRepository.getStoredSession() ?: return
        viewModelScope.launch {
            val result = profileAndKeyRepository.checkEntitlement(session.accessToken, forceRefresh = false)
            if (result.isSuccess) {
                val info = result.getOrThrow()
                if (info.status == "revoked") {
                    Log.w(TAG, "Entitlement revoked on resume, pausing playback and locking gate")
                    onRevokedOrExpired()
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Key revoke kar di gayi. Admin se contact karo",
                        isError = true
                    )
                } else if (info.status == "expired") {
                    Log.w(TAG, "Entitlement expired on resume, pausing playback and locking gate")
                    onRevokedOrExpired()
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Key expire ho gayi",
                        isError = true
                    )
                }
            }
            // On network error: do NOTHING. Keep user in Ready state!
        }
    }

    fun signInWithEmail(email: String, pass: String) {
        if (isSubmitting) return
        isSubmitting = true
        _gateState.value = AuthGateState.NeedLogin(isLoading = true)

        viewModelScope.launch {
            try {
                when (val res = authRepository.signInWithEmail(email, pass)) {
                    is SignInResult.Success -> {
                        resolveProfileAndEntitlement(res.session)
                    }
                    is SignInResult.NeedOtp -> {
                        // User exists with unconfirmed email. Transition to NeedEmailOtp without auto-resend.
                        _gateState.value = AuthGateState.NeedEmailOtp(
                            email = res.email,
                            message = "Pehle apna email OTP se verify karein"
                        )
                    }
                    is SignInResult.Error -> {
                        _gateState.value = AuthGateState.NeedLogin(
                            error = res.message,
                            isLoading = false
                        )
                    }
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun signUpWithEmail(email: String, pass: String) {
        if (isSubmitting) return
        isSubmitting = true
        _gateState.value = AuthGateState.NeedLogin(isLoading = true)

        viewModelScope.launch {
            try {
                when (val res = authRepository.signUpWithEmail(email, pass)) {
                    is SignUpResult.Success -> {
                        resolveProfileAndEntitlement(res.session)
                    }
                    is SignUpResult.NeedOtp -> {
                        startResendCooldown(60)
                        _gateState.value = AuthGateState.NeedEmailOtp(
                            email = res.email,
                            message = "Verification code aapke email par bheja gaya hai"
                        )
                    }
                    is SignUpResult.Error -> {
                        _gateState.value = AuthGateState.NeedLogin(
                            error = res.message,
                            isLoading = false
                        )
                    }
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun verifyEmailOtp(email: String, token: String) {
        if (isSubmitting) return
        val curr = _gateState.value as? AuthGateState.NeedEmailOtp ?: return

        isSubmitting = true
        _gateState.value = curr.copy(isLoading = true, error = null, message = null)

        viewModelScope.launch {
            try {
                when (val res = authRepository.verifyEmailOtp(email, token)) {
                    is VerifyOtpResult.Success -> {
                        resolveProfileAndEntitlement(res.session)
                    }
                    is VerifyOtpResult.OtpInvalidOrExpired -> {
                        _gateState.value = curr.copy(
                            error = "Code galat ya expire ho gaya",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.RateLimited -> {
                        _gateState.value = curr.copy(
                            error = "Thodi der baad try karo",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Offline -> {
                        _gateState.value = curr.copy(
                            error = "Internet connection check karein",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Timeout -> {
                        _gateState.value = curr.copy(
                            error = "Server respond nahi kar raha. Dobara try karein",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Server5xx -> {
                        _gateState.value = curr.copy(
                            error = "Server me dikkat hai (HTTP ${res.code}). Thodi der baad try karein",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Unknown -> {
                        _gateState.value = curr.copy(
                            error = res.message.ifEmpty { "Verification fail ho gaya" },
                            message = null,
                            isLoading = false
                        )
                    }
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun resendEmailOtp(email: String) {
        if (isSubmitting || _resendCooldownSeconds.value > 0) return
        val curr = _gateState.value as? AuthGateState.NeedEmailOtp ?: return

        isSubmitting = true
        _gateState.value = curr.copy(isLoading = true, error = null, message = null)

        viewModelScope.launch {
            try {
                when (val res = authRepository.resendEmailOtp(email)) {
                    is ResendOtpResult.Success -> {
                        startResendCooldown(60)
                        _gateState.value = curr.copy(
                            message = "Code dobara bhej diya gaya hai",
                            error = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.RateLimited -> {
                        startResendCooldown(60)
                        _gateState.value = curr.copy(
                            error = "Thodi der baad try karo",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Offline -> {
                        _gateState.value = curr.copy(
                            error = "Internet connection check karein",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Timeout -> {
                        _gateState.value = curr.copy(
                            error = "Server respond nahi kar raha. Dobara try karein",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Server5xx -> {
                        _gateState.value = curr.copy(
                            error = "Server me dikkat hai (HTTP ${res.code}). Thodi der baad try karein",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Unknown -> {
                        _gateState.value = curr.copy(
                            error = res.message.ifEmpty { "OTP bhejne me dikkat aayi" },
                            message = null,
                            isLoading = false
                        )
                    }
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun backToLogin() {
        cooldownJob?.cancel()
        _resendCooldownSeconds.value = 0
        _gateState.value = AuthGateState.NeedLogin()
    }

    fun signInWithGoogle() {
        if (isSubmitting) return
        isSubmitting = true
        _gateState.value = AuthGateState.NeedLogin(isLoading = true)

        viewModelScope.launch {
            try {
                val tokenResult = googleAuthManager.getGoogleIdToken()
                if (tokenResult.isFailure) {
                    _gateState.value = AuthGateState.NeedLogin(
                        error = tokenResult.exceptionOrNull()?.message ?: "Google Sign-In failed",
                        isLoading = false
                    )
                    return@launch
                }

                val idToken = tokenResult.getOrThrow()
                val sessionResult = authRepository.signInWithGoogleIdToken(idToken)
                if (sessionResult.isSuccess) {
                    val session = sessionResult.getOrThrow()
                    resolveProfileAndEntitlement(session)
                } else {
                    _gateState.value = AuthGateState.NeedLogin(
                        error = sessionResult.exceptionOrNull()?.message ?: "Google Sign-In failed",
                        isLoading = false
                    )
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun saveNickname(nickname: String) {
        if (isSubmitting) return
        val session = authRepository.getStoredSession() ?: run {
            _gateState.value = AuthGateState.NeedLogin()
            return
        }

        isSubmitting = true
        _gateState.value = AuthGateState.NeedNickname(isLoading = true)

        viewModelScope.launch {
            try {
                val res = profileAndKeyRepository.saveProfileNickname(session.userId, nickname, session.accessToken)
                if (res.isSuccess) {
                    userProfilePrefs.updateDisplayName(nickname)
                    // Check entitlement now
                    val entResult = profileAndKeyRepository.checkEntitlement(session.accessToken, forceRefresh = true)
                    if (entResult.isSuccess) {
                        val status = entResult.getOrThrow().status
                        if (status == "active") {
                            _gateState.value = AuthGateState.Ready
                        } else {
                            _gateState.value = AuthGateState.NeedKey()
                        }
                    } else {
                        _gateState.value = AuthGateState.NeedKey()
                    }
                } else {
                    _gateState.value = AuthGateState.NeedNickname(
                        error = res.exceptionOrNull()?.message ?: "Failed to save nickname",
                        isLoading = false
                    )
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun redeemKey(code: String) {
        if (isSubmitting) return
        val session = authRepository.getStoredSession() ?: run {
            _gateState.value = AuthGateState.NeedLogin()
            return
        }

        isSubmitting = true
        _gateState.value = AuthGateState.NeedKey(isLoading = true)

        viewModelScope.launch {
            try {
                val res = profileAndKeyRepository.redeemKey(code, session.accessToken)
                if (res.isSuccess) {
                    val redeem = res.getOrThrow()
                    if (redeem.code == "ok") {
                        _gateState.value = AuthGateState.Ready
                    } else {
                        _gateState.value = AuthGateState.NeedKey(
                            message = redeem.message,
                            isError = true,
                            isLoading = false
                        )
                    }
                } else {
                    _gateState.value = AuthGateState.NeedKey(
                        message = res.exceptionOrNull()?.message ?: "Key verify karne me error",
                        isError = true,
                        isLoading = false
                    )
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    fun dismissError() {
        val curr = _gateState.value
        if (curr is AuthGateState.NeedLogin && curr.error != null) {
            _gateState.value = curr.copy(error = null)
        } else if (curr is AuthGateState.NeedEmailOtp && (curr.error != null || curr.message != null)) {
            _gateState.value = curr.copy(error = null, message = null)
        } else if (curr is AuthGateState.NeedNickname && curr.error != null) {
            _gateState.value = curr.copy(error = null)
        } else if (curr is AuthGateState.NeedKey && curr.message != null) {
            _gateState.value = curr.copy(message = null)
        }
    }
}
