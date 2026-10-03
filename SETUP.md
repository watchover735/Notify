# NotiFy Backend & Auth Setup Guide (Manual Steps)

Ye guide Rahul ke liye hai. In steps ko follow karke Supabase SQL, Google Cloud OAuth aur Supabase Auth configure karein.

---

## 1. Supabase SQL Schema & Admin Setup

1. Supabase dashboard me jaao: **[https://supabase.com/dashboard](https://supabase.com/dashboard)**
2. Apna NotiFy project open karo (`pnwoccbrpcihjhjmujfb`).
3. Left sidebar me **SQL Editor** kholo.
4. **Step 1:** File `supabase/schema.sql` ka saara content copy karke SQL Editor me paste karo aur **Run** dabao.
   - Ye `public.profiles`, `public.license_keys`, `public.key_attempts` tables banayega (IF NOT EXISTS).
   - RLS enable karega.
   - `get_entitlement()` aur `redeem_key(p_code)` RPC functions banayega.
5. **Step 2:** File `supabase/admin.sql` ka content copy karke SQL Editor me paste karo aur **Run** dabao.
   - Ye `admin_generate_keys`, `admin_revoke_key`, `admin_revoke_user`, `admin_extend_user` banayega.
   - EXECUTE permissions `anon` aur `authenticated` se strictly REVOKE ho jayengi. Sirf `service_role` / Postgres superuser chala sakta hai.

### Security Test (admin_* callable by authenticated user nahi hona chahiye):
SQL editor me ye verify karne ke liye test query:
```sql
-- Normal authenticated context me check karo (fail hona chahiye):
SET ROLE authenticated;
SELECT public.admin_generate_keys(1, NULL, 'test');
-- Expected result: ERROR: permission denied for function admin_generate_keys

-- Admin context me wapas switch karo:
RESET ROLE;
SELECT * FROM public.admin_generate_keys(3, NULL, 'Initial VIP Keys');
```

---

## 2. Supabase Auth Configuration

1. Supabase Dashboard me **Authentication** -> **Providers** me jaao.
2. **Email Provider:**
   - Enable Email Provider: **ON**
   - **Confirm email**: **OFF** (users bina email verification link click kiye seedha login ho sakein).
3. **Google Provider:**
   - Enable Google Provider: **ON**
   - Yahan Google Cloud Console se **Client ID (Web)** aur **Client Secret (Web)** daalna hoga (niche Step 3 dekho).

---

## 3. Google Cloud Console OAuth Setup (Google Sign-In)

Google Sign-In Android Credential Manager ke sath kaam karne ke liye Google Cloud Console me **2 Client IDs** chahiye hoti hain:
1. **Web application Client ID** (Ye Supabase ko ID Token verify karne ke liye chahiye hoti hai aur Android app me serverClientId banegi).
2. **Android Client ID** (Ye Google Sign-In ko allow karti hai aapke package name aur Keystore SHA-1 ko verify karne ke liye).

### Step-by-step:
1. **[Google Cloud Console](https://console.cloud.google.com/)** me login karo.
2. Apna project select ya create karo (e.g. `NotiFy-App`).
3. **APIs & Services** -> **OAuth consent screen** me jaao:
   - User Type: **External** select karo.
   - App Name: `NotiFy`
   - User support email aur developer contact email daalo.
   - Scopes: `openid`, `profile`, `email`.
   - Save and Continue.
4. **Credentials** -> **Create Credentials** -> **OAuth client ID**:
   - **Client 1 (Web Application):**
     - Application type: **Web application**
     - Name: `NotiFy Web Client (Supabase)`
     - Authorized redirect URIs me Supabase ka redirect URL daalo:
       `https://pnwoccbrpcihjhjmujfb.supabase.co/auth/v1/callback`
     - Create dabao.
     - **Web Client ID** aur **Client Secret** copy karo.
     - Is Web Client ID ko Supabase Auth Google provider me aur app ke `SupabaseConfig.kt` me `GOOGLE_WEB_CLIENT_ID` me daalo.
   - **Client 2 (Android - Debug Keystore):**
     - Application type: **Android**
     - Name: `NotiFy Android Debug`
     - Package name: `com.notify`
     - SHA-1 certificate fingerprint:
       Debug keystore ka SHA-1 command:
       ```powershell
       keytool -list -v -keystore $env:USERPROFILE\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
       ```
   - **Client 3 (Android - Release Keystore):**
     > ⚠️ **CRITICAL WARNING:**
     > Google login aksar Debug me chalta hai par Release APK me fail ho jata hai agar Release Keystore ka SHA-1 Google Cloud Console me register na ho!
     > Jab aap release APK/AAB banao, release keystore ka SHA-1 lekar ek aur Android Client ID banao:
     - Application type: **Android**
     - Package name: `com.notify`
     - SHA-1: Aapke release keystore ka SHA-1 (ya agar Play Store App Signing use kar rahe ho, to Play Console me *App Integrity* -> *App signing key certificate* ka SHA-1).

---

## 4. Admin Keys Generation & Management (Cheat Sheet)

Supabase SQL Editor me jab bhi keys banani ya manage karni hon:

```sql
-- 1. Permanent Keys (5 keys)
SELECT * FROM public.admin_generate_keys(5, NULL, 'Permanent Key Batch');

-- 2. 1-Day Trial Keys (10 keys)
SELECT * FROM public.admin_generate_keys(10, 1, '1-Day Trial');

-- 3. 2-Day Trial Keys (10 keys)
SELECT * FROM public.admin_generate_keys(10, 2, '2-Day Trial');

-- 4. 7-Day Keys (10 keys)
SELECT * FROM public.admin_generate_keys(10, 7, '7-Day Access');

-- 5. Revoke a Key
SELECT public.admin_revoke_key('NTFY-XXXX-XXXX-XXXX');

-- 6. Revoke a User by Email
SELECT public.admin_revoke_user('user@example.com');

-- 7. Extend User License by 7 Days
SELECT public.admin_extend_user('user@example.com', 7);

-- 8. View Active Users and their Expire Dates
SELECT 
    u.email,
    p.nickname,
    k.code,
    k.expires_at,
    k.revoked,
    CASE 
        WHEN k.revoked THEN 'revoked'
        WHEN k.expires_at IS NOT NULL AND k.expires_at <= now() THEN 'expired'
        ELSE 'active'
    END AS status
FROM public.license_keys k
JOIN auth.users u ON k.redeemed_by = u.id
LEFT JOIN public.profiles p ON p.id = u.id
ORDER BY k.redeemed_at DESC;
```

---

## 5. Telegram Admin Bot Setup (Key Management Bot)

Ye setup aapko Telegram se direct NotiFy access keys generate, revoke, extend aur monitor karne ki suvidha deta hai.

### Step 1: SQL Setup chalao
Supabase SQL Editor me jaao aur `supabase/telegram_admin_setup.sql` ka content run karo.
- Ye `processed_updates` (idempotency table) create karega.
- Ye `admin_log` (audit trail table) create karega.
- Ye `pending_confirmations` (interactive confirmation table) create karega.
- Ye `admin_get_user_info` aur `admin_get_stats` RPC functions create karega.

### Step 2: Apna Numeric Telegram ID nikalo
1. Telegram me search karo: **[@userinfobot](https://t.me/userinfobot)**
2. `/start` bhejo.
3. Bot aapko aapka numeric **Id** dega (e.g. `123456789`). Is number ko note kar lo.

### Step 3: Telegram Bot banao (agar abhi nahi banaya)
1. Telegram me search karo: **[@BotFather](https://t.me/BotFather)**
2. `/newbot` bhejo aur prompt follow karke bot create karo.
3. BotFather aapko **HTTP API Token** dega (e.g. `123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ`). Is token ko secure rakhein.

### Step 4: Supabase Secrets configure karo
Supabase CLI se (ya Supabase Dashboard -> **Project Settings** -> **Edge Functions** -> **Manage Secrets** me) ye 3 secrets set karein:

```bash
# Apne real values se replace karein (token aur secret kisi repo me commit mat karna):
supabase secrets set TELEGRAM_BOT_TOKEN="<YOUR_TELEGRAM_BOT_TOKEN>"
supabase secrets set ADMIN_TELEGRAM_ID="<YOUR_NUMERIC_ID>"
supabase secrets set TELEGRAM_WEBHOOK_SECRET="<CHOOSE_A_RANDOM_SECRET_STRING_e.g._my_super_secret_token_123>"
```

### Step 5: Edge Function deploy karo
> ⚠️ **IMPORTANT NOTE ON `--no-verify-jwt`:**
> Telegram ke incoming webhook requests me Supabase user JWT nahi hota. Isliye function ko `--no-verify-jwt` ke sath deploy karna zaroori hai. Security gate function ke andar `X-Telegram-Bot-Api-Secret-Token` header aur sender ID comparison se 100% enforce hota hai.

```bash
supabase functions deploy telegram-admin --no-verify-jwt
```

### Step 6: Telegram Webhook set karo
Telegram ko Supabase Edge Function ka webhook URL batane ke liye ye curl command chalayein:

```bash
curl -F "url=https://pnwoccbrpcihjhjmujfb.supabase.co/functions/v1/telegram-admin" \
     -F "secret_token=<YOUR_RANDOM_SECRET_STRING>" \
     https://api.telegram.org/bot<YOUR_TELEGRAM_BOT_TOKEN>/setWebhook
```
*(Response `{"ok":true,"result":true,"description":"Webhook was set"}` aana chahiye).*

Webhook verify karne ke liye:
```bash
curl https://api.telegram.org/bot<YOUR_TELEGRAM_BOT_TOKEN>/getWebhookInfo
```

---

## 6. Verification & Test Steps

Deploy ke baad apne Telegram bot par ye tests karein:

1. **Test `/help`:**
   - Bot ko `/help` bhejo -> Saare available commands ka clean HTML formatting me list aana chahiye.

2. **Test `/genkey 1 1d test`:**
   - Command bhejo -> Ek 1-day access key formatted <code> block me aana chahiye jise mobile me tap karke copy kiya ja sake.

3. **Test Security (Unauthorized Access):**
   - Kisi doosre Telegram account se bot ko `/help` ya koi message bhejo.
   - **Expected behavior:** Bot bilkul CHUP rahega (koi reply ya acknowledgement nahi aayega taaki unhe bot ka pata na chale).

4. **Test Idempotency & Deduplication:**
   - Same Telegram webhook payload dobara bhejne par function bina action execute kiye 200 return karega (`processed_updates` table update_id check karke dedupe karta hai).

5. **Test Interactive Revoke Confirmation:**
   - `/revoke <KEY>` bhejo -> Inline buttons ["✅ Haan, revoke karo", "❌ Cancel"] aayenge.
   - Button 60 seconds tak valid rahega. 60 second baad click karne par "Confirmation expire ho chuki hai" ka error aayega.
   - Double-tap karne par action sirf ek hi baar execute hoga.

6. **Test `/stats` aur `/unused`:**
   - `/stats` bhejo -> Total keys, unused, active users, expired aur revoked counts show honge.
   - `/unused` bhejo -> Latest unused keys with duration label show honge.

