"""Messaging channel routes, against a fake dashboard and gateway. No real Hermes is ever contacted."""

import json
from urllib.parse import urlsplit

import pytest

from rasul_bridge import desktop as desktop_module
from test_bridge import asynchronous
from test_desktop import FakeDesktop, desktop

SECRET = "FAKE-SECRET-VALUE-0123456789"
BOT_TOKEN = "123456:FAKE-BOT-TOKEN-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
PAIRING = "pair_ABCDEF-12345"
WA_PAIRING = "wa_pair_ABCDEF-12345"
QR = "2@FAKE-QR-PAYLOAD,abc=/+0123456789"
PHONE = "491234567890"
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
        self.wa_payload = {"pairing_id": WA_PAIRING, "status": "waiting", "qr_payload": QR,
                           "expires_at": "2026-10-18T12:00:00Z", "mode": "bot", "allowed_users": PHONE,
                           "account_id": PHONE + ":12@s.whatsapp.net", "account_name": "Test\nPerson",
                           "account_phone": PHONE, "error": None}
        self.wa_apply_payload = {"ok": True, "platform": "whatsapp", "needs_restart": False,
                                 "restart_started": True, "restart_pid": 9, "restart_action": "gateway-restart"}
        self.hot_served, self.settled = False, None  # settings save: the live-serve flag and a state to force

    def result(self, method, path, value):
        if path == "/api/messaging/platforms":
            return {"env_path": SECRET, "gateway_start_command": SECRET, "platforms": self.platforms}
        if path.startswith("/api/messaging/platforms/") and method == "PUT":
            ident = path.rsplit("/", 1)[1]
            for row in self.platforms:
                if row["id"] == ident and "enabled" in value:
                    row["enabled"] = value["enabled"]
                    row["state"] = self.settled or ("pending_restart" if value["enabled"] else "disabled")
                    return {"ok": True, "platform": ident, "hot_served": self.hot_served}
                if row["id"] == ident and self.settled:
                    row["state"] = self.settled
            # like Hermes: a settings save leaves a running channel "connected" and only reports hot_served
            return {"ok": True, "platform": ident, "hot_served": self.hot_served}
        if path.startswith("/api/messaging/platforms/") and path.endswith("/test"):
            return self.test_payload
        if path == "/api/gateway/restart":
            return {"ok": True, "pid": 7, "name": "gateway-restart"}
        if path == "/api/messaging/whatsapp/onboarding/start":
            return self.wa_payload
        if path.startswith("/api/messaging/whatsapp/onboarding/") and path.endswith("/apply"):
            return self.wa_apply_payload
        if path.startswith("/api/messaging/whatsapp/onboarding/"):
            return {"ok": True} if method == "DELETE" else self.wa_payload
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
        assert status == 200 and value["needs_restart"] is True and value["state"] == "disabled"
        assert [r[3] for r in fake.sent("PUT", "/api/messaging/platforms/discord")][-1] == {"enabled": False, "profile": "default"}


@pytest.mark.parametrize("enabled,hot,state,needed", [
    (False, False, None, True), (False, True, None, False), (True, False, None, True),
    (True, True, "connected", False), (True, True, None, True), (True, "yes", "connected", True)])
@asynchronous
async def test_enabled_needs_restart_unless_hermes_served_it_live(tmp_path, monkeypatch, enabled, hot, state, needed):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.hot_served, fake.settled = hot, state
        status, value = await call("PUT", "/channels/telegram/enabled", {"enabled": enabled})
        assert status == 200 and value["enabled"] is enabled and value["gateway_running"] is True
        assert value["needs_restart"] is needed


