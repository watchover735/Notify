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
