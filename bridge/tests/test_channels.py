"""Messaging channel routes, against a fake dashboard and gateway. No real Hermes is ever contacted."""

import json
from urllib.parse import urlsplit

import pytest

from test_bridge import asynchronous
from test_desktop import FakeDesktop, desktop

SECRET = "FAKE-SECRET-VALUE-0123456789"
BOT_TOKEN = "123456:FAKE-BOT-TOKEN-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
PAIRING = "pair_ABCDEF-12345"
LINK = "https://t.me/newbot/hermes_bot/Rasul?start=abc123"
FORBIDDEN = ("value", "redacted_value", "env_path", "gateway_start_command", "ingress_url", "docs_url",
             "help", "url", "whatsapp_setup", "poll_token", "qr_payload", "updated_at", "bot_token")


def env_var(key, **over):
    return {"key": key, "required": True, "is_set": True, "redacted_value": SECRET, "value": SECRET,
            "description": SECRET, "prompt": "Bot token\n(from the setup)", "help": SECRET, "url": SECRET,
            "is_password": True, "is_list": False, "advanced": False, **over}


def platform(ident, **over):
    return {"id": ident, "name": ident.title(), "description": "Chat with the agent\non " + ident,
            "docs_url": SECRET, "enabled": True, "configured": True, "gateway_running": True,
            "state": "connected", "error_code": None, "error_message": None, "updated_at": SECRET,
            "home_channel": {"chat_id": SECRET}, "ingress_url": SECRET, "whatsapp_setup": {"mode": SECRET},
            "env_vars": [env_var("TELEGRAM_BOT_TOKEN"), env_var("TELEGRAM_ALLOWED_USERS", required=False,
                                                                 is_list=True, is_password=False)],
            **over}


class FakeChannels(FakeDesktop):
    def __init__(self, directory):
        super().__init__(directory)
        self.platforms = [platform("telegram"), platform("discord", enabled=False, state="disabled", home_channel=None)]
        self.test_payload = {"ok": True, "state": "connected", "message": "Telegram is connected.",
                             "auth_posture": "allow_list", "extra": SECRET}
        self.start_payload = {"pairing_id": PAIRING, "poll_token": SECRET, "deep_link": LINK,
                              "qr_payload": SECRET, "suggested_username": "rasul_bot", "expires_at": "2026-10-18T12:00:00Z"}
        self.status_payload = {"status": "waiting", "expires_at": "2026-10-18T12:00:00Z", "token": BOT_TOKEN,
                               "poll_token": SECRET}
        self.apply_payload = {"ok": True, "platform": "telegram", "bot_username": "rasul_bot",
                              "needs_restart": False, "restart_started": True, "restart_pid": 9,
                              "restart_action": "gateway-restart", "token": BOT_TOKEN}

    def result(self, method, path, value):
        if path == "/api/messaging/platforms":
            return {"env_path": SECRET, "gateway_start_command": SECRET, "platforms": self.platforms}
        if path.startswith("/api/messaging/platforms/") and method == "PUT":
            ident = path.rsplit("/", 1)[1]
            for row in self.platforms:
                if row["id"] == ident:
                    row["enabled"] = value["enabled"]
                    row["state"] = "pending_restart" if value["enabled"] else "disabled"
            return {"ok": True, "platform": ident, "hot_served": False}
        if path.startswith("/api/messaging/platforms/") and path.endswith("/test"):
            return self.test_payload
        if path == "/api/gateway/restart":
            return {"ok": True, "pid": 7, "name": "gateway-restart"}
        if path == "/api/messaging/telegram/onboarding/start":
            return self.start_payload
        if path.startswith("/api/messaging/telegram/onboarding/") and path.endswith("/apply"):
            return self.apply_payload
        if path.startswith("/api/messaging/telegram/onboarding/"):
            return {"ok": True} if method == "DELETE" else self.status_payload
        return super().result(method, path, value)

    def sent(self, method, path):
        return [r for r in self.requests if r[0] == method and urlsplit(r[1]).path == path]


def channels(tmp_path, monkeypatch):
    return desktop(tmp_path, monkeypatch, FakeChannels)