@asynchronous
async def test_enabled_conflict_and_unknown_are_mapped(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.status, fake.payload = 409, {"detail": "Cannot enable 'api_server'\non this profile. " + "x" * 400}
        status, value = await call("PUT", "/channels/telegram/enabled", {"enabled": True})
        assert status == 409 and value["error"] == "channel_conflict"
        assert value["message"] == "The computer would not turn this channel on."
        fake.status, fake.payload = 404, {"detail": SECRET}
        assert await call("PUT", "/channels/nothing/enabled", {"enabled": True}) == (404, {"error": "channel_not_found"})
        fake.status, fake.payload = 500, {"detail": SECRET}
        assert await call("PUT", "/channels/telegram/enabled", {"enabled": True}) == (502, {"error": "desktop_error"})
        fake.status, fake.payload = 409, "not an object"
        assert await call("PUT", "/channels/telegram/enabled", {"enabled": True}) == (409, {"error": "channel_conflict", "message": "The computer would not turn this channel on."})


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
            409, {"error": "channel_setup_not_ready", "message": "The setup is not finished yet."})
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


WA_STATUS = "/channels/whatsapp/setup/" + WA_PAIRING
WA_APPLY = WA_STATUS + "/apply"
WA_API = "/api/messaging/whatsapp/onboarding/"
SETTINGS = "/channels/telegram/settings"
RATES = [
    ("GET", "/channels", None, 12),
    ("PUT", "/channels/telegram/enabled", {"enabled": True}, 6),
    ("POST", "/channels/telegram/test", None, 3),
    ("POST", "/channels/restart", {"force": True}, 2),
    ("POST", "/channels/telegram/setup", None, 3),
    ("GET", "/channels/telegram/setup/" + PAIRING, None, 30),
    ("POST", APPLY, {"allowed_user_ids": ["12345"], "force": True}, 3),
    ("DELETE", "/channels/telegram/setup/" + PAIRING, None, 6),
    ("POST", "/channels/whatsapp/setup", {"mode": "bot"}, 3),
    ("GET", WA_STATUS, None, 30),
    ("POST", WA_APPLY, {"force": True}, 3),
    ("DELETE", WA_STATUS, None, 6),
    ("PUT", "/channels/telegram/settings", {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}}, 6),
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


