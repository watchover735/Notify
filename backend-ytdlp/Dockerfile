FROM python:3.11-slim

# Install system dependencies (ffmpeg, ca-certificates, curl)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        ca-certificates \
        curl && \
    rm -rf /var/lib/apt/lists/*

# Set up non-root user (Hugging Face Spaces UID 1000 standard)
RUN useradd -m -u 1000 user

USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH \
    PORT=7860

WORKDIR $HOME/app

# Install Python dependencies
COPY --chown=user requirements.txt .
RUN pip install --no-cache-dir --upgrade -r requirements.txt

# Copy backend code
COPY --chown=user . .

# Expose default Hugging Face Spaces port
EXPOSE 7860

# Run FastAPI with uvicorn on $PORT (7860)
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-7860}"]
