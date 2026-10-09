-- ============================================================================
-- NotiFy Migration: Telemetry (DAU, Active Time, Inactivity) & Cloud Playlists
-- Safe & Idempotent
-- ============================================================================

-- 1. Fix Hinglish messages in redeem_key RPC
CREATE OR REPLACE FUNCTION public.redeem_key(p_code TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
    v_clean_code TEXT;
    v_failed_attempts INT;
    v_now TIMESTAMPTZ := timezone('utc'::text, now());
    v_key_row RECORD;
    v_updated_row RECORD;
    v_has_permanent BOOLEAN := false;
    v_best_active_expires_at TIMESTAMPTZ;
    v_new_expires_at TIMESTAMPTZ;
    v_base_time TIMESTAMPTZ;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object(
            'code', 'unauthenticated',
            'message', 'User is not authenticated'
        );
    END IF;

    v_clean_code := UPPER(TRIM(REGEXP_REPLACE(p_code, '\s+', '', 'g')));

    -- 1. Rate-limit check (5 failed attempts within last 15 minutes)
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

    -- 7. Atomic redeem
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


-- ============================================================================
-- 2. Telemetry & User Engagement Tracking
-- ============================================================================

-- Add telemetry columns to profiles table
ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()),
    ADD COLUMN IF NOT EXISTS total_active_seconds BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_play_seconds BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS app_version TEXT;

-- Daily telemetry table (tracks daily usage, DAU, active & play seconds)
CREATE TABLE IF NOT EXISTS public.user_daily_telemetry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL DEFAULT CURRENT_DATE,
    active_seconds INT NOT NULL DEFAULT 0,
    play_seconds INT NOT NULL DEFAULT 0,
    sessions_count INT NOT NULL DEFAULT 1,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id, activity_date)
);

CREATE INDEX IF NOT EXISTS idx_user_daily_telemetry_date ON public.user_daily_telemetry(activity_date);
CREATE INDEX IF NOT EXISTS idx_user_daily_telemetry_user ON public.user_daily_telemetry(user_id, activity_date);

ALTER TABLE public.user_daily_telemetry ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    DROP POLICY IF EXISTS "Users can view own daily telemetry" ON public.user_daily_telemetry;
END $$;

CREATE POLICY "Users can view own daily telemetry"
    ON public.user_daily_telemetry FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

-- Heartbeat RPC: atomic increment of daily and profile telemetry
CREATE OR REPLACE FUNCTION public.record_user_heartbeat(
    p_active_seconds INT DEFAULT 30,
    p_play_seconds INT DEFAULT 0,
    p_app_version TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID;
    v_now TIMESTAMPTZ := timezone('utc'::text, now());
    v_today DATE := CURRENT_DATE;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error', 'unauthenticated');
    END IF;

    -- 1. Upsert daily telemetry
    INSERT INTO public.user_daily_telemetry (
        user_id,
        activity_date,
        active_seconds,
        play_seconds,
        sessions_count,
        last_active_at,
        updated_at
    )
    VALUES (
        v_user_id,
        v_today,
        GREATEST(0, p_active_seconds),
        GREATEST(0, p_play_seconds),
        1,
        v_now,
        v_now
    )
    ON CONFLICT (user_id, activity_date)
    DO UPDATE SET
        active_seconds = public.user_daily_telemetry.active_seconds + GREATEST(0, p_active_seconds),
        play_seconds = public.user_daily_telemetry.play_seconds + GREATEST(0, p_play_seconds),
        last_active_at = v_now,
        updated_at = v_now;

    -- 2. Update profiles table
    UPDATE public.profiles
    SET last_seen_at = v_now,
        total_active_seconds = COALESCE(total_active_seconds, 0) + GREATEST(0, p_active_seconds),
        total_play_seconds = COALESCE(total_play_seconds, 0) + GREATEST(0, p_play_seconds),
        app_version = COALESCE(p_app_version, app_version),
        updated_at = v_now
    WHERE id = v_user_id;

    RETURN jsonb_build_object(
        'ok', true,
        'server_time', v_now
    );
END;
$$;

REVOKE ALL ON FUNCTION public.record_user_heartbeat(INT, INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.record_user_heartbeat(INT, INT, TEXT) FROM anon;
GRANT EXECUTE ON FUNCTION public.record_user_heartbeat(INT, INT, TEXT) TO authenticated;

-- Analytics View 1: Daily Active Users (DAU) & total/average usage
CREATE OR REPLACE VIEW public.v_daily_active_users AS
SELECT
    activity_date,
    COUNT(DISTINCT user_id) AS dau,
    ROUND(SUM(active_seconds) / 60.0, 1) AS total_active_minutes,
    ROUND(AVG(active_seconds) / 60.0, 1) AS avg_active_minutes_per_user,
    ROUND(SUM(play_seconds) / 60.0, 1) AS total_music_play_minutes
FROM public.user_daily_telemetry
GROUP BY activity_date
ORDER BY activity_date DESC;

-- Analytics View 2: User Engagement Summary (Total time, last active, days inactive, average daily usage)
CREATE OR REPLACE VIEW public.v_user_engagement_summary AS
SELECT
    p.id AS user_id,
    u.email,
    p.nickname,
    ROUND(COALESCE(p.total_active_seconds, 0) / 3600.0, 2) AS total_active_hours,
    ROUND(COALESCE(p.total_play_seconds, 0) / 3600.0, 2) AS total_music_play_hours,
    p.last_seen_at,
    CASE 
        WHEN p.last_seen_at IS NULL THEN NULL
        ELSE EXTRACT(DAY FROM (timezone('utc'::text, now()) - p.last_seen_at))::INT
    END AS days_inactive,
    COALESCE(ROUND(avg_daily.avg_active_mins, 1), 0) AS avg_daily_active_minutes,
    p.app_version
FROM public.profiles p
LEFT JOIN auth.users u ON p.id = u.id
LEFT JOIN (
    SELECT user_id, AVG(active_seconds) / 60.0 AS avg_active_mins
    FROM public.user_daily_telemetry
    GROUP BY user_id
) avg_daily ON avg_daily.user_id = p.id
ORDER BY p.last_seen_at DESC NULLS LAST;


-- ============================================================================
-- 3. Cloud Playlists (Spotify & YouTube metadata synced to User Account)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.user_playlists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    playlist_id TEXT NOT NULL,
    title TEXT NOT NULL,
    source_url TEXT,
    artwork_uri TEXT,
    tracks_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id, playlist_id)
);

CREATE INDEX IF NOT EXISTS idx_user_playlists_user ON public.user_playlists(user_id);
CREATE INDEX IF NOT EXISTS idx_user_playlists_playlist_id ON public.user_playlists(playlist_id);

ALTER TABLE public.user_playlists ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    DROP POLICY IF EXISTS "Users can view own playlists" ON public.user_playlists;
    DROP POLICY IF EXISTS "Users can insert own playlists" ON public.user_playlists;
    DROP POLICY IF EXISTS "Users can update own playlists" ON public.user_playlists;
    DROP POLICY IF EXISTS "Users can delete own playlists" ON public.user_playlists;
END $$;

CREATE POLICY "Users can view own playlists"
    ON public.user_playlists FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own playlists"
    ON public.user_playlists FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own playlists"
    ON public.user_playlists FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can delete own playlists"
    ON public.user_playlists FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);
