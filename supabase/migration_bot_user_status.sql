-- ============================================================================
-- Update admin_get_user_info and admin_get_stats for Telegram Bot User Status
-- Safe & Idempotent
-- ============================================================================

-- 1. admin_get_user_info: comprehensive user status including telemetry & playlists
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
    v_last_seen_at TIMESTAMPTZ;
    v_total_active_sec BIGINT;
    v_total_play_sec BIGINT;
    v_app_version TEXT;
    v_days_inactive INT;
    v_avg_daily_mins NUMERIC;
    v_playlist_count INT;
    v_today_active_mins NUMERIC;
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

    -- Fetch profile telemetry
    SELECT 
        nickname, 
        last_seen_at, 
        COALESCE(total_active_seconds, 0),
        COALESCE(total_play_seconds, 0),
        app_version
    INTO 
        v_nickname, 
        v_last_seen_at, 
        v_total_active_sec, 
        v_total_play_sec,
        v_app_version
    FROM public.profiles
    WHERE id = v_user_id;

    -- Calculate days inactive
    IF v_last_seen_at IS NOT NULL THEN
        v_days_inactive := EXTRACT(DAY FROM (timezone('utc'::text, now()) - v_last_seen_at))::INT;
    ELSE
        v_days_inactive := NULL;
    END IF;

    -- Calculate average daily active minutes
    SELECT COALESCE(ROUND(AVG(active_seconds) / 60.0, 1), 0)
    INTO v_avg_daily_mins
    FROM public.user_daily_telemetry
    WHERE user_id = v_user_id;

    -- Calculate today's active minutes
    SELECT COALESCE(ROUND(active_seconds / 60.0, 1), 0)
    INTO v_today_active_mins
    FROM public.user_daily_telemetry
    WHERE user_id = v_user_id AND activity_date = CURRENT_DATE;

    -- Count cloud playlists
    SELECT COUNT(*)
    INTO v_playlist_count
    FROM public.user_playlists
    WHERE user_id = v_user_id;

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
        'redeemed_at', v_key.redeemed_at,
        'last_seen_at', v_last_seen_at,
        'days_inactive', v_days_inactive,
        'total_active_hours', ROUND(v_total_active_sec / 3600.0, 2),
        'total_play_hours', ROUND(v_total_play_sec / 3600.0, 2),
        'avg_daily_active_mins', v_avg_daily_mins,
        'today_active_mins', COALESCE(v_today_active_mins, 0),
        'cloud_playlists', v_playlist_count,
        'app_version', COALESCE(v_app_version, 'Unknown')
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_user_info(TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_get_user_info(TEXT) TO service_role;


-- 2. admin_list_users: list recent users with telemetry and key status
CREATE OR REPLACE FUNCTION public.admin_list_users(p_limit INT DEFAULT 15)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_users JSONB;
BEGIN
    SELECT jsonb_agg(
        jsonb_build_object(
            'email', u.email,
            'nickname', COALESCE(p.nickname, 'No nick'),
            'last_seen_at', p.last_seen_at,
            'total_active_hours', ROUND(COALESCE(p.total_active_seconds, 0) / 3600.0, 2),
            'days_inactive', CASE 
                WHEN p.last_seen_at IS NULL THEN NULL 
                ELSE EXTRACT(DAY FROM (timezone('utc'::text, now()) - p.last_seen_at))::INT 
            END,
            'key_status', CASE
                WHEN lk.id IS NULL THEN 'NO_KEY'
                WHEN lk.revoked THEN 'REVOKED'
                WHEN lk.expires_at IS NULL THEN 'PERMANENT'
                WHEN lk.expires_at > clock_timestamp() THEN 'ACTIVE'
                ELSE 'EXPIRED'
            END
        )
    )
    INTO v_users
    FROM (
        SELECT id, email FROM auth.users ORDER BY created_at DESC LIMIT p_limit
    ) u
    LEFT JOIN public.profiles p ON p.id = u.id
    LEFT JOIN LATERAL (
        SELECT id, revoked, expires_at
        FROM public.license_keys
        WHERE redeemed_by = u.id
        ORDER BY COALESCE(expires_at, '9999-12-31'::timestamptz) DESC
        LIMIT 1
    ) lk ON true;

    RETURN COALESCE(v_users, '[]'::jsonb);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_users(INT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_list_users(INT) TO service_role;


-- 3. admin_get_stats: enhanced stats with DAU and playlists
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
    v_total_registered_users INT;
    v_dau_today INT;
    v_total_playlists INT;
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

    SELECT COUNT(*) INTO v_total_registered_users FROM auth.users;

    SELECT COUNT(DISTINCT user_id) INTO v_dau_today
    FROM public.user_daily_telemetry
    WHERE activity_date = CURRENT_DATE;

    SELECT COUNT(*) INTO v_total_playlists FROM public.user_playlists;

    RETURN jsonb_build_object(
        'total_keys', v_total_keys,
        'unused_keys', v_unused_keys,
        'active_keys', v_active_keys,
        'active_users', v_active_users,
        'expired_keys', v_expired_keys,
        'revoked_keys', v_revoked_keys,
        'total_registered_users', v_total_registered_users,
        'dau_today', v_dau_today,
        'total_playlists', v_total_playlists
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_stats() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_get_stats() TO service_role;