def no_forbidden(value, fake):
    text = json.dumps(value)
    for item in (SECRET, BOT_TOKEN, fake.token, *(f'"{key}"' for key in FORBIDDEN)):
        assert item not in text


@asynchronous
async def test_list_projects_only_safe_fields(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.platforms += [
            platform("Bad-Id"), platform("x" * 33), "junk", platform("noflag", enabled="yes"),
            platform("nofields", env_vars=None), platform("nameless", name="  "),
            platform("odd", env_vars=[env_var("lower_case"), env_var("OK_KEY", required="yes"), 5,
                                      env_var("GOOD_KEY", is_set=False)], state=None,
                     error_code="startup_failed", error_message="x" * 400 + "\nsecond"),
        ]
        status, value = await call("GET", "/channels")
        assert status == 200
        no_forbidden(value, fake)
        field = {"required": True, "is_set": True, "is_list": False, "prompt": "Bot token (from the setup)"}
        assert value["channels"][0] == {
            "id": "telegram", "name": "Telegram", "description": "Chat with the agent on telegram",
            "enabled": True, "configured": True, "gateway_running": True, "state": "connected",
            "error_code": None, "error_message": None, "home_channel_set": True,
            "fields": [{"key": "TELEGRAM_BOT_TOKEN", **field},
                       {"key": "TELEGRAM_ALLOWED_USERS", **field, "required": False, "is_list": True}]}
        assert value["channels"][1]["home_channel_set"] is False and value["channels"][1]["state"] == "disabled"
        assert [c["id"] for c in value["channels"]] == ["telegram", "discord", "odd"]
        odd = value["channels"][2]
        assert odd["state"] is None and odd["error_code"] == "startup_failed"
        assert len(odd["error_message"]) == 200 and "\n" not in odd["error_message"]
        assert [f["key"] for f in odd["fields"]] == ["GOOD_KEY"]
        for row in value["channels"]:
            assert set(row) == {"id", "name", "description", "enabled", "configured", "gateway_running", "state",
                                "error_code", "error_message", "home_channel_set", "fields"}


@asynchronous
async def test_list_must_be_a_list(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.platforms = {"telegram": platform("telegram")}
        assert await call("GET", "/channels") == (502, {"error": "desktop_error"})


@pytest.mark.parametrize("method,suffix,body", [
    ("PUT", "/channels/Bad-Id/enabled", {"enabled": True}),
    ("PUT", "/channels/" + "a" * 33 + "/enabled", {"enabled": True}),
    ("PUT", "/channels//enabled", {"enabled": True}),
    ("POST", "/channels/Bad/test", None),
    ("POST", "/channels/a.b/test", None),
    ("GET", "/channels/telegram/setup/short", None),
    ("GET", "/channels/telegram/setup/" + "a" * 129, None),
    ("GET", "/channels/telegram/setup/bad.pairing", None),
    ("POST", "/channels/telegram/setup/short/apply", {"allowed_user_ids": ["1"], "force": False}),
    ("DELETE", "/channels/telegram/setup/short", None),
    ("DELETE", "/channels/telegram", None),
    ("GET", "/channels/telegram", None),
    ("POST", "/channels", None),
])
@asynchronous
async def test_bad_ids_never_route(tmp_path, monkeypatch, method, suffix, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, _ = await call(method, suffix, body)
        assert status == 404
        assert not [r for r in fake.requests if "/api/messaging" in r[1] or "/api/gateway" in r[1]]


@pytest.mark.parametrize("method,suffix,body", [
    ("PUT", "/channels/telegram/enabled", {"enabled": True, "env": {"TELEGRAM_BOT_TOKEN": "x"}}),
    ("PUT", "/channels/telegram/enabled", {"enabled": True, "clear_env": []}),
    ("PUT", "/channels/telegram/enabled", {"enabled": "yes"}),
    ("PUT", "/channels/telegram/enabled", {"enabled": 1}),
    ("PUT", "/channels/telegram/enabled", {}),
    ("PUT", "/channels/telegram/enabled", None),
    ("POST", "/channels/telegram/test", {"x": 1}),
    ("POST", "/channels/restart", {"force": False, "profile": "other"}),
    ("POST", "/channels/restart", {"force": "yes"}),
    ("POST", "/channels/restart", {}),
    ("POST", "/channels/restart", None),
    ("POST", "/channels/telegram/setup", {"bot_name": "ok", "token": "x"}),
    ("POST", "/channels/telegram/setup", {"bot_name": 5}),
    ("POST", "/channels/telegram/setup", {"bot_name": "x" * 65}),
    ("POST", "/channels/telegram/setup", {"bot_name": "a\nb"}),
    ("GET", "/channels/telegram/setup/" + PAIRING, {"x": 1}),
    ("DELETE", "/channels/telegram/setup/" + PAIRING, {"x": 1}),
    ("GET", "/channels", {"x": 1}),
    ("POST", "/channels/telegram/setup/" + PAIRING + "/apply", {"allowed_user_ids": ["1"]}),
    ("POST", "/channels/telegram/setup/" + PAIRING + "/apply", {"allowed_user_ids": ["1"], "force": False, "token": "x"}),
])
@asynchronous
async def test_strict_bodies(tmp_path, monkeypatch, method, suffix, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call(method, suffix, body) == (400, {"error": "desktop_invalid"})
        assert not [r for r in fake.requests if "/api/messaging" in r[1] or "/api/gateway" in r[1]]


@asynchronous
async def test_duplicate_body_key_and_oversize_are_refused(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert (await call("PUT", "/channels/telegram/enabled", b'{"enabled":true,"enabled":false}'))[0] == 400
        assert (await call("POST", "/channels/restart", b" " * 1025))[0] == 400
        assert not fake.sent("PUT", "/api/messaging/platforms/telegram")


@asynchronous
async def test_enabled_sends_only_enabled_and_profile(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, value = await call("PUT", "/channels/discord/enabled", {"enabled": True})
        assert status == 200
        sent = fake.sent("PUT", "/api/messaging/platforms/discord")
        assert len(sent) == 1 and sent[0][3] == {"enabled": True, "profile": "default"}
        assert value["id"] == "discord" and value["enabled"] is True and value["needs_restart"] is True
        no_forbidden(value, fake)
        status, value = await call("PUT", "/channels/discord/enabled", {"enabled": False})
        assert status == 200 and value["needs_restart"] is False and value["state"] == "disabled"
        assert [r[3] for r in fake.sent("PUT", "/api/messaging/platforms/discord")][-1] == {"enabled": False, "profile": "default"}


@asynchronous
async def test_enabled_conflict_and_unknown_are_mapped(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.status, fake.payload = 409, {"detail": "Cannot enable 'api_server'\non this profile. " + "x" * 400}
        status, value = await call("PUT", "/channels/telegram/enabled", {"enabled": True})
        assert status == 409 and value["error"] == "channel_conflict"
        assert len(value["message"]) == 200 and "\n" not in value["message"] and value["message"].startswith("Cannot enable")
        fake.status, fake.payload = 404, {"detail": SECRET}
        assert await call("PUT", "/channels/nothing/enabled", {"enabled": True}) == (404, {"error": "channel_not_found"})
        fake.status, fake.payload = 500, {"detail": SECRET}
        assert await call("PUT", "/channels/telegram/enabled", {"enabled": True}) == (502, {"error": "desktop_error"})
        fake.status, fake.payload = 409, "not an object"
        assert await call("PUT", "/channels/telegram/enabled", {"enabled": True}) == (409, {"error": "channel_conflict", "message": ""})


@asynchronous
async def test_enabled_when_row_vanishes_is_clean(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        original = fake.result

        def result(method, path, value):
            if method == "PUT":
                fake.platforms = []
            return original(method, path, value)
        fake.result = result
        assert await call("PUT", "/channels/telegram/enabled", {"enabled": True}) == (404, {"error": "channel_not_found"})


@asynchronous
async def test_test_mapping(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, value = await call("POST", "/channels/telegram/test")
        assert status == 200
        assert value == {"ok": True, "state": "connected", "message": "Telegram is connected.", "auth_posture": "allow_list"}
        assert len(fake.sent("POST", "/api/messaging/platforms/telegram/test")) == 1
        fake.test_payload = {"ok": False, "state": "not_configured", "message": "Missing\nsetup: " + "y" * 500}
        status, value = await call("POST", "/channels/telegram/test")
        assert status == 200 and value["ok"] is False and "auth_posture" not in value
        assert len(value["message"]) == 300 and "\n" not in value["message"]


@asynchronous
async def test_test_malformed_and_unknown(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.test_payload = {"ok": "yes", "state": "x", "message": "m"}
        assert await call("POST", "/channels/telegram/test") == (502, {"error": "desktop_error"})
        fake.test_payload, fake.status, fake.payload = None, 404, {"detail": "Unknown"}
        assert await call("POST", "/channels/telegram/test") == (404, {"error": "channel_not_found"})


@pytest.mark.parametrize("agents", [1, 3])
@asynchronous
async def test_restart_refused_while_busy(tmp_path, monkeypatch, agents):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.gateway.active_agents = agents
        assert await call("POST", "/channels/restart", {"force": False}) == (
            409, {"error": "gateway_busy", "active_agents": agents})
        assert not fake.sent("POST", "/api/gateway/restart")
        assert await call("POST", "/channels/restart", {"force": True}) == (200, {"ok": True})
        assert len(fake.sent("POST", "/api/gateway/restart")) == 1


@asynchronous
async def test_restart_unknown_idle_counts_as_busy(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (bridge, fake, call):
        bridge.store.write("profile-keys.json", {})
        assert await call("POST", "/channels/restart", {"force": False}) == (
            409, {"error": "gateway_busy", "active_agents": None})
        assert not fake.sent("POST", "/api/gateway/restart")
        assert await call("POST", "/channels/restart", {"force": True}) == (200, {"ok": True})


@asynchronous
async def test_restart_when_idle(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.gateway.active_agents = 0
        assert await call("POST", "/channels/restart", {"force": False}) == (200, {"ok": True})
        assert len(fake.sent("POST", "/api/gateway/restart")) == 1


@asynchronous
async def test_setup_start_returns_only_four_fields(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, value = await call("POST", "/channels/telegram/setup", {"bot_name": "Rasul"})
        assert status == 200
        assert value == {"pairing_id": PAIRING, "deep_link": LINK, "suggested_username": "rasul_bot",
                         "expires_at": "2026-10-18T12:00:00Z"}
        no_forbidden(value, fake)
        assert fake.sent("POST", "/api/messaging/telegram/onboarding/start")[0][3] == {"bot_name": "Rasul"}
        assert (await call("POST", "/channels/telegram/setup"))[0] == 200
        assert fake.sent("POST", "/api/messaging/telegram/onboarding/start")[1][3] == {}
        assert (await call("POST", "/channels/telegram/setup", {"bot_name": ""}))[0] == 200
        assert fake.sent("POST", "/api/messaging/telegram/onboarding/start")[2][3] == {}


@pytest.mark.parametrize("link,good", [
    ("https://t.me/newbot/hermes_bot/Rasul?start=abc123", True),
    ("https://telegram.me/hermes_bot?start=abc-123_x", True),
    ("https://t.me/hermes_bot", True),
    ("http://t.me/hermes_bot", False),
    ("https://evil.example/hermes_bot", False),
    ("https://t.me.evil.example/hermes_bot", False),
    ("https://evil.example/t.me/hermes_bot", False),
    ("https://user@t.me/hermes_bot", False),
    ("https://user:pass@t.me/hermes_bot", False),
    ("https://t.me:443/hermes_bot", False),
    ("https://t.me/hermes_bot#frag", False),
    ("https://t.me/hermes_bot#", False),
    ("https://t.me/hermes_bot?start=a b", False),
    ("https://t.me/hermes_bot?start=a%20b", False),
    ("https://t.me/hermes_bot?start=a;b", False),
    ("https://t.me/hermes_bot?next=https://evil.example", False),
    ("https://t.me/hermes bot", False),
    ("https://t.me/hermes_bot\n", False),
    ("https://t.me/\\evil.example", False),
    ("https://t.me/he.rmes", False),
    ("https://t.me/" + "a" * 65, False),
    ("https://t.me/", False),
    ("https://t.me", False),
    ("tg://resolve?domain=hermes_bot", False),
    ("javascript:alert(1)", False),
    ("", False),
    (None, False),
    (5, False),
])
@asynchronous
async def test_setup_deep_link_validation(tmp_path, monkeypatch, link, good):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.start_payload = {**fake.start_payload, "deep_link": link}
        status, value = await call("POST", "/channels/telegram/setup")
        if good:
            assert status == 200 and value["deep_link"] == link
        else:
            assert (status, value) == (502, {"error": "channel_setup_bad_link"})


@pytest.mark.parametrize("pairing", ["short", "has space 123", "dots.not.ok", "a" * 129, "", None, 5])
@asynchronous
async def test_setup_bad_pairing_id_from_hermes(tmp_path, monkeypatch, pairing):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.start_payload = {**fake.start_payload, "pairing_id": pairing}
        assert await call("POST", "/channels/telegram/setup") == (502, {"error": "desktop_error"})


@asynchronous
async def test_status_waiting_ready_and_never_a_token(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        suffix = "/channels/telegram/setup/" + PAIRING
        status, value = await call("GET", suffix)
        assert (status, value) == (200, {"status": "waiting", "expires_at": "2026-10-18T12:00:00Z"})
        assert fake.sent("GET", "/api/messaging/telegram/onboarding/" + PAIRING)
        fake.status_payload = {"status": "ready", "bot_username": "rasul_bot", "owner_user_id": "12345",
                               "expires_at": "2026-10-18T12:00:00Z", "token": BOT_TOKEN, "bot_token": BOT_TOKEN,
                               "poll_token": SECRET}
        status, value = await call("GET", suffix)
        assert status == 200
        assert value == {"status": "ready", "bot_username": "rasul_bot", "owner_user_id": "12345",
                         "expires_at": "2026-10-18T12:00:00Z"}
        no_forbidden(value, fake)
        fake.status_payload = {"status": "ready", "bot_username": "", "owner_user_id": "12ab", "token": BOT_TOKEN}
        assert await call("GET", suffix) == (200, {"status": "ready"})
        for odd in ("claimed", "expired", None, 5):
            fake.status_payload = {"status": odd, "token": BOT_TOKEN}
            assert await call("GET", suffix) == (502, {"error": "desktop_error"})


@asynchronous
async def test_status_expired_and_missing(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        suffix = "/channels/telegram/setup/" + PAIRING
        fake.status, fake.payload = 410, {"detail": "Telegram setup expired. " + SECRET}
        assert await call("GET", suffix) == (410, {"error": "channel_setup_expired"})
        fake.status, fake.payload = 404, {"detail": "Telegram setup session not found."}
        assert await call("GET", suffix) == (404, {"error": "channel_setup_not_found"})
        fake.status, fake.payload = 502, {"detail": SECRET}
        assert await call("GET", suffix) == (502, {"error": "desktop_error"})


@asynchronous
async def test_cancel(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("DELETE", "/channels/telegram/setup/" + PAIRING) == (200, {"ok": True})
        assert len(fake.sent("DELETE", "/api/messaging/telegram/onboarding/" + PAIRING)) == 1


APPLY = "/channels/telegram/setup/" + PAIRING + "/apply"


@pytest.mark.parametrize("ids", [["abc"], ["12 3"], ["-5"], ["1.5"], [""], [" 12"], ["12\n"], ["\u0661\u0662"], [12],
                                 [None], [], ["1"] * 21, "12345", {"a": "1"}, ["1" * 21], [["1"]]])
@asynchronous
async def test_apply_validates_ids(tmp_path, monkeypatch, ids):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("POST", APPLY, {"allowed_user_ids": ids, "force": True}) == (400, {"error": "desktop_invalid"})
        assert not fake.sent("POST", "/api/messaging/telegram/onboarding/" + PAIRING + "/apply")


@asynchronous
async def test_apply_sends_ids_and_profile_and_projects(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.gateway.active_agents = 0
        status, value = await call("POST", APPLY, {"allowed_user_ids": ["12345", "67890", "12345"], "force": False})
        assert status == 200
        assert value == {"ok": True, "bot_username": "rasul_bot", "restart_started": True, "needs_restart": False}
        no_forbidden(value, fake)
        sent = fake.sent("POST", "/api/messaging/telegram/onboarding/" + PAIRING + "/apply")
        assert len(sent) == 1 and sent[0][3] == {"allowed_user_ids": ["12345", "67890"], "profile": "default"}
        fake.apply_payload = {**fake.apply_payload, "bot_username": None, "restart_started": False, "needs_restart": True}
        assert await call("POST", APPLY, {"allowed_user_ids": ["1"] * 20, "force": False}) == (
            200, {"ok": True, "bot_username": None, "restart_started": False, "needs_restart": True})


@pytest.mark.parametrize("agents", [2, None])
@asynchronous
async def test_apply_refused_while_busy_unless_forced(tmp_path, monkeypatch, agents):
    async with channels(tmp_path, monkeypatch) as (bridge, fake, call):
        if agents is None:
            bridge.store.write("profile-keys.json", {})
        else:
            fake.gateway.active_agents = agents
        body = {"allowed_user_ids": ["12345"], "force": False}
        assert await call("POST", APPLY, body) == (409, {"error": "gateway_busy", "active_agents": agents})
        path = "/api/messaging/telegram/onboarding/" + PAIRING + "/apply"
        assert not fake.sent("POST", path)
        assert (await call("POST", APPLY, {**body, "force": True}))[0] == 200
        assert len(fake.sent("POST", path)) == 1


@asynchronous
async def test_apply_not_ready_missing_and_malformed(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        body = {"allowed_user_ids": ["12345"], "force": True}
        fake.status, fake.payload = 409, {"detail": "Telegram setup is not ready yet."}
        assert await call("POST", APPLY, body) == (
            409, {"error": "channel_setup_not_ready", "message": "Telegram setup is not ready yet."})
        fake.status, fake.payload = 404, {"detail": "gone"}
        assert await call("POST", APPLY, body) == (404, {"error": "channel_setup_not_found"})


@pytest.mark.parametrize("odd", [{"ok": False}, {"ok": True, "restart_started": "yes", "needs_restart": False},
                                 {"ok": True, "restart_started": True}])
@asynchronous
async def test_apply_malformed_reply(tmp_path, monkeypatch, odd):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.apply_payload = odd
        body = {"allowed_user_ids": ["12345"], "force": True}
        assert await call("POST", APPLY, body) == (502, {"error": "desktop_error"})


RATES = [
    ("GET", "/channels", None, 12),
    ("PUT", "/channels/telegram/enabled", {"enabled": True}, 6),
    ("POST", "/channels/telegram/test", None, 3),
    ("POST", "/channels/restart", {"force": True}, 2),
    ("POST", "/channels/telegram/setup", None, 3),
    ("GET", "/channels/telegram/setup/" + PAIRING, None, 30),
    ("POST", APPLY, {"allowed_user_ids": ["12345"], "force": True}, 3),
    ("DELETE", "/channels/telegram/setup/" + PAIRING, None, 6),
]


@pytest.mark.parametrize("method,suffix,body,rate", RATES)
@asynchronous
async def test_rate_limits(tmp_path, monkeypatch, method, suffix, body, rate):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        for _ in range(rate):
            assert (await call(method, suffix, body))[0] == 200
        before = len(fake.requests)
        assert await call(method, suffix, body) == (429, {"error": "desktop_rate_limited"})
        assert len(fake.requests) == before


@asynchronous
async def test_audit_lines_carry_no_ids_or_tokens(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.gateway.active_agents = 1
        assert (await call("POST", "/channels/restart", {"force": True}))[0] == 200
        assert (await call("POST", APPLY, {"allowed_user_ids": ["12345", "67890"], "force": True}))[0] == 200
        assert (await call("POST", "/channels/restart", {"force": False}))[0] == 409
        await call("POST", "/channels/telegram/setup")
        await call("GET", "/channels/telegram/setup/" + PAIRING)
        text = bridge.audit.path.read_text()
        for secret in (PAIRING, "12345", "67890", BOT_TOKEN, SECRET, fake.token, LINK, "rasul_bot"):
            assert secret not in text
        rows = [json.loads(line) for line in text.splitlines()]
        marks = [r for r in rows if r.get("event") == "channels"]
        assert [(r["action"], r["force"]) for r in marks] == [("restart", True), ("telegram_apply", True)]
        assert all(set(r) == {"time", "event", "action", "force"} for r in marks)
