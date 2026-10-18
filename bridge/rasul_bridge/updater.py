"""Hermes update from the phone: check, queue until idle or force, and report the result.

State lives in memory only. A bridge restart drops a queued update (state returns to idle).
"""

import asyncio
from collections import deque
from contextlib import suppress
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlencode

from .audit import Audit
from .http import HTTPError

QUEUE_TTL = 24 * 3600
RUN_TTL = 30 * 60
APPLIES_PER_HOUR = 2
MODES = ("when_idle", "now")


def elevated():
    """True when the bridge runs with administrator or root rights (known lockout hazard)."""
    if sys.platform == "win32":
        import ctypes
        try:
            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        except (AttributeError, OSError):
            return True
    return os.geteuid() == 0


def process_elevated(pid):
    """True/False for the rights of another process; None when it cannot be determined."""
    try:
        if sys.platform == "win32":
            import ctypes
            from ctypes import wintypes
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            advapi = ctypes.WinDLL("advapi32", use_last_error=True)
            kernel.OpenProcess.restype = wintypes.HANDLE
            kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
            kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
            advapi.OpenProcessToken.argtypes = (wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE))
            advapi.GetTokenInformation.argtypes = (wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD,
                                                   ctypes.POINTER(wintypes.DWORD))
            process = kernel.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
            if not process:
                return None
            try:
                token = wintypes.HANDLE()
                if not advapi.OpenProcessToken(process, 0x8, ctypes.byref(token)):  # TOKEN_QUERY
                    return None
                try:
                    flag, size = wintypes.DWORD(), wintypes.DWORD()
                    if not advapi.GetTokenInformation(token, 20, ctypes.byref(flag), 4, ctypes.byref(size)):
                        return None  # 20 = TokenElevation
                    return bool(flag.value)
                finally:
                    kernel.CloseHandle(token)
            finally:
                kernel.CloseHandle(process)
        for line in Path("/proc/%d/status" % pid).read_text().splitlines():
            if line.startswith("Uid:"):
                return int(line.split()[2]) == 0  # effective uid
    except (OSError, ValueError, IndexError, AttributeError):
        pass
    return None


def clean(value, limit):
    """Plain single-line text: no control characters, bounded length."""
    if not isinstance(value, str):
        return ""
    return re.sub(r"[\x00-\x1f\x7f\s]+", " ", value).strip()[:limit]


def map_check(raw):
    if not isinstance(raw, dict):
        raise ValueError
    behind = raw.get("behind")
    known = type(behind) is int
    # Hermes reports -1 for "update available, count unknown" (hermes_cli/source_check.py:23)
    behind = None if known and behind < 0 else (behind if known and behind > 0 else 0)
    commits = raw.get("commits")
    commits = commits if isinstance(commits, list) else []
    changes = []
    for commit in commits[:20]:
        title = clean(commit.get("summary"), 200) if isinstance(commit, dict) else ""
        if title:
            changes.append({"title": title})
    latest = None
    if commits and isinstance(commits[0], dict) and isinstance(commits[0].get("sha"), str) \
            and re.fullmatch(r"[0-9a-f]{7,40}", commits[0]["sha"]):
        latest = commits[0]["sha"]
    applicable = raw.get("can_apply") is True and "rollback_to" not in raw
    if raw.get("update_available") is True and applicable:
        reason = None
    elif not known and raw.get("behind") is None and raw.get("install_method") == "git":
        reason = "check_failed"
    elif known and raw["behind"] == 0:
        reason = "up_to_date"
    else:
        reason = "not_applicable"
    return {"current": clean(raw.get("current_version"), 64) or "unknown", "latest": latest,
            "behind": behind, "can_apply": reason is None, "reason": reason, "changes": changes}