@asynchronous
async def test_whatsapp_start_projection_and_request(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, value = await call("POST", "/channels/whatsapp/setup", {"mode": "self-chat", "allowed_users": "+4912,5678,+4912"})
        assert status == 200
        assert value == {"pairing_id": WA_PAIRING, "status": "waiting", "qr_payload": QR,
                         "expires_at": "2026-10-18T12:00:00Z", "account_name": "Test Person",
                         "account_phone_last4": "7890"}
        text = json.dumps(value)
        for hidden in (PHONE, "account_id", "s.whatsapp.net", "allowed_users", "account_phone\"", fake.token):
            assert hidden not in text
        sent = fake.sent("POST", WA_API + "start")
        assert len(sent) == 1 and sent[0][3] == {"mode": "self-chat", "allowed_users": "+4912,5678", "profile": "default"}
        assert (await call("POST", "/channels/whatsapp/setup", {"mode": "bot"}))[0] == 200
        assert fake.sent("POST", WA_API + "start")[1][3] == {"mode": "bot", "profile": "default"}
        assert (await call("POST", "/channels/whatsapp/setup", {"mode": "bot", "allowed_users": ""}))[0] == 200
        assert fake.sent("POST", WA_API + "start")[2][3] == {"mode": "bot", "profile": "default"}


@asynchronous
async def test_whatsapp_qr_only_while_waiting_and_only_clean(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        for state in ("installing", "starting", "connected", "error", "cancelled"):
            fake.wa_payload = {**fake.wa_payload, "status": state}
            status, value = await call("GET", WA_STATUS)
            assert status == 200 and value["status"] == state and "qr_payload" not in value
        for odd in ("", "x" * 1025, "caf\u00e9", "a\nb", "a\tb", None, 5, ["a"]):
            fake.wa_payload = {**fake.wa_payload, "status": "waiting", "qr_payload": odd}
            status, value = await call("GET", WA_STATUS)
            assert status == 200 and value["status"] == "waiting" and "qr_payload" not in value
        fake.wa_payload = {**fake.wa_payload, "qr_payload": "x" * 1024}
        assert (await call("GET", WA_STATUS))[1]["qr_payload"] == "x" * 1024


@asynchronous
async def test_whatsapp_phone_error_and_missing_fields(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.wa_payload = {"pairing_id": WA_PAIRING, "status": "connected", "account_phone": "+49 (12) 3-4567",
                           "error": "boom\n" + "e" * 300}
        status, value = await call("GET", WA_STATUS)
        assert status == 200
        assert value == {"pairing_id": WA_PAIRING, "status": "connected", "account_phone_last4": "4567",
                         "error": "whatsapp_setup_failed"}
        for phone in ("123", "", "abc", None, 49123, ["491234567890"]):
            fake.wa_payload = {"pairing_id": WA_PAIRING, "status": "connected", "account_phone": phone}
            assert (await call("GET", WA_STATUS)) == (200, {"pairing_id": WA_PAIRING, "status": "connected"})


@pytest.mark.parametrize("odd", [
    {"status": "waiting"}, {"pairing_id": "short", "status": "waiting"}, {"pairing_id": 5, "status": "waiting"},
    {"pairing_id": WA_PAIRING}, {"pairing_id": WA_PAIRING, "status": "expired"},
    {"pairing_id": WA_PAIRING, "status": 7}, {"pairing_id": "other_pair_ABCDEF", "status": "waiting"}, [], None])
@asynchronous
async def test_whatsapp_malformed_reply(tmp_path, monkeypatch, odd):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.wa_payload = odd
        assert await call("GET", WA_STATUS) == (502, {"error": "desktop_error"})
        if not (isinstance(odd, dict) and odd.get("pairing_id") == "other_pair_ABCDEF"):
            assert await call("POST", "/channels/whatsapp/setup", {"mode": "bot"}) == (502, {"error": "desktop_error"})


@pytest.mark.parametrize("body", [
    None, {}, {"allowed_users": "123"}, {"mode": "other"}, {"mode": None}, {"mode": 1}, {"mode": "Bot"},
    {"mode": "bot", "x": 1}, {"mode": "bot", "force": True}, {"mode": "bot", "allowed_users": 123},
    {"mode": "bot", "allowed_users": ["123"]}, {"mode": "bot", "allowed_users": "12 3"},
    {"mode": "bot", "allowed_users": "12,"}, {"mode": "bot", "allowed_users": ",12"}, {"mode": "bot", "allowed_users": "12,,3"},
    {"mode": "bot", "allowed_users": "++12"}, {"mode": "bot", "allowed_users": "12+3"}, {"mode": "bot", "allowed_users": "abc"},
    {"mode": "bot", "allowed_users": "\u0661\u0662"}, {"mode": "bot", "allowed_users": "1" * 21},
    {"mode": "bot", "allowed_users": ",".join(["1"] * 21)}, {"mode": "bot", "allowed_users": "12\n"},
    {"mode": "bot", "allowed_users": "+"}, {"mode": "bot", "allowed_users": "12@s.whatsapp.net"}])
@asynchronous
async def test_whatsapp_start_validates_body(tmp_path, monkeypatch, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("POST", "/channels/whatsapp/setup", body) == (400, {"error": "desktop_invalid"})
        assert not fake.sent("POST", WA_API + "start")


@asynchronous
async def test_whatsapp_allowed_users_limits(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        twenty = ",".join(["+" + "1" * 20] * 20)
        assert (await call("POST", "/channels/whatsapp/setup", {"mode": "bot", "allowed_users": twenty}))[0] == 200


@pytest.mark.parametrize("method,suffix,body", [
    ("GET", WA_STATUS, {"x": 1}), ("DELETE", WA_STATUS, {"x": 1}),
    ("POST", WA_APPLY, None), ("POST", WA_APPLY, {}), ("POST", WA_APPLY, {"force": "yes"}), ("POST", WA_APPLY, {"force": 1}),
    ("POST", WA_APPLY, {"force": True, "mode": "x"}), ("POST", WA_APPLY, {"force": True, "mode": None}),
    ("POST", WA_APPLY, {"force": True, "token": "x"}), ("POST", WA_APPLY, {"force": True, "allowed_users": "a"}),
    ("POST", WA_APPLY, {"force": True, "allowed_users": None}), ("POST", WA_APPLY, {"force": True, "allowed_users": 1}),
])
@asynchronous
async def test_whatsapp_strict_bodies(tmp_path, monkeypatch, method, suffix, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call(method, suffix, body) == (400, {"error": "desktop_invalid"})
        assert not [r for r in fake.requests if "/api/messaging" in r[1] or "/api/gateway" in r[1]]


@pytest.mark.parametrize("method,suffix,body", [
    ("GET", "/channels/whatsapp/setup/short", None), ("GET", "/channels/whatsapp/setup/bad.pairing", None),
    ("DELETE", "/channels/whatsapp/setup/short", None), ("POST", "/channels/whatsapp/setup/short/apply", {"force": True}),
    ("GET", "/channels/whatsapp/setup/" + "a" * 129, None), ("PUT", "/channels/Bad-Id/settings", {"values": {"A": "v"}}),
    ("PUT", "/channels/" + "a" * 33 + "/settings", {"values": {"A": "v"}}), ("PUT", "/channels//settings", {"values": {"A": "v"}}),
    ("GET", "/channels/telegram/settings", None), ("POST", "/channels/telegram/settings", {"values": {"A": "v"}}),
])
@asynchronous
async def test_whatsapp_and_settings_bad_ids_never_route(tmp_path, monkeypatch, method, suffix, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert (await call(method, suffix, body))[0] == 404
        assert not [r for r in fake.requests if "/api/messaging" in r[1] or "/api/gateway" in r[1]]


@asynchronous
async def test_whatsapp_status_unknown_and_expired(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.status, fake.payload = 404, {"detail": "gone"}
        assert await call("GET", WA_STATUS) == (404, {"error": "channel_setup_not_found"})
        fake.status, fake.payload = 410, {"detail": "WhatsApp setup expired."}
        assert await call("GET", WA_STATUS) == (410, {"error": "channel_setup_expired"})
        fake.status, fake.payload = 500, {"detail": "boom"}
        assert await call("GET", WA_STATUS) == (502, {"error": "desktop_error"})


@pytest.mark.parametrize("agents", [2, None])
@asynchronous
async def test_whatsapp_apply_busy_guard(tmp_path, monkeypatch, agents):
    async with channels(tmp_path, monkeypatch) as (bridge, fake, call):
        if agents is None:
            bridge.store.write("profile-keys.json", {})
        else:
            fake.gateway.active_agents = agents
        assert await call("POST", WA_APPLY, {"force": False}) == (409, {"error": "gateway_busy", "active_agents": agents})
        assert not fake.sent("POST", WA_API + WA_PAIRING + "/apply")
        status, value = await call("POST", WA_APPLY, {"force": True})
        assert (status, value) == (200, {"ok": True, "restart_started": True, "needs_restart": False})
        assert len(fake.sent("POST", WA_API + WA_PAIRING + "/apply")) == 1


@asynchronous
async def test_whatsapp_apply_request_and_mapping(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.gateway.active_agents = 0
        path = WA_API + WA_PAIRING + "/apply"
        assert (await call("POST", WA_APPLY, {"force": False}))[0] == 200
        assert fake.sent("POST", path)[0][3] == {"profile": "default"}
        assert (await call("POST", WA_APPLY, {"force": False, "mode": "self-chat", "allowed_users": "+12,34,+12"}))[0] == 200
        assert fake.sent("POST", path)[1][3] == {"profile": "default", "mode": "self-chat", "allowed_users": "+12,34"}
        fake.wa_apply_payload = {**fake.wa_apply_payload, "restart_started": False, "needs_restart": True}
        assert await call("POST", WA_APPLY, {"force": False}) == (200, {"ok": True, "restart_started": False, "needs_restart": True})


@pytest.mark.parametrize("code,reply", [
    (404, {"error": "channel_setup_not_found"}), (410, {"error": "channel_setup_expired"}),
    (409, {"error": "channel_setup_not_ready", "message": "The setup is not finished yet."})])
@asynchronous
async def test_whatsapp_apply_refusals_are_mapped(tmp_path, monkeypatch, code, reply):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.status, fake.payload = code, {"detail": "WhatsApp setup is not connected yet."}
        assert await call("POST", WA_APPLY, {"force": True}) == (code, reply)


@pytest.mark.parametrize("status_code", [500, 502])
@asynchronous
async def test_whatsapp_apply_not_ready_survives_a_failing_status_check(tmp_path, monkeypatch, status_code):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.status, fake.payload = 409, None

        def result(method, path, value):
            fake.status = status_code  # the first call (apply) already used 409
            return {"detail": "WhatsApp setup is not connected yet."}
        fake.result = result
        assert await call("POST", WA_APPLY, {"force": True}) == (
            409, {"error": "channel_setup_not_ready", "message": "The setup is not finished yet."})


@asynchronous
async def test_whatsapp_apply_on_an_expired_setup_says_expired(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.status, fake.payload = 409, None  # like Hermes: apply on an expired setup is 409, its status is 410

        def result(method, path, value):
            fake.status = 410
            return {"detail": "WhatsApp setup is not connected yet."}
        fake.result = result
        assert await call("POST", WA_APPLY, {"force": True}) == (410, {"error": "channel_setup_expired"})


@pytest.mark.parametrize("odd", [{"ok": False}, {"ok": True, "restart_started": "yes", "needs_restart": False},
                                 {"ok": True, "restart_started": True}, [], None])
@asynchronous
async def test_whatsapp_apply_malformed_reply(tmp_path, monkeypatch, odd):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.wa_apply_payload = odd
        assert await call("POST", WA_APPLY, {"force": True}) == (502, {"error": "desktop_error"})


@asynchronous
async def test_whatsapp_cancel(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("DELETE", WA_STATUS) == (200, {"ok": True})
        assert len(fake.sent("DELETE", WA_API + WA_PAIRING)) == 1


def whatsapp_row():
    return platform("whatsapp", env_vars=[
        env_var("WHATSAPP_ALLOWED_USERS", required=False, is_list=True), env_var("WHATSAPP_MODE", required=False)])


@asynchronous
async def test_settings_allow_list_comes_from_a_fresh_list(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        put = "/api/messaging/platforms/telegram"
        assert await call("PUT", SETTINGS, {"values": {"WHATSAPP_MODE": "bot"}}) == (400, {"error": "channel_unknown_setting"})
        assert await call("PUT", SETTINGS, {"clear": ["NOT_A_FIELD"]}) == (400, {"error": "channel_unknown_setting"})
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}, "clear": []}))[0] == 200
        fake.platforms[0]["env_vars"].append(env_var("TELEGRAM_NEW_FIELD"))
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_NEW_FIELD": "v"}}))[0] == 200
        fake.platforms[0]["env_vars"].pop()
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_NEW_FIELD": "v"}}))[0] == 400
        assert len(fake.sent("PUT", put)) == 2


@asynchronous
async def test_settings_forwards_only_env_clear_and_profile(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, value = await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN},
                                                      "clear": ["TELEGRAM_ALLOWED_USERS"]})
        assert status == 200
        sent = fake.sent("PUT", "/api/messaging/platforms/telegram")
        assert len(sent) == 1
        assert sent[0][3] == {"env": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}, "clear_env": ["TELEGRAM_ALLOWED_USERS"],
                              "profile": "default"}
        assert value["id"] == "telegram" and value["needs_restart"] is True and value["state"] == "connected"
        text = json.dumps(value)
        for hidden in (BOT_TOKEN, SECRET, fake.token):
            assert hidden not in text
        assert set(value) == {"id", "name", "description", "enabled", "configured", "gateway_running", "state",
                              "error_code", "error_message", "home_channel_set", "fields", "needs_restart"}
        assert (await call("PUT", SETTINGS, {"clear": ["TELEGRAM_BOT_TOKEN"]}))[0] == 200
        assert fake.sent("PUT", "/api/messaging/platforms/telegram")[1][3] == {
            "env": {}, "clear_env": ["TELEGRAM_BOT_TOKEN"], "profile": "default"}


@pytest.mark.parametrize("secret", ["abcd\u2026wxyz", "\u00abredacted:abcd\u00bb", "ab****yz", "***", "abcd...wxyz", "a" * 4000 + "****"])
@asynchronous
async def test_settings_refuse_a_redacted_preview(tmp_path, monkeypatch, secret):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        status, value = await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": secret}})
        assert (status, value) == (400, {"error": "channel_setting_redacted"})
        assert not fake.sent("PUT", "/api/messaging/platforms/telegram")


@pytest.mark.parametrize("body", [
    None, {}, {"values": {}}, {"clear": []}, {"values": {}, "clear": []}, {"values": [], "clear": ["TELEGRAM_BOT_TOKEN"]},
    {"values": {"TELEGRAM_BOT_TOKEN": 5}}, {"values": {"TELEGRAM_BOT_TOKEN": None}}, {"values": {"TELEGRAM_BOT_TOKEN": ""}},
    {"values": {"TELEGRAM_BOT_TOKEN": "   "}}, {"values": {"TELEGRAM_BOT_TOKEN": "a\nb"}}, {"values": {"TELEGRAM_BOT_TOKEN": "a\x00b"}},
    {"values": {"TELEGRAM_BOT_TOKEN": "a\x7fb"}}, {"values": {"TELEGRAM_BOT_TOKEN": "a" * 4097}},
    {"values": {"lower": "v"}}, {"values": {"1BAD": "v"}}, {"values": {"A" * 65: "v"}}, {"clear": ["lower"]}, {"clear": [5]},
    {"clear": "TELEGRAM_BOT_TOKEN"}, {"clear": ["TELEGRAM_BOT_TOKEN"] * 2},
    {"values": {"TELEGRAM_BOT_TOKEN": "v"}, "clear": ["TELEGRAM_BOT_TOKEN"]},
    {"values": {"TELEGRAM_BOT_TOKEN": "v"}, "extra": 1},
    {"values": {f"K{i}": "v" for i in range(9)}}, {"clear": [f"K{i}" for i in range(9)]},
    b'{"values":{"TELEGRAM_BOT_TOKEN":"a","TELEGRAM_BOT_TOKEN":"b"}}'])
@asynchronous
async def test_settings_strict_bodies(tmp_path, monkeypatch, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", SETTINGS, body) == (400, {"error": "desktop_invalid"})
        assert not [r for r in fake.requests if "/api/messaging" in r[1]]


@asynchronous
async def test_settings_size_limits(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": "a" * 4096}}))[0] == 200
        assert (await call("PUT", SETTINGS, b" " * (256 * 1024 + 1)))[0] == 400
        assert (await call("POST", "/channels/restart", b" " * 1025))[0] == 400


@asynchronous
async def test_settings_unknown_channel_and_hermes_refusals(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", "/channels/nothing/settings", {"values": {"A": "v"}}) == (404, {"error": "channel_not_found"})
        assert await call("PUT", "/channels/Bad-Id/settings", {"values": {"A": "v"}}) == (404, {"error": "Route not found"})
        fake.platforms.append(whatsapp_row())
        assert (await call("PUT", "/channels/whatsapp/settings", {"values": {"WHATSAPP_MODE": "bot"}}))[0] == 200
        fake.status, fake.payload = 500, {"detail": "boom"}
        assert await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}}) == (502, {"error": "desktop_error"})


@asynchronous
async def test_settings_hermes_validation_error_is_a_fixed_code(tmp_path, monkeypatch):
    class Refusing(FakeChannels):
        replies = iter([200, 400])  # the list read, then the write
        status = property(lambda self: next(self.replies), lambda self, value: None)

        def result(self, method, path, value):
            if method == "PUT":
                return {"detail": "invalid " + BOT_TOKEN}
            return super().result(method, path, value)

    async with desktop(tmp_path, monkeypatch, Refusing) as (_, fake, call):
        reply = await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}})
        assert reply == (400, {"error": "channel_setting_invalid"})
        assert len(fake.sent("PUT", "/api/messaging/platforms/telegram")) == 1


@asynchronous
async def test_settings_row_vanishing_after_save_is_clean(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        original = fake.result

        def result(method, path, value):
            if method == "PUT":
                fake.platforms = []
            return original(method, path, value)

        fake.result = result
        assert await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}}) == (404, {"error": "channel_not_found"})


