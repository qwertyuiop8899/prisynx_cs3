"""kisskh-relay — challenge-aware fetch relay for the KissKH CloudStream plugin.

KissKH sits behind Cloudflare. On some networks every non-browser client gets a
managed challenge, whatever its TLS fingerprint, and only a real browser passes
by running the JS and receiving the `cf_clearance` cookie. This relay therefore
works in two steps:

1. fast path: request with a browser TLS fingerprint (curl_cffi) and the
   clearance cookie obtained earlier — milliseconds, used for most calls;
2. solve path: if the fast path is challenged, ask FlareSolverr (headless
   Chrome) for the page, keep the returned cookies and User-Agent, and serve
   that body. The clearance is then reused by the fast path until it expires.

The plugin only needs to rewrite its API calls:

    Relay template: http://<host>:8191/fetch?token=<token>&url={url}

Generated subtitles (decrypted legacy files, DeepL translations) are published
with POST /store and served back from GET /sub/<id>.srt, so the player always
receives a plain HTTP URL.
"""

from __future__ import annotations

import asyncio
import os
import secrets
import time
from contextlib import asynccontextmanager
from urllib.parse import urlparse

import httpx
from curl_cffi.requests import AsyncSession
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, Response

TOKEN = os.getenv("RELAY_TOKEN", "").strip()


def _normalize_cookies(raw: str) -> str:
    """Accept a bare cf_clearance value, a real cookie string, or a copied header."""
    value = " ".join(raw.split())
    if not value:
        return ""
    if "=" not in value:
        # Valore incollato senza nome: assumiamo cf_clearance.
        return f"cf_clearance={value}"
    for prefix in ("cookie:", "set-cookie:"):
        if value.lower().startswith(prefix):
            value = value[len(prefix):].strip()
    return value

# Comma separated list of allowed hosts. "kisskh.co" also allows "*.kisskh.co".
ALLOWED_HOSTS = sorted(
    {h.strip().lower() for h in os.getenv("RELAY_HOSTS", "kisskh.co").split(",") if h.strip()}
)

IMPERSONATE = os.getenv("RELAY_IMPERSONATE", "chrome").strip() or "chrome"
TIMEOUT = float(os.getenv("RELAY_TIMEOUT", "25"))
CACHE_TTL = float(os.getenv("RELAY_CACHE_TTL", "30"))
MAX_BYTES = int(os.getenv("RELAY_MAX_BYTES", str(8 * 1024 * 1024)))
MAX_CACHE_ENTRIES = int(os.getenv("RELAY_CACHE_ENTRIES", "256"))
# Log di ogni richiesta (host, path, esito): utile per capire cosa chiede la TV.
VERBOSE = os.getenv("RELAY_VERBOSE", "0").strip() not in ("0", "false", "no")

# FlareSolverr endpoint, e.g. http://flaresolverr:8191/v1 (empty = disabled).
SOLVER_URL = os.getenv("RELAY_SOLVER_URL", "").strip()
# Headless Chrome on ARM boxes needs a lot of time: 120 s is a sane default.
SOLVER_TIMEOUT = float(os.getenv("RELAY_SOLVER_TIMEOUT", "120"))
# Extra seconds FlareSolverr should wait after the page is "ready" (0 = default).
SOLVER_WAIT = float(os.getenv("RELAY_SOLVER_WAIT", "0"))
CLEARANCE_TTL = float(os.getenv("RELAY_CLEARANCE_TTL", "1500"))  # ~25 min
FAST_RETRY_AFTER = float(os.getenv("RELAY_FAST_RETRY", "600"))  # 10 min

# URL usata per riscaldare il clearance in background. Meglio la home del sito:
# il challenge sull'endpoint API, navigato da solo, tende a non risolversi mai,
# mentre la home si comporta come un browser vero e lascia i cookie per le API.
WARM_URL = os.getenv("RELAY_WARM_URL", "").strip() or (
    f"https://{ALLOWED_HOSTS[0]}/" if ALLOWED_HOSTS else ""
)
WARM_ENABLED = os.getenv("RELAY_WARM", "1").strip() not in ("0", "false", "no")

