# NotiFy — Known Issues

> **Evidence Standard:** VERIFIED = confirmed by direct code inspection with file:line.
> UNKNOWN / NOT VERIFIED = cannot confirm without runtime/DB access.
>
> **Rule:** Do NOT fix here. Use this file only to document. Each fix needs a separate task + commit.

---

## ISSUE-001: HTTP 4xx/5xx Auth Errors Show Misleading "Internet Check Karo" Message

**Status:** VERIFIED

**Description:** When Supabase auth endpoints return an HTTP error (401, 403, 422, etc.), the code correctly extracts and translates the error message. However, for `IOException` (network-level failures), the generic message "Internet check karein aur dobara try karein" is shown — which is correct in that case. The potential misleading case is when the server returns a non-2xx status but the error body parsing falls through to a fallback message.

**Evidence:**
- `SupabaseAuthRepository.kt` L107–L109: HTTP error -> `parseErrorMessage(body, "Email ya password galat hai")` — this is generally correct (server returned error body).
- `SupabaseAuthRepository.kt` L116–L118: `IOException` -> "Internet check karein aur dobara try karein" — correct for network errors.
- `SupabaseAuthRepository.kt` L119–L121: Other exceptions show raw exception message — may be opaque.
- `SupabaseProfileAndKeyRepository.kt` L147–L149: `IOException` on saveProfileNickname -> "Internet connection check karein".
- **Actual misleading scenario:** `AuthGateViewModel.kt` L83–L86: On PROFILE FETCH failure (any error) -> "Internet connection check karein" shown as `AuthGateState.Error`. This includes server errors (e.g. RLS deny), not just network issues.
- `AuthGateViewModel.kt` L124–L128: On entitlement check failure (any error) -> "Internet check karein." even if server returned 401/500.

**Impact:** User may think their internet is down when actually the server returned an error.

**Affected Files:**
- `AuthGateViewModel.kt` L83–L86, L124–L128
- `SupabaseProfileAndKeyRepository.kt` L149

**Fix Direction (DO NOT IMPLEMENT WITHOUT TASK):** Differentiate `IOException` from `HttpException` (server error). Show server-specific message for 4xx/5xx.

---

## ISSUE-002: LoginScreen Sign-Up Mode Reset Bug

**Status:** VERIFIED (by code analysis) — runtime behavior NOT verified live

**Description:** `LoginScreen.kt` uses local Compose state `isSignUpMode` (L97) and `showEmailForm` (L98). When `isLoading = true` triggers from `AuthGateViewModel`, the `gateState` changes to `AuthGateState.NeedLogin(isLoading = true)` which causes Crossfade recomposition (`AuthGate.kt` L45). Because `LoginScreen` is re-invoked with fresh composition (Crossfade creates new composition for each `targetState`), local `remember` states reset: `isSignUpMode` reverts to `false` and `showEmailForm` reverts to `!isGoogleConfigured`.

**Evidence:**
- `AuthGate.kt` L45: `Crossfade(targetState = gateState)` — each distinct state object creates a new composition branch.
- `AuthGateViewModel.kt` L165–L186: `signInWithEmail()` sets `_gateState.value = AuthGateState.NeedLogin(isLoading = true)` BEFORE the coroutine result.
- `AuthGateViewModel.kt` L188–L209: Same pattern for `signUpWithEmail()`.
- `LoginScreen.kt` L97: `var isSignUpMode by remember { mutableStateOf(false) }` — resets on recomposition from new Crossfade state.

**Trigger:** User enters Sign-Up mode, fills password field, taps submit. Loading state shows briefly, then error returned -> `NeedLogin(isLoading = false, error = "...")` is a NEW state object -> Crossfade recomposes -> `isSignUpMode` resets to `false` -> user is now in Sign-In mode.

**Impact:** UX confusion — user's form mode switches unexpectedly on error.

**Affected Files:** `AuthGate.kt` L45, `AuthGateViewModel.kt` L168, `LoginScreen.kt` L97

**Fix Direction (DO NOT IMPLEMENT):** Move `isSignUpMode` to `AuthGateState.NeedLogin` or use `key()` in Crossfade to preserve state across same-state transitions.

