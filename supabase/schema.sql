-- ============================================================================
-- NotiFy Supabase Schema
-- Run this in Supabase SQL Editor for your project.
-- Safe: Uses CREATE TABLE IF NOT EXISTS and CREATE OR REPLACE FUNCTION.
-- Does NOT touch or alter any existing Edge Functions or audio streaming tables.
-- ============================================================================

-- 1. Profiles Table
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    nickname TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable RLS on profiles
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

-- Drop existing policies if re-running
DO $$
BEGIN
    DROP POLICY IF EXISTS "Users can select own profile" ON public.profiles;
    DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
    DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
END $$;

-- Policy: User can only select their own profile
CREATE POLICY "Users can select own profile"
    ON public.profiles
    FOR SELECT
    TO authenticated
    USING (auth.uid() = id);

-- Policy: User can insert their own profile
CREATE POLICY "Users can insert own profile"
    ON public.profiles
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = id);

-- Policy: User can update their own profile
CREATE POLICY "Users can update own profile"
    ON public.profiles
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

-- 2. License Keys Table
CREATE TABLE IF NOT EXISTS public.license_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code TEXT UNIQUE NOT NULL,
    duration_days INT NULL, -- NULL = permanent
    redeemed_by UUID NULL REFERENCES auth.users(id) ON DELETE SET NULL,
    redeemed_at TIMESTAMPTZ NULL,
    expires_at TIMESTAMPTZ NULL, -- NULL = permanent
    revoked BOOLEAN NOT NULL DEFAULT false,
    revoked_at TIMESTAMPTZ NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable RLS on license_keys
ALTER TABLE public.license_keys ENABLE ROW LEVEL SECURITY;
-- NOTE: No client policies. Direct SELECT/INSERT/UPDATE/DELETE from client is BLOCKED.
-- All access is mediated through SECURITY DEFINER RPCs below.

-- 3. Key Attempts Table (Brute-force protection tracking)
CREATE TABLE IF NOT EXISTS public.key_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    success BOOLEAN NOT NULL DEFAULT false,
    code_entered TEXT
);

CREATE INDEX IF NOT EXISTS idx_key_attempts_user_time 
    ON public.key_attempts (user_id, attempted_at DESC);

-- Enable RLS on key_attempts
ALTER TABLE public.key_attempts ENABLE ROW LEVEL SECURITY;
-- NOTE: No client policies. Managed strictly through RPC.

