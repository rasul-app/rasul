"""Add a phone approval surface without replacing the local prompt."""

import hashlib
import hmac
import http.client
import json
import logging
from pathlib import Path
from queue import Empty, Full, Queue
import re
import secrets
import threading
import time


class Mirror:
    def __init__(self, profile, secret_path, port=8788):
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", profile):
            raise ValueError("Invalid profile")
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("Invalid local port")
        self.profile, self.port = profile, port
        self.secret = Path(secret_path).expanduser().read_text(encoding="ascii").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", self.secret):
            raise ValueError("Invalid local credential")
        self.base = "/internal/profiles/" + profile + "/approvals"
        self.queue = Queue(maxsize=128)
        self.tracked = {}
        self.withdrawals = set()
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.thread = None
        self.warned = False

    def warn(self):
        with self.lock:
            if self.warned:
                return
            self.warned = True
        logging.getLogger(__name__).warning("Approval bridge unavailable; local prompts continue")

    def exchange(self, connection, method, suffix, body):
        path = self.base + suffix
        nonce, timestamp = secrets.token_hex(16), str(int(time.time()))
        message = "\n".join(("plugin", method, path, hashlib.sha256(body).hexdigest(), nonce, timestamp))
        signature = hmac.new(self.secret.encode("ascii"), message.encode("ascii"), "sha256").hexdigest()
        connection.request(method, path, body,
                           {"X-Approval-Nonce": nonce, "X-Approval-Timestamp": timestamp,
                            "X-Approval-Mac": signature, "Content-Type": "application/json"})
        result = connection.getresponse()
        data = result.read(4194305)
        supplied = result.getheader("X-Approval-Mac", "")
        message = "\n".join(("bridge", nonce, hashlib.sha256(data).hexdigest()))
        expected = hmac.new(self.secret.encode("ascii"), message.encode("ascii"), "sha256").hexdigest()
        if (len(data) > 4194304 or not re.fullmatch(r"[0-9a-f]{64}", supplied)
                or not hmac.compare_digest(supplied, expected) or result.status != 200):
            raise OSError("Local approval request failed")
        return data

    def request(self, method, suffix="", value=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=0.5)
        try:
            self.exchange(connection, "GET", "/hello", b"")
            # Keep proof and publication on the same established connection.
            if connection.sock is None:
                raise OSError("Local approval request failed")
            body = b"" if value is None else json.dumps(value, ensure_ascii=False).encode("utf-8")
            return json.loads(self.exchange(connection, method, suffix, body))
        finally:
            connection.close()

    def on_request(self, command="", description="", session_key="", surface="", **kwargs):
        try:
            if (self.stop.is_set() or kwargs.get("coalesced") or surface in ("smart", "cli")
                    or surface.startswith("transport:")):
                return
            from agent.redact import redact_sensitive_text
            from tools.approval import list_gateway_approvals
            from tools.approval_context import _get_approval_timeout
            timeout = min(3600, max(0, _get_approval_timeout()))
            if timeout == 0:
                return
            deadline = time.time() + timeout

            def display(text):
                redacted = redact_sensitive_text(text, force=True, redact_url_credentials=True)
                return redacted if len(redacted) <= 2048 else redacted[:2037] + "[truncated]"

            for entry in list_gateway_approvals(session_key):
                request_id = entry.get("request_id", "")
                if not re.fullmatch(r"[0-9a-f]{32}", request_id):
                    continue
                payload = {
                    "request_id": request_id, "session_key": session_key, "profile": self.profile,
                    "surface": surface,
                    "command": display(entry.get("command", "")),
                    "description": display(entry.get("description", "")),
                    "allow_session": not entry.get("smart_denied") and entry.get("allow_session") is True,
                    "allow_permanent": not entry.get("smart_denied") and entry.get("allow_permanent") is True,
                    "expires_at": deadline,
                }
                with self.lock:
                    if request_id in self.tracked or len(self.tracked) >= 128:
                        continue
                    self.tracked[request_id] = (session_key, deadline)
                    try:
                        self.queue.put_nowait(payload)
                    except Full:
                        self.tracked.pop(request_id, None)
        except Exception:
            self.warn()

    def pending(self, session_key, request_id):
        from tools.approval import list_gateway_approvals
        return any(e.get("request_id") == request_id for e in list_gateway_approvals(session_key))

    def forget(self, request_id):
        with self.lock:
            self.tracked.pop(request_id, None)
            self.withdrawals.discard(request_id)

    def tick(self):
        while not self.stop.is_set():
            try:
                payload = self.queue.get_nowait()
            except Empty:
                break
            request_id, session_key = payload["request_id"], payload["session_key"]
            if not self.pending(session_key, request_id):
                self.forget(request_id)
                continue
            try:
                self.request("POST", value=payload)
            except Exception:
                with self.lock:
                    self.withdrawals.add(request_id)
                self.warn()
        with self.lock:
            tracked = dict(self.tracked)
            withdrawals = set(self.withdrawals)
        if not tracked or self.stop.is_set():
            return
        poll_error = None
        try:
            answers = self.request("GET", "/answers")["answers"] if tracked.keys() - withdrawals else []
        except Exception as error:
            poll_error, answers = error, []
        from tools.approval import resolve_gateway_approval
        for answer in answers:
            if self.stop.is_set():
                return
            request_id = answer.get("request_id")
            local = tracked.get(request_id)
            if not local or request_id in withdrawals or answer.get("session_key") != local[0]:
                continue
            if (time.time() < local[1] and answer.get("choice") in ("once", "session", "always", "deny")
                    and self.pending(local[0], request_id)):
                resolve_gateway_approval(local[0], answer["choice"], request_id=request_id)
        for request_id, (session_key, deadline) in tracked.items():
            if self.stop.is_set():
                return
            if request_id in withdrawals or not self.pending(session_key, request_id) or time.time() >= deadline:
                try:
                    self.request("POST", "/" + request_id + "/resolved", {})
                except Exception:
                    if time.time() < deadline:
                        self.warn()
                        continue
                self.forget(request_id)
        if poll_error is not None:
            raise poll_error

    def run(self):
        while not self.stop.is_set():
            try:
                self.tick()
            except Exception:
                self.warn()
            self.stop.wait(0.2)

    def start(self):
        self.thread = threading.Thread(target=self.run, name="approval-mirror", daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        if self.thread is not None:
            self.thread.join(timeout=2)


def register(ctx):
    try:
        path = ctx.get_config("secret_path")
        if not path:
            return
        mirror = Mirror(ctx.profile_name, path, ctx.get_config("port", 8788))
        ctx.on_unload(mirror.close)
        ctx.register_hook("pre_approval_request", mirror.on_request)
        mirror.start()
        return mirror
    except Exception:
        logging.getLogger(__name__).warning("Approval bridge configuration unavailable; local prompts continue")
