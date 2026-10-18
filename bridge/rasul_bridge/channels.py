"""Messaging channels from the phone: list, switch on or off, test, restart, Telegram setup.

Every response is rebuilt from checked fields. Hermes payloads are never passed through, and
no channel secret (token, allow-list, address, local path) is ever read into a response.
"""

from contextlib import suppress
from datetime import datetime, timezone
import re
from urllib.parse import urlsplit

from .audit import Audit
from .updater import clean

CHANNEL = r"[a-z0-9_]{1,32}"
PAIRING = r"[A-Za-z0-9_-]{8,128}"
USER_ID = r"[0-9]{1,20}"
PLATFORMS = "/api/messaging/platforms"
TELEGRAM = "/api/messaging/telegram/onboarding"
LINK_HOSTS = ("t.me", "telegram.me")
MAX_CHANNELS, MAX_FIELDS = 64, 32


class Refused(ValueError):
    """Hermes answered with a status the caller chose to map; anything uncaught is a 502."""

    def __init__(self, status, body):
        super().__init__(status)
        self.status, self.body = status, body


def refusal(refused, codes):
    body = {"error": codes[refused.status]}
    if refused.status == 409:
        detail = refused.body.get("detail") if isinstance(refused.body, dict) else None
        body["message"] = clean(detail, 200)
    return refused.status, body


