"""ARIX Screen Sharing — signaling server (protocol v2).

Responsibilities
* Create short-lived six-digit pairing codes (POST /api/session).
* Authenticate the two peers of a session and relay WebRTC signaling between them.
* Hand each authenticated peer its ICE servers, including short-lived TURN
  credentials derived from a server-side secret (the secret never leaves the server).

Protocol (server -> client JSON messages)
  welcome            {role, protocol, ice_servers, receiver_token?, peer_present}
  paired             both peers are connected; negotiation may start
  signal             {from, data}  relayed payload from the other peer
  peer_disconnected  the other peer's socket closed (session stays alive for a grace period)
  session_ended      {reason}      session is over; do not reconnect
  error              {code, message}

Client -> server: any JSON object is relayed to the other peer, except the reserved
control messages {"type":"ping"} (answered with pong) and {"type":"stop"} (sender only,
ends the session).

NOTE: this server provides signaling only. Media flows peer-to-peer (or through TURN).
"""
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import string
import time
from collections import deque
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Deque, Dict, List, Optional
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Request, WebSocket
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

log = logging.getLogger("arix")

BASE = Path(__file__).resolve().parent.parent
WEB = BASE / "web"
VERSION = "0.3.0"
PROTOCOL = 2


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


# ---- tunables (environment variables, all optional) -------------------------
PAIRING_TTL = _env_int("ARIX_PAIRING_TTL", 300)        # seconds a code can be entered
GRACE = _env_int("ARIX_RECONNECT_GRACE", 120)          # seconds a paired session survives with nobody connected
MAX_LIFETIME = _env_int("ARIX_MAX_SESSION_SECONDS", 12 * 3600)
MAX_SESSIONS = _env_int("ARIX_MAX_SESSIONS", 500)
MAX_MESSAGE_BYTES = 64 * 1024
TURN_TTL = _env_int("ARIX_TURN_TTL", 6 * 3600)
TRUST_PROXY = os.environ.get("ARIX_TRUST_PROXY", "") == "1"  # honour X-Forwarded-For (behind nginx/caddy)
ROLES = {"sender", "receiver"}


# ---- helpers ----------------------------------------------------------------
class RateLimiter:
    """Sliding-window limiter keyed by an arbitrary string (usually client IP)."""

    def __init__(self, limit: int, window: float):
        self.limit = limit
        self.window = window
        self._hits: Dict[str, Deque[float]] = {}

    def _prune(self, key: str, now: float) -> Deque[float]:
        dq = self._hits.setdefault(key, deque())
        while dq and now - dq[0] > self.window:
            dq.popleft()
        if not dq:
            self._hits.pop(key, None)
            return deque()
        return dq

    def blocked(self, key: str) -> bool:
        return len(self._prune(key, time.monotonic())) >= self.limit

    def hit(self, key: str) -> None:
        now = time.monotonic()
        self._prune(key, now)
        self._hits.setdefault(key, deque()).append(now)

    def allow(self, key: str) -> bool:
        if self.blocked(key):
            return False
        self.hit(key)
        return True

    def reset(self) -> None:
        self._hits.clear()


create_limiter = RateLimiter(10, 60)       # session creations per IP per minute
fail_limiter = RateLimiter(10, 60)         # failed joins per IP per minute
global_fail_limiter = RateLimiter(60, 60)  # failed joins from everyone per minute


@dataclass
class Session:
    code: str
    sender_token: str
    sid: str                                # random id used for TURN usernames (never the pairing code)
    created: float
    receiver_token: Optional[str] = None    # set when the first receiver claims the code
    sender: Optional[WebSocket] = None
    receiver: Optional[WebSocket] = None
    last_seen: float = 0.0
    ended: bool = False


sessions: Dict[str, Session] = {}


def client_ip(ws_or_req) -> str:
    if TRUST_PROXY:
        fwd = ws_or_req.headers.get("x-forwarded-for")
        if fwd:
            return fwd.split(",")[0].strip()
    return ws_or_req.client.host if ws_or_req.client else "unknown"


def is_expired(s: Session, now: float) -> bool:
    if s.ended or now - s.created > MAX_LIFETIME:
        return True
    if s.receiver_token is None:                      # code never claimed: only valid for PAIRING_TTL
        return now - s.created > PAIRING_TTL
    if s.sender is not None or s.receiver is not None:
        return False
    return now - s.last_seen > GRACE                  # paired before, nobody connected for too long


