-- ============================================================================
-- NotiFy Supabase Schema: Telegram Admin Bot Support
-- Tables and functions for Telegram Bot Key Management & Audit Logging
-- IMPORTANT: Run this script in the Supabase SQL Editor.
-- Does NOT modify any existing tables or functions.
-- ============================================================================

-- 1. processed_updates: Idempotency table to prevent duplicate Telegram webhooks
CREATE TABLE IF NOT EXISTS public.processed_updates (
    update_id BIGINT PRIMARY KEY,
    processed_at TIMESTAMPTZ DEFAULT clock_timestamp()
);

ALTER TABLE public.processed_updates ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.processed_updates FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.processed_updates TO service_role;

-- 2. admin_log: Audit trail of all actions executed via the Telegram bot
-- Note: Keys are never logged in full, only the last 4 characters.
CREATE TABLE IF NOT EXISTS public.admin_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action TEXT NOT NULL,
    args JSONB,
    telegram_user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT clock_timestamp()
);

ALTER TABLE public.admin_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_log FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.admin_log TO service_role;

-- 3. pending_confirmations: Temporary storage for interactive destructive confirmations
-- Enforces: 60-second expiration, single-execution idempotency, and Telegram 64-byte callback limit.
CREATE TABLE IF NOT EXISTS public.pending_confirmations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action TEXT NOT NULL, -- 'revoke_key' | 'revoke_user'
    target TEXT NOT NULL, -- key code or email
    telegram_user_id BIGINT NOT NULL,
    executed BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT clock_timestamp()
);

ALTER TABLE public.pending_confirmations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.pending_confirmations FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.pending_confirmations TO service_role;

-- Index for speedy confirmation lookup and expiration checks
CREATE INDEX IF NOT EXISTS idx_pending_confirmations_lookup
    ON public.pending_confirmations (id, executed, created_at);


-- 4. admin_get_user_info(p_email TEXT)
-- Retrieves user status, profile nickname, and key state securely for the admin bot.
CREATE OR REPLACE FUNCTION public.admin_get_user_info(p_email TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_clean_email TEXT;
    v_user_id UUID;
    v_nickname TEXT;
    v_key RECORD;
    v_status TEXT;
    v_key_last4 TEXT;
BEGIN
    v_clean_email := LOWER(TRIM(p_email));

    SELECT id INTO v_user_id
    FROM auth.users
    WHERE LOWER(email) = v_clean_email;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'found', false,
            'message', 'User not found with email: ' || p_email
        );
    END IF;

    -- Fetch profile nickname if set
    SELECT nickname INTO v_nickname
    FROM public.profiles
    WHERE id = v_user_id;

    -- Fetch latest license key
    SELECT code, duration_days, expires_at, revoked, redeemed_at
    INTO v_key
    FROM public.license_keys
    WHERE redeemed_by = v_user_id
    ORDER BY COALESCE(expires_at, '9999-12-31'::timestamptz) DESC
    LIMIT 1;

    IF v_key.code IS NULL THEN
        v_status := 'none';
        v_key_last4 := NULL;
    ELSIF v_key.revoked THEN
        v_status := 'revoked';
        v_key_last4 := RIGHT(v_key.code, 4);
    ELSIF v_key.expires_at IS NULL THEN
        v_status := 'active (permanent)';
        v_key_last4 := RIGHT(v_key.code, 4);
    ELSIF v_key.expires_at > clock_timestamp() THEN
        v_status := 'active';
        v_key_last4 := RIGHT(v_key.code, 4);
    ELSE
        v_status := 'expired';
        v_key_last4 := RIGHT(v_key.code, 4);
    END IF;

    RETURN jsonb_build_object(
        'found', true,
        'email', v_clean_email,
        'user_id', v_user_id,
        'nickname', COALESCE(v_nickname, 'Not set'),
        'status', v_status,
        'key_last4', v_key_last4,
        'expires_at', v_key.expires_at,
        'redeemed_at', v_key.redeemed_at
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_user_info(TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_get_user_info(TEXT) TO service_role;


-- 5. admin_get_stats()
-- Aggregates total, unused, active, expired, and revoked key counts.
CREATE OR REPLACE FUNCTION public.admin_get_stats()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_total_keys INT;
    v_unused_keys INT;
    v_active_keys INT;
    v_expired_keys INT;
    v_revoked_keys INT;
    v_active_users INT;
BEGIN
    SELECT COUNT(*) INTO v_total_keys FROM public.license_keys;

    SELECT COUNT(*) INTO v_unused_keys
    FROM public.license_keys
    WHERE redeemed_by IS NULL AND revoked = false;

    SELECT COUNT(*) INTO v_revoked_keys
    FROM public.license_keys
    WHERE revoked = true;

    SELECT COUNT(*) INTO v_expired_keys
    FROM public.license_keys
    WHERE redeemed_by IS NOT NULL
      AND revoked = false
      AND expires_at IS NOT NULL
      AND expires_at <= clock_timestamp();

    SELECT COUNT(*) INTO v_active_keys
    FROM public.license_keys
    WHERE redeemed_by IS NOT NULL
      AND revoked = false
      AND (expires_at IS NULL OR expires_at > clock_timestamp());

    SELECT COUNT(DISTINCT redeemed_by) INTO v_active_users
    FROM public.license_keys
    WHERE redeemed_by IS NOT NULL
      AND revoked = false
      AND (expires_at IS NULL OR expires_at > clock_timestamp());

    RETURN jsonb_build_object(
        'total_keys', v_total_keys,
        'unused_keys', v_unused_keys,
        'active_keys', v_active_keys,
        'active_users', v_active_users,
        'expired_keys', v_expired_keys,
        'revoked_keys', v_revoked_keys
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_stats() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_get_stats() TO service_role;
