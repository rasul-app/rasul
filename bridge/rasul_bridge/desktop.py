"""Bounded local desktop access with explicit profile and response projection."""

import asyncio
import ctypes
from ctypes import wintypes
from collections import deque
from contextlib import suppress
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import socket
import struct
import sys
import time
from urllib.parse import urlencode, urlsplit

from .channels import BODY_LIMIT, CHANNEL, PAIRING, USER_ID, Refused, handle as channel_handle, valid_body
from .http import HTTPError, Reader, headers
from . import logs
from .proxy import Upstream, response_body
from .updater import Updater, clean

CAP = 1024 * 1024
TIMEOUT = 10
TABLE_CAP = 16 * 1024 * 1024
OWNERSHIP_WAIT = 2
SERVER = r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"
BODIES = {"toggle": {"name", "enabled"}, "install": {"identifier"}, "update_post": {"mode"},
          "mcp_enabled": {"enabled"}, "mcp_install": {"id"}, "chan_enabled": {"enabled"},
          "chan_restart": {"force"}, "chan_apply": {"allowed_user_ids", "force"}}
OPTIONAL = {"chan_setup": {"bot_name"}}
CHECKED = ("chan_wa_setup", "chan_wa_apply", "chan_settings")
RULES = [
    ("GET", r"/jobs/([A-Za-z0-9_-]{1,200})/runs", "runs", 10),
    ("GET", r"/skills", "skills", 30),
    ("PUT", r"/skills/toggle", "toggle", 6),
    ("GET", r"/skills/catalog", "catalog", 6),
    ("POST", r"/skills/install", "install", 1),
    ("GET", r"/mcp", "mcp", 12),
    ("GET", r"/mcp/catalog", "mcp_catalog", 6),
    ("POST", r"/mcp/catalog/install", "mcp_install", 1),
    ("PUT", rf"/mcp/({SERVER})/enabled", "mcp_enabled", 6),
    ("POST", rf"/mcp/({SERVER})/test", "mcp_test", 3),
    ("GET", r"/hermes/update", "update_get", 12),
    ("POST", r"/hermes/update", "update_post", 6),
    ("DELETE", r"/hermes/update", "update_delete", 6),
    ("GET", r"/usage", "usage", 6),
    ("GET", r"/channels", "chan_list", 12),
    ("PUT", rf"/channels/({CHANNEL})/enabled", "chan_enabled", 6),
    ("POST", rf"/channels/({CHANNEL})/test", "chan_test", 3),
    ("POST", r"/channels/restart", "chan_restart", 2),
    ("POST", r"/channels/telegram/setup", "chan_setup", 3),
    ("GET", rf"/channels/telegram/setup/({PAIRING})", "chan_status", 30),
    ("POST", rf"/channels/telegram/setup/({PAIRING})/apply", "chan_apply", 3),
    ("DELETE", rf"/channels/telegram/setup/({PAIRING})", "chan_cancel", 6),
    ("POST", r"/channels/whatsapp/setup", "chan_wa_setup", 3),
    ("GET", rf"/channels/whatsapp/setup/({PAIRING})", "chan_wa_status", 30),
    ("POST", rf"/channels/whatsapp/setup/({PAIRING})/apply", "chan_wa_apply", 3),
    ("DELETE", rf"/channels/whatsapp/setup/({PAIRING})", "chan_wa_cancel", 6),
    ("PUT", rf"/channels/({CHANNEL})/settings", "chan_settings", 6),
    ("GET", r"/logs", "logs", 10),
]
USAGE = {"input_tokens": "total_input", "output_tokens": "total_output",
         "cache_read_tokens": "total_cache_read", "reasoning_tokens": "total_reasoning",
         "estimated_cost": "total_estimated_cost", "sessions": "total_sessions", "api_calls": "total_api_calls"}


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError
        result[key] = value
    return result


def decode(data):
    try:
        return json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except RecursionError:
        raise ValueError from None


