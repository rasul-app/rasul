"""Ephemeral approval mirroring and a separate local authenticated listener."""

import asyncio
from collections import deque
from contextlib import suppress
import hashlib
import hmac
import json
import math
import re
import secrets
import time

from .config import PROFILE_ID
from .http import HTTPError, Reader, chunk, request, response, stream_start
from .storage import atomic_write, private_dir


REQUEST_ID = re.compile(r"[0-9a-f]{32}\Z")
PHONE_ROUTE = re.compile(
    r"/v1/profiles/([a-z0-9][a-z0-9_-]{0,63})/approvals"
    r"(?:/(events)|/([0-9a-f]{32})/(answer))?\Z")
ALL_ROUTE = re.compile(r"/v1/approvals(?:/[^?]*)?\Z")
INTERNAL_ROUTE = re.compile(
    r"/internal/profiles/([a-z0-9][a-z0-9_-]{0,63})/approvals"
    r"(?:/(answers|hello)|/([0-9a-f]{32})/(resolved))?\Z")


def decode(body):
    try:
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError
        return value
    except (ValueError, UnicodeError):
        raise HTTPError(400, "approvals_invalid") from None


class LocalLink:
    def __init__(self, writer):
        self.writer = writer

    async def write(self, data):
        self.writer.write(data)
        await self.writer.drain()


