# NotiFy — Architecture Decisions

> **Evidence Standard:** Every decision is marked VERIFIED (commit msg / code doc confirms reason)
> or INFERRED (reason deduced from code structure / commit context, not explicitly stated).
> Claims without evidence are marked UNKNOWN / NOT VERIFIED.
>
> Format: `File:Line` links are repo-relative.

---

## D-001: Parallel-Race Stream Resolution Architecture

**Decision:** FastInnerTube, SoundCloud, JioSaavn, Deezer race concurrently. First valid winner returned. yt-dlp runs as last-resort fallback outside the race.

**Evidence (VERIFIED):**
- Class Javadoc: `ResolvedStreamProviderChain.kt` L33–L54 explicitly documents the "Parallel-Race architecture" and its invariants.
- Commit `f95a820` — "fix(stream): enforce upper duration tolerance and title modifier filter in parallel race (FIX A)" — confirms race architecture predates auth work.

**Reason (VERIFIED):** "High-performance composite stream resolver" targeting "sub-1s resolution with a 2.0s race cap" — stated in Javadoc L38–L39.

**Trade-off noted (INFERRED):** 2–4 concurrent HTTP calls per resolution (~2–5 KB each). Documented in Javadoc L51–L53 as DATA-SAVER consideration for future toggle.

---

## D-002: Cobalt Resolver = Disabled

**Decision:** `isCobaltEnabled = false` hardcoded in `ResolvedStreamProviderChain.kt` L334.

**Evidence (VERIFIED):** Code comment L332–L333: "temporarily disabled due to Cobalt v10 API changes & Cloudflare Turnstile blocks; saves 500-1200ms per attempt and removes circuit breaker overhead".

**Status:** Temporary; no re-enable timeline committed.

---

## D-003: yt-dlp Runs On-Device (Not Remote Backend)

**Decision:** Stream fallback uses `youtubedl-android` library executing on-device Python/yt-dlp runtime.

**Evidence (VERIFIED):**
- Commit `20c4488` message: "revert(stream): restore local youtubedl-android execution for OnlineStreamResolver and remove remote yt-dlp backend config".
- `backend-ytdlp/` folder exists but Android app does NOT call it.

**Reason (INFERRED from commit):** Remote backend was unreliable / added network dependency. On-device execution more resilient.

---

## D-004: AuthGate State Machine (Sealed Interface)

**Decision:** Auth flow modeled as `sealed interface AuthGateState` with 6 states: Loading, NeedLogin, NeedNickname, NeedKey, Ready, Error.

**Evidence (VERIFIED):**
- `AuthGateViewModel.kt` L15–L26: sealed interface definition.
- Commit `5a53694` message: "feat(auth): implement AuthGate state machine, navigation gate, and onResume entitlement re-check with playback pause".

**Reason (VERIFIED — commit message):** Single state machine for auth flow, prevents partial auth states in UI. onResume re-check ensures license revocation takes effect on app foreground.

---

## D-005: Auth Token in Plain SharedPreferences

**Decision:** Access token, refresh token, user ID stored in `SharedPreferences` with `MODE_PRIVATE`.

**Evidence (VERIFIED):**
- `SupabaseAuthRepository.kt` L18–L23: constants `PREFS_NAME = "notify_auth_prefs"`, key constants.
- `SupabaseAuthRepository.kt` L275–L283: `saveSession()` calls `prefs.edit().putString(...)`.

**Reason (INFERRED):** Simplest persistence mechanism. Android Keystore encryption not implemented.

**Security Risk:** NOTED — see `known-issues.md` ISSUE-005.

---

## D-006: allowBackup = true Without Exclusion Rules

**Decision:** `AndroidManifest.xml` L30: `android:allowBackup="true"`. No `fullBackupContent` or `dataExtractionRules` exclusion file referenced.

**Evidence (VERIFIED):**
- `AndroidManifest.xml` L30: `android:allowBackup="true"`.
- grep search for `fullBackupContent`, `dataExtractionRules`, `backup_rules`: no results found.

**Reason (INFERRED):** Default value not changed. Auth prefs backup exclusion not implemented.

**Security Risk:** NOTED — see `known-issues.md` ISSUE-006.

---

## D-007: onAppResume Re-check = Throttled by Repository

**Decision:** `AuthGateViewModel.onAppResume()` calls `checkEntitlement(forceRefresh = false)` — throttled inside `SupabaseProfileAndKeyRepository`.

**Evidence (VERIFIED):**
- `AuthGateViewModel.kt` L137–L163: `onAppResume()` implementation.
- Comment L134: "Max once per 15 minutes throttled inside repository."
- `AuthGateViewModel.kt` L138: guard `if (_gateState.value !is AuthGateState.Ready) return` — only re-checks when app is in Ready state.

**Reason (VERIFIED — code comment):** Prevents excessive Supabase RPC calls on every app resume. Throttle: 15 minutes.

---

## D-008: Google Sign-In = Currently Disabled (Placeholder)

**Decision:** `GOOGLE_WEB_CLIENT_ID = "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"` in `SupabaseConfig.kt` L12.

**Evidence (VERIFIED):**
- `SupabaseConfig.kt` L12: placeholder value.
- `LoginScreen.kt` L83–L86: `isGoogleConfigured` check at runtime.
- Commit `3896f34`: "feat(auth): disable Google Sign-In button when client ID is placeholder and default to email login".

**Reason (VERIFIED — commit message):** Google OAuth not yet configured. Button auto-hides when placeholder detected.

---

## D-009: Duration-Mismatch Validation (±20% tolerance)

**Decision:** Stream providers' results validated against expected duration. Rejected if outside ±20% or <= 35s when expected > 60s (snippet detection).

