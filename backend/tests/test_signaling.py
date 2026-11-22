"""Backend tests: health, session creation, expiry, pairing, relay, disconnect, auth, rate limits.
Run from backend/:  python -m pytest -q
These test the signaling server only (no real phone, no media)."""
import base64
import hashlib
import hmac
import sys
import time
from pathlib import Path

import pytest
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import main  # noqa: E402


@pytest.fixture(autouse=True)
def clean(monkeypatch):
    main.sessions.clear()
    for lim in (main.create_limiter, main.fail_limiter, main.global_fail_limiter):
        lim.reset()
    for var in ("ARIX_STUN_URLS", "ARIX_TURN_URLS", "ARIX_TURN_SECRET"):
        monkeypatch.delenv(var, raising=False)
    yield
    main.sessions.clear()


@pytest.fixture
def client():
    with TestClient(main.app) as c:
        yield c


def new_session(client):
    r = client.post("/api/session")
    assert r.status_code == 200
    return r.json()


def sender_ws(client, s):
    return client.websocket_connect(f"/ws/{s['code']}/sender?token={s['sender_token']}")


def receiver_ws(client, s, token=""):
    return client.websocket_connect(f"/ws/{s['code']}/receiver?token={token}")


def expect_error(ws, code):
    msg = ws.receive_json()
    assert msg["type"] == "error" and msg["code"] == code, msg
    with pytest.raises(WebSocketDisconnect):
        ws.receive_json()


# --- HTTP --------------------------------------------------------------------
def test_health(client):
    d = client.get("/api/health").json()
    assert d["status"] == "online" and d["protocol"] == 2 and "version" in d
    assert d["turn_configured"] is False


def test_index_served_with_security_headers(client):
    r = client.get("/")
    assert r.status_code == 200 and "ARIX" in r.text
    assert "default-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["x-content-type-options"] == "nosniff"


def test_create_session_shape(client):
    s = new_session(client)
    assert len(s["code"]) == 6 and s["code"].isdigit()
    assert s["expires_in"] == main.PAIRING_TTL and len(s["sender_token"]) >= 20


def test_codes_are_unique_and_random(client):
    codes = {new_session(client)["code"] for _ in range(8)}
    assert len(codes) == 8


def test_create_session_rate_limited(client):
    for _ in range(10):
        assert client.post("/api/session").status_code == 200
    assert client.post("/api/session").status_code == 429


# --- expiry ------------------------------------------------------------------
def test_unclaimed_code_expires(client):
    s = new_session(client)
    main.sessions[s["code"]].created -= main.PAIRING_TTL + 1
    with receiver_ws(client, s) as ws:
        expect_error(ws, "invalid_code")


def test_unclaimed_code_with_connected_sender_expires_via_reaper(client):
    s = new_session(client)
    with sender_ws(client, s) as ws:
        assert ws.receive_json()["type"] == "welcome"
        main.sessions[s["code"]].created -= main.PAIRING_TTL + 1
        client.post("/api/session")  # triggers reap()
        assert ws.receive_json() == {"type": "session_ended", "reason": "expired"}


def test_claimed_session_outlives_pairing_ttl(client):
    s = new_session(client)
    with sender_ws(client, s) as snd, receiver_ws(client, s) as rcv:
        snd.receive_json(); rcv.receive_json()
        main.sessions[s["code"]].created -= main.PAIRING_TTL * 10
        client.post("/api/session")  # reap(): still alive because it is paired & connected
        assert s["code"] in main.sessions
    # after both leave, grace period applies
    main.sessions[s["code"]].last_seen -= main.GRACE + 1
    client.post("/api/session")
    assert s["code"] not in main.sessions


# --- pairing & relay ---------------------------------------------------------
def test_pairing_and_welcome_contents(client):
    s = new_session(client)
    with receiver_ws(client, s) as rcv:
        w = rcv.receive_json()
        assert w["type"] == "welcome" and w["role"] == "receiver" and w["peer_present"] is False
        assert w["receiver_token"] and w["ice_servers"] == []
        with sender_ws(client, s) as snd:
            ws_ = snd.receive_json()
            assert ws_["type"] == "welcome" and ws_["role"] == "sender" and ws_["peer_present"] is True
            assert "receiver_token" not in ws_
            assert snd.receive_json() == {"type": "paired"}
            assert rcv.receive_json() == {"type": "paired"}


