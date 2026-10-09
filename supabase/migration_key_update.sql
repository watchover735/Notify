-- ============================================================================
-- MIGRATION: Key Stacking & Permanent Access Guard
-- File: supabase/migration_key_update.sql
-- Description:
--   1. Updates redeem_key to allow key stacking on top of current active key
--   2. Prevents key consumption if user already has permanent active access
--   3. Maintains atomicity and brute force limits (5 failed in 15 mins)
--   4. Preserves exact response JSON shape
-- ============================================================================

-- RPC: get_entitlement()
-- Returns license status for the authenticated user based on server clock.
-- Picks the best active entitlement (Permanent > Furthest future expiry).
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
    -- Tier 1: Active (not revoked, not expired)
    -- Tier 2: Expired (not revoked, expired)
    -- Tier 3: Revoked
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


-- RPC: redeem_key(p_code TEXT)
-- Atomically redeems a key for auth.uid().
-- Stacking logic:
--   - If user already has permanent access, new key is NOT redeemed ('permanent_already').
--   - If user has active access, duration_days is added to MAX(active expires_at, now()).
--   - If new key is permanent (duration_days IS NULL), expires_at becomes NULL.
-- Returns: { "code": "ok"|"invalid"|"already_used"|"revoked"|"too_many_attempts"|"permanent_already", "message": "...", "expires_at": ..., "server_time": ... }
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
    v_has_permanent BOOLEAN;
    v_best_active_expires_at TIMESTAMPTZ;
    v_base_time TIMESTAMPTZ;
    v_new_expires_at TIMESTAMPTZ;
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
            'message', 'Too many failed attempts. Please try again after 15 minutes.'
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
            'message', 'Invalid license key. Please check and try again.'
        );
    END IF;

    -- 3. Check if key is already revoked
    IF v_key_row.revoked THEN
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'revoked',
            'message', 'This key has been revoked. Please contact support.'
        );
    END IF;

    -- 4. Check if key is already redeemed
    IF v_key_row.redeemed_by IS NOT NULL THEN
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'already_used',
            'message', 'This key has already been used.'
        );
    END IF;

    -- 5. Guard: If user already has PERMANENT active access, do NOT consume this key!
    SELECT EXISTS (
        SELECT 1
        FROM public.license_keys
        WHERE redeemed_by = v_user_id
          AND revoked = false
          AND expires_at IS NULL
    ) INTO v_has_permanent;

    IF v_has_permanent THEN
        RETURN jsonb_build_object(
            'code', 'permanent_already',
            'message', 'You already have permanent access, key was not used.',
            'server_time', v_now
        );
    END IF;

    -- 6. Key Stacking calculation:
    -- If new key is permanent (duration_days IS NULL), expires_at becomes NULL.
    -- Otherwise, add duration_days to MAX(user's best active expires_at, now()).
    IF v_key_row.duration_days IS NULL THEN
        v_new_expires_at := NULL;
    ELSE
        SELECT MAX(expires_at)
        INTO v_best_active_expires_at
        FROM public.license_keys
        WHERE redeemed_by = v_user_id
          AND revoked = false
          AND expires_at > v_now;

        v_base_time := GREATEST(COALESCE(v_best_active_expires_at, v_now), v_now);
        v_new_expires_at := v_base_time + (v_key_row.duration_days || ' days')::INTERVAL;
    END IF;

    -- 7. Atomic redeem: Single UPDATE prevents race conditions if 2 requests submit concurrently
    UPDATE public.license_keys
    SET redeemed_by = v_user_id,
        redeemed_at = v_now,
        expires_at = v_new_expires_at
    WHERE id = v_key_row.id
      AND redeemed_by IS NULL
      AND revoked = false
    RETURNING id, code, duration_days, redeemed_by, redeemed_at, expires_at
    INTO v_updated_row;

    IF NOT FOUND THEN
        -- Another concurrent request claimed this key
        INSERT INTO public.key_attempts (user_id, attempted_at, success, code_entered)
        VALUES (v_user_id, v_now, false, v_clean_code);

        RETURN jsonb_build_object(
            'code', 'already_used',
            'message', 'This key has already been used.'
        );
    END IF;

    -- 8. Record successful attempt
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
