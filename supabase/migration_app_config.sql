-- ============================================================================
-- Migration: App Config & Forced/Soft Update System (LITE)
-- File: supabase/migration_app_config.sql
-- Description:
--   Single-row configuration table `app_config` and public RPC `get_app_config`
--   to govern soft and hard app updates dynamically via Supabase.
-- ============================================================================

-- 1. Create table app_config (singleton row id = 1)
CREATE TABLE IF NOT EXISTS public.app_config (
    id integer PRIMARY KEY CHECK (id = 1),
    latest_version_code integer NOT NULL,
    min_supported_version_code integer NOT NULL,
    max_skips integer NOT NULL DEFAULT 3 CHECK (max_skips >= 0),
    force_message text NOT NULL DEFAULT 'NotiFy ka naya update zaroori hai. Kripya app update karein.',
    download_url text DEFAULT 'https://github.com/watchover735/Notify/releases/latest',
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_version_sanity CHECK (min_supported_version_code <= latest_version_code)
);

-- 2. Enable Row Level Security (no direct SELECT/INSERT/UPDATE/DELETE for clients)
ALTER TABLE public.app_config ENABLE ROW LEVEL SECURITY;

-- 3. Seed initial row (id = 1)
-- Current app version: versionCode = 20 (v0.7.4)
-- Default min_supported = 19 (allows existing users without blocking)
INSERT INTO public.app_config (
    id,
    latest_version_code,
    min_supported_version_code,
    max_skips,
    force_message,
    download_url,
    updated_at
) VALUES (
    1,
    20,
    19,
    3,
    'NotiFy ka naya update aa chuka hai. Behtar experience aur naye features ke liye kripya app update karein.',
    'https://github.com/watchover735/Notify/releases/latest',
    now()
)
ON CONFLICT (id) DO UPDATE SET
    latest_version_code = EXCLUDED.latest_version_code,
    min_supported_version_code = EXCLUDED.min_supported_version_code,
    updated_at = now();

-- 4. RPC function to read config (SECURITY DEFINER, public access via anon/authenticated)
CREATE OR REPLACE FUNCTION public.get_app_config()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_config record;
BEGIN
    SELECT 
        latest_version_code,
        min_supported_version_code,
        max_skips,
        force_message,
        download_url
    INTO v_config
    FROM public.app_config
    WHERE id = 1;

    IF NOT FOUND THEN
        -- Fallback default if row is somehow deleted
        RETURN jsonb_build_object(
            'latest_version_code', 20,
            'min_supported_version_code', 19,
            'max_skips', 3,
            'force_message', 'NotiFy ka naya version zaroori hai. Kripya update karein.',
            'download_url', 'https://github.com/watchover735/Notify/releases/latest',
            'server_time', now()
        );
    END IF;

    RETURN jsonb_build_object(
        'latest_version_code', v_config.latest_version_code,
        'min_supported_version_code', v_config.min_supported_version_code,
        'max_skips', v_config.max_skips,
        'force_message', v_config.force_message,
        'download_url', v_config.download_url,
        'server_time', now()
    );
END;
$$;

-- 5. Permissions: allow both anon and authenticated to execute RPC
GRANT EXECUTE ON FUNCTION public.get_app_config() TO anon, authenticated;

-- ============================================================================
-- ADMIN SQL USAGE EXAMPLES (Run directly in Supabase SQL Editor as admin)
-- ============================================================================
--
-- EXAMPLE (a): Announce a normal (soft) update to version code 21
-- Users on code 20 can skip up to 3 times before it becomes mandatory.
--
--   UPDATE public.app_config
--   SET latest_version_code = 21,
--       min_supported_version_code = 20,
--       max_skips = 3,
--       force_message = 'NotiFy v0.7.5 aa gaya hai! Naye features ke liye update karein.',
--       download_url = 'https://github.com/watchover735/Notify/releases/latest',
--       updated_at = now()
--   WHERE id = 1;
--
-- EXAMPLE (b): Force an immediate URGENT update to version code 21
-- All users below version code 21 are instantly blocked (no skips allowed).
--
--   UPDATE public.app_config
--   SET latest_version_code = 21,
--       min_supported_version_code = 21,
--       force_message = 'NotiFy ka critical security/API update zaroori hai. App use karne ke liye update karein.',
--       download_url = 'https://github.com/watchover735/Notify/releases/latest',
--       updated_at = now()
--   WHERE id = 1;
--
-- EXAMPLE (c): Rollback / Relax minimum supported version
-- In case of unexpected issues, allow older versions (e.g. 19+) again without hard block.
--
--   UPDATE public.app_config
--   SET min_supported_version_code = 19,
--       updated_at = now()
--   WHERE id = 1;
--