class Updater:
    def __init__(self, desktop, poll=15):
        self.desktop, self.poll = desktop, poll
        self.state, self.result, self.queued_at = "idle", None, None
        self.check = {"current": "unknown", "latest": None, "behind": 0, "can_apply": False,
                      "reason": "dashboard_not_running", "changes": []}
        self.applies = deque()
        self.lock = asyncio.Lock()
        self.task = None
        self.active = None
        self.sleep = asyncio.sleep
        self.dash_pid = None

    # -- plumbing -----------------------------------------------------------------

    async def dashboard(self, profile):
        upstream, token = await self.desktop.discover(profile)
        self.dash_pid = upstream.record["pid"]

        async def request(verb, path, data=None, **parameters):
            return await upstream.collect_json(
                verb, path + "?" + urlencode({"profile": profile, **parameters}), token, data)
        return request

    def gateway_key(self):
        """Key of the default profile, else of any profile that has one; None when no profile has one."""
        keys = self.desktop.profiles.store.read("profile-keys.json", {})
        for name in ["default", *sorted(k for k in keys if k != "default")]:
            if isinstance(keys.get(name), str) and keys[name]:
                return keys[name]
        return None

    async def active_agents(self):
        """Total of every busy signal /health/detailed reports; None (never idle) when unknown.

        Hermes gateway/platforms/api_server.py:2465 active_agents and :2466 gateway_busy, plus
        readiness.checks.background_queues active_api_runs and active_delegations (gateway/readiness.py:117-123).
        """
        try:
            key = self.gateway_key()
            if not key:
                return None
            async with asyncio.timeout(5):
                status, data = await self.desktop.profiles.upstream.collect("/health/detailed", key)
            if status != 200:
                return None
            value = json.loads(data)
            count = value.get("active_agents")
            if type(count) is not int or count < 0:
                return None
            queues = ((value.get("readiness") or {}).get("checks") or {}).get("background_queues") or {}
            for field in ("active_api_runs", "active_delegations"):
                extra = queues.get(field, 0)
                if type(extra) is not int or extra < 0:
                    return None
                count += extra
            if value.get("gateway_busy") is True:
                count = max(count, 1)
            return count
        except Exception:
            return None

    def audit(self, event):
        with suppress(Exception):
            Audit(self.desktop.profiles.store).write(
                {"time": datetime.now(timezone.utc).isoformat(), "event": "hermes_update", **event})

    def now_iso(self):
        return datetime.now(timezone.utc).isoformat()

    def payload(self):
        return {**self.check, "state": self.state, "active_agents": self.active,
                "queued_at": self.queued_at, "result": self.result}

    async def refresh(self, profile):
        """Best-effort check; a missing dashboard is reported, never raised."""
        try:
            async with asyncio.timeout(10):
                request = await self.dashboard(profile)
                self.check = map_check(await request("GET", "/api/hermes/update/check"))
            return True
        except (HTTPError, OSError, TimeoutError, ValueError, KeyError, EOFError, TypeError, AttributeError):
            self.check = {**self.check, "can_apply": False, "reason": "dashboard_not_running"}
            return False

    def dashboard_rights(self):
        """Reason to refuse when the process that runs the update (the dashboard) is elevated or unknown."""
        state = process_elevated(self.dash_pid) if self.dash_pid else None
        if state is None:
            return "cannot_verify_dashboard_rights"
        return "dashboard_elevated" if state else None

    def finish(self, ok, message, before, after):
        self.state = "done" if ok else "failed"
        self.queued_at = None
        self.result = {"ok": ok, "from": before, "to": after, "message": clean(message, 200)}

    # -- routes -------------------------------------------------------------------

    async def handle(self, profile, name, value):
        if name == "update_get":
            return 200, await self.get(profile)
        if name == "update_delete":
            if self.state == "running":
                raise HTTPError(409, "update_running")
            if self.state != "queued":
                raise HTTPError(409, "nothing_queued")
            self.task.cancel()
            self.state, self.queued_at = "idle", None
            self.result = {"ok": False, "from": self.check["current"], "to": self.check["current"],
                           "message": "Cancelled."}
            return 200, self.payload()
        return await self.post(profile, value["mode"])

    async def get(self, profile):
        if self.state != "running":
            await self.refresh(profile)
            self.active = await self.active_agents()
        if self.state in ("queued", "running"):
            self.check = {**self.check, "can_apply": False, "reason": "update_in_progress"}
        elif elevated():
            self.check = {**self.check, "can_apply": False, "reason": "bridge_elevated"}
        elif self.check["can_apply"] and (reason := self.dashboard_rights()):
            self.check = {**self.check, "can_apply": False, "reason": reason}
        return self.payload()

    async def post(self, profile, mode):
        async with self.lock:
            if self.state in ("queued", "running"):
                raise HTTPError(409, "update_in_progress")
            if elevated():
                raise HTTPError(403, "bridge_elevated")
            if not await self.refresh(profile):
                raise HTTPError(403, "dashboard_not_running")
            if self.check["can_apply"] and (reason := self.dashboard_rights()):
                self.check = {**self.check, "can_apply": False, "reason": reason}
            if not self.check["can_apply"]:
                raise HTTPError(403 if self.check["reason"] != "up_to_date" else 409, self.check["reason"])
            now = self.desktop.clock()
            while self.applies and self.applies[0] <= now - 3600:
                self.applies.popleft()
            if len(self.applies) >= APPLIES_PER_HOUR:
                raise HTTPError(429, "update_rate_limited")
            self.applies.append(now)
            self.result = None
            if mode == "now":
                self.state = "running"
                await self.begin(profile)
                if self.state == "failed":
                    raise HTTPError(502, "update_not_started")
            else:
                self.state, self.queued_at = "queued", self.now_iso()
                self.task = asyncio.create_task(self.guard(self.wait_idle(profile)))
            return 202, self.payload()

    # -- background ---------------------------------------------------------------

    async def begin(self, profile):
        """Ask the dashboard to start the update, then watch it. Caller already set state=running."""
        before = self.check["current"]
        try:
            async with asyncio.timeout(10):
                request = await self.dashboard(profile)
                if self.dashboard_rights():
                    raise ValueError
                receipt = await request("POST", "/api/hermes/update")
            if not isinstance(receipt, dict) or receipt.get("ok") is not True:
                raise ValueError
            action = receipt.get("action_id")
            action = action if isinstance(action, str) and re.fullmatch(r"[0-9a-f]{32}", action) else None
        except Exception:
            self.finish(False, "The update could not be started.", before, before)
            return
        self.task = asyncio.create_task(self.guard(self.monitor(profile, action, before)))

    async def guard(self, work):
        """Run a background task; any failure ends the state in failed instead of leaving it stuck."""
        try:
            await work
        except Exception:
            current = self.check["current"]
            self.audit({"outcome": "task_error"})
            self.finish(False, "The update stopped unexpectedly.", current, current)

    async def wait_idle(self, profile):
        deadline = self.desktop.clock() + QUEUE_TTL
        idle = 0
        try:
            while True:
                if self.desktop.clock() >= deadline:
                    self.state, self.queued_at = "idle", None
                    self.result = {"ok": False, "from": self.check["current"], "to": self.check["current"],
                                   "message": "The queued update expired."}
                    return
                self.active = await self.active_agents()
                idle = idle + 1 if self.active == 0 else 0
                if idle >= 2:
                    self.state, self.queued_at = "running", None
                    await self.begin(profile)
                    return
                await self.sleep(self.poll)
        except asyncio.CancelledError:
            raise

    async def monitor(self, profile, action, before):
        deadline = self.desktop.clock() + RUN_TTL
        while self.desktop.clock() < deadline:
            await self.sleep(self.poll)
            try:  # the dashboard restarts during an update; unreachable means keep waiting
                async with asyncio.timeout(10):
                    request = await self.dashboard(profile)
                    status = await request("GET", "/api/actions/hermes-update/status", lines=1)
                    summary = status.get("receipt") if isinstance(status, dict) else None
                    if isinstance(summary, dict) and action is not None and summary.get("action_id") == action:
                        if summary.get("outcome") in ("success", "partial", "failed", "refused"):
                            await self.complete(request, summary, before)
                            return
                    if isinstance(status, dict) and status.get("running") is False \
                            and type(status.get("exit_code")) is int:
                        ok = status["exit_code"] == 0
                        self.finish(ok, "Update completed." if ok else "Update did not complete.", before,
                                    await self.reread(request, before))
                        return
            except (HTTPError, OSError, TimeoutError, ValueError, KeyError, EOFError, TypeError, AttributeError):
                continue
        self.finish(False, "The update did not report a result in time.", before, before)

    async def reread(self, request, before):
        with suppress(Exception):
            return map_check(await request("GET", "/api/hermes/update/check"))["current"]
        return before

    async def complete(self, request, summary, before):
        outcome = summary.get("outcome")
        after = clean(summary.get("post_version"), 64) or await self.reread(request, before)
        pre = summary.get("pre_sha")
        self.audit({"outcome": clean(outcome, 16),
                    "pre_sha": pre if isinstance(pre, str) and re.fullmatch(r"[0-9a-f]{7,40}", pre) else None})
        if outcome == "success":
            message = "Update completed."
            steps = [clean(f.get("step"), 40) for f in summary.get("followups") or [] if isinstance(f, dict)]
            steps = [s for s in steps if s]
            if steps:
                message = "Update completed; follow-up needed: " + ", ".join(steps[:3])
        elif outcome == "partial":
            message = "Update completed with some steps skipped."
        else:
            message = "Update did not complete."
        self.finish(outcome in ("success", "partial"), message, before, after)
