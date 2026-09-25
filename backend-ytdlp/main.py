"""
NotiFy Remote yt-dlp Audio Stream Resolver Backend
Lightweight microservice to resolve YouTube audio stream URLs and catalog searches.
Designed for deployment on Render, Railway, Fly.io, or any VPS/container.
"""

import json
import logging
import os
import re
import sys
import time
from urllib.parse import parse_qs, urlparse

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("notify-ytdlp")

# Try importing yt_dlp
try:
    import yt_dlp
except ImportError:
    logger.error("yt_dlp module not found. Please install it with: pip install yt-dlp")
    yt_dlp = None

# In-memory resolution cache: video_id -> (timestamp, data)
RESOLUTION_CACHE = {}
CACHE_TTL_SECONDS = 1800  # 30 minutes
START_TIME = time.time()

FORMAT_SPEC = os.getenv("YTDLP_FORMAT_SPEC", "bestaudio[ext=m4a]/bestaudio/best")
CLIENT_PROFILES = [
    "tv_embedded,visionos",
    "android",
    "ios,tv,mweb",
    "all",
]


def extract_video_id(url_or_id: str) -> str:
    """Extract 11-char YouTube video ID from URL or ID."""
    if not url_or_id:
        return ""
    trimmed = url_or_id.strip()
    if len(trimmed) == 11 and not any(c in trimmed for c in "/?&="):
        return trimmed
    try:
        parsed = urlparse(trimmed)
        if "youtu.be" in (parsed.hostname or ""):
            return parsed.path.strip("/")
        qs = parse_qs(parsed.query)
        if "v" in qs and qs["v"]:
            return qs["v"][0]
        match = re.search(r"[a-zA-Z0-9_-]{11}", trimmed)
        if match:
            return match.group(0)
    except Exception:
        pass
    return trimmed


def extract_expiry_epoch_ms(stream_url: str) -> int:
    """Extract expiration epoch milliseconds from googlevideo URL."""
    try:
        parsed = urlparse(stream_url)
        qs = parse_qs(parsed.query)
        if "expire" in qs and qs["expire"]:
            return int(qs["expire"][0]) * 1000
    except Exception:
        pass
    # Fallback to 5 hours from now
    return int((time.time() + 18000) * 1000)


def resolve_stream_core(video_id: str, canonical_url: str = None) -> dict:
    """Core stream resolution logic using yt-dlp."""
    if not yt_dlp:
        return {"success": False, "error": "yt-dlp is not installed on this server"}

    clean_id = extract_video_id(video_id) or (extract_video_id(canonical_url) if canonical_url else "")
    if not clean_id:
        return {"success": False, "error": "Invalid or missing videoId"}

    # Check in-memory cache
    now = time.time()
    if clean_id in RESOLUTION_CACHE:
        cached_time, cached_result = RESOLUTION_CACHE[clean_id]
        if now - cached_time < CACHE_TTL_SECONDS:
            logger.info("CACHE_HIT videoId=%s", clean_id)
            return cached_result

    target_url = canonical_url if canonical_url and "youtube.com" in canonical_url else f"https://www.youtube.com/watch?v={clean_id}"

    last_error = None
    for profile in CLIENT_PROFILES:
        try:
            ydl_opts = {
                "format": FORMAT_SPEC,
                "noplaylist": True,
                "quiet": True,
                "no_warnings": True,
                "no_check_certificates": True,
                "extractor_args": {
                    "youtube": {
                        "player_client": profile.split(","),
                    }
                },
            }
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(target_url, download=False)
                if not info:
                    continue
                stream_url = info.get("url")
                if not stream_url:
                    # Look inside formats
                    formats = info.get("formats", [])
                    audio_formats = [f for f in formats if f.get("vcodec") == "none" and f.get("url")]
                    if audio_formats:
                        stream_url = audio_formats[-1]["url"]
                    elif formats:
                        stream_url = formats[-1].get("url")

                if stream_url:
                    format_id = str(info.get("format_id", "m4a/audio"))
                    ext = info.get("ext", "m4a")
                    abr = info.get("abr") or 128
                    expires_at = extract_expiry_epoch_ms(stream_url)

                    res = {
                        "success": True,
                        "streamUrl": stream_url,
                        "format": f"ytdlp_{ext}_{format_id}",
                        "formatId": format_id,
                        "container": ext,
                        "mimeType": f"audio/{ext}" if ext != "m4a" else "audio/mp4",
                        "bitrate": int(abr),
                        "videoId": clean_id,
                        "expiresAtEpochMs": expires_at,
                        "clientProfile": profile,
                    }
                    # Save to cache
                    RESOLUTION_CACHE[clean_id] = (now, res)
                    logger.info("RESOLVE_SUCCESS videoId=%s profile=%s format=%s", clean_id, profile, format_id)
                    return res
        except Exception as e:
            last_error = str(e)
            logger.warning("PROFILE_FAILED videoId=%s profile=%s error=%s", clean_id, profile, last_error)
            # Check for permanent errors (geo-block, copyright)
            lower = last_error.lower()
            if any(term in lower for term in ["not available in your country", "copyright claim", "private video", "has been removed"]):
                logger.info("PERMANENT_ERROR videoId=%s aborting profiles", clean_id)
                break

    return {
        "success": False,
        "error": f"Failed to resolve video {clean_id}: {last_error or 'No stream URL found'}",
        "videoId": clean_id,
    }