def test_relay_both_directions_preserves_payload(client):
    s = new_session(client)
    with sender_ws(client, s) as snd, receiver_ws(client, s) as rcv:
        assert snd.receive_json()["type"] == "welcome" and rcv.receive_json()["type"] == "welcome"
        assert snd.receive_json()["type"] == "paired" and rcv.receive_json()["type"] == "paired"
        offer = {"type": "offer", "sdp": "v=0\r\n...", "negotiation": "n1"}
        snd.send_json(offer)
        assert rcv.receive_json() == {"type": "signal", "from": "sender", "data": offer}
        ans = {"type": "answer", "sdp": "v=0\r\nans", "negotiation": "n1"}
        rcv.send_json(ans)
        assert snd.receive_json() == {"type": "signal", "from": "receiver", "data": ans}
        ice = {"type": "ice_candidate", "candidate": {"sdpMid": "0", "sdpMLineIndex": 0, "candidate": "candidate:1 1 udp 1 10.0.0.2 5000 typ host"}}
        snd.send_json(ice)
        assert rcv.receive_json()["data"] == ice


def test_ping_pong_and_bad_input_do_not_kill_socket(client):
    s = new_session(client)
    with sender_ws(client, s) as snd:
        snd.receive_json()
        snd.send_json({"type": "ping"})
        assert snd.receive_json() == {"type": "pong"}
        snd.send_text("not json")
        assert snd.receive_json()["code"] == "bad_json"
        snd.send_text("[1,2,3]")
        assert snd.receive_json()["code"] == "bad_json"
        snd.send_text("x" * (main.MAX_MESSAGE_BYTES + 1))
        assert snd.receive_json()["code"] == "bad_message"
        snd.send_json({"type": "ping"})
        assert snd.receive_json() == {"type": "pong"}


def test_relay_to_absent_peer_is_dropped_not_fatal(client):
    s = new_session(client)
    with sender_ws(client, s) as snd:
        snd.receive_json()
        snd.send_json({"type": "offer", "sdp": "x"})
        snd.send_json({"type": "ping"})
        assert snd.receive_json() == {"type": "pong"}


# --- auth / roles ------------------------------------------------------------
def test_sender_needs_valid_token(client):
    s = new_session(client)
    with client.websocket_connect(f"/ws/{s['code']}/sender?token=wrong") as ws:
        expect_error(ws, "unauthorized")
    with client.websocket_connect(f"/ws/{s['code']}/sender") as ws:
        expect_error(ws, "unauthorized")


def test_unknown_code_and_unknown_role(client):
    with client.websocket_connect("/ws/000000/receiver") as ws:
        expect_error(ws, "invalid_code")
    s = new_session(client)
    with client.websocket_connect(f"/ws/{s['code']}/admin") as ws:
        expect_error(ws, "invalid_code")


def test_second_receiver_without_token_rejected_with_token_replaces(client):
    s = new_session(client)
    with receiver_ws(client, s) as first:
        token = first.receive_json()["receiver_token"]
        with receiver_ws(client, s) as intruder:
            expect_error(intruder, "code_in_use")
        with receiver_ws(client, s, token) as second:      # legit reconnect replaces stale socket
            assert second.receive_json()["type"] == "welcome"
            with pytest.raises(WebSocketDisconnect):
                first.receive_json()


def test_failed_join_rate_limit(client):
    for _ in range(10):
        with client.websocket_connect("/ws/111111/receiver") as ws:
            expect_error(ws, "invalid_code")
    with client.websocket_connect("/ws/111111/receiver") as ws:
        expect_error(ws, "rate_limited")


