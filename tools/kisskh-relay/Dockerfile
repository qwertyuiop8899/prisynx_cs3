FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# curl_cffi ships prebuilt wheels with libcurl-impersonate: no browser needed.
RUN pip install --no-cache-dir \
    "fastapi>=0.115" \
    "uvicorn[standard]>=0.30" \
    "curl_cffi>=0.7" \
    "httpx>=0.27"

COPY app.py .

EXPOSE 8191

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8191/healthz',timeout=4)"

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8191", "--no-access-log"]