UPSTREAM_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "en-US,en;q=0.9,it;q=0.8",
}


@asynccontextmanager
async def lifespan(_: FastAPI):
    task: asyncio.Task | None = None
    if SOLVER_URL:
        task = asyncio.create_task(_warm_loop())
    try:
        yield
    finally:
        if task is not None:
            task.cancel()


app = FastAPI(
    title="kisskh-relay",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)

# url -> (expires_at, status, body, content_type, source)
_cache: dict[str, tuple[float, int, bytes, str, str]] = {}
# id -> (expires_at, text) per i sottotitoli generati dal plugin (decifrati/DeepL)
_store: dict[str, tuple[float, str]] = {}
STORE_TTL = float(os.getenv("RELAY_STORE_TTL", "21600"))  # 6 ore
MAX_STORE_CHARS = int(os.getenv("RELAY_STORE_MAX", "2000000"))
# cf_clearance state, shared by all requests (bound to this box's public IP).
# RELAY_COOKIES / RELAY_USER_AGENT allow seeding it by hand (no FlareSolverr).
_clearance: dict[str, object] = {
    "cookies": _normalize_cookies(os.getenv("RELAY_COOKIES", "")),
    "ua": os.getenv("RELAY_USER_AGENT", "").strip(),
    "expires": 0.0,
}
if _clearance["cookies"]:
    _clearance["expires"] = time.monotonic() + CLEARANCE_TTL
    print(
        f"[seed] cookie(s)={[p.split('=', 1)[0] for p in str(_clearance['cookies']).split(';')]} "
        f"ua={'set' if _clearance['ua'] else 'MISSING'}",
        flush=True,
    )
_fast_disabled_until = 0.0
_warming = False
_stats = {"fast": 0, "solved": 0, "failed": 0}


def _host_allowed(host: str) -> bool:
    host = (host or "").lower()
    return any(host == allowed or host.endswith("." + allowed) for allowed in ALLOWED_HOSTS)


def _validate(raw: str) -> str:
    try:
        parsed = urlparse(raw)
    except ValueError:
        return ""
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return ""
    if not _host_allowed(parsed.hostname or ""):
        return ""
    return raw


def _token_ok(token: str) -> bool:
    if not TOKEN:
        return True
    return secrets.compare_digest(token.strip(), TOKEN)


def _cache_get(url: str) -> tuple[int, bytes, str, str] | None:
    if CACHE_TTL <= 0:
        return None
    entry = _cache.get(url)
    if entry is None:
        return None
    expires_at, status, body, content_type, source = entry
    if expires_at < time.monotonic():
        _cache.pop(url, None)
        return None
    return status, body, content_type, source


def _cache_put(url: str, status: int, body: bytes, content_type: str, source: str) -> None:
    if CACHE_TTL <= 0 or status != 200:
        return
    if len(_cache) >= MAX_CACHE_ENTRIES:
        _cache.clear()
    _cache[url] = (time.monotonic() + CACHE_TTL, status, body, content_type, source)


def _clearance_valid() -> bool:
    return bool(_clearance["cookies"]) and float(_clearance["expires"]) > time.monotonic()


def _store_clearance(cookies: str, ua: str) -> None:
    _clearance["cookies"] = cookies
    _clearance["ua"] = ua
    _clearance["expires"] = time.monotonic() + CLEARANCE_TTL


def _guess_type(body: bytes) -> str:
    head = body.lstrip()[:1]
    if head in (b"{", b"["):
        return "application/json"
    return "text/html"


def _drop_dead_clearance(status: int, body: bytes) -> None:
    """A 403 challenge means the cookie is no longer accepted: force a refresh."""
    if status != 403 or b"Just a moment" not in body[:2000]:
        return
    if _clearance["cookies"]:
        print("[expired] cf_clearance rifiutato: rinnovalo", flush=True)
    _clearance["expires"] = 0.0