---

## ISSUE-003: "Google disabled" Log Fires on Every LoginScreen Composition

**Status:** VERIFIED

**Description:** `LoginScreen.kt` L88–L92 uses `LaunchedEffect(isGoogleConfigured)`. Since `isGoogleConfigured` is computed via `remember { ... }` on a constant (`SupabaseConfig.GOOGLE_WEB_CLIENT_ID` is a compile-time constant = placeholder), the value is always `false` and never changes. `LaunchedEffect(false)` fires once per composition entry. Because `AuthGate.kt` Crossfade can retrigger LoginScreen recomposition, this log may fire multiple times per session.

**Evidence:**
- `LoginScreen.kt` L83–L92: `isGoogleConfigured = remember { ... }` always evaluates to `false`.
- `LoginScreen.kt` L88: `LaunchedEffect(isGoogleConfigured)` — key never changes, but Crossfade may reconstruct.
- `SupabaseConfig.kt` L12: `GOOGLE_WEB_CLIENT_ID = "YOUR_GOOGLE_WEB_CLIENT_ID..."` — placeholder.

**Impact:** Log noise. Not a functional bug. Low priority until Google Sign-In is actually configured.

**Affected Files:** `LoginScreen.kt` L88–L92

---

## ISSUE-004: Auth Token in Plain SharedPreferences (No Keystore Encryption)

**Status:** VERIFIED

**Description:** Supabase access token, refresh token, and user ID stored in `SharedPreferences` (`notify_auth_prefs`) without Android Keystore encryption.

**Evidence:**
- `SupabaseAuthRepository.kt` L18: `private const val PREFS_NAME = "notify_auth_prefs"`
- `SupabaseAuthRepository.kt` L32–L33: `context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)`
- `SupabaseAuthRepository.kt` L275–L283: `saveSession()` writes tokens as plain strings.

**Impact:** On rooted devices, SharedPreferences XML file is accessible. Tokens can be extracted. Severity depends on token lifetime (Supabase default: 1 hour access token, longer refresh token).

**Fix Direction (DO NOT IMPLEMENT):** Use `EncryptedSharedPreferences` from AndroidX Security library with Android Keystore-backed key.

---

## ISSUE-005: AndroidManifest allowBackup = true, notify_auth_prefs Not Excluded

**Status:** VERIFIED

**Description:** `android:allowBackup="true"` in `AndroidManifest.xml` L30 without any `fullBackupContent` or `dataExtractionRules` XML that excludes `notify_auth_prefs`. This means Google's Auto Backup may include auth tokens in device backups.

**Evidence:**
- `AndroidManifest.xml` L30: `android:allowBackup="true"` — confirmed.
- grep for `fullBackupContent`, `dataExtractionRules`, `backup_rules`: no results in project.
- `SupabaseAuthRepository.kt` L18: `PREFS_NAME = "notify_auth_prefs"` — not excluded from backup.

**Impact:** Auth tokens backed up to Google Drive backup. If user restores on new device, stale tokens may persist. Security risk if backup is compromised.

**Fix Direction (DO NOT IMPLEMENT):** Create `res/xml/backup_rules.xml` excluding `notify_auth_prefs` and reference in Manifest with `android:fullBackupContent` / `android:dataExtractionRules`.

---

## ISSUE-006: Android Auto Does NOT Check AuthGate

**Status:** VERIFIED (code analysis) — live Auto environment NOT tested

**Description:** `NotiFyPlaybackService` (exposed via `MediaBrowserService` intent-filter in `AndroidManifest.xml` L58) is directly accessible to Android Auto without going through `AuthGate`. The `AuthGate` is a Compose UI layer in `MainActivity`, which Android Auto does not invoke.

**Evidence:**
- `AndroidManifest.xml` L52–L60: `NotiFyPlaybackService` exported with `MediaBrowserService` intent filter.
- `MainActivity.kt` L78–L94: `AuthGate` wraps `NotiFyApp` only within Activity `setContent`.
- `AndroidAutoMediaTreeProvider.kt` L47–L64: No auth check in constructor or any method.
- `PlaybackQueueCoordinator.kt` L86–L118: No auth state dependency.