-- ============================================================================
-- RPC: get_entitlement()
-- Returns license status for the authenticated user based on server clock.
-- Output: { "status": "active"|"none"|"expired"|"revoked", "expires_at": ..., "server_time": ... }
-- ============================================================================
CREATE OR REPLACE FUNCTION public.get_entitlement()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
    v_key RECORD;
    v_server_time TIMESTAMPTZ;
    v_status TEXT;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    v_server_time := clock_timestamp();

    -- Find key redeemed by this user (prioritizing active/valid key)
    SELECT id, code, duration_days, expires_at, revoked, revoked_at, redeemed_at
    INTO v_key
    FROM public.license_keys
    WHERE redeemed_by = v_user_id
    ORDER BY 
        CASE 
            WHEN revoked THEN 3
            WHEN expires_at IS NOT NULL AND expires_at <= v_server_time THEN 2
            ELSE 1
        END,
        COALESCE(expires_at, '9999-12-31'::timestamptz) DESC,
        redeemed_at DESC
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'status', 'none',
            'expires_at', null,
            'server_time', v_server_time
        );
    END IF;

    IF v_key.revoked THEN
        v_status := 'revoked';
    ELSIF v_key.expires_at IS NOT NULL AND v_key.expires_at <= v_server_time THEN
        v_status := 'expired';
    ELSE
        v_status := 'active';
    END IF;

    RETURN jsonb_build_object(
        'status', v_status,
        'expires_at', v_key.expires_at,
        'server_time', v_server_time
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_entitlement() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_entitlement() FROM anon;
GRANT EXECUTE ON FUNCTION public.get_entitlement() TO authenticated;

-- ============================================================================
-- RPC: redeem_key(p_code TEXT)
-- Atomically redeems a key for auth.uid().
-- Guarded by:
--   - 5 failed attempts in last 15 minutes limit (brute force guard)
--   - Atomic UPDATE with WHERE redeemed_by IS NULL AND revoked = false (prevents race condition)
-- Duration begins from redeem time (now()), NOT generation time.
-- Returns: { "code": "ok"|"invalid"|"already_used"|"revoked"|"too_many_attempts", "message": "...", "expires_at": ... }
-- ============================================================================
CREATE OR REPLACE FUNCTION public.redeem_key(p_code TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
    v_now TIMESTAMPTZ;
    v_clean_code TEXT;
    v_failed_attempts INT;
    v_key_row RECORD;
    v_updated_row RECORD;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    v_now := clock_timestamp();

    -- Normalize code: trim, uppercase, remove interior whitespace
    v_clean_code := UPPER(TRIM(p_code));
    v_clean_code := REGEXP_REPLACE(v_clean_code, '\s+', '', 'g');

    -- 1. Brute-force guard: 5 failed attempts in last 15 minutes
    SELECT COUNT(*)
    INTO v_failed_attempts
    FROM public.key_attempts
    WHERE user_id = v_user_id
      AND success = false
      AND attempted_at >= (v_now - INTERVAL '15 minutes');

    IF v_failed_attempts >= 5 THEN
        RETURN jsonb_build_object(
            'code', 'too_many_attempts',
            'message', 'Bahut zyada koshish. 15 min baad try karo.'
        );
    END IF;

    -- 2. Lookup key existence (matching exact code or hyphen-stripped)
    SELECT id, code, duration_days, redeemed_by, revoked, expires_at
    INTO v_key_row
    FROM public.license_keys
    WHERE code = v_clean_code 
       OR REPLACE(code, '-', '') = REPLACE(v_clean_code, '-', '')
    LIMIT 1;

    IF NOT FOUND THEN
        -- Record failed attempt
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'invalid',
            'message', 'Key galat hai'
        );
    END IF;

    -- 3. Check if already revoked
    IF v_key_row.revoked THEN
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'revoked',
            'message', 'Key revoke kar di gayi. Admin se contact karo'
        );
    END IF;

    -- 4. Check if already redeemed
    IF v_key_row.redeemed_by IS NOT NULL THEN
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'already_used',
            'message', 'Ye key pehle use ho chuki hai'
        );
    END IF;

    -- 5. Atomic redeem: Single UPDATE prevents race conditions if 2 users submit concurrently
    UPDATE public.license_keys
    SET redeemed_by = v_user_id,
        redeemed_at = v_now,
        expires_at = CASE 
            WHEN duration_days IS NULL THEN NULL 
            ELSE v_now + (duration_days || ' days')::INTERVAL 
        END
    WHERE id = v_key_row.id
      AND redeemed_by IS NULL
      AND revoked = false
    RETURNING id, code, duration_days, redeemed_by, redeemed_at, expires_at
    INTO v_updated_row;

    IF NOT FOUND THEN
        -- Another concurrent request took this key
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'already_used',
            'message', 'Ye key pehle use ho chuki hai'
        );
    END IF;

    -- 6. Record successful attempt
    INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
    VALUES (v_user_id, v_now, true, v_clean_code);

    RETURN jsonb_build_object(
        'code', 'ok',
        'message', 'Key successfully redeemed',
        'expires_at', v_updated_row.expires_at,
        'server_time', v_now
    );
END;
$$;

REVOKE ALL ON FUNCTION public.redeem_key(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.redeem_key(TEXT) FROM anon;
GRANT EXECUTE ON FUNCTION public.redeem_key(TEXT) TO authenticated;
