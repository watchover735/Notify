package com.notify.auth

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notify.core.preferences.UserProfilePreferences
import com.notify.updater.AppConfig
import com.notify.updater.AppConfigRepository
import com.notify.updater.HardUpdateReason
import com.notify.updater.UpdatePolicyDecision
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private const val TAG = "AuthGateViewModel"

data class HardUpdateState(
    val config: AppConfig,
    val reason: HardUpdateReason
)

data class SoftUpdateState(
    val config: AppConfig,
    val skipsLeft: Int
)

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

    // Live expiry checks & playback coordinator
    private var periodicCheckJob: Job? = null
    private var exactTimeCheckJob: Job? = null
    private val checkMutex = Mutex()
    private var onPausePlayback: (() -> Unit)? = null
    private var isPlaybackActiveSupplier: (() -> Boolean)? = null
    private var isAppInForeground = true

    // App Config & Force/Soft Update
    private val appConfigRepository = AppConfigRepository(application)
    private val _hardUpdateState = MutableStateFlow<HardUpdateState?>(null)
    val hardUpdateState: StateFlow<HardUpdateState?> = _hardUpdateState.asStateFlow()

    private val _softUpdateState = MutableStateFlow<SoftUpdateState?>(null)
    val softUpdateState: StateFlow<SoftUpdateState?> = _softUpdateState.asStateFlow()

    @Volatile
    private var hasDismissedSoftDialogThisSession = false
    @Volatile
    private var lastConfigFetchElapsed = 0L

    companion object {
        private const val CONFIG_FETCH_THROTTLE_MS = 15 * 60 * 1000L // 15 mins
    }

    init {
        checkAppConfig(isResume = false)
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
                    message = "Please check your internet connection",
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
                    startLiveExpiryChecks(info.expiresAtEpochMs)
                }
                "expired" -> {
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Key has expired",
                        isError = true
                    )
                }
                "revoked" -> {
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Key has been revoked. Please contact admin",
                        isError = true
                    )
                }
                else -> {
                    _gateState.value = AuthGateState.NeedKey(
                        message = "Please enter your access key",
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
                startLiveExpiryChecks(cached.expiresAtEpochMs)
            } else {
                _gateState.value = AuthGateState.Error(
                    message = "Could not verify access. Please check your internet connection.",
                    onRetry = { checkInitialAuthState() }
                )
            }
        }
    }

    fun setPlaybackPauseAction(action: () -> Unit) {
        this.onPausePlayback = action
    }

    fun setPlaybackActiveSupplier(supplier: () -> Boolean) {
        this.isPlaybackActiveSupplier = supplier
    }

    private fun isPlaybackActive(): Boolean {
        return try {
            isPlaybackActiveSupplier?.invoke() == true
        } catch (_: Exception) {
            false
        }
    }

    fun startLiveExpiryChecks(expiresAtEpochMs: Long?) {
        scheduleExactTimeCheck(expiresAtEpochMs)
        startPeriodicCheck()
    }

    fun stopLiveExpiryChecks() {
        exactTimeCheckJob?.cancel()
        exactTimeCheckJob = null
        periodicCheckJob?.cancel()
        periodicCheckJob = null
    }

    private fun scheduleExactTimeCheck(expiresAtEpochMs: Long?) {
        exactTimeCheckJob?.cancel()
        exactTimeCheckJob = null
        if (expiresAtEpochMs == null) return

        val estimatedServerNow = profileAndKeyRepository.getEstimatedServerTimeMs()
        val delayMs = expiresAtEpochMs - estimatedServerNow

        exactTimeCheckJob = viewModelScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (_gateState.value is AuthGateState.Ready) {
                performEntitlementCheck(forceRefresh = true)
            }
        }
    }

    private fun startPeriodicCheck() {
        if (periodicCheckJob?.isActive == true) return
        periodicCheckJob = viewModelScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // 5 minutes
                if (_gateState.value !is AuthGateState.Ready) break
                val shouldCheck = isAppInForeground || isPlaybackActive()
                if (shouldCheck) {
                    performEntitlementCheck(forceRefresh = false)
                }
            }
        }
    }

    suspend fun performEntitlementCheck(forceRefresh: Boolean) {
        if (_gateState.value !is AuthGateState.Ready) return
        if (!checkMutex.tryLock()) return // Prevent concurrent overlap

        try {
            val session = authRepository.getStoredSession() ?: return
            val result = profileAndKeyRepository.checkEntitlement(session.accessToken, forceRefresh = forceRefresh)
            if (result.isSuccess) {
                val info = result.getOrThrow()
                when (info.status) {
                    "revoked" -> {
                        Log.w(TAG, "Entitlement revoked, pausing playback and locking gate")
                        onPausePlayback?.invoke()
                        stopLiveExpiryChecks()
                        _gateState.value = AuthGateState.NeedKey(
                            message = "Key has been revoked. Please contact admin",
                            isError = true
                        )
                    }
                    "expired" -> {
                        Log.w(TAG, "Entitlement expired, pausing playback and locking gate")
                        onPausePlayback?.invoke()
                        stopLiveExpiryChecks()
                        _gateState.value = AuthGateState.NeedKey(
                            message = "Key has expired",
                            isError = true
                        )
                    }
                    "active" -> {
                        scheduleExactTimeCheck(info.expiresAtEpochMs)
                    }
                }
            } else {
                // Failure mode: NEVER treat network error or timeout as "expired".
                // UNLESS offline and monotonic estimated server time has exceeded cached expires_at:
                val cached = profileAndKeyRepository.getCachedEntitlement()
                if (cached != null && cached.expiresAtEpochMs != null) {
                    val estimatedServerNow = profileAndKeyRepository.getEstimatedServerTimeMs()
                    if (estimatedServerNow >= cached.expiresAtEpochMs) {
                        Log.w(TAG, "Offline estimated server time exceeded expires_at, locking gate")
                        onPausePlayback?.invoke()
                        stopLiveExpiryChecks()
                        _gateState.value = AuthGateState.NeedKey(
                            message = "Key has expired",
                            isError = true
                        )
                    }
                }
            }
        } finally {
            checkMutex.unlock()
        }
    }

    /**
     * Called on App RESUME. Max once per 15 minutes throttled inside repository.
     * onRevokedOrExpired callback allows pausing playback.
     */
    fun onAppResume(onRevokedOrExpired: (() -> Unit)? = null) {
        if (onRevokedOrExpired != null) {
            this.onPausePlayback = onRevokedOrExpired
        }
        isAppInForeground = true

        // Always verify update requirements on resume (15-min throttled)
        checkAppConfig(isResume = true)

        if (_gateState.value !is AuthGateState.Ready) return

        viewModelScope.launch {
            performEntitlementCheck(forceRefresh = false)
        }
    }

    /**
     * Checks remote app configuration and evaluates update policy (Hard/Soft/None).
     * Cold start: decides immediately from local cache, then launches background fetch.
     * Resume: throttled to 15-minute intervals.
     */
    fun checkAppConfig(isResume: Boolean = false) {
        // 1. Immediate decision from cached config
        val (cachedDecision, cachedConfig) = appConfigRepository.evaluatePolicy(null)
        if (cachedDecision is UpdatePolicyDecision.Hard && cachedConfig != null) {
            _hardUpdateState.value = HardUpdateState(cachedConfig, cachedDecision.reason)
            _softUpdateState.value = null
            onPausePlayback?.invoke()
        } else if (cachedDecision is UpdatePolicyDecision.Soft && cachedConfig != null && !hasDismissedSoftDialogThisSession) {
            _softUpdateState.value = SoftUpdateState(cachedConfig, cachedDecision.skipsLeft)
        }

        // 2. Fresh background fetch with 15-min throttle on resume
        val now = SystemClock.elapsedRealtime()
        if (isResume && (now - lastConfigFetchElapsed) < CONFIG_FETCH_THROTTLE_MS && lastConfigFetchElapsed != 0L) {
            return
        }
        lastConfigFetchElapsed = now

        viewModelScope.launch {
            val result = appConfigRepository.fetchAppConfig()
            if (result.isSuccess) {
                val freshConfig = result.getOrNull()
                val (freshDecision, config) = appConfigRepository.evaluatePolicy(freshConfig)
                if (freshDecision is UpdatePolicyDecision.Hard && config != null) {
                    _hardUpdateState.value = HardUpdateState(config, freshDecision.reason)
                    _softUpdateState.value = null
                    onPausePlayback?.invoke()
                } else if (freshDecision is UpdatePolicyDecision.Soft && config != null && !hasDismissedSoftDialogThisSession) {
                    _softUpdateState.value = SoftUpdateState(config, freshDecision.skipsLeft)
                } else if (freshDecision is UpdatePolicyDecision.None) {
                    _hardUpdateState.value = null
                    _softUpdateState.value = null
                }
            }
        }
    }

    /**
     * Called when the user clicks "Baad me" in the soft update dialog.
     * Increments the skip counter for the target version, dismisses dialog for this session,
     * and transitions to Hard block if skips are now exhausted.
     */
    fun onDismissSoftUpdate() {
        val currentSoft = _softUpdateState.value ?: return
        appConfigRepository.incrementSkips(currentSoft.config.latestVersionCode)
        hasDismissedSoftDialogThisSession = true
        _softUpdateState.value = null

        val (decision, config) = appConfigRepository.evaluatePolicy(currentSoft.config)
        if (decision is UpdatePolicyDecision.Hard && config != null) {
            _hardUpdateState.value = HardUpdateState(config, decision.reason)
            onPausePlayback?.invoke()
        }
    }

    /**
     * Called when the user clicks "Update Now" in the soft update dialog.
     */
    fun onAcceptSoftUpdate() {
        hasDismissedSoftDialogThisSession = true
        _softUpdateState.value = null
    }

    /**
     * Exposes playback pause to updater UI components.
     */
    fun pausePlayback() {
        onPausePlayback?.invoke()
    }

    fun onAppPause() {
        isAppInForeground = false
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveExpiryChecks()
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
                            message = "Please verify your email with OTP first"
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
                            message = "Verification code has been sent to your email"
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
                            error = "Invalid or expired code",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.RateLimited -> {
                        _gateState.value = curr.copy(
                            error = "Too many requests. Please try again later",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Offline -> {
                        _gateState.value = curr.copy(
                            error = "Please check your internet connection",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Timeout -> {
                        _gateState.value = curr.copy(
                            error = "Server is not responding. Please try again",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Server5xx -> {
                        _gateState.value = curr.copy(
                            error = "Server error (HTTP ${res.code}). Please try again later",
                            message = null,
                            isLoading = false
                        )
                    }
                    is VerifyOtpResult.Unknown -> {
                        _gateState.value = curr.copy(
                            error = res.message.ifEmpty { "Verification failed" },
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
                            message = "Code resent successfully",
                            error = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.RateLimited -> {
                        startResendCooldown(60)
                        _gateState.value = curr.copy(
                            error = "Too many requests. Please try again later",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Offline -> {
                        _gateState.value = curr.copy(
                            error = "Please check your internet connection",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Timeout -> {
                        _gateState.value = curr.copy(
                            error = "Server is not responding. Please try again",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Server5xx -> {
                        _gateState.value = curr.copy(
                            error = "Server error (HTTP ${res.code}). Please try again later",
                            message = null,
                            isLoading = false
                        )
                    }
                    is ResendOtpResult.Unknown -> {
                        _gateState.value = curr.copy(
                            error = res.message.ifEmpty { "Failed to send OTP" },
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
                            startLiveExpiryChecks(entResult.getOrThrow().expiresAtEpochMs)
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
                        startLiveExpiryChecks(redeem.expiresAtEpochMs)
                    } else {
                        _gateState.value = AuthGateState.NeedKey(
                            message = redeem.message,
                            isError = true,
                            isLoading = false
                        )
                    }
                } else {
                    _gateState.value = AuthGateState.NeedKey(
                        message = res.exceptionOrNull()?.message ?: "Error verifying key",
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

    fun getCachedNickname(): String {
        return profileAndKeyRepository.getCachedNickname()
            ?: userProfilePrefs.getDisplayName().takeIf { it.isNotBlank() }
            ?: "NotiFy User"
    }

    fun getStoredEmail(): String {
        return authRepository.getStoredSession()?.email.orEmpty()
    }

    fun isGoogleUser(): Boolean {
        return authRepository.isGoogleUser()
    }

    fun getKeyStatusDescription(): String {
        val info = profileAndKeyRepository.getCachedEntitlement() ?: return "Key: None"
        if (info.expiresAtEpochMs == null) {
            return "Key: Permanent"
        }
        val estimatedServerNow = profileAndKeyRepository.getEstimatedServerTimeMs()
        val remainingMs = info.expiresAtEpochMs - estimatedServerNow
        if (remainingMs <= 0) {
            return "Key: Expired"
        }
        val days = (remainingMs / (24 * 3600 * 1000L)).toInt()
        val hours = (remainingMs / (3600 * 1000L)).toInt()
        return when {
            days == 0 -> if (hours <= 1) "Key: Expires in 1 hour" else "Key: $hours hours left"
            days == 1 -> "Key: Expires tomorrow"
            else -> "Key: $days days left"
        }
    }

    suspend fun updateKeyFromDrawer(code: String): Result<RedeemResult> {
        val session = authRepository.getStoredSession()
            ?: return Result.failure(Exception("Not authenticated"))
        val cleanCode = code.trim().uppercase().replace("\\s+".toRegex(), "")
        val res = profileAndKeyRepository.redeemKey(cleanCode, session.accessToken)
        if (res.isSuccess) {
            val redeem = res.getOrThrow()
            if (redeem.code == "ok") {
                startLiveExpiryChecks(redeem.expiresAtEpochMs)
            }
        }
        return res
    }

    sealed interface ChangePasswordResult {
        data object Success : ChangePasswordResult
        data class Error(val message: String) : ChangePasswordResult
    }

    suspend fun changePassword(
        currentPass: String,
        newPass: String,
        confirmPass: String
    ): ChangePasswordResult {
        if (currentPass.isBlank()) {
            return ChangePasswordResult.Error("Please enter your current password")
        }
        if (newPass.length < 6) {
            return ChangePasswordResult.Error("Password must be at least 6 characters")
        }
        if (newPass != confirmPass) {
            return ChangePasswordResult.Error("New password and confirm password do not match")
        }
        if (newPass == currentPass) {
            return ChangePasswordResult.Error("New password must be different from current password")
        }

        val email = getStoredEmail()
        if (email.isBlank()) {
            return ChangePasswordResult.Error("User email not found")
        }

        // Verify current password via re-sign-in
        when (val loginRes = authRepository.signInWithEmail(email, currentPass)) {
            is SignInResult.Success -> {
                val updateRes = authRepository.updateUserPassword(newPass, loginRes.session.accessToken)
                return if (updateRes.isSuccess) {
                    ChangePasswordResult.Success
                } else {
                    ChangePasswordResult.Error(updateRes.exceptionOrNull()?.message ?: "Failed to change password")
                }
            }
            is SignInResult.Error -> {
                return ChangePasswordResult.Error("Incorrect current password")
            }
            is SignInResult.NeedOtp -> {
                return ChangePasswordResult.Error("Email verification is pending")
            }
        }
    }

    sealed interface ResetPasswordResult {
        data object Success : ResetPasswordResult
        data class Error(val message: String) : ResetPasswordResult
    }

    suspend fun sendPasswordRecovery(email: String): Result<Unit> {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            return Result.failure(Exception("Please enter your email"))
        }
        val res = authRepository.sendPasswordRecovery(cleanEmail)
        if (res.isSuccess) {
            startResendCooldown(60)
        }
        return res
    }

    suspend fun verifyRecoveryAndResetPassword(
        email: String,
        token: String,
        newPass: String,
        confirmPass: String
    ): ResetPasswordResult {
        if (token.trim().length < 6) {
            return ResetPasswordResult.Error("Please enter verification code")
        }
        if (newPass.length < 6) {
            return ResetPasswordResult.Error("Password must be at least 6 characters")
        }
        if (newPass != confirmPass) {
            return ResetPasswordResult.Error("New password and confirm password do not match")
        }

        when (val verifyRes = authRepository.verifyRecoveryOtp(email, token)) {
            is VerifyOtpResult.Success -> {
                val updateRes = authRepository.updateUserPassword(newPass, verifyRes.session.accessToken)
                return if (updateRes.isSuccess) {
                    resolveProfileAndEntitlement(verifyRes.session)
                    ResetPasswordResult.Success
                } else {
                    ResetPasswordResult.Error(updateRes.exceptionOrNull()?.message ?: "Failed to set new password")
                }
            }
            is VerifyOtpResult.OtpInvalidOrExpired -> {
                return ResetPasswordResult.Error("Invalid or expired code")
            }
            is VerifyOtpResult.RateLimited -> {
                return ResetPasswordResult.Error("Email limit reached. Please try again later")
            }
            is VerifyOtpResult.Offline -> {
                return ResetPasswordResult.Error("Please check your internet connection and try again")
            }
            is VerifyOtpResult.Timeout -> {
                return ResetPasswordResult.Error("Server is not responding. Please try again")
            }
            is VerifyOtpResult.Server5xx -> {
                return ResetPasswordResult.Error("Server error (${verifyRes.code}). Please try again later")
            }
            is VerifyOtpResult.Unknown -> {
                return ResetPasswordResult.Error(verifyRes.message)
            }
        }
    }

    fun logout() {
        stopLiveExpiryChecks()
        onPausePlayback?.invoke()

        val session = authRepository.getStoredSession()
        viewModelScope.launch {
            try {
                authRepository.signOut(session?.accessToken)
            } catch (e: Exception) {
                Log.w(TAG, "Error during signOut network call", e)
            } finally {
                profileAndKeyRepository.clearLocalData()
                authRepository.clearLocalSession()
                _gateState.value = AuthGateState.NeedLogin()
            }
        }
    }
}