def build_ice_servers(s: Session) -> List[dict]:
    servers: List[dict] = []
    stun = [u.strip() for u in os.environ.get("ARIX_STUN_URLS", "").split(",") if u.strip()]
    if stun:
        servers.append({"urls": stun})
    turn = [u.strip() for u in os.environ.get("ARIX_TURN_URLS", "").split(",") if u.strip()]
    secret = os.environ.get("ARIX_TURN_SECRET", "")
    if turn and secret:  # coturn "use-auth-secret" / TURN REST API credentials
        username = f"{int(time.time()) + TURN_TTL}:{s.sid}"
        digest = hmac.new(secret.encode(), username.encode(), hashlib.sha1).digest()
        servers.append({"urls": turn, "username": username,
                        "credential": base64.b64encode(digest).decode()})
    return servers


async def safe_send(ws: Optional[WebSocket], obj: dict) -> bool:
    if ws is None:
        return False
    try:
        await ws.send_json(obj)
        return True
    except Exception:
        return False


async def safe_close(ws: Optional[WebSocket], code: int = 1000) -> None:
    if ws is None:
        return
    try:
        await ws.close(code=code)
    except Exception:
        pass


async def reject(ws: WebSocket, code: str, message: str) -> None:
    await safe_send(ws, {"type": "error", "code": code, "message": message})
    await safe_close(ws, 1008)


def origin_ok(ws: WebSocket) -> bool:
    """Block cross-site WebSocket hijacking. Native apps send no Origin header."""
    origin = ws.headers.get("origin")
    if not origin:
        return True
    host = ws.headers.get("host", "")
    allowed = {h.strip() for h in os.environ.get("ARIX_ALLOWED_ORIGINS", "").split(",") if h.strip()}
    return urlparse(origin).netloc == host or origin in allowed


async def end_session(s: Session, reason: str) -> None:
    if s.ended:
        return
    s.ended = True
    sessions.pop(s.code, None)
    for ws in (s.sender, s.receiver):
        await safe_send(ws, {"type": "session_ended", "reason": reason})
        await safe_close(ws, 1000)
    s.sender = s.receiver = None


async def reap() -> None:
    now = time.time()
    for s in [s for s in sessions.values() if is_expired(s, now)]:
        await end_session(s, "expired")


@asynccontextmanager
async def lifespan(_: FastAPI):
    async def loop():
        while True:
            await asyncio.sleep(10)
            try:
                await reap()
            except Exception:  # never let the janitor die
                log.exception("reaper error")

    task = asyncio.create_task(loop())
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(title="ARIX Screen Sharing", version=VERSION, lifespan=lifespan)
app.mount("/static", StaticFiles(directory=WEB), name="static")

