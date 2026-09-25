---
title: notify-ytdlp-backend
emoji: 🎵
colorFrom: blue
colorTo: green
sdk: docker
app_port: 7860
---

# NotiFy Remote yt-dlp Audio Stream Backend

Lightweight audio stream extraction microservice for **NotiFy**, offloading CPU-intensive `yt-dlp` execution and FFmpeg processing from Android mobile devices to eliminate phone heating and reduce APK size.

---

## 🚀 Features

- **Direct Audio Stream Extraction**: Resolves YouTube audio (`m4a`, `opus`, `mp3`) via `yt-dlp`.
- **Client Profile Cycling**: Automatic fallback across multiple YouTube player clients (`tv_embedded`, `visionos`, `android`, `ios`, `mweb`).
- **In-Memory Caching**: 30-minute caching of resolved streams to minimize redundant network extraction.
- **Cold-Start Keep-Alive**: `/health` and `/ping` endpoints designed for automated pingers or the mobile app pre-warming.
- **Dual Runtime Mode**:
  - Runs with **FastAPI + Uvicorn** in production / Docker.
  - Automatically falls back to Python's built-in **`http.server.ThreadingHTTPServer`** when run directly with `python main.py` with zero dependencies beyond `yt-dlp`.

---

## 📡 API Endpoints

### 1. Resolve Audio Stream
- **Method**: `POST /resolve-ytdlp`
- **Request Body**:
```json
{
  "videoId": "dQw4w9WgXcQ",
  "canonicalUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
}
```
- **Response**:
```json
{
  "success": true,
  "streamUrl": "https://rr1---sn-ci5gup-a0ie.googlevideo.com/videoplayback?...",
  "format": "ytdlp_m4a_140",
  "formatId": "140",
  "container": "m4a",
  "mimeType": "audio/mp4",
  "bitrate": 128,
  "videoId": "dQw4w9WgXcQ",
  "expiresAtEpochMs": 1727280000000,
  "clientProfile": "tv_embedded,visionos"
}
```

### 2. Search Catalog
- **Method**: `POST /search-ytdlp`
- **Request Body**:
```json
{
  "query": "Arijit Singh Kesariya",
  "limit": 5
}
```

### 3. Health & Keep-Alive Ping
- **Method**: `GET /health` or `GET /ping`
- **Response**:
```json
{
  "status": "ok",
  "uptime_seconds": 3600,
  "ytdlp_version": "2026.08.19",
  "cached_streams": 12
}
```

---

## 🛠️ Local Development & Testing

### Option A: Direct Python (Zero extra packages beyond yt-dlp)
```bash
python main.py
# Server runs on http://localhost:8080
```

### Option B: FastAPI + Uvicorn
```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8080 --reload
```

### Option C: Docker
```bash
docker build -t notify-ytdlp-backend .
docker run -p 8080:8080 notify-ytdlp-backend
```

---

## ☁️ Free Tier Deployment Guide

### Deploying to Render (Free Tier)
1. Fork / push this repo to GitHub.
2. Go to [Render Dashboard](https://dashboard.render.com/) -> **New Web Service**.
3. Connect your repository.
4. Select **Docker** environment (or specify `backend-ytdlp/Dockerfile` as the Dockerfile Path, and `backend-ytdlp` as Root Directory).
5. Set Health Check path to `/health`.
6. Click **Create Web Service**.
7. Once deployed, copy your Render URL (e.g., `https://notify-ytdlp-backend.onrender.com`) and update `SupabaseConfig.YTDLP_BACKEND_URL` in the Android app.

### Deploying to Railway
1. Go to [Railway](https://railway.app/) -> **New Project** -> **Deploy from GitHub repo**.
2. Set Root Directory to `backend-ytdlp`.
3. Railway automatically detects Dockerfile and deploys.

### Deploying to Fly.io
```bash
cd backend-ytdlp
fly launch
fly deploy
```

---

## ⏰ Keep-Alive Ping (Anti-Sleep for Free Tier)
Free-tier hosting providers (like Render) spin down instances after 15 minutes of inactivity. To prevent cold starts:
1. **Client Pre-Warm**: NotiFy Android app automatically pings `/health` when launched in `MainActivity.kt`.
2. **Free Cron Pingers**: Set up a free 10-minute HTTP monitor using [cron-job.org](https://cron-job.org), [UptimeRobot](https://uptimerobot.com), or GitHub Actions workflow to ping `https://<YOUR_RENDER_URL>/health`.