async def _fast_get(target: str) -> tuple[int, bytes, str]:
    headers = dict(UPSTREAM_HEADERS)
    headers["Referer"] = f"{urlparse(target).scheme}://{urlparse(target).netloc}/"
    if _clearance["ua"]:
        headers["User-Agent"] = str(_clearance["ua"])
    if _clearance["cookies"]:
        headers["Cookie"] = str(_clearance["cookies"])

    async with AsyncSession(impersonate=IMPERSONATE, timeout=TIMEOUT) as session:
        response = await session.get(target, headers=headers, allow_redirects=True)

    content_type = (response.headers.get("content-type") or _guess_type(response.content))
    return response.status_code, response.content or b"", content_type.split(";")[0]


async def _solve(target: str) -> tuple[int, bytes, str]:
    payload = {
        "cmd": "request.get",
        "url": target,
        "maxTimeout": int(SOLVER_TIMEOUT * 1000),
    }
    if SOLVER_WAIT > 0:
        payload["waitInSeconds"] = SOLVER_WAIT

    async with httpx.AsyncClient(timeout=SOLVER_TIMEOUT + 30) as client:
        response = await client.post(SOLVER_URL, json=payload)
        data = response.json()

    if data.get("status") != "ok":
        raise RuntimeError(str(data.get("message") or "solver error")[:120])

    solution = data.get("solution") or {}
    body = (solution.get("response") or "").encode("utf-8")
    status = int(solution.get("status") or 200)

    cookie_parts = [
        f"{cookie.get('name')}={cookie.get('value')}"
        for cookie in (solution.get("cookies") or [])
        if cookie.get("name") and cookie.get("value")
    ]
    user_agent = str(solution.get("userAgent") or "")
    if cookie_parts:
        _store_clearance("; ".join(cookie_parts), user_agent)

    content_type = _guess_type(body)
    return status, body, content_type


async def _fetch(target: str) -> tuple[int, bytes, str, str]:
    global _fast_disabled_until

    if _clearance_valid() and time.monotonic() >= _fast_disabled_until:
        try:
            status, body, content_type = await _fast_get(target)
            if status == 200:
                _stats["fast"] += 1
                return status, body, content_type, "fast"
            _drop_dead_clearance(status, body)
        except Exception:
            pass
        # Cookie no longer accepted: stop wasting a round trip for a while.
        _fast_disabled_until = time.monotonic() + FAST_RETRY_AFTER

    solver_error: str | None = None
    if SOLVER_URL:
        try:
            status, body, content_type = await _solve(target)
            _stats["solved"] += 1
            return status, body, content_type, "solved"
        except Exception as exc:
            _stats["failed"] += 1
            solver_error = f"{exc.__class__.__name__}: {exc}"

    status, body, content_type = await _fast_get(target)
    _drop_dead_clearance(status, body)
    if status == 200 or solver_error is None:
        return status, body, content_type, "fast"

    # No clearance and the solver failed: return the real reason instead of an
    # upstream 4xx/5xx that looks like a relay bug.
    raise RuntimeError(solver_error)


async def _warm_loop() -> None:
    """Keep the cf_clearance fresh so the plugin never waits for a solve."""
    global _warming

    # Give FlareSolverr time to boot on slow boxes (it can take minutes on ARM).
    await asyncio.sleep(20)

    failures = 0
    while True:
        try:
            stale = not _clearance_valid() or (
                float(_clearance["expires"]) - time.monotonic() < CLEARANCE_TTL * 0.3
            )
            if stale and WARM_URL:
                _warming = True
                status, _, _ = await _solve(WARM_URL)
                failures = 0
                print(f"[warm] solve status={status} clearance={_clearance_valid()}", flush=True)
        except Exception as exc:
            failures += 1
            # Un solve fallito costa minuti di CPU: non martellare il box.
            delay = min(60 * (2 ** min(failures, 4)), 900)
            print(
                f"[warm] failed ({failures}x): {exc.__class__.__name__} — retry in {delay}s",
                flush=True,
            )
            _warming = False
            await asyncio.sleep(delay)
            continue
        finally:
            _warming = False

        await asyncio.sleep(60)