def project(row, schema):
    if not isinstance(row, dict):
        raise ValueError
    result = {}
    for key, types in schema.items():
        value = row[key]
        if type(value) not in types or (type(value) is float and not math.isfinite(value)):
            raise ValueError
        result[key] = value
    return result


def rows(value):
    if not isinstance(value, list) or any(not isinstance(row, dict) for row in value):
        raise ValueError
    return value


TEXT = (str,)
BOOL = (bool,)
NUMBER = (int, float, type(None))


def process_times(pid):
    """Read live process fingerprints without contacting its network service."""
    if sys.platform == "win32":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            raise OSError
        try:
            code = wintypes.DWORD()
            times = [wintypes.FILETIME() for _ in range(4)]
            if (not kernel.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value != 259
                    or not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times))):
                raise OSError
            ticks = (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime
            created = ticks / 10000000 - 11644473600
            return round(created * 100), created
        finally:
            kernel.CloseHandle(handle)
    if sys.platform == "linux":
        os.kill(pid, 0)
        fields = (Path("/proc") / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()
        if fields[0] in ("Z", "X"):
            raise ValueError
        start = int(fields[19])
        boot = next(int(line.split()[1]) for line in Path("/proc/stat").read_text().splitlines()
                    if line.startswith("btime "))
        return start, boot + start / os.sysconf("SC_CLK_TCK")
    raise ValueError


def tcp_table(kind):
    """Read a bounded IPv4 owner table, allowing concurrent table growth."""
    table = ctypes.WinDLL("iphlpapi", use_last_error=True).GetExtendedTcpTable
    table.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD), wintypes.BOOL,
                      wintypes.ULONG, ctypes.c_int, wintypes.ULONG]
    table.restype = wintypes.DWORD
    size = wintypes.DWORD()
    if table(None, ctypes.byref(size), False, socket.AF_INET, kind, 0) != 122:
        raise OSError
    for _ in range(3):
        if not 4 <= size.value <= TABLE_CAP - 4096:
            raise ValueError
        data = ctypes.create_string_buffer(size.value + 4096)
        size.value = len(data)
        result = table(data, ctypes.byref(size), False, socket.AF_INET, kind, 0)
        if result == 122:
            continue
        if result != 0:
            raise OSError
        if not 4 <= size.value <= len(data):
            raise ValueError
        count = struct.unpack_from("=I", data)[0]
        if 4 + count * 24 > size.value:
            raise ValueError
        return [struct.unpack_from("=6I", data, offset)
                for offset in range(4, 4 + count * 24, 24)]
    raise OSError


def start_matches(recorded, current):
    return abs(recorded - current) <= (1 if sys.platform == "win32" else 0)


def listener_owned(pid, host, port):
    """Require every matching IPv4 listener to belong to the recorded process."""
    address = socket.inet_aton(host)
    if sys.platform == "win32":
        owners = []
        for state, local, local_port, _, _, owner in tcp_table(3):
            if (state == 2 and socket.ntohs(local_port & 65535) == port
                    and local in (0, int.from_bytes(address, sys.byteorder))):
                owners.append(owner)
        return bool(owners) and all(owner == pid for owner in owners)
    if sys.platform == "linux":
        inodes = set()
        for line in Path("/proc/net/tcp").read_text().splitlines()[1:]:
            fields = line.split()
            local, local_port = fields[1].split(":")
            if (fields[3] == "0A" and int(local_port, 16) == port
                    and int(local, 16) in (0, int.from_bytes(address, "little"))):
                inodes.add(fields[9])
        owned = set()
        for fd in (Path("/proc") / str(pid) / "fd").iterdir():
            try:
                target = os.readlink(fd)
            except FileNotFoundError:
                continue
            match = re.fullmatch(r"socket:\[([0-9]+)\]", target)
            if match:
                owned.add(match[1])
        return bool(inodes) and inodes <= owned
    raise ValueError


def contains_credential(value, token):
    if isinstance(value, str):
        return token in value
    if isinstance(value, dict):
        return any(contains_credential(item, token) for item in value.values())
    if isinstance(value, list):
        return any(contains_credential(item, token) for item in value)
    return False


