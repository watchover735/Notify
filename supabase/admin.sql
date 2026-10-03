-- ============================================================================
-- NotiFy Supabase Admin Functions
-- IMPORTANT: These functions are for ADMINS ONLY (via Supabase SQL Editor or service_role).
-- Permissions are strictly REVOKED from 'anon' and 'authenticated' users.
-- ============================================================================

-- ============================================================================
-- 1. admin_generate_keys(n, duration_days, note)
-- Generates formatted 'NTFY-XXXX-XXXX-XXXX' keys using unambiguous characters:
-- '23456789ABCDEFGHJKLMNPQRSTUVWXYZ' (no 0/O, 1/I/L).
-- duration_days: NULL = permanent, 1 = 1 day, 7 = 7 days, etc.
-- ============================================================================
CREATE OR REPLACE FUNCTION public.admin_generate_keys(
    n INT,
    duration_days INT DEFAULT NULL,
    note TEXT DEFAULT NULL
)
RETURNS TABLE (
    code TEXT,
    duration_days INT,
    note TEXT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_chars TEXT := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    v_code TEXT;
    v_part1 TEXT;
    v_part2 TEXT;
    v_part3 TEXT;
    v_i INT;
    v_j INT;
    v_idx INT;
BEGIN
    IF n <= 0 OR n > 1000 THEN
        RAISE EXCEPTION 'n must be between 1 and 1000';
    END IF;

    FOR v_i IN 1..n LOOP
        LOOP
            v_part1 := '';
            FOR v_j IN 1..4 LOOP
                v_idx := floor(random() * length(v_chars) + 1)::INT;
                v_part1 := v_part1 || substr(v_chars, v_idx, 1);
            END LOOP;

            v_part2 := '';
            FOR v_j IN 1..4 LOOP
                v_idx := floor(random() * length(v_chars) + 1)::INT;
                v_part2 := v_part2 || substr(v_chars, v_idx, 1);
            END LOOP;

            v_part3 := '';
            FOR v_j IN 1..4 LOOP
                v_idx := floor(random() * length(v_chars) + 1)::INT;
                v_part3 := v_part3 || substr(v_chars, v_idx, 1);
            END LOOP;

            v_code := 'NTFY-' || v_part1 || '-' || v_part2 || '-' || v_part3;

            BEGIN
                INSERT INTO public.license_keys (code, duration_days, note)
                VALUES (v_code, admin_generate_keys.duration_days, admin_generate_keys.note);
                EXIT; -- Key generated and inserted, exit retry loop
            EXCEPTION WHEN unique_violation THEN
                -- In rare random collision, loop again
                NULL;
            END;
        END LOOP;
    END LOOP;

    RETURN QUERY
    SELECT k.code, k.duration_days, k.note, k.created_at
    FROM public.license_keys k
    WHERE k.note IS NOT DISTINCT FROM admin_generate_keys.note
    ORDER BY k.created_at DESC
    LIMIT n;
END;
$$;

-- STRICT PERMISSIONS: Revoke execution from PUBLIC, anon, and authenticated
REVOKE ALL ON FUNCTION public.admin_generate_keys(INT, INT, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_generate_keys(INT, INT, TEXT) TO service_role;


-- ============================================================================
-- 2. admin_revoke_key(p_code)
-- Revokes a specific key by its code.
-- ============================================================================
CREATE OR REPLACE FUNCTION public.admin_revoke_key(p_code TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_clean_code TEXT;
    v_rows_affected INT;
BEGIN
    v_clean_code := UPPER(TRIM(p_code));
    UPDATE public.license_keys
    SET revoked = true,
        revoked_at = clock_timestamp()
    WHERE code = v_clean_code
       OR REPLACE(code, '-', '') = REPLACE(v_clean_code, '-', '');

    GET DIAGNOSTICS v_rows_affected = ROW_COUNT;

    IF v_rows_affected = 0 THEN
        RETURN jsonb_build_object('success', false, 'message', 'Key not found: ' || p_code);
    END IF;

    RETURN jsonb_build_object('success', true, 'message', 'Key revoked successfully: ' || v_clean_code);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_revoke_key(TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_revoke_key(TEXT) TO service_role;


-- ============================================================================
-- 3. admin_revoke_user(p_email)
-- Revokes all active keys belonging to the user with the given email address.
-- ============================================================================
CREATE OR REPLACE FUNCTION public.admin_revoke_user(p_email TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
    v_rows_affected INT;
BEGIN
    SELECT id INTO v_user_id
    FROM auth.users
    WHERE LOWER(email) = LOWER(TRIM(p_email));

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'message', 'User not found with email: ' || p_email);
    END IF;

    UPDATE public.license_keys
    SET revoked = true,
        revoked_at = clock_timestamp()
    WHERE redeemed_by = v_user_id
      AND revoked = false;

    GET DIAGNOSTICS v_rows_affected = ROW_COUNT;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_user_id,
        'keys_revoked', v_rows_affected,
        'message', 'Revoked ' || v_rows_affected || ' active key(s) for user ' || p_email
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_revoke_user(TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_revoke_user(TEXT) TO service_role;


-- ============================================================================
-- 4. admin_extend_user(p_email, extra_days)
-- Extends the expiration date of an active key for a user by extra_days.
-- ============================================================================
CREATE OR REPLACE FUNCTION public.admin_extend_user(p_email TEXT, extra_days INT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
    v_key_id UUID;
    v_new_expires_at TIMESTAMPTZ;
    v_current_expires_at TIMESTAMPTZ;
BEGIN
    IF extra_days <= 0 THEN
        RAISE EXCEPTION 'extra_days must be greater than 0';
    END IF;

    SELECT id INTO v_user_id
    FROM auth.users
    WHERE LOWER(email) = LOWER(TRIM(p_email));

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'message', 'User not found with email: ' || p_email);
    END IF;

    SELECT id, expires_at INTO v_key_id, v_current_expires_at
    FROM public.license_keys
    WHERE redeemed_by = v_user_id
      AND revoked = false
    ORDER BY COALESCE(expires_at, '9999-12-31'::timestamptz) DESC
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'message', 'No redeemed key found for user ' || p_email);
    END IF;

    IF v_current_expires_at IS NULL THEN
        RETURN jsonb_build_object('success', true, 'message', 'User already has a permanent license');
    END IF;

    IF v_current_expires_at < clock_timestamp() THEN
        v_new_expires_at := clock_timestamp() + (extra_days || ' days')::INTERVAL;
    ELSE
        v_new_expires_at := v_current_expires_at + (extra_days || ' days')::INTERVAL;
    END IF;

    UPDATE public.license_keys
    SET expires_at = v_new_expires_at,
        duration_days = COALESCE(duration_days, 0) + extra_days
    WHERE id = v_key_id;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_user_id,
        'new_expires_at', v_new_expires_at,
        'message', 'User license extended by ' || extra_days || ' day(s)'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_extend_user(TEXT, INT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_extend_user(TEXT, INT) TO service_role;


-- ============================================================================
-- USAGE EXAMPLES (Run these queries directly in Supabase SQL Editor as Admin):
-- ============================================================================

-- 1. Generate 5 permanent keys:
-- SELECT * FROM public.admin_generate_keys(5, NULL, 'Permanent VIP batch');

-- 2. Generate 10 keys valid for 1 day:
-- SELECT * FROM public.admin_generate_keys(10, 1, '1-day trial batch');

-- 3. Generate 10 keys valid for 2 days:
-- SELECT * FROM public.admin_generate_keys(10, 2, '2-day trial batch');

-- 4. Generate 20 keys valid for 7 days:
-- SELECT * FROM public.admin_generate_keys(20, 7, 'Weekly promo batch');

-- 5. Revoke a specific key:
-- SELECT public.admin_revoke_key('NTFY-XXXX-XXXX-XXXX');

-- 6. Revoke access for a user by their email:
-- SELECT public.admin_revoke_user('user@example.com');

-- 7. Extend an existing user's license by 14 days:
-- SELECT public.admin_extend_user('user@example.com', 14);

-- 8. View all active users with their entitlement and expiration:
-- SELECT 
--     u.email,
--     p.nickname,
--     k.code AS key_code,
--     k.duration_days,
--     k.redeemed_at,
--     k.expires_at,
--     k.revoked,
--     CASE 
--         WHEN k.revoked THEN 'revoked'
--         WHEN k.expires_at IS NOT NULL AND k.expires_at <= now() THEN 'expired'
--         ELSE 'active'
--     END AS current_status
-- FROM public.license_keys k
-- JOIN auth.users u ON k.redeemed_by = u.id
-- LEFT JOIN public.profiles p ON p.id = u.id
-- ORDER BY k.redeemed_at DESC;

-- 9. List unused / unredeemed keys:
-- SELECT code, duration_days, note, created_at
-- FROM public.license_keys
-- WHERE redeemed_by IS NULL AND revoked = false
-- ORDER BY created_at DESC;