@asynchronous
async def test_audit_names_settings_keys_and_whatsapp_apply_only(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.gateway.active_agents = 1
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}, "clear": ["TELEGRAM_ALLOWED_USERS"]}))[0] == 200
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": "abcd\u2026wxyz"}}))[0] == 400
        assert (await call("POST", WA_APPLY, {"force": True, "allowed_users": "+49123"}))[0] == 200
        assert (await call("POST", WA_APPLY, {"force": False}))[0] == 409
        await call("POST", "/channels/whatsapp/setup", {"mode": "bot", "allowed_users": "+49123"})
        await call("GET", WA_STATUS)
        text = bridge.audit.path.read_text()
        for secret in (BOT_TOKEN, SECRET, fake.token, WA_PAIRING, QR, PHONE, "+49123", "49123", "abcd"):
            assert secret not in text
        marks = [json.loads(line) for line in text.splitlines()]
        marks = [r for r in marks if r.get("event") == "channels"]
        assert [r["action"] for r in marks] == ["settings", "whatsapp_apply"]
        assert marks[0]["set"] == ["TELEGRAM_BOT_TOKEN"] and marks[0]["cleared"] == ["TELEGRAM_ALLOWED_USERS"]
        assert set(marks[0]) == {"time", "event", "action", "set", "cleared"}
        assert marks[1]["force"] is True and set(marks[1]) == {"time", "event", "action", "force"}