class Approvals:
    def __init__(self, store, profiles, audit, clock=time.time):
        self.store, self.profiles, self.audit, self.clock = store, profiles, audit, clock
        self.entries = {}
        self.history = deque(maxlen=512)
        self.revision = 0
        self.rates = {}
        self.nonces = {}
        self.credentials = {}
        self.tasks = set()
        self.server = None

    def secret_path(self, profile):
        if not isinstance(profile, str) or not PROFILE_ID.fullmatch(profile):
            raise ValueError("Invalid profile")
        return self.store.root / "approval-secrets" / (profile + ".secret")

    def credential(self, profile):
        if profile not in self.profiles.directories():
            raise HTTPError(401, "approvals_unauthorized")
        path = self.secret_path(profile)
        with self.store.lock():
            private_dir(path.parent)
            if path.is_symlink() or (path.exists() and path.stat().st_nlink != 1):
                raise OSError("Unsafe credential file")
            if not path.exists():
                atomic_write(path, secrets.token_urlsafe(32).encode("ascii"))
            return path.read_bytes()

    async def start(self, port):
        self.credentials = {profile: self.credential(profile) for profile in self.profiles.directories()}
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", port)
        self.port = self.server.sockets[0].getsockname()[1]

    async def close(self):
        if self.server:
            self.server.close()
            await self.server.wait_closed()
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    def emit(self, event, profile, request_id):
        self.revision += 1
        self.history.append((self.revision, event, {"profile": profile, "request_id": request_id}))

    def sweep(self):
        now = self.clock()
        for key, entry in list(self.entries.items()):
            if entry["expires_at"] <= now and entry["state"] in ("pending", "answered"):
                self.clear(entry, "expired")
            retirement = min(entry["expires_at"], entry.get("resolved_at", entry["expires_at"]) + 60) + 60
            if retirement <= now:
                self.entries.pop(key, None)

    def clear(self, entry, state="resolved"):
        entry["state"] = state
        entry["resolved_at"] = self.clock()
        entry.pop("payload", None)
        self.emit("resolved", entry["profile"], entry["request_id"])
        closed = [key for key, value in self.entries.items() if value["state"] not in ("pending", "answered")]
        for key in closed[:-1024]:
            del self.entries[key]

    def publish(self, profile, value):
        fields = {"request_id", "session_key", "profile", "surface", "command", "description",
                  "allow_session", "allow_permanent", "expires_at"}
        if (set(value) != fields or value.get("profile") != profile
                or not isinstance(value["request_id"], str) or not REQUEST_ID.fullmatch(value["request_id"])
                or any(not isinstance(value[k], str) or not 0 < len(value[k]) <= limit
                       for k, limit in (("session_key", 512), ("surface", 256)))
                or any(not isinstance(value[k], str) or len(value[k]) > 8192
                       for k in ("command", "description"))
                or any(type(value[k]) is not bool for k in ("allow_session", "allow_permanent"))
                or type(value["expires_at"]) not in (int, float)
                or not math.isfinite(value["expires_at"])):
            raise HTTPError(400, "approvals_invalid")
        self.sweep()
        if value["expires_at"] <= self.clock():
            raise HTTPError(410, "approvals_expired")
        key = (profile, value["request_id"])
        if key in self.entries:
            raise HTTPError(409, "approvals_conflict")
        if sum(e["state"] in ("pending", "answered") for e in self.entries.values()) >= 1024:
            raise HTTPError(429, "approvals_rate_limited")
        value = {**value, "expires_at": min(value["expires_at"], self.clock() + 3600)}
        self.entries[key] = {"profile": profile, "request_id": value["request_id"],
                             "session_key": value["session_key"], "expires_at": value["expires_at"],
                             "state": "pending", "payload": dict(value)}
        self.emit("new", profile, value["request_id"])
        return {"request_id": value["request_id"]}

    def lookup(self, profile, request_id):
        self.sweep()
        entry = self.entries.get((profile, request_id))
        if entry is None:
            raise HTTPError(404, "approvals_not_found")
        if entry["state"] == "expired":
            raise HTTPError(410, "approvals_expired")
        return entry

    def pending(self, profile=None):
        """One profile in arrival order, or (None) every live profile by expiry."""
        self.sweep()
        live = self.profiles.directories()
        items = [dict(e["payload"]) for e in self.entries.values()
                 if e["state"] == "pending" and (e["profile"] == profile if profile else e["profile"] in live)]
        if profile is None:
            items.sort(key=lambda item: (item["expires_at"], item["request_id"]))
        return {"approvals": items}

    def rate(self, device):
        now = self.clock()
        self.rates = {k: v for k, v in self.rates.items() if v and v[-1] > now - 60}
        times = self.rates.setdefault(device, deque())
        while times and times[0] <= now - 60:
            times.popleft()
        if len(times) >= 60 or len(self.rates) > 256:
            raise HTTPError(429, "approvals_rate_limited")
        times.append(now)

    async def phone(self, link, device, method, match, body):
        if method != "POST" or not match[4]:
            self.rate(device)
            return await self.phone_request(link, device, method, match, body)
        choice, status = "invalid", 500
        try:
            self.rate(device)
            value = decode(body)
            if value.get("choice") in ("once", "session", "always", "deny"):
                choice = value["choice"]
            result = await self.phone_request(link, device, method, match, body)
            status = 200
            await response(link, 200, result)
        except HTTPError as error:
            status = error.status
            raise
        finally:
            self.audit("approval", device, match[1], match[3], choice, status)

    async def phone_all(self, link, device, method, target):
        self.rate(device)
        if method != "GET" or target not in ("/v1/approvals", "/v1/approvals/events"):
            raise HTTPError(404, "approvals_not_found")
        if target.endswith("/events"):
            await self.events(link)
        else:
            await response(link, 200, self.pending())

    async def phone_request(self, link, device, method, match, body):
        profile, events, request_id, answer = match.groups()
        if profile not in self.profiles.directories():
            raise HTTPError(404, "approvals_not_found")
        if method == "GET" and events:
            await self.events(link, profile)
            return
        if method == "GET" and not request_id and not events:
            await response(link, 200, self.pending(profile))
            return
        if method != "POST" or not answer:
            raise HTTPError(404, "approvals_not_found")
        value = decode(body)
        entry = self.lookup(profile, request_id)
        if entry["state"] != "pending":
            raise HTTPError(409, "approvals_conflict")
        choice = value.get("choice")
        payload = entry["payload"]
        choices = ["once", "deny"]
        if payload["allow_session"]:
            choices.append("session")
        if payload["allow_session"] and payload["allow_permanent"]:
            choices.append("always")
        if set(value) != {"choice"} or choice not in choices:
            raise HTTPError(400, "approvals_invalid_choice")
        entry.update(state="answered", choice=choice)
        return {"request_id": request_id, "state": "answered"}

    async def events(self, link, profile=None):
        await stream_start(link)
        previous, heartbeat = None, time.monotonic()
        while True:
            self.sweep()
            if previous is None or (self.history and previous < self.history[0][0] - 1):
                previous = self.revision
                data = json.dumps(self.pending(profile)).encode()
                await chunk(link, b"event: snapshot\ndata: " + data + b"\n\n")
                continue
            batch = [h for h in self.history if h[0] > previous]
            for revision, event, value in batch:
                if (value["profile"] == profile if profile else value["profile"] in self.profiles.directories()):
                    await chunk(link, f"id: {revision}\nevent: {event}\ndata: ".encode()
                                + json.dumps(value).encode() + b"\n\n")
                previous = revision
            if batch:
                continue
            if time.monotonic() - heartbeat >= 15:
                await chunk(link, b": keepalive\n\n")
                heartbeat = time.monotonic()
                continue
            await asyncio.sleep(0.1)

    def authenticate(self, method, path, profile, head, body):
        secret = self.credentials.get(profile)
        if secret is None:
            raise HTTPError(401, "approvals_unauthorized")
        nonce = head.get("x-approval-nonce", "")
        timestamp = head.get("x-approval-timestamp", "")
        supplied = head.get("x-approval-mac", "")
        now = self.clock()
        if (not re.fullmatch(r"[0-9a-f]{32}", nonce)
                or not re.fullmatch(r"[1-9][0-9]{0,11}", timestamp)
                or not re.fullmatch(r"[0-9a-f]{64}", supplied)
                or abs(now - int(timestamp)) > 60):
            raise HTTPError(401, "approvals_unauthorized")
        message = "\n".join(("plugin", method, path, hashlib.sha256(body).hexdigest(), nonce, timestamp))
        expected = hmac.new(secret, message.encode("ascii"), "sha256").hexdigest()
        self.nonces = {key: expiry for key, expiry in self.nonces.items() if expiry >= now}
        key = (profile, nonce)
        if (not hmac.compare_digest(supplied, expected) or key in self.nonces
                or sum(k[0] == profile for k in self.nonces) >= 2048):
            raise HTTPError(401, "approvals_unauthorized")
        self.nonces[key] = int(timestamp) + 60
        return secret, nonce

    async def signed_response(self, link, secret, nonce, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        message = "\n".join(("bridge", nonce, hashlib.sha256(body).hexdigest()))
        signature = hmac.new(secret, message.encode("ascii"), "sha256").hexdigest()
        await link.write((f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                          f"Content-Length: {len(body)}\r\nX-Approval-Mac: {signature}\r\n"
                          "X-Content-Type-Options: nosniff\r\n\r\n").encode() + body)

    def internal(self, method, match, body):
        profile, answers, request_id, resolved = match.groups()
        if method == "GET" and answers == "hello" and not body:
            return {}
        if method == "POST" and not answers and not request_id:
            return self.publish(profile, decode(body))
        if method == "GET" and answers == "answers":
            self.sweep()
            return {"answers": [{"request_id": e["request_id"], "session_key": e["session_key"],
                                  "choice": e["choice"]} for e in self.entries.values()
                                 if e["profile"] == profile and e["state"] == "answered"]}
        if method == "POST" and resolved:
            if decode(body):
                raise HTTPError(400, "approvals_invalid")
            entry = self.lookup(profile, request_id)
            if entry["state"] != "resolved":
                self.clear(entry)
            return {"request_id": request_id, "state": "resolved"}
        raise HTTPError(404, "approvals_not_found")

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        link = LocalLink(writer)
        try:
            if len(self.tasks) > 16 or writer.get_extra_info("peername")[0] != "127.0.0.1":
                raise HTTPError(403, "approvals_unauthorized")
            async with asyncio.timeout(2):
                source = Reader(reader)
                for index in range(2):
                    method, target, head, body = await request(source, maximum_body=32768)
                    match = INTERNAL_ROUTE.fullmatch(target)
                    if not match:
                        raise HTTPError(404, "approvals_not_found")
                    secret, nonce = self.authenticate(method, target, match[1], head, body)
                    value = self.internal(method, match, body)
                    await self.signed_response(link, secret, nonce, value)
                    if index or method != "GET" or match[2] != "hello":
                        break
        except HTTPError as error:
            with suppress(OSError):
                await response(link, error.status, {"error": error.message})
        except (TimeoutError, OSError, EOFError):
            pass
        finally:
            writer.close()
            with suppress(OSError):
                await writer.wait_closed()
            self.tasks.discard(task)