def connection_owned(pid, host, port, client):
    """Return True for owned, None for pending, and False for mismatched or absent."""
    address = socket.inet_aton(host)
    if len(client) != 2 or client[0] != host:
        return False
    if sys.platform == "win32":
        owners, pending = [], False
        for state, local, local_port, remote, remote_port, owner in tcp_table(5):
            if (local == remote == int.from_bytes(address, sys.byteorder)
                    and socket.ntohs(local_port & 65535) == port
                    and socket.ntohs(remote_port & 65535) == client[1]):
                if (state in (4, 5) and owner == 0) or (state == 4 and owner == pid):
                    pending = True
                elif state == 5:
                    owners.append(owner)
                else:
                    return False
        if owners and any(owner != pid for owner in owners):
            return False
        return None if pending else bool(owners)
    if sys.platform == "linux":
        inodes, pending = set(), False
        expected = int.from_bytes(address, "little")
        for line in Path("/proc/net/tcp").read_text().splitlines()[1:]:
            fields = line.split()
            local, local_port = fields[1].split(":")
            remote, remote_port = fields[2].split(":")
            if (int(local, 16) == int(remote, 16) == expected
                    and int(local_port, 16) == port and int(remote_port, 16) == client[1]):
                if fields[3] == "03" or fields[3] == "01" and fields[9] == "0":
                    pending = True
                elif fields[3] == "01":
                    inodes.add(fields[9])
                else:
                    return False
        owned = set()
        for fd in (Path("/proc") / str(pid) / "fd").iterdir():
            try:
                target = os.readlink(fd)
            except FileNotFoundError:
                continue
            match = re.fullmatch(r"socket:\[([0-9]+)\]", target)
            if match:
                owned.add(match[1])
        if inodes - owned:
            return False
        return None if pending else bool(inodes)
    raise ValueError