def search_ytdlp_core(query: str, limit: int = 5) -> dict:
    """Search YouTube catalog using yt-dlp ytsearch."""
    if not yt_dlp:
        return {"success": False, "error": "yt-dlp is not installed"}

    limit = max(1, min(limit, 20))
    search_target = f"ytsearch{limit}:{query}"

    try:
        ydl_opts = {
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "no_check_certificates": True,
            "extract_flat": True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            res = ydl.extract_info(search_target, download=False)
            entries = res.get("entries", []) if res else []
            candidates = []
            for item in entries:
                if not item:
                    continue
                candidates.append({
                    "id": item.get("id"),
                    "title": item.get("title"),
                    "uploader": item.get("uploader") or item.get("channel"),
                    "duration": item.get("duration"),
                    "viewCount": item.get("view_count"),
                    "url": item.get("url") or f"https://www.youtube.com/watch?v={item.get('id')}",
                })
            return {"success": True, "results": candidates}
    except Exception as e:
        logger.error("SEARCH_FAILED query=%s error=%s", query, str(e))
        return {"success": False, "error": str(e)}


# -------------------------------------------------------------
# Web Framework: FastAPI (if installed) or Standard Library HTTP
# -------------------------------------------------------------
try:
    from fastapi import FastAPI, Request
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.responses import JSONResponse
    from pydantic import BaseModel

    app = FastAPI(
        title="NotiFy yt-dlp Stream Resolver",
        version="1.0.0",
        description="Lightweight remote fallback backend for YouTube stream extraction",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    class ResolveRequest(BaseModel):
        videoId: str = ""
        canonicalUrl: str = ""

    class SearchRequest(BaseModel):
        query: str = ""
        limit: int = 5

    @app.get("/")
    @app.get("/health")
    @app.get("/ping")
    def health_check():
        version = getattr(yt_dlp, "__version__", "unknown") if yt_dlp else "none"
        return {
            "status": "ok",
            "uptime_seconds": int(time.time() - START_TIME),
            "ytdlp_version": version,
            "cached_streams": len(RESOLUTION_CACHE),
        }

    @app.post("/resolve-ytdlp")
    def resolve_endpoint(body: ResolveRequest):
        res = resolve_stream_core(body.videoId, body.canonicalUrl)
        return JSONResponse(content=res, status_code=200)

    @app.post("/search-ytdlp")
    def search_endpoint(body: SearchRequest):
        res = search_ytdlp_core(body.query, body.limit)
        return JSONResponse(content=res, status_code=200)

    USE_FASTAPI = True

except ImportError:
    USE_FASTAPI = False
    app = None


# Standard Library Fallback Server (runs with zero dependencies beyond yt-dlp)
if not USE_FASTAPI or __name__ == "__main__":
    from http.server import HTTPServer, BaseHTTPRequestHandler

    class FallbackRequestHandler(BaseHTTPRequestHandler):
        def _send_cors_headers(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")

        def do_OPTIONS(self):
            self.send_response(204)
            self._send_cors_headers()
            self.end_headers()

        def do_HEAD(self):
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()

        def do_GET(self):
            path = self.path.split("?")[0]
            if path in ["/", "/health", "/ping"]:
                self.send_response(200)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                payload = {
                    "status": "ok",
                    "uptime_seconds": int(time.time() - START_TIME),
                    "ytdlp_version": getattr(yt_dlp, "__version__", "unknown") if yt_dlp else "none",
                    "cached_streams": len(RESOLUTION_CACHE),
                }
                self.wfile.write(json.dumps(payload).encode("utf-8"))
            else:
                self.send_response(404)
                self.end_headers()

        def do_POST(self):
            path = self.path.split("?")[0]
            content_length = int(self.headers.get("Content-Length", 0))
            body_bytes = self.rfile.read(content_length) if content_length > 0 else b"{}"
            try:
                body = json.loads(body_bytes.decode("utf-8"))
            except Exception:
                body = {}

            if path == "/resolve-ytdlp":
                video_id = body.get("videoId", "")
                canonical_url = body.get("canonicalUrl", "")
                result = resolve_stream_core(video_id, canonical_url)
            elif path == "/search-ytdlp":
                query = body.get("query", "")
                limit = int(body.get("limit", 5))
                result = search_ytdlp_core(query, limit)
            else:
                result = {"success": False, "error": f"Unknown endpoint {path}"}

            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(result).encode("utf-8"))

        def log_message(self, format, *args):
            logger.info("%s - %s", self.address_string(), format % args)


def run_standalone(port: int = 8080):
    if USE_FASTAPI:
        import uvicorn
        logger.info("Starting FastAPI server on 0.0.0.0:%d", port)
        uvicorn.run("main:app", host="0.0.0.0", port=port, log_level="info")
    else:
        from socketserver import ThreadingMixIn
        class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
            daemon_threads = True

        server_address = ("0.0.0.0", port)
        httpd = ThreadedHTTPServer(server_address, FallbackRequestHandler)
        logger.info("Starting Python Threaded HTTP Server on 0.0.0.0:%d", port)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            logger.info("Server stopped.")
            httpd.server_close()


if __name__ == "__main__":
    port = int(os.getenv("PORT", 8080))
    run_standalone(port)