WA_ERRORS = [
    ("500: WhatsApp bridge script was not found at C:\\Users\\someuser\\hermes\\bridge.js.", "whatsapp_bridge_missing"),
    ("Failed to install WhatsApp bridge dependencies: [Errno 2] C:\\Users\\someuser\\npm", "whatsapp_install_failed"),
    ("npm install failed for WhatsApp bridge: ENOENT /home/someuser/bridge", "whatsapp_install_failed"),
    ("Node.js preparation failed: no binary for someuser", "whatsapp_install_failed"),
    ("Installing WhatsApp bridge dependencies timed out.", "whatsapp_timeout"),
    ("WhatsApp QR setup expired. Start a new setup.", "whatsapp_setup_failed"),
    ("logged_out", "whatsapp_logged_out"),
    ("WhatsApp pairing process exited with code 1.", "whatsapp_setup_failed"),
    ("Stream errored for " + PHONE + ":12@s.whatsapp.net", "whatsapp_setup_failed"),
    ("WhatsApp pairing failed.", "whatsapp_setup_failed"),
    ("totally new text", "whatsapp_setup_failed")]


@pytest.mark.parametrize("text,code", WA_ERRORS)
@asynchronous
async def test_whatsapp_error_is_a_fixed_code(tmp_path, monkeypatch, text, code):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.wa_payload = {"pairing_id": WA_PAIRING, "status": "error", "error": text}
        status, value = await call("GET", WA_STATUS)
        assert (status, value) == (200, {"pairing_id": WA_PAIRING, "status": "error", "error": code})
        status, value = await call("POST", "/channels/whatsapp/setup", {"mode": "bot"})
        assert status == 200 and value["error"] == code
        body = json.dumps(value)
        for leak in ("someuser", PHONE, "s.whatsapp.net", "npm", "C:"):
            assert leak not in body