class LocalDesktop(Upstream):
    def verify(self):
        try:
            record = self.record
            home = record["home"]
            if (type(home) is not str or (home and (not Path(home).is_absolute()
                    or os.path.normcase(os.path.realpath(home)) != os.path.normcase(os.path.realpath(self.home))))):
                raise ValueError
            profiles = record["profiles"]
            if (type(profiles) is not list or any(type(profile) is not str for profile in profiles)
                    or self.profile not in profiles):
                raise ValueError
            start, created = process_times(record["pid"])
            expected = record.get("startTime")
            if expected is not None:
                if type(expected) is not int or expected <= 0 or not start_matches(expected, start):
                    raise ValueError
            else:
                expected = record["createTime"]
                if (type(expected) not in (int, float) or not math.isfinite(expected)
                        or expected <= 0 or abs(expected - created) > 0.001):
                    raise ValueError
            if not listener_owned(record["pid"], record["host"], record["port"]):
                raise ValueError
            if not start_matches(start, process_times(record["pid"])[0]):
                raise ValueError
        except (OSError, ValueError, KeyError, TypeError, IndexError, StopIteration):
            raise HTTPError(503, "desktop_unavailable") from None

    async def connect(self):
        self.verify()
        reader, writer = await super().connect()
        try:
            # No request bytes may leave until the connected server socket is owned.
            start = process_times(self.record["pid"])[0]
            deadline = time.monotonic() + OWNERSHIP_WAIT
            while True:
                owned = connection_owned(self.record["pid"], self.host, self.port,
                                         writer.get_extra_info("sockname"))
                if owned is True:
                    break
                if owned is False or time.monotonic() >= deadline:
                    raise ValueError
                await asyncio.sleep(min(0.02, max(0, deadline - time.monotonic())))
            self.verify()
            if not start_matches(start, process_times(self.record["pid"])[0]):
                raise ValueError
            return reader, writer
        except BaseException as error:
            writer.close()
            with suppress(OSError):
                await writer.wait_closed()
            if isinstance(error, (OSError, ValueError, KeyError, TypeError, IndexError, StopIteration)):
                raise HTTPError(503, "desktop_unavailable") from None
            raise

    async def authenticate(self, token, role, directory):
        reader, writer = await self.connect()
        wrapped = Reader(reader)
        async def proof(path, close=False):
            self.verify()
            lines = [f"GET {path} HTTP/1.1", f"Host: {self.host}:{self.port}",
                     f"Authorization: Bearer {token}",
                     "Connection: " + ("close" if close else "keep-alive"), "Content-Length: 0"]
            writer.write(("\r\n".join(lines) + "\r\n\r\n").encode("ascii"))
            await writer.drain()
            raw = (await wrapped.until(b"\r\n\r\n")).split(b"\r\n")
            if not re.fullmatch(rb"HTTP/1\.1 2[0-9]{2}(?: [^\r\n]*)?", raw[0]):
                raise ValueError
            head = headers(raw[1:])
            if not close and (head.get("connection", "").lower() == "close"
                              or not ({"content-length", "transfer-encoding"} & head.keys())):
                raise ValueError
            result = bytearray()
            async for part in response_body(wrapped, head):
                result.extend(part)
                if len(result) > CAP:
                    raise ValueError
            return decode(result)
        try:
            identity = await proof("/api/host/identity")
            if (not isinstance(identity, dict) or identity.get("ok") is not True
                    or type(identity.get("pid")) is not int or identity["pid"] != self.record["pid"]
                    or identity.get("role") != role or type(identity.get("protocolVersion")) is not int
                    or identity["protocolVersion"] != 1):
                raise ValueError
            status = await proof("/api/status?" + urlencode({"profile": self.profile}), close=True)
            home = status.get("hermes_home") if isinstance(status, dict) else None
            if (type(home) is not str or not Path(home).is_absolute()
                    or os.path.normcase(os.path.realpath(home)) != os.path.normcase(os.path.realpath(directory))):
                raise ValueError
            self.verify()
        finally:
            writer.close()
            with suppress(OSError):
                await writer.wait_closed()

    async def collect_json(self, method, path, token, body=None, soft=()):
        self.verify()
        data = json.dumps(body).encode() if body is not None else b""
        status, head, reader, writer = await self.open(
            method, path, token, {"content-type": "application/json"}, data)
        try:
            if not 200 <= status < 300 and status not in soft:
                raise HTTPError(502, "desktop_error")
            result = bytearray()
            async for part in response_body(reader, head):
                result.extend(part)
                if len(result) > CAP:
                    raise ValueError
            if status in soft:
                try:
                    refusal = decode(result)
                except ValueError:
                    refusal = None
                raise Refused(status, refusal)
            return decode(result)
        finally:
            writer.close()
            await writer.wait_closed()


