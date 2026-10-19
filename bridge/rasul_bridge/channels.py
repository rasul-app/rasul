"""Messaging channels from the phone: list, switch on or off, test, restart, Telegram and WhatsApp
setup, and channel settings.

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
WHATSAPP = "/api/messaging/whatsapp/onboarding"
WHATSAPP_STATES = ("installing", "starting", "waiting", "connected", "error", "cancelled")
WHATSAPP_MODES = ("bot", "self-chat")
PHONES = r"\+?[0-9]{1,20}(,\+?[0-9]{1,20}){0,19}"
SETTING = r"[A-Z][A-Z0-9_]{0,63}"
MAX_CHANNELS, MAX_FIELDS = 64, 32
MAX_VALUE, MAX_SETTINGS = 4096, 8
MESSAGES = {"channel_conflict": "The computer would not turn this channel on.",
            "channel_setup_not_ready": "The setup is not finished yet."}
# Static first words of the setup errors Hermes can report; anything else is the generic code.
WHATSAPP_ERRORS = (("whatsapp bridge script was not found", "whatsapp_bridge_missing"),
                   ("failed to install whatsapp bridge", "whatsapp_install_failed"),
                   ("npm install failed for whatsapp bridge", "whatsapp_install_failed"),
                   ("node.js preparation failed", "whatsapp_install_failed"),
                   ("installing whatsapp bridge dependencies timed out", "whatsapp_timeout"),
                   ("logged_out", "whatsapp_logged_out"))
BODY_LIMIT = {"chan_settings": 256 * 1024}  # every other body stays at 1024 bytes


class Refused(ValueError):
    """Hermes answered with a status the caller chose to map; anything uncaught is a 502."""

    def __init__(self, status, body):
        super().__init__(status)
        self.status, self.body = status, body


def refusal(refused, codes):
    body = {"error": codes[refused.status]}
    if refused.status == 409:
        body["message"] = MESSAGES[body["error"]]
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


def audit(desktop, name, force=None, **names):
    """One line per change: the action and, for settings, key names only. Never ids, values or tokens."""
    record = {"time": datetime.now(timezone.utc).isoformat(), "event": "channels", "action": name}
    if force is not None:
        record["force"] = force
    with suppress(Exception):
        Audit(desktop.profiles.store).write({**record, **names})


def valid_body(name, value):
    """Whole-body check for the routes that do not fit the generic key rules in desktop.py."""
    if not isinstance(value, dict):
        return False
    if name == "chan_settings":
        values, clear = value.get("values", {}), value.get("clear", [])
        return (set(value) <= {"values", "clear"} and isinstance(values, dict) and isinstance(clear, list)
                and 0 < len(values) + len(clear) and len(values) <= MAX_SETTINGS and len(clear) <= MAX_SETTINGS
                and all(type(k) is str and re.fullmatch(SETTING, k) and type(v) is str and v.strip()
                        and len(v) <= MAX_VALUE and not re.search(r"[\x00-\x1f\x7f]", v) for k, v in values.items())
                and all(type(k) is str and re.fullmatch(SETTING, k) for k in clear)
                and len(set(clear)) == len(clear) and not set(values) & set(clear))
    users = value.get("allowed_users", "")
    keys = {"chan_wa_setup": {"mode", "allowed_users"}, "chan_wa_apply": {"mode", "allowed_users", "force"}}[name]
    return (set(value) <= keys and (value.get("mode") in WHATSAPP_MODES or (name == "chan_wa_apply" and "mode" not in value))
            and type(users) is str and (users == "" or re.fullmatch(PHONES, users) is not None)
            and (name == "chan_wa_setup" or type(value.get("force")) is bool))


def whatsapp_error(text):
    """A fixed code for a Hermes setup error. The text itself can hold paths, names or numbers."""
    lowered = re.sub(r"^[0-9]{3}: ", "", clean(text, 200)).lower()  # the str() of an HTTP error starts with its status
    return next((code for start, code in WHATSAPP_ERRORS if lowered.startswith(start)), "whatsapp_setup_failed")


def account_name(value):
    """The profile name, or its last 4 digits when it only looks like a phone number."""
    name = clean(value, 64)
    if re.fullmatch(r"[+\s-]*[0-9][0-9+\s-]*", name):
        digits = re.sub(r"[^0-9]", "", name)
        return digits[-4:] if len(digits) >= 4 else ""
    # Any other run of 5 or more digits (also Arabic-Indic, with any separators except : and @, which end a run) keeps only its last 4.
    return re.sub(r"\d(?:(?:[^\w:@]|_)*\d){4,}", lambda run: re.sub(r"\D", "", run.group())[-4:], name)


def whatsapp(raw, wanted=None):
    """A rebuilt WhatsApp setup state: the QR only while waiting for a scan, the phone as its last 4 digits."""
    pairing = raw.get("pairing_id") if isinstance(raw, dict) else None
    if (not isinstance(pairing, str) or not re.fullmatch(PAIRING, pairing) or pairing != (wanted or pairing)
            or raw.get("status") not in WHATSAPP_STATES):
        raise ValueError
    result = {"pairing_id": pairing, "status": raw["status"]}
    code = raw.get("qr_payload")
    if raw["status"] == "waiting" and isinstance(code, str) and re.fullmatch(r"[\x20-\x7e]{1,1024}", code):
        result["qr_payload"] = code
    phone = re.sub(r"[^0-9]", "", raw["account_phone"]) if isinstance(raw.get("account_phone"), str) else ""
    for key, text in (("expires_at", clean(raw.get("expires_at"), 64)), ("account_name", account_name(raw.get("account_name"))),
                      ("account_phone_last4", phone[-4:] if len(phone) >= 4 else ""),
                      ("error", whatsapp_error(raw["error"]) if clean(raw.get("error"), 200) else "")):
        if text:
            result[key] = text
    return result


def hides_secret(text):
    """True for a value that is a masked preview rather than a real secret."""
    return ("\u2026" in text or "****" in text or text.startswith("\u00abredacted") or text.strip() == "***"
            or re.fullmatch(r".{4}\.\.\..{4}", text.strip()) is not None)


def phones(value):
    return ",".join(dict.fromkeys(value.split(",")))


async def settings(desktop, profile, match, value, request):
    values, clear = value.get("values", {}), value.get("clear", [])
    if any(hides_secret(text) for text in values.values()):
        return 400, {"error": "channel_setting_redacted"}
    if any(not text.isascii() for text in values.values()):  # Hermes drops these silently
        return 400, {"error": "channel_setting_invalid"}
    row = next((c for c in await listing(request) if c["id"] == match[1]), None)
    if row is None:
        return 404, {"error": "channel_not_found"}
    if not (set(values) | set(clear)) <= {f["key"] for f in row["fields"]}:
        return 400, {"error": "channel_unknown_setting"}
    audit(desktop, "settings", set=sorted(values), cleared=sorted(clear))
    try:
        saved = await request("PUT", f"{PLATFORMS}/{match[1]}", {"env": values, "clear_env": clear, "profile": profile},
                              soft=(400, 404))
    except Refused as refused:
        return refusal(refused, {400: "channel_setting_invalid", 404: "channel_not_found"})
    row = next((c for c in await listing(request) if c["id"] == match[1]), None)
    if row is None:
        return 404, {"error": "channel_not_found"}
    return 200, {**row, "needs_restart": restart_needed(saved, row)}


def restart_needed(saved, row):
    """The Hermes channels page rule: a restart is needed unless Hermes served the change to the running gateway."""
    hot = isinstance(saved, dict) and saved.get("hot_served") is True
    return not hot or row["state"] == "pending_restart"


async def whatsapp_handle(desktop, profile, name, match, value, request):
    codes = {404: "channel_setup_not_found", 409: "channel_setup_not_ready", 410: "channel_setup_expired"}
    if name == "chan_wa_setup":
        body = {"mode": value["mode"], "profile": profile}
        if value.get("allowed_users"):
            body["allowed_users"] = phones(value["allowed_users"])
        return 200, whatsapp(await request("POST", WHATSAPP + "/start", body))
    if name == "chan_wa_status":
        try:
            raw = await request("GET", f"{WHATSAPP}/{match[1]}", soft=(404, 410))
        except Refused as refused:
            return refusal(refused, codes)
        return 200, whatsapp(raw, match[1])
    if name == "chan_wa_apply":
        if reply := await busy(desktop, value["force"]):
            return reply
        audit(desktop, "whatsapp_apply", value["force"])
        body = {"profile": profile}
        if "mode" in value:
            body["mode"] = value["mode"]
        if value.get("allowed_users"):
            body["allowed_users"] = phones(value["allowed_users"])
        try:
            raw = await request("POST", f"{WHATSAPP}/{match[1]}/apply", body, soft=(404, 409, 410))
        except Refused as refused:
            if refused.status == 409:  # Hermes answers "not connected" for an expired setup; the status route says 410
                try:
                    await request("GET", f"{WHATSAPP}/{match[1]}", soft=(404, 409, 410))
                except Refused as expired:
                    if expired.status == 410:
                        return refusal(expired, codes)
                except Exception:  # any other failure of the status check keeps the original refusal
                    pass
            return refusal(refused, codes)
        if (not isinstance(raw, dict) or raw.get("ok") is not True
                or type(raw.get("restart_started")) is not bool or type(raw.get("needs_restart")) is not bool):
            raise ValueError
        return 200, {"ok": True, "restart_started": raw["restart_started"], "needs_restart": raw["needs_restart"]}
    receipt = await request("DELETE", f"{WHATSAPP}/{match[1]}")  # chan_wa_cancel
    if not isinstance(receipt, dict) or receipt.get("ok") is not True:
        raise ValueError
    return 200, {"ok": True}


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
    if name == "chan_settings":
        return await settings(desktop, profile, match, value, request)
    if name.startswith("chan_wa_"):
        return await whatsapp_handle(desktop, profile, name, match, value, request)
    if name == "chan_list":
        return 200, {"channels": await listing(request)}
    if name == "chan_enabled":
        try:
            saved = await request("PUT", f"{PLATFORMS}/{match[1]}", {"enabled": value["enabled"], "profile": profile},
                                  soft=(404, 409))
        except Refused as refused:
            return refusal(refused, {404: "channel_not_found", 409: "channel_conflict"})
        row = next((c for c in await listing(request) if c["id"] == match[1]), None)
        if row is None:
            return 404, {"error": "channel_not_found"}
        return 200, {**row, "needs_restart": restart_needed(saved, row)}
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