**Impact:** A user whose license was revoked could potentially continue playback via Android Auto without the gate blocking them. The `onResume()` re-check only fires when the phone Activity resumes.

**Affected Files:** `AndroidAutoMediaTreeProvider.kt`, `PlaybackQueueCoordinator.kt`

**Fix Direction (DO NOT IMPLEMENT):** Add entitlement check in `NotiFyPlaybackService.onGetSession()` or in `AndroidAutoMediaTreeProvider` before serving content.

---

## ISSUE-007: schema.sql Applied to Supabase — Status Unknown

**Status:** UNKNOWN / NOT VERIFIED

**Description:** Cannot verify from local source code alone whether `supabase/schema.sql`, `admin.sql`, and `telegram_admin_setup.sql` have been executed against the live Supabase project (`pnwoccbrpcihjhjmujfb`). Database state requires direct Supabase Dashboard or SQL Editor access to confirm table existence.

**Evidence needed:** Query `information_schema.tables` on live Supabase project to verify `profiles`, `license_keys`, `key_attempts`, `processed_updates`, `admin_log`, `pending_confirmations` tables exist.

**Impact:** If schema NOT applied: all auth flows (`get_entitlement`, `redeem_key`) will fail with 404/500.

---

## ISSUE-008: Deezer Resolver Always Fails Duration Validation for Short-Preview Tracks

**Status:** VERIFIED (by logic analysis)

**Description:** Deezer edge function always returns `durationMs: 30000` (30 seconds). `validateDuration()` in `ResolvedStreamProviderChain.kt` L529 rejects any stream where `expectedDurationMs > 60000 AND streamDuration <= 35000`. Therefore Deezer will ALWAYS be rejected for any track longer than ~60s.

**Evidence:**
- `resolve-deezer/index.ts` L111: `durationMs: 30000` — hardcoded.
- `ResolvedStreamProviderChain.kt` L529: `val isSnippet = expectedDurationMs > 60_000L && streamDuration <= 35_000L`.
- `ResolvedStreamProviderChain.kt` L531: `if (streamDuration < minAllowedMs || ... || isSnippet) { return false }`.

**Impact:** Deezer's contribution to the parallel race is effectively zero for any full-length track. Wasted network slot. Race still works (other providers win), but Deezer slot is burning 1500ms trying.

**Fix Direction (DO NOT IMPLEMENT):** Either remove Deezer from the race, or adjust Deezer to indicate it is a preview/fallback quality tier and only use it if all other providers failed.

---

## ISSUE-009: Smart Shuffle Insert Keys Not Cleared on Session End

**Status:** UNKNOWN / NOT VERIFIED

**Description:** `smartShuffleInsertedKeys` (set at `PlaybackQueueCoordinator.kt` L181) is cleared in `playQueue()` L355. However, it is unclear if there are edge cases (e.g. service restart mid-session) where the set could persist stale keys. Not verified under all lifecycle scenarios.

**Evidence:**
- `PlaybackQueueCoordinator.kt` L181: `private val smartShuffleInsertedKeys = mutableSetOf<String>()`.
- L355: `smartShuffleInsertedKeys.clear()` inside `playQueue()`.
- Service detach at L295–L308 does NOT clear `smartShuffleInsertedKeys`.

**Impact (if issue exists):** Smart shuffle tracks may not be re-inserted after service restart + same queue restore.

---

## ISSUE-010: Consecutive Resolve Failure Counter Not Reset on Manual Next

**Status:** UNKNOWN / NOT VERIFIED

**Description:** `consecutiveResolveFailures` (L194) is reset in `playQueue()`. It is unclear if navigating manually to next/previous track resets this counter, potentially triggering premature auto-skip after user manually skips past a failed track.

**Evidence:**
- `PlaybackQueueCoordinator.kt` L194: `@Volatile private var consecutiveResolveFailures = 0`.
- L379: Reset in `playQueue()`.
- Manual skip path not fully traced in this audit.

**Impact:** UNKNOWN — needs runtime testing.