def field(row):
    if not isinstance(row, dict):
        return None
    key, flags = row.get("key"), [row.get(name) for name in ("required", "is_set", "is_list")]
    if not isinstance(key, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", key) \
            or any(type(flag) is not bool for flag in flags):
        return None
    return {"key": key, "required": flags[0], "is_set": flags[1], "is_list": flags[2],
            "prompt": clean(row.get("prompt"), 100)}


def channel(row):
    """One projected row, or None when Hermes sent something malformed."""
    if not isinstance(row, dict):
        return None
    ident, flags = row.get("id"), [row.get(name) for name in ("enabled", "configured", "gateway_running")]
    state, fields = row.get("state"), row.get("env_vars")
    if (not isinstance(ident, str) or not re.fullmatch(CHANNEL, ident)
            or any(type(flag) is not bool for flag in flags)
            or not (state is None or isinstance(state, str)) or not isinstance(fields, list)):
        return None
    name = clean(row.get("name"), 64)
    if not name:
        return None
    return {"id": ident, "name": name, "description": clean(row.get("description"), 300),
            "enabled": flags[0], "configured": flags[1], "gateway_running": flags[2],
            "state": clean(state, 32) if state is not None else None,
            "error_code": clean(row.get("error_code"), 64) or None,
            "error_message": clean(row.get("error_message"), 200) or None,
            "home_channel_set": isinstance(row.get("home_channel"), dict) and bool(row["home_channel"]),
            "fields": [f for f in map(field, fields[:MAX_FIELDS]) if f]}


async def listing(request):
    platforms = (await request("GET", PLATFORMS))["platforms"]
    if not isinstance(platforms, list):
        raise ValueError
    return [c for c in map(channel, platforms[:MAX_CHANNELS]) if c]


def deep_link(value):
    """A rebuilt https link to Telegram, or None for anything that is not exactly that shape."""
    if not isinstance(value, str) or not re.fullmatch(r"[\x21-\x7e]{1,512}", value):
        return None
    try:
        parts = urlsplit(value)
    except ValueError:
        return None
    if (parts.scheme != "https" or parts.netloc not in LINK_HOSTS or parts.fragment or "#" in value
            or not parts.path.startswith("/") or not re.fullmatch(r"[A-Za-z0-9_/]{1,64}", parts.path[1:])
            or not re.fullmatch(r"[A-Za-z0-9_=&-]{0,256}", parts.query)):
        return None
    return "https://" + parts.netloc + parts.path + ("?" + parts.query if parts.query else "")


def audit(desktop, name, force):
    with suppress(Exception):
        Audit(desktop.profiles.store).write({"time": datetime.now(timezone.utc).isoformat(),
                                             "event": "channels", "action": name, "force": force})


async def busy(desktop, force):
    """A 409 reply while any agent works (or the count is unknown), unless the owner forces it."""
    if force:
        return None
    count = await desktop.updater.active_agents()
    if count is None or count > 0:
        return 409, {"error": "gateway_busy", "active_agents": count}
    return None


async def handle(desktop, profile, name, match, value, request):
    """Returns (status, body)."""
    if name == "chan_list":
        return 200, {"channels": await listing(request)}
    if name == "chan_enabled":
        try:
            await request("PUT", f"{PLATFORMS}/{match[1]}", {"enabled": value["enabled"], "profile": profile},
                          soft=(404, 409))
        except Refused as refused:
            return refusal(refused, {404: "channel_not_found", 409: "channel_conflict"})
        row = next((c for c in await listing(request) if c["id"] == match[1]), None)
        if row is None:
            return 404, {"error": "channel_not_found"}
        return 200, {**row, "needs_restart": row["state"] == "pending_restart"}
    if name == "chan_test":
        try:
            raw = await request("POST", f"{PLATFORMS}/{match[1]}/test", soft=(404,))
        except Refused as refused:
            return refusal(refused, {404: "channel_not_found"})
        if not isinstance(raw, dict) or type(raw.get("ok")) is not bool:
            raise ValueError
        result = {"ok": raw["ok"], "state": clean(raw.get("state"), 32), "message": clean(raw.get("message"), 300)}
        if isinstance(raw.get("auth_posture"), str) and clean(raw["auth_posture"], 32):
            result["auth_posture"] = clean(raw["auth_posture"], 32)
        return 200, result
    if name == "chan_restart":
        if reply := await busy(desktop, value["force"]):
            return reply
        audit(desktop, "restart", value["force"])
        receipt = await request("POST", "/api/gateway/restart")
        if not isinstance(receipt, dict) or receipt.get("ok") is not True:
            raise ValueError
        return 200, {"ok": True}
    if name == "chan_setup":
        raw = await request("POST", TELEGRAM + "/start", {"bot_name": value["bot_name"]} if value.get("bot_name") else {})
        pairing = raw.get("pairing_id")
        if not isinstance(pairing, str) or not re.fullmatch(PAIRING, pairing):
            raise ValueError
        link = deep_link(raw.get("deep_link"))
        if link is None:
            return 502, {"error": "channel_setup_bad_link"}
        return 200, {"pairing_id": pairing, "deep_link": link,
                     "suggested_username": clean(raw.get("suggested_username"), 64),
                     "expires_at": clean(raw.get("expires_at"), 64)}
    codes = {404: "channel_setup_not_found", 409: "channel_setup_not_ready", 410: "channel_setup_expired"}
    if name == "chan_status":
        try:
            raw = await request("GET", f"{TELEGRAM}/{match[1]}", soft=(404, 410))
        except Refused as refused:
            return refusal(refused, codes)
        if not isinstance(raw, dict) or raw.get("status") not in ("waiting", "ready"):
            raise ValueError
        result = {"status": raw["status"]}
        if raw["status"] == "ready":
            username = clean(raw.get("bot_username"), 64)
            owner = raw.get("owner_user_id")
            if username:
                result["bot_username"] = username
            if isinstance(owner, str) and re.fullmatch(USER_ID, owner):
                result["owner_user_id"] = owner
        if clean(raw.get("expires_at"), 64):
            result["expires_at"] = clean(raw["expires_at"], 64)
        return 200, result
    if name == "chan_apply":
        if reply := await busy(desktop, value["force"]):
            return reply
        audit(desktop, "telegram_apply", value["force"])
        try:
            raw = await request("POST", f"{TELEGRAM}/{match[1]}/apply",
                                {"allowed_user_ids": list(dict.fromkeys(value["allowed_user_ids"])),
                                 "profile": profile}, soft=(404, 409))
        except Refused as refused:
            return refusal(refused, codes)
        if (not isinstance(raw, dict) or raw.get("ok") is not True
                or type(raw.get("restart_started")) is not bool or type(raw.get("needs_restart")) is not bool):
            raise ValueError
        return 200, {"ok": True, "bot_username": clean(raw.get("bot_username"), 64) or None,
                     "restart_started": raw["restart_started"], "needs_restart": raw["needs_restart"]}
    receipt = await request("DELETE", f"{TELEGRAM}/{match[1]}")  # chan_cancel
    if not isinstance(receipt, dict) or receipt.get("ok") is not True:
        raise ValueError
    return 200, {"ok": True}