class Desktop:
    def __init__(self, profiles, clock=time.monotonic):
        self.profiles, self.clock = profiles, clock
        self.requests = {}
        self.catalogs = {}
        self.updater = Updater(self)

    @staticmethod
    def directory():
        override = os.environ.get("HERMES_GATEWAY_LOCK_DIR")
        if override:
            return Path(override)
        state = os.environ.get("XDG_STATE_HOME", "")
        root = Path(state) if os.path.isabs(state) else Path.home() / ".local" / "state"
        return root / "hermes" / "gateway-locks"

    @staticmethod
    def read_private(path):
        if path.is_symlink() or path.parent.is_symlink():
            raise ValueError
        info, parent = path.stat(), path.parent.stat()
        if info.st_size > 65536:
            raise ValueError
        if os.name != "nt":
            if (info.st_uid != os.getuid() or parent.st_uid != os.getuid()
                    or parent.st_mode & 0o022 or info.st_mode & 0o022):
                raise ValueError
            if path.suffix == ".token" and info.st_mode & 0o077:
                raise ValueError
        return path.read_bytes()

    async def discover(self, profile):
        directory = self.directory()
        for role in ("serve", "-".join(("desktop", "serve"))):
            try:
                record = decode(self.read_private(directory / ("host-" + role + ".json")))
                token = self.read_private(directory / ("host-" + role + ".token")).decode("utf-8-sig").strip()
                if (record["role"] != role or type(record["pid"]) is not int or record["pid"] <= 0
                        or type(record["protocolVersion"]) is not int or record["protocolVersion"] != 1
                        or record["host"] != "127.0.0.1" or type(record["port"]) is not int
                        or not 1 <= record["port"] <= 65535 or not re.fullmatch(r"[!-~]{16,512}", token)
                        or not hmac.compare_digest(hashlib.sha256(token.encode()).hexdigest()[:16],
                                                   record["tokenFingerprint"])):
                    continue
                upstream = LocalDesktop("http://127.0.0.1:" + str(record["port"]))
                upstream.record, upstream.home = record, self.profiles.config.hermes_home
                upstream.profile = profile
                await upstream.authenticate(token, role, self.profiles.directories()[profile])
                return upstream, token
            except (OSError, ValueError, TypeError, KeyError, HTTPError, EOFError):
                continue
        raise HTTPError(503, "desktop_unavailable")

    def charge(self, device, profile, name, cap):
        now = self.clock()
        self.requests = {key: deque(t for t in times if t > now - 60)
                         for key, times in self.requests.items() if times and times[-1] > now - 60}
        key = (device, profile, name)
        if key not in self.requests and sum(key[0] == device for key in self.requests) >= 256:
            raise HTTPError(429, "desktop_rate_limited")
        times = self.requests.setdefault(key, deque())
        if len(times) >= cap:
            raise HTTPError(429, "desktop_rate_limited")
        times.append(now)

    async def call(self, device, profile, method, suffix, body):
        parsed = urlsplit(suffix)
        if (parsed.scheme or parsed.netloc or parsed.fragment or "#" in suffix or "%" in parsed.path
                or "\\" in suffix or suffix.endswith("?")):
            raise HTTPError(400, "desktop_invalid")
        selected = next(((re.fullmatch(pattern, parsed.path), name, cap)
                         for verb, pattern, name, cap in RULES
                         if method == verb and re.fullmatch(pattern, parsed.path)), None)
        if selected is None:
            raise HTTPError(404, "Route not found")
        match, name, cap = selected
        query = {}
        if name == "logs":
            query = logs.parse(parsed.query)
        elif parsed.query:
            query_key, maximum = ("limit", 100) if name == "runs" else ("days", 365)
            if name not in ("runs", "usage") or not re.fullmatch(query_key + r"=[1-9][0-9]{0,2}", parsed.query):
                raise HTTPError(400, "desktop_invalid")
            number = int(parsed.query.split("=")[1])
            if number > maximum:
                raise HTTPError(400, "desktop_invalid")
            query[query_key] = number
        value = None
        try:
            if name in CHECKED:
                if len(body) > BODY_LIMIT.get(name, 1024):
                    raise ValueError
                value = decode(body) if body else {}
                if not valid_body(name, value):
                    raise ValueError
            elif name in BODIES or name in OPTIONAL:
                if len(body) > 1024:
                    raise ValueError
                value = decode(body) if body or name in BODIES else {}
                if not isinstance(value, dict) or (set(value) != BODIES[name] if name in BODIES
                                                   else not set(value) <= OPTIONAL[name]):
                    raise ValueError
                for key, item in value.items():
                    if key == "enabled":
                        valid = type(item) is bool
                    elif key == "mode":
                        valid = type(item) is str and item in ("when_idle", "now")
                    elif key == "id":
                        valid = type(item) is str and re.fullmatch(SERVER, item) is not None
                    elif key == "force":
                        valid = type(item) is bool
                    elif key == "bot_name":
                        valid = type(item) is str and len(item) <= 64 and not re.search(r"[\x00-\x1f\x7f]", item)
                    elif key == "allowed_user_ids":
                        valid = (type(item) is list and 1 <= len(item) <= 20
                                 and all(type(i) is str and re.fullmatch(USER_ID, i) for i in item))
                    else:
                        valid = type(item) is str and 0 < len(item) <= 200
                    if not valid:
                        raise ValueError
            elif body:
                raise ValueError
        except (UnicodeError, ValueError, TypeError):
            raise HTTPError(400, "desktop_invalid") from None
        if profile == "current" or profile not in self.profiles.directories():
            raise HTTPError(404, "Profile not found")
        self.charge(device, profile, name, cap)
        if name.startswith("update_"):
            try:
                return await self.updater.handle(profile, name, value)
            except (OSError, ValueError, TypeError, KeyError, AttributeError, IndexError):
                raise HTTPError(502, "desktop_error") from None
        discovered = False
        try:
            async with asyncio.timeout(TIMEOUT):
                upstream, token = await self.discover(profile)
                discovered = True
                async def request(verb, path, data=None, soft=(), **parameters):
                    return await upstream.collect_json(verb, path + "?" + urlencode({"profile": profile, **parameters}), token, data, soft)
                result = await self.execute(device, profile, name, match, query, value, request, token)
                code = 202 if name == "install" else 200
                if name.startswith("chan_") or name == "logs":
                    code, result = result
                encoded = json.dumps(result, separators=(",", ":"), allow_nan=False).encode()
                if (len(encoded) > CAP or contains_credential(result, token)
                        or token in encoded.decode() or json.dumps(token)[1:-1] in encoded.decode()):
                    raise ValueError
                return code, result
        except HTTPError as error:
            if error.message in ("desktop_unavailable", "desktop_invalid"):
                raise
            raise HTTPError(502, "desktop_error") from None
        except TimeoutError:
            raise HTTPError(502 if discovered else 503,
                            "desktop_error" if discovered else "desktop_unavailable") from None
        except (OSError, EOFError, ValueError, TypeError, KeyError, UnicodeError, AttributeError, IndexError):
            raise HTTPError(502, "desktop_error") from None

    async def mcp(self, profile, name, match, value, request):
        if name in ("mcp_catalog", "mcp_install"):
            raw = rows((await request("GET", "/api/mcp/catalog"))["entries"])
            entries = []
            for row in raw:
                auth = row.get("auth_type") in (None, "none") and not row.get("required_env")
                entry = project(row, {"name": TEXT, "installed": BOOL, "enabled": BOOL})
                if not re.fullmatch(SERVER, entry["name"]):
                    continue
                entries.append({"id": entry["name"], "name": entry["name"],
                                "description": clean(row.get("description"), 300),
                                "installed": entry["installed"], "enabled": entry["enabled"],
                                "installable": bool(auth and not row.get("needs_install")
                                                    and not entry["installed"])})
            if name == "mcp_catalog":
                return {"entries": entries}
            chosen = next((e for e in entries if e["id"] == value["id"]), None)
            if chosen is None or not chosen["installable"]:
                raise HTTPError(400, "desktop_invalid")
            receipt = await request("POST", "/api/mcp/catalog/install",
                                    {"name": chosen["id"], "env": {}, "enable": True, "profile": profile})
            if receipt.get("ok") is not True or receipt.get("background") is not False:
                raise ValueError
            return {"id": chosen["id"], "state": "installed"}
        servers = rows((await request("GET", "/api/mcp/servers"))["servers"])
        server = next((s for s in servers if s.get("name") == match[1]), None)
        if server is None or server.get("plugin") is not None:
            raise HTTPError(400, "desktop_invalid")
        if name == "mcp_enabled":
            await request("PUT", "/api/mcp/servers/" + match[1] + "/enabled", {**value, "profile": profile})
            servers = rows((await request("GET", "/api/mcp/servers"))["servers"])
            found = next((s for s in servers if s.get("name") == match[1]), None)
            if found is None:
                raise HTTPError(404, "desktop_invalid")
            return project(found, {"name": TEXT, "enabled": BOOL})
        raw = await request("POST", "/api/mcp/servers/" + match[1] + "/test")
        result = {"name": match[1], "ok": project(raw, {"ok": BOOL})["ok"],
                  "tools": [{"name": clean(t.get("name"), 100), "description": clean(t.get("description"), 200)}
                            for t in rows(raw.get("tools") or [])[:100]]}
        if not result["ok"]:
            result["error"] = clean(raw.get("error"), 200)
        for key in ("prompts", "resources"):
            if type(raw.get(key)) is int and raw[key] >= 0:
                result[key] = raw[key]
        return result

    async def execute(self, device, profile, name, match, query, value, request, token):
        if name.startswith("chan_"):
            return await channel_handle(self, profile, name, match, value, request)
        if name == "logs":
            return await logs.handle(query, request)
        if name in ("skills", "toggle"):
            skills = rows(await request("GET", "/api/skills"))
            if name == "toggle":
                if not any(s.get("name") == value["name"] for s in skills):
                    raise HTTPError(400, "desktop_invalid")
                await request("PUT", "/api/skills/toggle", {**value, "profile": profile})
                skills = rows(await request("GET", "/api/skills"))
                found = next((s for s in skills if s["name"] == value["name"]), None)
                return project(found, {"name": TEXT, "enabled": BOOL})
            return {"skills": [dict(project(s, {"name": TEXT, "description": TEXT, "category": (str, type(None)), "enabled": BOOL}),
                                    **(project(s, {"essential": BOOL}) if "essential" in s else {})) for s in skills]}
        if name == "catalog":
            self.catalogs = {key: v for key, v in self.catalogs.items() if v[0] > self.clock() - 60}
            catalog_rows = rows((await request("GET", "/api/skills/hub/official"))["skills"])
            skills = [project(s, {"identifier": TEXT, "name": TEXT, "description": TEXT, "installed": BOOL}) for s in catalog_rows]
            if any(not re.fullmatch(r"official/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*", s["identifier"]) for s in skills):
                raise ValueError
            if len(self.catalogs) >= 256 and (device, profile) not in self.catalogs:
                raise ValueError
            self.catalogs[device, profile] = (self.clock(), token, {s["identifier"] for s in skills})
            return {"skills": skills}
        if name == "install":
            catalog = self.catalogs.get((device, profile))
            if not catalog or catalog[0] <= self.clock() - 60 or catalog[1] != token or value["identifier"] not in catalog[2]:
                raise HTTPError(400, "desktop_invalid")
            receipt = await request("POST", "/api/skills/hub/install", {**value, "profile": profile})
            if receipt.get("ok") is not True or type(receipt.get("pid")) is not int or receipt["pid"] <= 0:
                raise ValueError
            return {"identifier": value["identifier"], "state": "started"}
        if name == "mcp":
            servers = rows((await request("GET", "/api/mcp/servers"))["servers"])
            return {"servers": [project(s, {"name": TEXT, "transport": TEXT, "enabled": BOOL,
                                            "source": TEXT, "plugin": (str, type(None))}) for s in servers]}
        if name.startswith("mcp_"):
            return await self.mcp(profile, name, match, value, request)
        if name == "usage":
            days = query.get("days", 30)
            data = await request("GET", "/api/analytics/usage", days=days)
            totals = project(data["totals"], {key: NUMBER for key in USAGE.values()})
            return {"daily": [project(row, {"day": TEXT, **{key: NUMBER for key in USAGE}}) for row in rows(data["daily"])],
                    "totals": {key: totals[source] for key, source in USAGE.items()}, "period_days": days}
        runs = rows((await request("GET", "/api/cron/jobs/" + match[1] + "/runs", limit=query.get("limit", 20)))["runs"])
        if any(row.get("profile") != profile for row in runs):
            raise ValueError
        return {"runs": [project(row, {"id": TEXT, "title": (str, type(None)), "started_at": NUMBER,
                                       "ended_at": NUMBER, "is_active": BOOL, "preview": (str, type(None))}) for row in runs]}