@asynchronous
async def test_whatsapp_account_name_that_is_a_phone_number_keeps_last_four(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        for name, shown in (("+49 1234567890", "7890"), ("0049-123 456", "3456"), ("  +4 9  ", "")):
            fake.wa_payload = {"pairing_id": WA_PAIRING, "status": "connected", "account_name": name}
            expected = {"pairing_id": WA_PAIRING, "status": "connected", **({"account_name": shown} if shown else {})}
            assert await call("GET", WA_STATUS) == (200, expected)
        arabic = "".join(chr(0x660 + int(digit)) for digit in "491234567890")
        for name, shown in (("Ann 491234567890", "Ann 7890"), ("(049) 1234-567890", "(7890"),
                            ("049.1234.567890", "7890"), (arabic, arabic[-4:]),
                            ("491234567890:12@s.whatsapp.net", "7890:12@s.whatsapp.net"), ("Team 42", "Team 42"),
                            ("Room 1234 and 5678", "Room 1234 and 5678"),
                            ("+1 000/000_0000", "+0000"), ("1000" + chr(0x2013) + "000" + chr(0x2013) + "0000", "0000"),
                            ("Ann 12" + chr(0x200B) + "345" + chr(0x2060) + "678" + chr(0xFEFF) + "90", "Ann 7890"),
                            ("Ann 123,456,7890", "Ann 7890"), ("Year 2026", "Year 2026")):
            fake.wa_payload = {"pairing_id": WA_PAIRING, "status": "connected", "account_name": name}
            assert (await call("GET", WA_STATUS))[1]["account_name"] == shown
        for name in ("Test Person", "Dev 2", "12 Monkeys", "x" * 80):
            fake.wa_payload = {"pairing_id": WA_PAIRING, "status": "connected", "account_name": name}
            assert (await call("GET", WA_STATUS))[1]["account_name"] == name[:64]


@pytest.mark.parametrize("hot,state,needed", [
    (False, "connected", True), (True, "connected", False), (True, "pending_restart", True),
    (False, "pending_restart", True), ("yes", "connected", True), (None, "connected", True)])
@asynchronous
async def test_settings_need_restart_unless_hermes_served_it_live(tmp_path, monkeypatch, hot, state, needed):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.hot_served, fake.settled = hot, state
        status, value = await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}})
        assert status == 200 and value["state"] == state and value["needs_restart"] is needed