CSP = ("default-src 'self'; connect-src 'self' ws: wss:; media-src 'self' blob:; "
       "img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("Cache-Control", "no-store")
    if response.headers.get("content-type", "").startswith("text/html"):
        response.headers.setdefault("Content-Security-Policy", CSP)
    return response


# ---- HTTP API ---------------------------------------------------------------
@app.get("/")
async def home():
    return FileResponse(WEB / "index.html")


@app.get("/api/health")
async def health():
    return {
        "service": "ARIX Screen Sharing",
        "status": "online",
        "version": VERSION,
        "protocol": PROTOCOL,
        "active_sessions": len(sessions),
        "turn_configured": bool(os.environ.get("ARIX_TURN_URLS") and os.environ.get("ARIX_TURN_SECRET")),
    }


@app.get("/api/config")
async def public_config():
    """Non-secret info only. ICE servers/TURN credentials are delivered over the authenticated WebSocket."""
    return {
        "protocol": PROTOCOL,
        "pairing_ttl": PAIRING_TTL,
        "stun_configured": bool(os.environ.get("ARIX_STUN_URLS")),
        "turn_configured": bool(os.environ.get("ARIX_TURN_URLS") and os.environ.get("ARIX_TURN_SECRET")),
    }


@app.post("/api/session")
async def create_session(request: Request):
    if not create_limiter.allow(client_ip(request)):
        raise HTTPException(429, "Too many codes requested. Wait a minute and try again.")
    await reap()
    if len(sessions) >= MAX_SESSIONS:
        raise HTTPException(503, "Server is busy. Try again shortly.")
    for _ in range(50):
        code = "".join(secrets.choice(string.digits) for _ in range(6))
        if code not in sessions:
            now = time.time()
            sessions[code] = Session(code=code, sender_token=secrets.token_urlsafe(24),
                                     sid=secrets.token_hex(8), created=now, last_seen=now)
            return {"code": code, "sender_token": sessions[code].sender_token, "expires_in": PAIRING_TTL}
    raise HTTPException(503, "Could not create pairing code")


# ---- WebSocket signaling ----------------------------------------------------
@app.websocket("/ws/{code}/{role}")
async def signaling(websocket: WebSocket, code: str, role: str):
    await websocket.accept()
    ip = client_ip(websocket)

    if not origin_ok(websocket):
        return await reject(websocket, "origin", "Origin not allowed")
    if fail_limiter.blocked(ip) or global_fail_limiter.blocked("*"):
        return await reject(websocket, "rate_limited", "Too many failed attempts. Wait a minute.")

    def fail():
        fail_limiter.hit(ip)
        global_fail_limiter.hit("*")

    s = sessions.get(code) if role in ROLES else None
    if s is None or is_expired(s, time.time()):
        fail()
        return await reject(websocket, "invalid_code", "Code is invalid or expired")

    token = websocket.query_params.get("token", "")
    if role == "sender":
        if not hmac.compare_digest(token, s.sender_token):
            fail()
            return await reject(websocket, "unauthorized", "Not authorized for this session")
    else:
        if s.receiver_token is None:
            s.receiver_token = secrets.token_urlsafe(18)        # first receiver claims the code
        elif not hmac.compare_digest(token, s.receiver_token):
            fail()
            return await reject(websocket, "code_in_use", "This code is already in use")

    # attach (a reconnecting peer replaces its own stale socket)
    old = s.sender if role == "sender" else s.receiver
    if role == "sender":
        s.sender = websocket
    else:
        s.receiver = websocket
    if old is not None:
        await safe_close(old, 1000)
    s.last_seen = time.time()

    other = lambda: s.receiver if role == "sender" else s.sender  # noqa: E731
    welcome = {"type": "welcome", "role": role, "protocol": PROTOCOL,
               "ice_servers": build_ice_servers(s), "peer_present": other() is not None}
    if role == "receiver":
        welcome["receiver_token"] = s.receiver_token
    await safe_send(websocket, welcome)
    if other() is not None:
        for peer in (s.sender, s.receiver):
            await safe_send(peer, {"type": "paired"})

    window: Deque[float] = deque()
    try:
        while True:
            msg = await websocket.receive()
            if msg["type"] == "websocket.disconnect":
                break
            now = time.monotonic()
            window.append(now)
            while window and now - window[0] > 10:
                window.popleft()
            if len(window) > 300:                                    # >30 msg/s sustained: abuse
                await reject(websocket, "rate_limited", "Too many messages")
                break
            text = msg.get("text")
            if text is None or len(text) > MAX_MESSAGE_BYTES:
                await safe_send(websocket, {"type": "error", "code": "bad_message", "message": "Text JSON up to 64 KB only"})
                continue
            try:
                payload = json.loads(text)
            except ValueError:
                payload = None
            if not isinstance(payload, dict):
                await safe_send(websocket, {"type": "error", "code": "bad_json", "message": "Expected a JSON object"})
                continue
            s.last_seen = time.time()
            kind = payload.get("type")
            if kind == "ping":
                await safe_send(websocket, {"type": "pong"})
            elif kind == "stop" and role == "sender":
                await end_session(s, "sender_stopped")
                return
            else:
                await safe_send(other(), {"type": "signal", "from": role, "data": payload})
    except RuntimeError:
        pass  # receive() after the socket was closed by end_session/replacement
    finally:
        current = s.sender if role == "sender" else s.receiver
        if current is websocket:
            if role == "sender":
                s.sender = None
            else:
                s.receiver = None
            s.last_seen = time.time()
            await safe_send(other(), {"type": "peer_disconnected"})