@app.post("/store")
async def store(request: Request, token: str = Query(""), ext: str = Query("srt")) -> Response:
    """Pubblica un sottotitolo generato dal plugin (decifrato o tradotto)."""
    if not _token_ok(token):
        return JSONResponse({"error": "invalid token"}, status_code=401)

    body_bytes = await request.body()
    if not body_bytes:
        return JSONResponse({"error": "empty body"}, status_code=400)
    if len(body_bytes) > MAX_STORE_CHARS:
        return JSONResponse({"error": "too large"}, status_code=413)

    now = time.monotonic()
    if len(_store) >= 256:
        for key in [k for k, (expiry, _) in _store.items() if expiry < now]:
            _store.pop(key, None)
        while len(_store) >= 256:
            _store.pop(next(iter(_store)), None)

    key = secrets.token_hex(8)
    _store[key] = (now + STORE_TTL, body_bytes.decode("utf-8", "replace"))
    extension = "vtt" if ext.strip().lower() == "vtt" else "srt"

    host = request.headers.get("host") or "127.0.0.1:8191"
    scheme = request.headers.get("x-forwarded-proto") or "http"
    url = f"{scheme}://{host}/sub/{key}.{extension}"
    if VERBOSE:
        print(f"[store] {key}.{extension} {len(body_bytes)} byte", flush=True)
    return JSONResponse({"url": url})


@app.get("/sub/{name}")
async def sub(name: str) -> Response:
    key = name.split(".")[0]
    entry = _store.get(key)
    if entry is None or entry[0] < time.monotonic():
        _store.pop(key, None)
        return JSONResponse({"error": "not found"}, status_code=404)

    media = "text/vtt" if name.endswith(".vtt") else "application/x-subrip"
    if VERBOSE:
        print(f"[sub] {name} {len(entry[1])} byte", flush=True)
    return Response(
        content=entry[1].encode("utf-8"),
        media_type=media,
        headers={"Cache-Control": "no-store"},
    )


@app.get("/healthz")
async def healthz() -> dict[str, object]:
    return {
        "ok": True,
        "impersonate": IMPERSONATE,
        "hosts": ALLOWED_HOSTS,
        "solver": bool(SOLVER_URL),
        "clearance": _clearance_valid(),
        "ua_set": bool(_clearance["ua"]),
        "ua": str(_clearance["ua"])[:40],
        "cookie_names": [
            part.split("=", 1)[0].strip()
            for part in str(_clearance["cookies"]).split(";")
            if "=" in part
        ],
        "warming": _warming,
        "stats": _stats,
    }


@app.get("/fetch")
async def fetch(
    url: str = Query(..., min_length=8),
    token: str = Query(""),
) -> Response:
    if not _token_ok(token):
        return JSONResponse({"error": "invalid token"}, status_code=401)

    target = _validate(url)
    if not target:
        host = (urlparse(url).hostname or "").lower()
        print(f"[deny] host non in allowlist: {host}", flush=True)
        return JSONResponse(
            {"error": "host not allowed", "host": host, "allowed": ALLOWED_HOSTS},
            status_code=403,
        )

    cached = _cache_get(target)
    if cached is not None:
        status, body, content_type, source = cached
        return Response(
            content=body,
            status_code=status,
            media_type=content_type,
            headers={"X-Relay": "cache", "X-Relay-Source": source},
        )

    try:
        status, body, content_type, source = await _fetch(target)
    except Exception as exc:
        return JSONResponse({"error": f"relay: {exc.__class__.__name__}: {str(exc)[:200]}"}, status_code=502)

    if VERBOSE:
        print(f"[req] {status} {source} {target} bytes={len(body)}", flush=True)

    if len(body) > MAX_BYTES:
        return JSONResponse({"error": "response too large"}, status_code=502)

    _cache_put(target, status, body, content_type, source)

    return Response(
        content=body,
        status_code=status,
        media_type=content_type,
        headers={"X-Relay": "miss", "X-Relay-Source": source},
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8191")), access_log=False)