def test_cross_origin_websocket_rejected_same_origin_ok(client):
    s = new_session(client)
    with client.websocket_connect(f"/ws/{s['code']}/receiver", headers={"origin": "http://evil.example"}) as ws:
        expect_error(ws, "origin")
    with client.websocket_connect(f"/ws/{s['code']}/receiver", headers={"origin": "http://testserver"}) as ws:
        assert ws.receive_json()["type"] == "welcome"


# --- disconnect / reconnect / stop -------------------------------------------
def test_peer_disconnected_notice_and_reconnect(client):
    s = new_session(client)
    with sender_ws(client, s) as snd:
        snd.receive_json()
        with receiver_ws(client, s) as rcv:
            token = rcv.receive_json()["receiver_token"]
            assert snd.receive_json() == {"type": "paired"}
        assert snd.receive_json() == {"type": "peer_disconnected"}
        with receiver_ws(client, s, token) as rcv2:      # browser refresh with stored token
            assert rcv2.receive_json()["peer_present"] is True
            assert rcv2.receive_json() == {"type": "paired"}
            assert snd.receive_json() == {"type": "paired"}
    assert s["code"] in main.sessions  # not ended by disconnects


def test_sender_reconnect_replaces_stale_socket(client):
    s = new_session(client)
    with receiver_ws(client, s) as rcv:
        rcv.receive_json()
        with sender_ws(client, s) as a:
            assert a.receive_json()["type"] == "welcome"
            assert a.receive_json() == {"type": "paired"}
            assert rcv.receive_json() == {"type": "paired"}
            with sender_ws(client, s) as b:               # phone reconnects before server notices the old one died
                assert b.receive_json()["type"] == "welcome"
                with pytest.raises(WebSocketDisconnect):
                    a.receive_json()


def test_sender_stop_ends_session_for_everyone(client):
    s = new_session(client)
    with sender_ws(client, s) as snd, receiver_ws(client, s) as rcv:
        snd.receive_json(); rcv.receive_json()
        snd.receive_json(); rcv.receive_json()  # paired x2
        snd.send_json({"type": "stop"})
        assert rcv.receive_json() == {"type": "session_ended", "reason": "sender_stopped"}
    assert s["code"] not in main.sessions
    with sender_ws(client, s) as ws:
        expect_error(ws, "invalid_code")


def test_receiver_cannot_stop_session(client):
    s = new_session(client)
    with receiver_ws(client, s) as rcv:
        rcv.receive_json()
        rcv.send_json({"type": "stop"})
        rcv.send_json({"type": "ping"})
        assert rcv.receive_json() == {"type": "pong"}
    assert s["code"] in main.sessions


# --- ICE / TURN --------------------------------------------------------------
def test_ice_servers_only_when_configured_and_turn_creds_are_valid_hmac(client, monkeypatch):
    monkeypatch.setenv("ARIX_STUN_URLS", "stun:stun.example.org:3478")
    monkeypatch.setenv("ARIX_TURN_URLS", "turn:turn.example.org:3478?transport=udp,turns:turn.example.org:5349")
    monkeypatch.setenv("ARIX_TURN_SECRET", "s3cret-value")
    s = new_session(client)
    with sender_ws(client, s) as snd:
        ice = snd.receive_json()["ice_servers"]
    assert ice[0] == {"urls": ["stun:stun.example.org:3478"]}
    turn = ice[1]
    assert turn["urls"][0].startswith("turn:") and turn["urls"][1].startswith("turns:")
    expiry, sid = turn["username"].split(":")
    assert int(expiry) > time.time() and s["code"] not in turn["username"]
    expected = base64.b64encode(hmac.new(b"s3cret-value", turn["username"].encode(), hashlib.sha1).digest()).decode()
    assert turn["credential"] == expected
    cfg = client.get("/api/config").json()
    assert cfg["turn_configured"] is True and "s3cret" not in str(cfg) and "s3cret" not in str(client.get("/api/health").json())


def test_turn_not_offered_without_secret(client, monkeypatch):
    monkeypatch.setenv("ARIX_TURN_URLS", "turn:turn.example.org:3478")
    s = new_session(client)
    with sender_ws(client, s) as snd:
        assert snd.receive_json()["ice_servers"] == []
