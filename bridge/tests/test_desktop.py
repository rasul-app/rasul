"""Scoped desktop contract exercised against a disposable local HTTP server."""

import asyncio
import ctypes
from ctypes import wintypes
from pathlib import Path
from contextlib import asynccontextmanager, suppress
import hashlib
import json
import os
import secrets
import socket
import struct
import subprocess
import sys
from urllib.parse import parse_qs, urlsplit

import pytest

from test_bridge import asynchronous, client, read_response, registered, running, send


ROUTES = [
    ("GET", "/jobs/job/runs?limit=20", None, 10),
    ("GET", "/skills", None, 30),
    ("PUT", "/skills/toggle", {"name": "sample", "enabled": False}, 6),
    ("GET", "/skills/catalog", None, 6),
    ("POST", "/skills/install", {"identifier": "official/general/sample"}, 1),
    ("GET", "/mcp", None, 12),
    ("GET", "/usage?days=30", None, 6),
]
def process_start_time(pid):
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            raise OSError
        try:
            times = [wintypes.FILETIME() for _ in range(4)]
            if not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
                raise OSError
            ticks = (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime
            return round((ticks / 10000000 - 11644473600) * 100)
        finally:
            kernel.CloseHandle(handle)
    return int((Path("/proc") / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()[19])


PREFIX = "/v1/profiles/default/desktop"


class FakeDesktop:
    def __init__(self, directory):
        self.directory = directory
        self.token = secrets.token_urlsafe(32)
        self.role = "serve"
        self.pid = os.getpid()
        self.requests = []
        self.enabled = True
        self.status = 200
        self.owner = self.pid
        self.extra_runs = []
        self.payload = None
        self.raw = None
        self.identity_status = 200
        self.identity_payload = None
        self.home_payload = None
        self.connections = []
        self.profile = "default"
        self.identity_delay = 0
        self.delay = 0
        self.tasks = set()

    async def handle(self, reader, writer):
        self.tasks.add(asyncio.current_task())
        try:
            while True:
                lines = (await reader.readuntil(b"\r\n\r\n")).decode().split("\r\n")
                method, target, _ = lines[0].split(" ")
                head = dict(line.split(": ", 1) for line in lines[1:] if line)
                body = await reader.readexactly(int(head["Content-Length"]))
                value = json.loads(body) if body else None
                self.requests.append((method, target, head, value))
                assert head["Authorization"] == "Bearer " + self.token
                self.connections.append(writer.get_extra_info("peername"))
                parsed = urlsplit(target)
                if parsed.path == "/api/host/identity":
                    await asyncio.sleep(self.identity_delay)
                    payload = {"ok": True, "protocolVersion": 1, "pid": self.owner,
                               "role": self.role, "servesSpa": True}
                    if self.identity_payload is not None:
                        payload = self.identity_payload
                    status = self.identity_status
                elif parsed.path == "/api/status":
                    assert parse_qs(parsed.query)["profile"] == [self.profile]
                    home = self.home if self.profile == "default" else self.home / "profiles" / self.profile
                    payload = self.home_payload if self.home_payload is not None else {"hermes_home": str(home)}
                    status = 200
                else:
                    await asyncio.sleep(self.delay)
                    assert parse_qs(parsed.query)["profile"] == [self.profile]
                    status = self.status
                    payload = self.payload
                    if payload is None:
                        payload = self.result(method, parsed.path, value)
                data = self.raw if self.raw is not None and parsed.path not in ("/api/host/identity", "/api/status") else json.dumps(payload).encode()
                writer.write(f"HTTP/1.1 {status} Response\r\nContent-Length: {len(data)}\r\n"
                             "Location: https://example.invalid/\r\nSet-Cookie: private=value\r\n\r\n".encode() + data)
                await writer.drain()
                if head.get("Connection", "").lower() == "close":
                    break
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            writer.close()
            with suppress(ConnectionError):
                await writer.wait_closed()
            self.tasks.discard(asyncio.current_task())

    def result(self, method, path, value):
        if path == "/api/skills":
            return [{"name": "sample", "description": "Sample", "category": "general",
                     "enabled": self.enabled, "usage": 4, "provenance": self.token}]
        if path == "/api/skills/toggle":
            self.enabled = value["enabled"]
            assert value["profile"] == self.profile
            return {"ok": True, "name": value["name"], "enabled": self.enabled}
        if path == "/api/skills/hub/official":
            return {"skills": [{"identifier": "official/general/sample", "name": "sample",
                                "description": "Sample", "installed": False,
                                "source": self.token, "repo": "https://example.invalid/"}]}
        if path == "/api/skills/hub/install":
            assert value["profile"] == self.profile
            return {"ok": True, "pid": 42, "name": value["identifier"]}
        if path == "/api/mcp/servers":
            return {"servers": [{"name": "sample", "transport": "stdio", "enabled": True,
                                 "source": "config", "plugin": None, "url": self.token,
                                 "command": self.token, "args": [self.token], "env": {"secret": self.token},
                                 "auth": self.token, "tools": [self.token]}]}
        if path == "/api/analytics/usage":
            return {"daily": [{"day": "2026-10-17", "input_tokens": 1, "output_tokens": 2,
                               "cache_read_tokens": None, "reasoning_tokens": 4, "estimated_cost": 0.1,
                               "sessions": 1, "api_calls": 2, "actual_cost": self.token}],
                    "totals": {"total_input": 1, "total_output": 2, "total_cache_read": None,
                               "total_reasoning": 4, "total_estimated_cost": 0.1,
                               "total_sessions": 1, "total_api_calls": 2, "total_actual_cost": self.token},
                    "period_days": 30, "by_model": self.token, "tools": self.token}
        if path == "/api/cron/jobs/job/runs":
            return {"runs": [{"id": "cron_job_1", "title": "Sample", "started_at": 1,
                              "ended_at": None, "is_active": True, "preview": "Sample",
                              "profile": "default", "cwd": self.token, "messages": [self.token],
                              "scheduler_owned": True}] + self.extra_runs, "limit": 20}
        raise AssertionError(path)

    def publish(self):
        from rasul_bridge.desktop import process_times
        record = {"role": self.role, "pid": self.pid, "host": "127.0.0.1", "port": self.port,
                  "protocolVersion": 1, "tokenFingerprint": hashlib.sha256(self.token.encode()).hexdigest()[:16],
                  "profiles": sorted({"default", self.profile}), "startTime": process_start_time(self.pid),
                  "createTime": process_times(self.pid)[1], "updatedAt": "2026-10-17T00:00:00+00:00",
                  "home": ""}
        self.record = self.directory / "host-serve.json"
        self.record.write_text(json.dumps(record))
        self.token_file = self.directory / "host-serve.token"
        self.token_file.write_text(self.token)
        self.token_file.chmod(0o600)


@asynccontextmanager
async def desktop(tmp_path, monkeypatch, factory=FakeDesktop):
    directory = tmp_path / "rendezvous"
    directory.mkdir(mode=0o700)
    monkeypatch.setenv("HERMES_GATEWAY_LOCK_DIR", str(directory))
    fake = factory(directory)
    server = await asyncio.start_server(fake.handle, "127.0.0.1", 0)
    fake.server = server
    fake.port = server.sockets[0].getsockname()[1]
    fake.publish()
    try:
        async with running(tmp_path) as (bridge, gateway):
            fake.gateway = gateway
            fake.home = bridge.config.hermes_home
            identity, device = registered(bridge)
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                async def call(method, suffix, value=None, prefix=PREFIX):
                    await send(link, method, prefix + suffix, value)
                    return await read_response(reader)
                yield bridge, fake, call
    finally:
        server.close()
        await server.wait_closed()
        await asyncio.gather(*tuple(fake.tasks), return_exceptions=True)


@pytest.mark.parametrize("method,suffix,body,rate", ROUTES)
@asynchronous
async def test_desktop_allow_projection_and_token_secrecy(tmp_path, monkeypatch, method, suffix, body, rate):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        if suffix == "/skills/install":
            assert (await call("GET", "/skills/catalog"))[0] == 200
        status, value = await call(method, suffix, body)
        assert status == (202 if method == "POST" else 200)
        encoded = json.dumps(value)
        for forbidden in (fake.token, '"cwd"', '"messages"', '"repo"', '"url"', '"args"',
                          '"env"', '"auth"', '"actual_cost"', '"by_model"', '"scheduler_owned"'):
            assert forbidden not in encoded
        if suffix == "/skills/toggle":
            assert value == {"name": "sample", "enabled": False}
            assert [r[0] for r in fake.requests if urlsplit(r[1]).path == "/api/skills"] == ["GET", "GET"]
        if suffix == "/skills/install":
            assert value == {"identifier": body["identifier"], "state": "started"}
            assert len([r for r in fake.requests if r[0] == "POST"]) == 1
        expected = {
            "/skills": {"skills": [{"name": "sample", "description": "Sample", "category": "general", "enabled": True}]},
            "/skills/catalog": {"skills": [{"identifier": "official/general/sample", "name": "sample", "description": "Sample", "installed": False}]},
            "/mcp": {"servers": [{"name": "sample", "transport": "stdio", "enabled": True, "source": "config", "plugin": None}]},
            "/jobs/job/runs?limit=20": {"runs": [{"id": "cron_job_1", "title": "Sample", "started_at": 1, "ended_at": None, "is_active": True, "preview": "Sample"}]},
        }
        if suffix in expected:
            assert value == expected[suffix]
        if suffix.startswith("/usage"):
            assert value == {"daily": [{"day": "2026-10-17", "input_tokens": 1, "output_tokens": 2,
                                        "cache_read_tokens": None, "reasoning_tokens": 4, "estimated_cost": 0.1,
                                        "sessions": 1, "api_calls": 2}],
                             "totals": {"input_tokens": 1, "output_tokens": 2, "cache_read_tokens": None,
                                        "reasoning_tokens": 4, "estimated_cost": 0.1, "sessions": 1, "api_calls": 2}, "period_days": 30}
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert fake.token not in json.dumps(rows)
        assert all(set(row) == {"route", "status"} for row in rows)


@pytest.mark.parametrize("method,suffix,body,rate", ROUTES)
@pytest.mark.parametrize("attack", ["method", "query", "duplicate_query", "body_keys", "duplicate_body", "size", "rate"])
@asynchronous
async def test_desktop_deny_per_route(tmp_path, monkeypatch, method, suffix, body, rate, attack):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        if attack == "rate" and suffix == "/skills/install":
            await call("GET", "/skills/catalog")
        expected = 400
        if attack == "method":
            if suffix == "/skills/install":
                assert (await call("GET", "/skills/catalog"))[0] == 200
            assert (await call(method, suffix, body))[0] in (200, 202)
            method = "DELETE"
            expected = 404
        elif attack in ("query", "duplicate_query"):
            separator = "&" if "?" in suffix else "?"
            suffix += separator + ("profile=other" if attack == "query" else "days=1&days=2")
        elif attack == "body_keys":
            body = {**(body or {}), "profile": "other"}
        elif attack == "duplicate_body":
            body = b'{"name":"sample","name":"sample","enabled":true}'
        elif attack == "size":
            body = b" " * 1025
        elif attack == "rate":
            for _ in range(rate):
                assert (await call(method, suffix, body))[0] in (200, 202)
            expected = 429
        before = len(fake.requests)
        assert (await call(method, suffix, body))[0] == expected
        assert len(fake.requests) == before


@pytest.mark.parametrize("identifier", ["official/general/other", "https://example.invalid/sample", "sample", "official/../sample"])
@asynchronous
async def test_desktop_install_requires_fetched_catalog(tmp_path, monkeypatch, identifier):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        await call("GET", "/skills/catalog")
        assert (await call("POST", "/skills/install", {"identifier": identifier}))[0] == 400
        assert not any(r[0] == "POST" for r in fake.requests)


@pytest.mark.parametrize("failure", ["down", "fingerprint", "owner", "protocol", "nonloopback", "gated"])
@asynchronous
async def test_desktop_discovery_fails_closed(tmp_path, monkeypatch, failure):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        record = json.loads(fake.record.read_text())
        if failure == "down":
            fake.record.unlink()
        elif failure == "fingerprint":
            fake.token_file.write_text(secrets.token_urlsafe(32))
        elif failure == "owner":
            fake.owner += 1
        elif failure == "protocol":
            record["protocolVersion"] = 2
        elif failure == "nonloopback":
            record["host"] = "192.0.2.1"
        elif failure == "gated":
            fake.identity_status = 401
        if failure in ("protocol", "nonloopback"):
            fake.record.write_text(json.dumps(record))
        assert await call("GET", "/skills") == (503, {"error": "desktop_unavailable"})


@pytest.mark.parametrize("status", [301, 302, 307, 308, 401, 403, 500])
@asynchronous
async def test_desktop_upstream_errors_and_redirects_are_sanitized(tmp_path, monkeypatch, status):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.status, fake.payload = status, {"error": fake.token}
        assert await call("GET", "/skills") == (502, {"error": "desktop_error"})
        assert len(fake.requests) == 3
        assert fake.token not in bridge.audit.path.read_text()


@pytest.mark.parametrize("suffix", ["/usage?days=0", "/usage?days=366", "/usage?days=01", "/usage?days=+1",
                                  "/usage?days=%201", "/jobs/job/runs?limit=101", "/jobs/job/runs?limit=1.0",
                                  "/skills#part", "/skills?", "/jobs/%2e%2e/runs"])
@asynchronous
async def test_desktop_strict_target_numbers(tmp_path, monkeypatch, suffix):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        assert (await call("GET", suffix))[0] == 400
        assert not fake.requests


@pytest.mark.parametrize("row", [{"profile": "other"}, {"source": "cron_output"}, {"profile": None}])
@asynchronous
async def test_desktop_history_requires_row_owner(tmp_path, monkeypatch, row):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.extra_runs = [row]
        assert await call("GET", "/jobs/job/runs?limit=20") == (502, {"error": "desktop_error"})


@asynchronous
async def test_desktop_response_cap(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.payload = "x" * (1024 * 1024)
        assert await call("GET", "/skills") == (502, {"error": "desktop_error"})


@asynchronous
async def test_desktop_pairing_and_profile_boundary(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        assert (await call("GET", "/skills"))[0] == 200
        before = len(fake.requests)
        assert (await call("GET", "/skills", prefix="/v1/profiles/other/desktop"))[0] == 404
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", PREFIX + "/skills")
            assert (await read_response(reader))[0] == 403
        assert len(fake.requests) == before


@pytest.mark.parametrize("body", [{"name": "missing", "enabled": True}, {"name": "sample", "enabled": 1},
                                 {"name": "sample", "enabled": "false"}, {"name": [], "enabled": True}])
@asynchronous
async def test_desktop_toggle_requires_installed_name_and_boolean(tmp_path, monkeypatch, body):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", "/skills/toggle", body) == (400, {"error": "desktop_invalid"})
        assert not any(r[0] == "PUT" for r in fake.requests)


@pytest.mark.parametrize("failure", ["unfetched", "expired", "device", "profile", "owner_changed"])
@asynchronous
async def test_desktop_catalog_scope_and_freshness(tmp_path, monkeypatch, failure):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        now = [1000]
        bridge.desktop.clock = lambda: now[0]
        if failure != "unfetched":
            assert (await call("GET", "/skills/catalog"))[0] == 200
        if failure == "expired":
            now[0] += 60
        elif failure in ("device", "profile"):
            key = next(iter(bridge.desktop.catalogs))
            bridge.desktop.catalogs[("other", key[1]) if failure == "device" else (key[0], "other")] = bridge.desktop.catalogs.pop(key)
        elif failure == "owner_changed":
            fake.token = secrets.token_urlsafe(32)
            fake.publish()
        assert await call("POST", "/skills/install", {"identifier": "official/general/sample"}) == (400, {"error": "desktop_invalid"})
        assert not any(r[0] == "POST" for r in fake.requests)


@pytest.mark.parametrize("suffix,payload", [("/skills", {}), ("/skills", [None]), ("/mcp", {"servers": [None]}),
                                          ("/usage", {"daily": [], "totals": None}),
                                          ("/jobs/job/runs", {"runs": [None]})])
@asynchronous
async def test_desktop_malformed_upstream_is_sanitized(tmp_path, monkeypatch, suffix, payload):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.payload = payload
        assert await call("GET", suffix) == (502, {"error": "desktop_error"})


@asynchronous
async def test_desktop_credential_echo_in_allowed_field_is_rejected(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.payload = [{"name": "sample", "description": fake.token, "category": "general", "enabled": True}]
        assert await call("GET", "/skills") == (502, {"error": "desktop_error"})


@pytest.mark.parametrize("phase", ["discovery", "request"])
@asynchronous
async def test_desktop_total_deadline(tmp_path, monkeypatch, phase):
    import rasul_bridge.desktop as module
    monkeypatch.setattr(module, "TIMEOUT", 0.03)
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        if phase == "discovery":
            fake.identity_delay = 0.1
        else:
            authenticated = await bridge.desktop.discover("default")
            async def discovered(profile):
                return authenticated
            monkeypatch.setattr(bridge.desktop, "discover", discovered)
            fake.delay = 0.1
        expected = (503, {"error": "desktop_unavailable"}) if phase == "discovery" else (502, {"error": "desktop_error"})
        assert await call("GET", "/skills") == expected


@asynchronous
async def test_desktop_explicit_profile_named_desktop(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        (bridge.config.hermes_home / "profiles" / "desktop").mkdir(parents=True)
        fake.profile = "desktop"
        fake.publish()
        assert (await call("GET", "/skills", prefix="/v1/profiles/desktop/desktop"))[0] == 200


@pytest.mark.parametrize("identifier", ["official/sample", "official/general/group/sample"])
@asynchronous
async def test_desktop_catalog_accepts_exact_official_paths(tmp_path, monkeypatch, identifier):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.payload = {"skills": [{"identifier": identifier, "name": "sample", "description": "Sample", "installed": False}]}
        assert (await call("GET", "/skills/catalog"))[0] == 200
        fake.payload = None
        assert await call("POST", "/skills/install", {"identifier": identifier}) == (202, {"identifier": identifier, "state": "started"})


@asynchronous
async def test_desktop_ambient_profile_alias_is_refused(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        (bridge.config.hermes_home / "profiles" / "current").mkdir(parents=True)
        fake.profile = "current"
        assert await call("GET", "/skills", prefix="/v1/profiles/current/desktop") == (404, {"error": "Profile not found"})
        assert not fake.requests


@pytest.mark.parametrize("method,suffix,body,rate", ROUTES)
@asynchronous
async def test_desktop_rate_scope_and_expiry(tmp_path, monkeypatch, method, suffix, body, rate):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        now = [1000]
        bridge.desktop.clock = lambda: now[0]
        for _ in range(rate):
            if suffix == "/skills/install":
                await call("GET", "/skills/catalog")
            assert (await call(method, suffix, body))[0] in (200, 202)
        assert (await call(method, suffix, body))[0] == 429
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            if suffix == "/skills/install":
                await send(link, "GET", PREFIX + "/skills/catalog")
                assert (await read_response(reader))[0] == 200
            await send(link, method, PREFIX + suffix, body)
            assert (await read_response(reader))[0] in (200, 202)
        now[0] += 60
        if suffix == "/skills/install":
            await call("GET", "/skills/catalog")
        assert (await call(method, suffix, body))[0] in (200, 202)
        (bridge.config.hermes_home / "profiles" / "other").mkdir(parents=True)
        fake.profile = "other"
        fake.publish()
        if suffix == "/skills/install":
            await call("GET", "/skills/catalog", prefix="/v1/profiles/other/desktop")
        if suffix.startswith("/jobs/"):
            fake.payload = {"runs": []}
        assert (await call(method, suffix, body, prefix="/v1/profiles/other/desktop"))[0] in (200, 202)


@asynchronous
async def test_desktop_response_serialization_cap(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.payload = [{"name": "sample", "description": "\u0627" * 180000,
                         "category": "general", "enabled": True}]
        assert await call("GET", "/skills") == (502, {"error": "desktop_error"})


@asynchronous
async def test_desktop_existing_forwarder_remains_narrow(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        assert (await call("GET", "/skills"))[0] == 200
        before = len(fake.requests)
        for suffix in ("/api/skills", "/api/skills/hub/official", "/api/mcp/servers", "/api/analytics/usage"):
            assert (await call("GET", suffix, prefix="/v1/profiles/default/hermes"))[0] == 404
        assert len(fake.requests) == before


@pytest.mark.parametrize("raw", [b"[" * 1000, b'{"name":"sample","enabled":NaN}', b'\xff'])
@asynchronous
async def test_desktop_untrusted_json_body(tmp_path, monkeypatch, raw):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", "/skills/toggle", raw) == (400, {"error": "desktop_invalid"})
        assert not fake.requests


@pytest.mark.parametrize("raw", [b"[" * 5000 + b"]" * 5000, b'{"skills":[],"skills":[]}', b'\xff'])
@asynchronous
async def test_desktop_untrusted_json_response(tmp_path, monkeypatch, raw):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.raw = raw
        assert await call("GET", "/skills/catalog") == (502, {"error": "desktop_error"})


@pytest.mark.parametrize("failure", ["dead", "reused", "foreign", "missing_start", "home"])
@asynchronous
async def test_desktop_preflight_refuses_without_credentials(tmp_path, monkeypatch, failure):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        record = json.loads(fake.record.read_text())
        if failure == "dead":
            record["pid"] = 2147483647
        elif failure == "reused":
            record["startTime"] += 10000
        elif failure == "foreign":
            record["pid"] = os.getppid()
            record["startTime"] = process_start_time(record["pid"])
        elif failure == "missing_start":
            record.pop("startTime")
            record.pop("createTime")
        else:
            record["home"] = str(tmp_path / "other")
        fake.record.write_text(json.dumps(record))
        response = await call("GET", "/skills")
        assert not any("Authorization" in request[2] for request in fake.requests), "credential request received"
        assert response == (503, {"error": "desktop_unavailable"})
        assert not fake.requests


@asynchronous
async def test_desktop_uncategorized_skill(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.payload = [{"name": "sample", "description": "Sample", "category": None, "enabled": True}]
        assert await call("GET", "/skills") == (200, {"skills": fake.payload})


@asynchronous
async def test_desktop_escaped_credential_echo(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        fake.token += chr(34) + chr(92)
        fake.publish()
        fake.payload = [{"name": "sample", "description": fake.token, "category": "general", "enabled": True}]
        response = await call("GET", "/skills")
        assert response[0] == 502
        assert response[1] == {"error": "desktop_error"}


@pytest.mark.parametrize("failure", ["missing", "relative", "wrong", "unreadable"])
@asynchronous
async def test_desktop_home_proof(tmp_path, monkeypatch, failure):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        record = json.loads(fake.record.read_text())
        if failure == "missing":
            record.pop("home")
        elif failure == "relative":
            record["home"] = "upstream"
        elif failure == "wrong":
            record["home"] = str(tmp_path / "other")
        else:
            record["home"] = None
        fake.record.write_text(json.dumps(record))
        assert await call("GET", "/skills") == (503, {"error": "desktop_unavailable"})
        assert not fake.requests


@asynchronous
async def test_desktop_home_canonical_directory(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        record = json.loads(fake.record.read_text())
        home = fake.directory.parent / "upstream"
        (home / "child").mkdir()
        record["home"] = str(home / "child" / "..")
        if os.name == "nt":
            record["home"] = record["home"].upper()
        fake.record.write_text(json.dumps(record))
        assert (await call("GET", "/skills"))[0] == 200


@pytest.mark.parametrize("failure", ["start", "listener", "unsupported"])
@asynchronous
async def test_desktop_rechecks_before_profile_mutation(tmp_path, monkeypatch, failure):
    import rasul_bridge.desktop as module
    from types import SimpleNamespace
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        original = fake.result
        def result(method, path, value):
            response = original(method, path, value)
            if path == "/api/skills":
                if failure == "start":
                    monkeypatch.setattr(module, "process_times", lambda pid: (1, 1))
                elif failure == "listener":
                    monkeypatch.setattr(module, "listener_owned", lambda *args: False)
                else:
                    monkeypatch.setattr(module, "sys", SimpleNamespace(platform="unsupported"))
            return response
        fake.result = result
        assert await call("PUT", "/skills/toggle", {"name": "sample", "enabled": False}) == (
            503, {"error": "desktop_unavailable"})
        assert len(fake.requests) == 3
        assert not any(request[0] == "PUT" for request in fake.requests)


@pytest.mark.parametrize("failure", [False, True])
@asynchronous
async def test_desktop_legacy_creation_time(tmp_path, monkeypatch, failure):
    from rasul_bridge.desktop import process_times
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        record = json.loads(fake.record.read_text())
        record.pop("startTime")
        record["createTime"] = process_times(fake.pid)[1] + (10 if failure else 0)
        fake.record.write_text(json.dumps(record))
        response = await call("GET", "/skills")
        if failure:
            assert response == (503, {"error": "desktop_unavailable"})
            assert not fake.requests
        else:
            assert response[0] == 200


@asynchronous
async def test_desktop_connection_error_requires_new_proof(tmp_path, monkeypatch):
    from rasul_bridge.desktop import LocalDesktop
    from rasul_bridge.http import HTTPError
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        upstream, token = await bridge.desktop.discover("default")
        async def broken(*args):
            raise ConnectionResetError
        monkeypatch.setattr(upstream, "open", broken)
        with pytest.raises(ConnectionResetError):
            await upstream.collect_json("GET", "/api/skills", token)
        upstream.record["startTime"] += 10000
        with pytest.raises(HTTPError) as raised:
            await upstream.collect_json("GET", "/api/skills", token)
        assert raised.value.message == "desktop_unavailable"
        assert len(fake.requests) == 2


@asynchronous
async def test_desktop_real_record_shape(tmp_path, monkeypatch):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        record = json.loads(fake.record.read_text())
        record["home"] = ""
        record["profiles"] = ["default"]
        fake.record.write_text(json.dumps(record))
        assert (await call("GET", "/skills"))[0] == 200


@pytest.mark.parametrize("profiles", [[], ["other"], "default", [1]])
@asynchronous
async def test_desktop_unserved_profile(tmp_path, monkeypatch, profiles):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        record = json.loads(fake.record.read_text())
        record["home"] = str(bridge.config.hermes_home)
        record["profiles"] = profiles
        fake.record.write_text(json.dumps(record))
        assert await call("GET", "/skills") == (503, {"error": "desktop_unavailable"})
        assert not fake.requests


@asynchronous
async def test_desktop_foreign_record(tmp_path, monkeypatch):
    import rasul_bridge.desktop as module
    from types import SimpleNamespace
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        monkeypatch.setattr(module, "os", SimpleNamespace(**{**vars(os), "name": "posix", "getuid": lambda: -1}))
        assert await call("GET", "/skills") == (503, {"error": "desktop_unavailable"})
        assert not fake.requests


@asynchronous
async def test_desktop_established_owner_mismatch(tmp_path, monkeypatch):
    import rasul_bridge.desktop as module
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        record = json.loads(fake.record.read_text())
        # Isolate connection proof from compatibility with an empty home.
        record["home"] = str(bridge.config.hermes_home)
        fake.record.write_text(json.dumps(record))
        monkeypatch.setattr(module, "connection_owned", lambda *args: False, raising=False)
        response = await call("GET", "/skills")
        assert len(fake.requests) == 0, "request sent before established owner proof"
        assert response == (503, {"error": "desktop_unavailable"})


@asynchronous
async def test_desktop_listener_takeover_receives_no_bytes(tmp_path, monkeypatch):
    from rasul_bridge.desktop import LocalDesktop
    from rasul_bridge.http import HTTPError
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, _):
        record = json.loads(fake.record.read_text())
        record["home"] = str(bridge.config.hermes_home)
        upstream = LocalDesktop("http://" + record["host"] + ":" + str(record["port"]))
        upstream.record, upstream.home, upstream.profile = record, bridge.config.hermes_home, "default"
        connect = asyncio.open_connection
        child = None
        script = (
            "import socket,sys; "
            "s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); "
            "s.bind((sys.argv[1],int(sys.argv[2]))); s.listen(); "
            "print(s.getsockname()[1],file=sys.stderr,flush=True); "
            "c,_=s.accept(); c.settimeout(2); "
            "data=c.recv(65536); sys.stdout.buffer.write(data); c.close(); s.close()"
        )
        async def takeover(host, port):
            nonlocal child
            # Substitute the listener only after the preflight verification.
            fake.server.close()
            await fake.server.wait_closed()
            child = subprocess.Popen([sys.executable, "-c", script, host, str(port)],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            port = int(await asyncio.to_thread(child.stderr.readline))
            return await connect(host, port)
        monkeypatch.setattr(asyncio, "open_connection", takeover)
        try:
            error = None
            try:
                await upstream.collect_json("GET", "/api/skills", fake.token)
            except (HTTPError, EOFError, OSError) as caught:
                error = caught
            received, _ = await asyncio.to_thread(child.communicate, timeout=5)
            received_length = len(received)
            assert received_length == 0, "replacement listener received request bytes"
            assert child.returncode == 0
            assert isinstance(error, HTTPError) and error.message == "desktop_unavailable"
        finally:
            if child is not None and child.poll() is None:
                child.kill()
                await asyncio.to_thread(child.communicate)


def test_desktop_ipv6_connection_fails_closed():
    from rasul_bridge.desktop import connection_owned
    assert not connection_owned(os.getpid(), "127.0.0.1", 1, ("::1", 2, 0, 0))


@pytest.mark.parametrize("platform", ["win32", "linux"])
@pytest.mark.parametrize("failure", [None, "owner", "local", "remote", "state", "ipv6"])
def test_desktop_connection_rows(tmp_path, monkeypatch, platform, failure):
    import rasul_bridge.desktop as module
    from types import SimpleNamespace
    host, port, client_port, pid = "127.0.0.1", 1, 2, os.getpid()
    local_port = port + (1 if failure == "local" else 0)
    remote_port = client_port + (1 if failure == "remote" else 0)
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform=platform, byteorder=sys.byteorder))
    if platform == "win32":
        address = int.from_bytes(socket.inet_aton(host), sys.byteorder)
        row = struct.pack("=6I", 2 if failure == "state" else 5,
                          address, socket.htons(local_port), address, socket.htons(remote_port),
                          pid + (1 if failure == "owner" else 0))
        # An IPv6-only table contributes no IPv4 ownership row.
        payload = struct.pack("=I", 0 if failure == "ipv6" else 1)
        if failure != "ipv6":
            payload += row
        def table(buffer, size, ordered, family, kind, reserved):
            assert family == socket.AF_INET and kind == 5
            ctypes.cast(size, ctypes.POINTER(wintypes.DWORD)).contents.value = len(payload)
            if buffer is None:
                return 122
            ctypes.memmove(buffer, payload, len(payload))
            return 0
        monkeypatch.setattr(module.ctypes, "WinDLL", lambda *args, **kwargs:
                            SimpleNamespace(GetExtendedTcpTable=table), raising=False)
    else:
        root = tmp_path / "proc"
        (root / str(pid) / "fd").mkdir(parents=True)
        (root / str(pid) / "fd" / "1").touch()
        address = int.from_bytes(socket.inet_aton(host), "little")
        state = "0A" if failure == "state" else "01"
        row = f"0: {address:08X}:{local_port:04X} {address:08X}:{remote_port:04X} {state} 0 0 0 0 0 41\n"
        (root / "tcp").write_text("header\n" + ("" if failure == "ipv6" else row))
        (root / "tcp6").write_text("header\n0: 00000000000000000000000001000000:0001 "
                                   "00000000000000000000000001000000:0002 01 0 0 0 0 0 41\n")
        path = Path
        monkeypatch.setattr(module, "Path", lambda value: root / "tcp" if value == "/proc/net/tcp"
                            else root if value == "/proc" else path(value))
        inode = "42" if failure == "owner" else "41"
        monkeypatch.setattr(module, "os", SimpleNamespace(**{**vars(os), "readlink": lambda fd: "socket:[" + inode + "]"}))
    assert module.connection_owned(pid, host, port, (host, client_port)) == (failure is None)


@pytest.mark.parametrize("payload", [[], "invalid"])
@pytest.mark.parametrize("fallback", [False, True])
@asynchronous
async def test_desktop_identity_shape_fallback(tmp_path, monkeypatch, payload, fallback):
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        if fallback:
            role = "-".join(("desktop", "serve"))
            record = json.loads(fake.record.read_text())
            record["role"] = role
            (fake.directory / ("host-" + role + ".json")).write_text(json.dumps(record))
            token_file = fake.directory / ("host-" + role + ".token")
            token_file.write_bytes(fake.token_file.read_bytes())
            token_file.chmod(0o600)
            fake.role = role
        first = True
        original = fake.handle
        async def handle(reader, writer):
            nonlocal first
            fake.identity_payload = payload if first else None
            first = False
            await original(reader, writer)
        fake.server.close()
        await fake.server.wait_closed()
        fake.server = await asyncio.start_server(handle, json.loads(fake.record.read_text())["host"], fake.port)
        try:
            response = await call("GET", "/skills")
            assert response[0] == (200 if fallback else 503)
            if not fallback:
                assert response == (503, {"error": "desktop_unavailable"})
        finally:
            fake.server.close()
            await fake.server.wait_closed()


@pytest.mark.parametrize("profile", ["default", "other"])
@pytest.mark.parametrize("proof", ["match", "canonical", "wrong", "missing", "relative", "shape"])
@asynchronous
async def test_desktop_status_home_proof(tmp_path, monkeypatch, profile, proof):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        selected = bridge.config.hermes_home
        if profile != "default":
            selected = selected / "profiles" / profile
            selected.mkdir(parents=True)
        fake.profile = profile
        fake.publish()
        home = str(selected)
        if proof == "canonical":
            (selected / "child").mkdir()
            home = str(selected / "child" / "..")
            if os.name == "nt":
                home = home.upper()
        elif proof == "wrong":
            home = str(bridge.config.hermes_home if profile != "default" else selected / "other")
        elif proof == "relative":
            home = "."
        fake.home_payload = {} if proof == "missing" else [] if proof == "shape" else {"hermes_home": home}
        response = await call("GET", "/skills", prefix=PREFIX.replace("default", profile))
        assert response[0] == (200 if proof in ("match", "canonical") else 503)
        assert [urlsplit(r[1]).path for r in fake.requests[:2]] == ["/api/host/identity", "/api/status"]
        assert fake.connections[0] == fake.connections[1]
        if response[0] == 503:
            assert len(fake.requests) == 2


def test_desktop_rate_limit_device_isolation():
    from rasul_bridge.desktop import Desktop
    from rasul_bridge.http import HTTPError
    desktop = Desktop(None, clock=lambda: 0)
    for index in range(256):
        desktop.charge("first", str(index), "skills", 30)
    desktop.charge("second", "default", "skills", 30)
    with pytest.raises(HTTPError) as raised:
        desktop.charge("first", "overflow", "skills", 30)
    assert raised.value.status == 429
    assert len(desktop.requests) == 257


@pytest.mark.parametrize("platform,delta,accepted", [
    ("win32", -1, True), ("win32", 1, True), ("win32", 2, False),
    ("linux", 0, True), ("linux", 1, False),
])
def test_desktop_start_time_precision(monkeypatch, platform, delta, accepted):
    import rasul_bridge.desktop as module
    from types import SimpleNamespace
    from rasul_bridge.http import HTTPError
    upstream = module.LocalDesktop("http://" + socket.inet_ntoa(struct.pack("!I", socket.INADDR_LOOPBACK)))
    upstream.home, upstream.profile = Path.cwd(), "default"
    upstream.record = {"home": "", "profiles": ["default"], "pid": os.getpid(),
                       "startTime": 100 + delta, "host": upstream.host, "port": upstream.port}
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform=platform))
    monkeypatch.setattr(module, "process_times", lambda pid: (100, 1))
    monkeypatch.setattr(module, "listener_owned", lambda *args: True)
    if accepted:
        upstream.verify()
    else:
        with pytest.raises(HTTPError) as raised:
            upstream.verify()
        assert raised.value.status == 503


@pytest.mark.parametrize("kind", ["listener", "connection"])
@pytest.mark.parametrize("failure", [None, "owner", "port", "address", "state", "retry", "exhausted", "large", "truncated"])
def test_desktop_windows_table(monkeypatch, kind, failure):
    import rasul_bridge.desktop as module
    from types import SimpleNamespace
    host = socket.inet_ntoa(struct.pack("!I", socket.INADDR_LOOPBACK))
    address = int.from_bytes(socket.inet_aton(host), sys.byteorder)
    pid, port, client_port = os.getpid(), 1, 2
    row = struct.pack("=6I", (2 if kind == "listener" else 5) if failure != "state" else 1,
                      address + (1 if failure == "address" else 0), socket.htons(port + (1 if failure == "port" else 0)),
                      address, socket.htons(client_port), pid + (1 if failure == "owner" else 0))
    payload = struct.pack("=I", 2 if failure == "truncated" else 1) + row
    calls = []
    def table(buffer, size, ordered, family, table_class, reserved):
        assert family == socket.AF_INET and table_class == (3 if kind == "listener" else 5)
        pointer = ctypes.cast(size, ctypes.POINTER(wintypes.DWORD))
        calls.append(pointer.contents.value)
        if buffer is None:
            pointer.contents.value = 2 * module.CAP if failure == "large" else len(payload)
            return 122
        if failure == "exhausted" or failure == "retry" and len(calls) <= 3:
            pointer.contents.value += 8192
            return 122
        ctypes.memmove(buffer, payload, len(payload))
        pointer.contents.value = len(payload)
        return 0
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform="win32", byteorder=sys.byteorder))
    monkeypatch.setattr(module.ctypes, "WinDLL", lambda *args, **kwargs:
                        SimpleNamespace(GetExtendedTcpTable=table), raising=False)
    if failure in ("exhausted", "truncated"):
        with pytest.raises((OSError, ValueError)):
            if kind == "listener":
                module.listener_owned(pid, host, port)
            else:
                module.connection_owned(pid, host, port, (host, client_port))
    else:
        result = module.listener_owned(pid, host, port) if kind == "listener" else module.connection_owned(pid, host, port, (host, client_port))
        assert result is (failure in (None, "retry", "large"))
    if failure == "retry":
        assert len(calls) == 4 and calls[2] > calls[1] and calls[3] > calls[2]
    if failure == "exhausted":
        assert len(calls) == 4


@pytest.mark.parametrize("platform,state,inode,expected", [
    ("linux", "01", "0", None), ("linux", "03", "41", None),
    ("linux", "01", "41", True), ("win32", 4, 0, None), ("win32", 5, 0, None),
])
def test_desktop_pending_connection_rows(tmp_path, monkeypatch, platform, state, inode, expected):
    import rasul_bridge.desktop as module
    from types import SimpleNamespace
    host = socket.inet_ntoa(struct.pack("!I", socket.INADDR_LOOPBACK))
    address = int.from_bytes(socket.inet_aton(host), "little")
    pid = os.getpid()
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform=platform, byteorder=sys.byteorder))
    if platform == "linux":
        root = tmp_path / "proc"
        (root / str(pid) / "fd").mkdir(parents=True)
        (root / str(pid) / "fd" / "1").touch()
        (root / "tcp").write_text(f"header\n0: {address:08X}:0001 {address:08X}:0002 {state} 0 0 0 0 0 {inode}\n")
        path = Path
        monkeypatch.setattr(module, "Path", lambda value: root / "tcp" if value == "/proc/net/tcp"
                            else root if value == "/proc" else path(value))
        monkeypatch.setattr(module, "os", SimpleNamespace(**{**vars(os), "readlink": lambda fd: "socket:[" + inode + "]"}))
    else:
        payload = struct.pack("=I6I", 1, state, address, socket.htons(1), address, socket.htons(2), inode)
        def table(buffer, size, *args):
            ctypes.cast(size, ctypes.POINTER(wintypes.DWORD)).contents.value = len(payload)
            if buffer is None:
                return 122
            ctypes.memmove(buffer, payload, len(payload))
            return 0
        monkeypatch.setattr(module.ctypes, "WinDLL", lambda *args, **kwargs:
                            SimpleNamespace(GetExtendedTcpTable=table), raising=False)
    assert module.connection_owned(pid, host, 1, (host, 2)) is expected


CHILD_BACKEND = r"""
import json, os, socket, struct, sys, time
config = json.loads(sys.stdin.readline())
server = socket.socket()
server.bind((socket.inet_ntoa(struct.pack("!I", socket.INADDR_LOOPBACK)), 0))
server.listen()
print(json.dumps([server.getsockname()[1], os.getpid()]), flush=True)
if config["never"]:
    sys.stdin.readline()
    connection, _ = server.accept()
    connection.settimeout(1)
    try:
        received = connection.recv(65536)
    except (TimeoutError, ConnectionResetError):
        received = b""
    print(len(received), flush=True)
    connection.close()
else:
    while True:
        time.sleep(0.3)
        connection, _ = server.accept()
        stream = connection.makefile("rb")
        while True:
            line = stream.readline()
            if not line:
                break
            target = line.decode().split()[1]
            head = {}
            while (line := stream.readline()) != b"\r\n":
                name, value = line.decode().strip().split(": ", 1)
                head[name] = value
            stream.read(int(head["Content-Length"]))
            if target == "/api/host/identity":
                value = {"ok": True, "pid": os.getpid(), "role": "serve", "protocolVersion": 1}
            elif target.startswith("/api/status?"):
                value = {"hermes_home": config["home"]}
            else:
                value = []
            data = json.dumps(value).encode()
            connection.sendall(f"HTTP/1.1 200 OK\r\nContent-Length: {len(data)}\r\n\r\n".encode() + data)
            if head.get("Connection") == "close":
                break
        stream.close()
        connection.close()
"""

@pytest.mark.parametrize("never", [False, True])
@asynchronous
async def test_desktop_separate_process_accept(tmp_path, monkeypatch, never):
    async with desktop(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.server.close()
        await fake.server.wait_closed()
        child = subprocess.Popen([sys.executable, "-c", CHILD_BACKEND], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            child.stdin.write(json.dumps({"home": str(bridge.config.hermes_home), "never": never}).encode() + b"\n")
            child.stdin.flush()
            fake.port, fake.pid = json.loads(await asyncio.to_thread(child.stdout.readline))
            fake.publish()
            response = await call("GET", "/skills")
            if never:
                assert response == (503, {"error": "desktop_unavailable"})
                output, _ = await asyncio.to_thread(child.communicate, b"inspect\n", timeout=5)
                if sys.platform != "win32":
                    # Linux reports a queued connection as pending, so nothing is sent before accept.
                    assert output.strip() == b"0", "unaccepted connection received request bytes"
                # Windows attributes a queued connection to the listening process itself,
                # so bytes may wait in the verified backend's queue; they reach no other process.
                assert child.returncode == 0
            else:
                assert response == (200, {"skills": []})
        finally:
            if child.poll() is None:
                child.kill()
            await asyncio.to_thread(child.communicate)


@pytest.mark.parametrize("outcome", ["owned", "timeout", "reused"])
@asynchronous
async def test_desktop_pending_wait_same_socket(tmp_path, monkeypatch, outcome):
    import rasul_bridge.desktop as module
    async with desktop(tmp_path, monkeypatch) as (_, fake, call):
        original = module.connection_owned
        fingerprints = module.process_times
        clients = []
        def ownership(pid, host, port, client):
            clients.append(client)
            if outcome == "timeout" or len(clients) <= 2:
                assert not fake.requests
                return None
            if outcome == "reused":
                monkeypatch.setattr(module, "process_times", lambda pid: (fingerprints(pid)[0] + 10000, 1))
            return original(pid, host, port, client)
        monkeypatch.setattr(module, "connection_owned", ownership)
        monkeypatch.setattr(module, "OWNERSHIP_WAIT", 0.05 if outcome == "timeout" else 2, raising=False)
        response = await call("GET", "/skills")
        assert len(clients) >= 3
        assert clients[0] == clients[1] == clients[2]
        if outcome == "owned":
            assert response[0] == 200
        else:
            assert len(set(clients)) == 1
            assert not fake.requests
            assert response == (503, {"error": "desktop_unavailable"})