@asynchronous
async def test_settings_unparsable_save_reply_needs_restart(tmp_path, monkeypatch):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        fake.settled = "connected"
        fake.hot_served = None
        original = fake.result
        fake.result = lambda method, path, value: [] if method == "PUT" else original(method, path, value)
        status, value = await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": BOT_TOKEN}})
        assert status == 200 and value["needs_restart"] is True


@pytest.mark.parametrize("secret", ["abc\u2028WHATSAPP_MODE=x", "abc\x85PATH=x", "token \U0001f600", "caf\u00e9", "\u00a0\u00a0v"])
@asynchronous
async def test_settings_refuse_non_ascii_values(tmp_path, monkeypatch, secret):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": secret}}) == (400, {"error": "channel_setting_invalid"})
        assert not fake.sent("PUT", "/api/messaging/platforms/telegram")
        assert (await call("PUT", SETTINGS, {"values": {"TELEGRAM_BOT_TOKEN": "plain-ascii~!"}}))[0] == 200


@pytest.mark.parametrize("suffix,body", [
    ("/channels/whatsapp/setup", {"mode": "bot", "allowed_users": "", "x": "a" * 500000}),
    (WA_APPLY, {"force": True, "x": "a" * 500000})])
@asynchronous
async def test_big_whatsapp_bodies_are_refused_before_parsing(tmp_path, monkeypatch, suffix, body):
    async with channels(tmp_path, monkeypatch) as (_, fake, call):
        parsed = []
        original = desktop_module.decode
        monkeypatch.setattr(desktop_module, "decode", lambda data: parsed.append(len(data)) or original(data))
        assert await call("POST", suffix, json.dumps(body).encode()) == (400, {"error": "desktop_invalid"})
        assert not parsed and not [r for r in fake.requests if "/api/messaging" in r[1]]
        assert (await call("POST", suffix, json.dumps({k: v for k, v in body.items() if k != "x"}).encode()))[0] == 200
        assert parsed and max(parsed) < 1024  # the small body was parsed; the 500 KB one never was