**Evidence (VERIFIED):**
- `ResolvedStreamProviderChain.kt` L491–L538: `validateDuration()` function.
- L522–L529: tolerance formula and snippet check.
- Commit `87b6152`: "fix(stream): validate stream duration against expected track duration to prevent short snippet matches".
- Commit `f95a820`: "fix(stream): enforce upper duration tolerance and title modifier filter in parallel race (FIX A)".

**Reason (VERIFIED — commit messages):** Prevents wrong-version tracks (previews, live versions, sped-up remixes) from playing.

---

## D-010: Android Auto = Same PlaybackQueueCoordinator (No Separate Session)

**Decision:** Android Auto media tree browsing goes through `AndroidAutoMediaTreeProvider.kt`, but actual playback uses the same `PlaybackQueueCoordinator` singleton.

**Evidence (VERIFIED):**
- `PlaybackQueueCoordinator.kt` L126: `private val mediaTreeProvider = AndroidAutoMediaTreeProvider(context, database, ioDispatcher)`.
- `AndroidAutoMediaTreeProvider.kt` L34–L46: class Javadoc — browse tree defined, playback delegated to coordinator.
- Commit `dcd07cf`: "feat: add Android Auto support".

**Reason (INFERRED):** Single coordinator singleton ensures queue state remains consistent between phone and Auto.

**Security Gap:** NOTED — see `known-issues.md` ISSUE-007.

---

## D-011: Snackbar Architecture = Singleton SharedFlow

**Decision:** App-wide snackbar events broadcast via `SnackbarManager` singleton using `MutableSharedFlow`.

**Evidence (VERIFIED):**
- `SnackbarManager.kt` L65–L93: singleton object with `replay=0, extraBufferCapacity=4`.
- `NotiFyApp.kt` L73–L151: collects events and renders `NotiFySnackbar`.

**Reason (VERIFIED — code comment):** `SnackbarManager.kt` L62–L64: "replay=0 + extraBufferCapacity=4 policy ensures events are not silently lost even if the collector is momentarily suspended, but old stale events are not re-shown."

---

## D-012: Supabase Admin Functions = service_role Only

**Decision:** `admin_generate_keys`, `admin_revoke_key`, `admin_revoke_user`, `admin_extend_user` callable only by `service_role`.

**Evidence (VERIFIED):**
- `admin.sql` L83: `REVOKE ALL ON FUNCTION public.admin_generate_keys(INT, INT, TEXT) FROM PUBLIC, anon, authenticated;`
- `admin.sql` L84: `GRANT EXECUTE ON FUNCTION public.admin_generate_keys(INT, INT, TEXT) TO service_role;`
- Same pattern repeats for all 4 admin functions.

**Reason (VERIFIED — code comment):** `admin.sql` L3–L4: "These functions are for ADMINS ONLY. Permissions are strictly REVOKED from 'anon' and 'authenticated' users."

---

## D-013: Telegram Bot = Webhook with HMAC Secret

**Decision:** `telegram-admin` edge function deployed with `--no-verify-jwt`. Security enforced via `X-Telegram-Bot-Api-Secret-Token` header validation + sender ID check.

**Evidence (VERIFIED):**
- `telegram-admin/index.ts` L5–L9: reads `TELEGRAM_BOT_TOKEN`, `ADMIN_TELEGRAM_ID`, `WEBHOOK_SECRET` from Deno.env.
- `SETUP.md` L168–L173: explains why `--no-verify-jwt` is needed and how security is maintained.

**Reason (VERIFIED — SETUP.md L168–L169):** "Telegram webhook requests do not carry Supabase user JWT. Security gate is enforced via X-Telegram-Bot-Api-Secret-Token header and sender ID comparison."

---

## D-014: Radio Window = Rolling 4-Track Window

**Decision:** `RadioWindowManager` maintains: current track + up to 3 future tracks (total window = 4).

**Evidence (VERIFIED):**
- `RadioWindowManager.kt` L37–L53: class Javadoc.
- L68: `const val MAX_FUTURE_TRACKS = 3`.
- Commit `ebcc591`: "fix(playback): resolve radio recommendation exhaustion, downloaded track seed, and resumption re-arm (FIX B)".

**Reason (INFERRED):** Balance between pre-buffering latency and track uniqueness/deduplication.

---

## D-015: Deezer = 30s Preview Only (Not Full Track)

**Decision:** Deezer edge function (`resolve-deezer/index.ts`) returns only the 30-second preview URL from Deezer public API.

**Evidence (VERIFIED):**
- `resolve-deezer/index.ts` L95–L111: parses `track.preview` URL.
- L111: `durationMs: 30000` hardcoded in response.
- L107: `formatId: "deezer_preview_mp3_128"`.

**Reason (INFERRED):** Deezer public API does not provide full-length streams without authentication/subscription.

**Implication:** Deezer will always fail duration validation for tracks > ~35s (validateDuration snippet check at L529 will reject if expected > 60s and stream <= 35s).

---

## D-016: JioSaavn Routing = Indic Script + Known Artist Dictionary

**Decision:** JioSaavn resolver only included in parallel race if query is Indic or artist is in known Indian artist dictionary. Deezer skipped for Indic routes.

**Evidence (VERIFIED):**
- `ProviderRoutingPolicy.kt` L9–L147: `INDIC_SCRIPT_REGEX`, `KNOWN_INDIAN_ARTISTS` set.
- `ResolvedStreamProviderChain.kt` L349–L365: routing decision and logging.
- Commit `659a8de`: mentions "sanitize artist placeholders" (context for routing improvements).

**Reason (VERIFIED — code comment):** `ProviderRoutingPolicy.kt` L6–L8: "Prevents unnecessary network calls (e.g. to JioSaavn for non-Indian catalog tracks)."
