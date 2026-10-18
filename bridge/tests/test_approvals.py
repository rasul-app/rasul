import asyncio
from collections import deque
from contextlib import asynccontextmanager
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import random
import secrets
import socket
import sys
import threading
import time
import types
import uuid

import pytest
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from rasul_bridge.cli import initialize
from rasul_bridge.config import Config
from rasul_bridge.http import chunk, headers
from rasul_bridge.server import Bridge
from rasul_bridge.storage import Store
from test_bridge import (FakeUpstream, asynchronous, client, read_response, registered, running, send)


def payload(request_id=None, profile="default", **extra):
    return {"request_id": request_id or uuid.uuid4().hex, "profile": profile,
            "session_key": "sample-session", "surface": "gateway", "command": "sample action",
            "description": "sample reason", "allow_session": True, "allow_permanent": True,
            "expires_at": time.time() + 300, **extra}


async def local(bridge, method, suffix="", value=None, secret=True, profile="default"):
    reader, writer = await asyncio.open_connection("127.0.0.1", bridge.approvals.port)
    try:
        authorization = ""
        if secret is not None:
            credential = (bridge.approvals.credential(profile).decode() if secret is True else secret)
            path = f"/internal/profiles/{profile}/approvals{suffix}"
            data = b"" if value is None else json.dumps(value).encode()
            head = request_headers(credential.encode(), method, path, data, secrets.token_hex(16), str(int(time.time())))
            authorization = "".join(k + ": " + v + "\r\n" for k, v in head.items())
        body = b"" if value is None else json.dumps(value).encode()
        writer.write((f"{method} /internal/profiles/{profile}/approvals{suffix} HTTP/1.1\r\n"
                      f"Host: example.invalid\r\n{authorization}Content-Length: {len(body)}\r\n\r\n").encode() + body)
        await writer.drain()
        head = (await reader.readuntil(b"\r\n\r\n")).split(b"\r\n")
        data = await reader.readexactly(int(headers(head[1:-2])["content-length"]))
        return int(head[0].split()[1]), json.loads(data)
    finally:
        writer.close()
        await writer.wait_closed()


async def phone(bridge, tmp_path, identity, method, suffix="", value=None, profile="default", extra=""):
    async with client(bridge, tmp_path, identity) as (link, reader, _):
        await send(link, method, f"/v1/profiles/{profile}/approvals{suffix}", value, extra)
        return await read_response(reader)


@asynccontextmanager
async def running_profiles(tmp_path, names):
    upstream = FakeUpstream()
    await upstream.start()
    store = Store(tmp_path / "data")
    initialize(store)
    config = Config(hermes_url=f"http://127.0.0.1:{upstream.port}", port=0,
                    hermes_home=tmp_path / "upstream", approvals_port=0)
    config.hermes_home.mkdir()
    for name in names:
        (config.hermes_home / "profiles" / name).mkdir(parents=True)
    bridge = Bridge(store, config, random_source=random.Random(148))
    bridge.profiles.init_keys()
    upstream.expected_key = lambda target: bridge.profiles.key(
        target.split("/")[2] if target.startswith("/p/") else "default")
    await bridge.start()
    try:
        async with asyncio.timeout(120):
            yield bridge, upstream
    finally:
        await bridge.close()
        await upstream.close()


@asynchronous
async def test_approvals_disabled_preserves_existing_routes(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        assert bridge.approvals is None
        assert not (bridge.store.root / "approval-secrets").exists()
        assert await phone(bridge, tmp_path, identity, "GET") == (404, {"error": "Route not found"})
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/hermes/health")
            assert await read_response(reader) == (200, {"status": "ok"})
        assert upstream.requests[-1][1] == "/health"


@asynchronous
async def test_approvals_list_answer_exact_id_duplicate_audit_and_resolve(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, device = registered(bridge)
        first, second = payload(), payload()
        for value in (first, second):
            assert (await local(bridge, "POST", value=value))[0] == 200
        assert (await phone(bridge, tmp_path, identity, "GET"))[1] == {"approvals": [first, second]}
        suffix = "/" + second["request_id"] + "/answer"
        assert await phone(bridge, tmp_path, identity, "POST", suffix, {"choice": "once"}) == (
            200, {"request_id": second["request_id"], "state": "answered"})
        assert (await phone(bridge, tmp_path, identity, "POST", suffix, {"choice": "deny"}))[0] == 409
        assert (await phone(bridge, tmp_path, identity, "GET"))[1] == {"approvals": [first]}
        assert (await local(bridge, "GET", "/answers"))[1] == {"answers": [
            {"request_id": second["request_id"], "session_key": second["session_key"], "choice": "once"}]}
        assert (await local(bridge, "POST", "/" + second["request_id"] + "/resolved", {}))[0] == 200
        assert (await local(bridge, "GET", "/answers"))[1] == {"answers": []}
        raw = bridge.audit.path.read_text()
        assert first["command"] not in raw and first["description"] not in raw
        audit = [json.loads(line) for line in raw.splitlines() if "choice" in json.loads(line)]
        assert len(audit) == 2
        assert audit[0]["device_id"] == device
        assert audit[0]["profile_id"] == "default"
        assert audit[0]["request_id"] == second["request_id"]
        assert audit[0]["choice"] == "once"
        assert [row["status"] for row in audit] == [200, 409]
        assert "payload" not in bridge.approvals.entries[("default", second["request_id"])]
        assert all(first["command"] not in json.dumps(event) for event in bridge.approvals.history)


@pytest.mark.parametrize("allow_session,allow_permanent,choice,expected", [
    (False, False, "session", 400), (False, False, "always", 400),
    (True, False, "always", 400), (False, True, "always", 400),
    (True, True, "other", 400), (False, False, "once", 200),
    (False, False, "deny", 200), (True, False, "session", 200),
    (True, True, "always", 200),
])
@asynchronous
async def test_approvals_allowed_choices(tmp_path, allow_session, allow_permanent, choice, expected):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, _ = registered(bridge)
        value = payload(allow_session=allow_session, allow_permanent=allow_permanent)
        await local(bridge, "POST", value=value)
        status, result = await phone(bridge, tmp_path, identity, "POST", "/" + value["request_id"] + "/answer",
                                     {"choice": choice})
        assert status == expected
        if expected == 400:
            assert result == {"error": "approvals_invalid_choice"}


@asynchronous
async def test_approvals_profile_isolation_expiry_and_pc_resolution(tmp_path):
    async with running_profiles(tmp_path, ("sample",)) as (bridge, _):
        identity, _ = registered(bridge)
        foreign = payload(profile="sample")
        assert (await local(bridge, "POST", value=foreign, profile="sample"))[0] == 200
        value = payload()
        assert (await local(bridge, "POST", value=value))[0] == 200
        suffix = "/" + value["request_id"] + "/answer"
        assert (await phone(bridge, tmp_path, identity, "POST", "/" + uuid.uuid4().hex + "/answer",
                            {"choice": "once"}))[0] == 404
        assert (await phone(bridge, tmp_path, identity, "GET", profile="sample"))[1] == {"approvals": [foreign]}
        assert (await phone(bridge, tmp_path, identity, "POST",
                            "/" + foreign["request_id"] + "/answer", {"choice": "once"}))[0] == 404
        assert (await phone(bridge, tmp_path, identity, "GET"))[1] == {"approvals": [value]}
        assert (await phone(bridge, tmp_path, identity, "POST", suffix, {"choice": "once"}, profile="sample"))[0] == 404
        assert (await local(bridge, "GET", "/answers", profile="sample")) == (200, {"answers": []})
        assert (await local(bridge, "GET", "/answers", secret=bridge.approvals.credential("default").decode(),
                            profile="sample")) == (401, {"error": "approvals_unauthorized"})
        assert (await local(bridge, "GET", "/answers",
                            secret=bridge.approvals.credential("sample").decode()))[0] == 401
        assert (await phone(bridge, tmp_path, identity, "POST",
                            "/" + foreign["request_id"] + "/answer", {"choice": "deny"},
                            profile="sample")) == (
            200, {"request_id": foreign["request_id"], "state": "answered"})
        assert (await local(bridge, "GET", "/answers", profile="sample"))[1] == {"answers": [
            {"request_id": foreign["request_id"], "session_key": foreign["session_key"], "choice": "deny"}]}
        assert (await local(bridge, "GET", "/answers"))[1] == {"answers": []}
        assert (await local(bridge, "POST", "/" + foreign["request_id"] + "/resolved", {},
                            profile="sample")) == (200, {"request_id": foreign["request_id"],
                                                         "state": "resolved"})
        assert (await local(bridge, "GET", "/answers", profile="sample"))[1] == {"answers": []}
        bridge.approvals.clock = lambda: value["expires_at"] + 1
        assert (await phone(bridge, tmp_path, identity, "GET"))[1] == {"approvals": []}
        assert (await phone(bridge, tmp_path, identity, "POST", suffix, {"choice": "once"}))[0] == 410
        assert "payload" not in bridge.approvals.entries[("default", value["request_id"])]
        bridge.approvals.clock = time.time
        value = payload()
        await local(bridge, "POST", value=value)
        assert (await local(bridge, "POST", "/" + value["request_id"] + "/resolved", {}))[0] == 200
        assert (await phone(bridge, tmp_path, identity, "GET"))[1] == {"approvals": []}
        assert (await phone(bridge, tmp_path, identity, "POST", "/" + value["request_id"] + "/answer",
                            {"choice": "once"}))[0] == 409


@asynchronous
async def test_approvals_internal_listener_and_credentials(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, _ = registered(bridge)
        assert bridge.approvals.port != bridge.port
        assert {s.getsockname()[0] for s in bridge.approvals.server.sockets} == {"127.0.0.1"}
        for secret in (None, "invalid"):
            assert await local(bridge, "POST", value=payload(), secret=secret) == (
                401, {"error": "approvals_unauthorized"})
        for who, expected in ((None, 403), (identity, 404)):
            async with client(bridge, tmp_path, who) as (link, reader, _):
                value = payload()
                path = "/internal/profiles/default/approvals"
                signed = request_headers(bridge.approvals.credential("default"), "POST", path,
                                         json.dumps(value).encode(), secrets.token_hex(16), str(int(time.time())))
                extra = "".join(k + ": " + v + "\r\n" for k, v in signed.items())
                await send(link, "POST", path, value, extra)
                assert (await read_response(reader))[0] == expected
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/approvals")
            assert (await read_response(reader))[0] == 403
        with pytest.raises(InvalidStatus) as failure:
            async with connect(f"ws://127.0.0.1:{bridge.port}/internal/profiles/default/approvals", proxy=None):
                pass
        assert failure.value.response.status_code == 404
        path = bridge.approvals.secret_path("default")
        assert path.parent.parent == bridge.store.root
        credential = path.read_bytes()
        assert len(credential) == 43
        assert bridge.approvals.credential("default") == credential
        if os.name != "nt":
            assert path.stat().st_mode & 0o777 == 0o600
            assert path.parent.stat().st_mode & 0o777 == 0o700
        else:
            import ctypes
            from ctypes import wintypes as w
            security = ctypes.WinDLL("advapi32", use_last_error=True)
            security.GetNamedSecurityInfoW.argtypes = [w.LPWSTR, w.DWORD, w.DWORD,
                ctypes.POINTER(w.LPVOID), ctypes.POINTER(w.LPVOID), ctypes.POINTER(w.LPVOID),
                ctypes.POINTER(w.LPVOID), ctypes.POINTER(w.LPVOID)]
            dacl, descriptor = w.LPVOID(), w.LPVOID()
            assert security.GetNamedSecurityInfoW(str(path), 1, 4, None, None, ctypes.byref(dacl),
                                                 None, ctypes.byref(descriptor)) == 0
            try:
                class ACL(ctypes.Structure):
                    _fields_ = [("revision", w.BYTE), ("reserved", w.BYTE), ("size", w.WORD),
                                ("count", w.WORD), ("reserved2", w.WORD)]
                assert ctypes.cast(dacl, ctypes.POINTER(ACL)).contents.count == 1
            finally:
                from rasul_bridge.storage import windows_api
                windows_api()[0].LocalFree(descriptor)


@asynchronous
async def test_approvals_rate_limit_per_device(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, device = registered(bridge)
        bridge.approvals.rates[device] = deque([time.time()] * 60)
        assert (await phone(bridge, tmp_path, identity, "GET"))[0] == 429
        other, _ = registered(bridge)
        assert (await phone(bridge, tmp_path, other, "GET"))[0] == 200


@asynchronous
async def test_approvals_rejected_answers_audit_identifiers_without_arbitrary_text(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, device = registered(bridge)
        value = payload(allow_session=False, allow_permanent=False)
        await local(bridge, "POST", value=value)
        suffix = "/" + value["request_id"] + "/answer"
        assert (await phone(bridge, tmp_path, identity, "POST", suffix, {"choice": "session"}))[0] == 400
        assert (await phone(bridge, tmp_path, identity, "POST", suffix,
                            {"choice": "sample-private-value"}))[0] == 400
        raw = bridge.audit.path.read_text()
        assert "sample-private-value" not in raw and value["command"] not in raw
        records = [json.loads(line) for line in raw.splitlines() if "request_id" in json.loads(line)]
        assert len(records) == 2
        assert all(r["device_id"] == device and r["profile_id"] == "default"
                   and r["request_id"] == value["request_id"] and r["status"] == 400 for r in records)
        assert [r["choice"] for r in records] == ["session", "invalid"]


@asynchronous
async def test_approvals_malformed_answers_still_use_device_rate_limit(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, device = registered(bridge)
        value = payload()
        await local(bridge, "POST", value=value)
        bridge.approvals.rates[device] = deque([time.time()] * 60)
        assert (await phone(bridge, tmp_path, identity, "POST", "/" + value["request_id"] + "/answer",
                            b"invalid"))[0] == 429


async def sse(reader):
    length = int((await reader.until()).strip(), 16)
    value = await reader.exact(length)
    assert await reader.exact(2) == b"\r\n"
    return value


@asynchronous
async def test_approvals_sse_new_and_resolved_profile_scoped(tmp_path):
    async with running_profiles(tmp_path, ("sample",)) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/approvals/events")
            assert (await reader.until(b"\r\n\r\n")).startswith(b"HTTP/1.1 200")
            assert b"event: snapshot" in await sse(reader)
            foreign = payload(profile="sample")
            assert (await local(bridge, "POST", value=foreign, profile="sample"))[0] == 200
            value = payload()
            assert (await local(bridge, "POST", value=value))[0] == 200
            async with asyncio.timeout(2):
                event = await sse(reader)
            assert b"event: new" in event and value["request_id"].encode() in event
            assert value["command"].encode() not in event
            assert foreign["request_id"].encode() not in event
            async with client(bridge, tmp_path, identity) as (other_link, other_reader, _):
                await send(other_link, "GET", "/v1/profiles/sample/approvals/events")
                assert (await other_reader.until(b"\r\n\r\n")).startswith(b"HTTP/1.1 200")
                async with asyncio.timeout(2):
                    snapshot = await sse(other_reader)
                assert b"event: snapshot" in snapshot and foreign["request_id"].encode() in snapshot
                assert value["request_id"].encode() not in snapshot
                assert (await local(bridge, "POST", "/" + foreign["request_id"] + "/resolved", {},
                                    profile="sample"))[0] == 200
                assert (await local(bridge, "POST", "/" + value["request_id"] + "/resolved", {}))[0] == 200
                async with asyncio.timeout(2):
                    event = await sse(reader)
                assert b"event: resolved" in event and value["request_id"].encode() in event
                assert foreign["request_id"].encode() not in event
                async with asyncio.timeout(2):
                    event = await sse(other_reader)
                assert b"event: resolved" in event and foreign["request_id"].encode() in event
                assert value["request_id"].encode() not in event


@asynchronous
async def test_approvals_sse_recovery_snapshot_cannot_skip_published_approval(tmp_path, monkeypatch):
    release, blocked, snapshots = asyncio.Event(), asyncio.Event(), []

    async def gated(link, data):
        if data.startswith(b"event: snapshot"):
            snapshots.append(data)
            if len(snapshots) == 2:
                blocked.set()
                await release.wait()
        await chunk(link, data)

    monkeypatch.setattr("rasul_bridge.approvals.chunk", gated)
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/approvals/events")
            assert (await reader.until(b"\r\n\r\n")).startswith(b"HTTP/1.1 200")
            assert b"event: snapshot" in await sse(reader)
            for _ in range(bridge.approvals.history.maxlen + 1):
                bridge.approvals.publish("default", payload())
            async with asyncio.timeout(2):
                await blocked.wait()
            late = payload()
            bridge.approvals.publish("default", late)
            release.set()
            async with asyncio.timeout(2):
                while True:
                    frame = await sse(reader)
                    if late["request_id"].encode() in frame:
                        break
            assert late["request_id"].encode() not in snapshots[1]


@pytest.mark.parametrize("change", [{"extra": 1}, {"allow_session": 1}, {"expires_at": float("nan")},
                                    {"request_id": "invalid"}, {"session_key": ""}])
@asynchronous
async def test_approvals_reject_malformed_internal_payload(tmp_path, change):
    async with running(tmp_path, approvals=True) as (bridge, _):
        assert (await local(bridge, "POST", value=payload(**change)))[0] == 400


@pytest.fixture
def plugin():
    path = Path(__file__).resolve().parents[2] / "hermes-plugin" / "rasul-approvals" / "__init__.py"
    spec = importlib.util.spec_from_file_location("approval_plugin_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def approval_module(monkeypatch):
    module = types.ModuleType("tools.approval")
    module.entries, module.lock, module.resolved = {}, threading.Lock(), []

    def enqueue(session_key, value):
        entry = types.SimpleNamespace(data=dict(value), event=threading.Event(), result=None)
        with module.lock:
            module.entries.setdefault(session_key, []).append(entry)
        return entry

    def snapshots(session_key):
        with module.lock:
            return [dict(e.data) for e in module.entries.get(session_key, [])]

    def resolve(session_key, choice, *, request_id):
        with module.lock:
            entries = module.entries.get(session_key, [])
            for entry in entries:
                if entry.data["request_id"] == request_id:
                    entries.remove(entry)
                    entry.result = choice
                    module.resolved.append((session_key, choice, request_id))
                    entry.event.set()
                    return 1
        return 0

    module.enqueue, module.list_gateway_approvals, module.resolve_gateway_approval = enqueue, snapshots, resolve
    context = types.ModuleType("tools.approval_context")
    context._get_approval_timeout = lambda: 300
    redactor = types.ModuleType("agent.redact")
    redactor.calls = []

    def redact(text, *, force, redact_url_credentials):
        redactor.calls.append((text, force, redact_url_credentials))
        assert force is True and redact_url_credentials is True
        return text.replace("sample-private-value", "[redacted]")

    redactor.redact_sensitive_text = redact
    monkeypatch.setitem(sys.modules, "tools", types.ModuleType("tools"))
    monkeypatch.setitem(sys.modules, "tools.approval", module)
    monkeypatch.setitem(sys.modules, "tools.approval_context", context)
    monkeypatch.setitem(sys.modules, "agent", types.ModuleType("agent"))
    monkeypatch.setitem(sys.modules, "agent.redact", redactor)
    module.redactor = redactor
    return module


def mirror(plugin, tmp_path):
    path = tmp_path / "sample.secret"
    path.write_text(secrets.token_urlsafe(32), encoding="ascii")
    return plugin.Mirror("default", path)


def test_plugin_hook_queues_redacted_payload_without_network(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    value = payload(command="sample-private-value", description="sample-private-value")
    entry = approval_module.enqueue("sample-session", value)
    instance.request = lambda *a, **kw: pytest.fail("Hook performed network I/O")
    start = time.monotonic()
    instance.on_request(session_key="sample-session", surface="gateway")
    assert time.monotonic() - start < 0.2
    entry.data["command"] = "changed after hook"
    entry.data["allow_session"] = False
    posted = instance.queue.get_nowait()
    assert posted == {**value, "command": "[redacted]", "description": "[redacted]",
                      "expires_at": posted["expires_at"]}
    assert 299 < posted["expires_at"] - time.time() <= 300
    assert len(approval_module.redactor.calls) == 2


def test_plugin_unconfigured_register_keeps_old_path(plugin):
    class Context:
        def get_config(self, key):
            assert key == "secret_path"
            return None

        def register_hook(self, *args):
            pytest.fail("Unconfigured plugin registered a hook")

    assert plugin.register(Context()) is None


def test_plugin_registers_observer_only_and_unload_stops_worker(plugin, approval_module, tmp_path):
    path = tmp_path / "sample.secret"
    path.write_text(secrets.token_urlsafe(32), encoding="ascii")

    class Context:
        profile_name = "default"

        def __init__(self):
            self.hooks, self.cleanup = {}, None

        def get_config(self, key, default=None):
            return str(path) if key == "secret_path" else default

        def on_unload(self, callback):
            self.cleanup = callback

        def register_hook(self, name, callback):
            self.hooks[name] = callback

        def register_approval_transport(self, *args):
            pytest.fail("Observer plugin registered a transport")

    context = Context()
    instance = plugin.register(context)
    try:
        assert instance.thread.is_alive()
        assert set(context.hooks) == {"pre_approval_request"}
    finally:
        context.cleanup()
    assert not instance.thread.is_alive()


def test_plugin_redaction_failure_skips_publication(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    entry = approval_module.enqueue("sample-session", payload())

    def failure(*args, **kwargs):
        raise RuntimeError("sample failure")

    approval_module.redactor.redact_sensitive_text = failure
    instance.on_request(session_key="sample-session", surface="gateway")
    assert instance.queue.empty() and not instance.tracked
    assert not entry.event.is_set()


def test_plugin_exact_entry_wakes_real_waiter_and_ignores_foreign_answer(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    first, target, foreign = payload(), payload(), payload()
    first_entry = approval_module.enqueue("sample-session", first)
    entry = approval_module.enqueue("sample-session", target)
    foreign_entry = approval_module.enqueue("other-session", foreign)
    started, finished = threading.Event(), threading.Event()

    def wait():
        started.set()
        if entry.event.wait(3):
            finished.set()

    waiter = threading.Thread(target=wait)
    waiter.start()
    assert started.wait(1)
    calls = []

    def request(method, suffix="", value=None):
        calls.append((method, suffix, value))
        if method == "GET":
            return {"answers": [
                {"request_id": foreign["request_id"], "session_key": "other-session", "choice": "once"},
                {"request_id": target["request_id"], "session_key": "sample-session", "choice": "deny"}]}
        return {}

    instance.request = request
    try:
        instance.on_request(session_key="sample-session", surface="gateway")
        instance.tick()
        assert finished.wait(1)
        assert entry.result == "deny" and not first_entry.event.is_set() and not foreign_entry.event.is_set()
        assert approval_module.resolved == [("sample-session", "deny", target["request_id"])]
        assert ("POST", "/" + target["request_id"] + "/resolved", {}) in calls
    finally:
        entry.event.set()
        waiter.join(timeout=3)
    assert not waiter.is_alive()


def test_plugin_bridge_down_is_skipped_and_logged_once(plugin, approval_module, tmp_path, caplog):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)
    with socket.socket() as unavailable:
        unavailable.bind(("127.0.0.1", 0))
        instance.port = unavailable.getsockname()[1]
        start = time.monotonic()
        for _ in range(2):
            instance.on_request(session_key="sample-session", surface="gateway")
            instance.tick()
        assert time.monotonic() - start < 2
    assert len(caplog.records) == 1
    assert not entry.event.is_set() and approval_module.resolved == []
    assert set(instance.tracked) == instance.withdrawals == {value["request_id"]}


@pytest.mark.parametrize("surface,extra", [("cli", {}), ("smart", {}), ("transport:sample", {}),
                                         ("gateway", {"coalesced": True})])
def test_plugin_skips_unsupported_surfaces(plugin, approval_module, tmp_path, surface, extra):
    instance = mirror(plugin, tmp_path)
    approval_module.enqueue("sample-session", payload())
    instance.on_request(session_key="sample-session", surface=surface, **extra)
    assert instance.queue.empty() and not instance.tracked


def test_plugin_pc_answer_reports_resolved_without_changing_choice(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)
    calls = []
    answers = []
    instance.request = lambda method, suffix="", value=None: (
        calls.append((method, suffix)) or ({"answers": answers} if method == "GET" else {}))
    instance.on_request(session_key="sample-session", surface="gateway")
    instance.tick()
    approval_module.resolve_gateway_approval("sample-session", "once", request_id=value["request_id"])
    answers.append({"request_id": value["request_id"], "session_key": "sample-session", "choice": "deny"})
    instance.tick()
    assert entry.result == "once"
    assert approval_module.resolved == [("sample-session", "once", value["request_id"])]
    assert ("POST", "/" + value["request_id"] + "/resolved") in calls
    assert not instance.tracked


def test_plugin_unload_during_answer_poll_does_not_resolve(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)

    def request(method, suffix="", value=None):
        if method == "GET":
            instance.stop.set()
            return {"answers": [{"request_id": entry.data["request_id"],
                                  "session_key": "sample-session", "choice": "once"}]}
        return {}

    instance.request = request
    instance.on_request(session_key="sample-session", surface="gateway")
    instance.tick()
    assert not entry.event.is_set() and not approval_module.resolved


@asynchronous
async def test_plugin_reads_bounded_multi_process_profile_answers(tmp_path, plugin):
    async with running(tmp_path, approvals=True) as (bridge, _):
        for _ in range(500):
            value = payload(session_key="s" * 512)
            bridge.approvals.publish("default", value)
            bridge.approvals.entries[("default", value["request_id"])].update(state="answered", choice="once")
        instance = plugin.Mirror("default", bridge.approvals.secret_path("default"), bridge.approvals.port)
        result = await asyncio.to_thread(instance.request, "GET", "/answers")
        assert len(result["answers"]) == 500


@asynchronous
async def test_plugin_round_trip_with_local_listener_and_waiter(tmp_path, plugin, approval_module):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, _ = registered(bridge)
        value = payload(command="sample-private-value")
        entry = approval_module.enqueue("sample-session", value)
        instance = plugin.Mirror("default", bridge.approvals.secret_path("default"), bridge.approvals.port)
        instance.start()
        try:
            instance.on_request(session_key="sample-session", surface="gateway")
            async with asyncio.timeout(3):
                while not bridge.approvals.pending("default")["approvals"]:
                    await asyncio.sleep(0.01)
            assert bridge.approvals.pending("default")["approvals"][0]["command"] == "[redacted]"
            assert (await phone(bridge, tmp_path, identity, "POST", "/" + value["request_id"] + "/answer",
                                {"choice": "once"}))[0] == 200
            assert await asyncio.to_thread(entry.event.wait, 3)
            assert entry.result == "once"
            async with asyncio.timeout(3):
                while bridge.approvals.entries[("default", value["request_id"])]["state"] != "resolved":
                    await asyncio.sleep(0.01)
        finally:
            await asyncio.to_thread(instance.close)
        assert not instance.thread.is_alive()


def request_headers(secret, method, path, body, nonce, timestamp):
    message = "\n".join(("plugin", method, path, hashlib.sha256(body).hexdigest(), nonce, timestamp))
    return {"X-Approval-Nonce": nonce, "X-Approval-Timestamp": timestamp,
            "X-Approval-Mac": hmac.new(secret, message.encode("ascii"), "sha256").hexdigest()}


def response_mac(secret, nonce, body):
    message = "\n".join(("bridge", nonce, hashlib.sha256(body).hexdigest()))
    return hmac.new(secret, message.encode("ascii"), "sha256").hexdigest()


async def signed_local(bridge, method="GET", suffix="/answers", *, nonce=None, timestamp=None,
                       body=b"", change=None):
    path = "/internal/profiles/default/approvals" + suffix
    head = request_headers(bridge.approvals.credential("default"), method, path, body,
                           nonce or secrets.token_hex(16), timestamp or str(int(time.time())))
    head.update(change or {})
    reader, writer = await asyncio.open_connection("127.0.0.1", bridge.approvals.port)
    try:
        raw = "".join(k + ": " + v + "\r\n" for k, v in head.items())
        writer.write((f"{method} {path} HTTP/1.1\r\nHost: example.invalid\r\n{raw}"
                      f"Content-Length: {len(body)}\r\n\r\n").encode() + body)
        await writer.drain()
        result = (await reader.readuntil(b"\r\n\r\n")).split(b"\r\n")
        return int(result[0].split()[1])
    finally:
        writer.close()
        await writer.wait_closed()


@asynchronous
async def test_approvals_request_mac_replay_rejected(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        nonce = secrets.token_hex(16)
        assert await signed_local(bridge, nonce=nonce) == 200
        assert await signed_local(bridge, nonce=nonce) == 401


@pytest.mark.parametrize("offset", [-61, 61])
@asynchronous
async def test_approvals_request_clock_skew_rejected(tmp_path, offset):
    async with running(tmp_path, approvals=True) as (bridge, _):
        bridge.approvals.clock = lambda: 2000000000
        assert await signed_local(bridge, timestamp=str(2000000000 + offset)) == 401
        assert await signed_local(bridge, timestamp=str(2000000000)) == 200


@pytest.mark.parametrize("change", [{"X-Approval-Timestamp": "20000 00000"},
                                    {"X-Approval-Timestamp": "02000000000"},
                                    {"X-Approval-Nonce": "invalid"},
                                    {"X-Approval-Mac": "invalid"}])
@asynchronous
async def test_approvals_request_mac_headers_strict(tmp_path, change):
    async with running(tmp_path, approvals=True) as (bridge, _):
        assert await signed_local(bridge, change=change) == 401


@asynccontextmanager
async def listener(instance, answer, attack="unsigned", phase="/answers"):
    received, tasks = [], set()
    old_answer = None

    async def handle(reader, writer):
        nonlocal old_answer
        task = asyncio.current_task()
        tasks.add(task)
        try:
            while True:
                raw = (await reader.readuntil(b"\r\n\r\n")).split(b"\r\n")
                method, path, _ = raw[0].decode().split(" ")
                head = headers(raw[1:-2])
                body = await reader.readexactly(int(head.get("content-length", "0")))
                received.append((method, path, head, body, writer.get_extra_info("peername")))
                value = {"answers": [answer]} if path.endswith("/answers") else {}
                data = json.dumps(value).encode()
                nonce = head.get("x-approval-nonce", "")
                signature = ""
                if attack != "unsigned":
                    signature = response_mac(instance.secret.encode(), nonce, data)
                    if (path == instance.base if phase == "" else path.endswith(phase)):
                        if attack == "replay":
                            signature = response_mac(instance.secret.encode(), "0" * 32, data)
                        elif attack == "tamper":
                            data += b" "
                        elif attack == "reflection":
                            signature = head.get("x-approval-mac", "")
                        elif attack == "old_answer":
                            if old_answer is None:
                                old_answer = (data, signature)
                            else:
                                data, signature = old_answer
                closing = "Connection: close\r\n" if attack == "close_hello" else ""
                writer.write((f"HTTP/1.1 200 OK\r\nContent-Length: {len(data)}\r\n"
                              f"{closing}X-Approval-Mac: {signature}\r\n\r\n").encode() + data)
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()
            tasks.discard(task)

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    instance.port = server.sockets[0].getsockname()[1]
    try:
        yield received
    finally:
        server.close()
        await server.wait_closed()
        for task in list(tasks):
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


@asynchronous
async def test_plugin_fake_listener_receives_no_secret_and_cannot_resolve(tmp_path, plugin, approval_module, caplog):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)
    answer = {"request_id": value["request_id"], "session_key": "sample-session", "choice": "once"}
    async with listener(instance, answer) as received:
        for _ in range(2):
            instance.on_request(session_key="sample-session", surface="gateway")
            await asyncio.to_thread(instance.tick)
    assert received
    wire = repr(received).encode()
    assert bool(instance.secret.encode() not in wire)
    assert value["request_id"].encode() not in wire and value["session_key"].encode() not in wire
    assert all(row[1].endswith("/hello") and row[3] == b"" for row in received)
    assert not entry.event.is_set() and entry.result is None
    assert approval_module.list_gateway_approvals("sample-session") == [value]
    assert approval_module.resolved == []
    assert set(instance.tracked) == instance.withdrawals == {value["request_id"]}
    assert len(caplog.records) == 1 and value["command"] not in caplog.text
    assert bool(instance.secret not in caplog.text)


@pytest.mark.parametrize("attack", ["replay", "tamper", "reflection"])
@pytest.mark.parametrize("phase", ["/hello", "", "/answers", "/resolved"])
@asynchronous
async def test_plugin_rejects_unbound_response_before_using_fields(tmp_path, plugin, approval_module, attack, phase):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)
    answer = {"request_id": value["request_id"], "session_key": "sample-session", "choice": "once"}
    suffix = "/" + value["request_id"] + "/resolved" if phase == "/resolved" else phase
    async with listener(instance, answer, attack, phase) as received:
        with pytest.raises(OSError):
            await asyncio.to_thread(instance.request, "GET" if phase in ("/hello", "/answers") else "POST",
                                    suffix if phase != "/hello" else "/answers",
                                    None if phase in ("/hello", "/answers") else {})
    assert not entry.event.is_set() and not approval_module.resolved
    if phase == "/hello":
        assert len(received) == 1 and received[0][3] == b""
    else:
        assert len(received) == 2 and received[0][4] == received[1][4]


@asynchronous
async def test_approvals_request_mac_binds_body_method_and_path(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        value = payload()
        body = json.dumps(value).encode()
        path = "/internal/profiles/default/approvals"
        head = request_headers(bridge.approvals.credential("default"), "POST", path, body,
                               secrets.token_hex(16), str(int(time.time())))
        assert await signed_local(bridge, "POST", "", body=body + b" ", change=head) == 401
        assert await signed_local(bridge, "GET", "", body=body, change=head) == 401
        assert await signed_local(bridge, "POST", "/answers", body=body, change=head) == 401
        assert not bridge.approvals.entries
        assert await signed_local(bridge, "POST", "", body=body, change=head) == 200


@asynchronous
async def test_approvals_nonce_cache_retains_future_dated_requests_and_refuses_saturation(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        now = 2000000000
        bridge.approvals.clock = lambda: now
        nonce = secrets.token_hex(16)
        assert await signed_local(bridge, nonce=nonce, timestamp=str(now + 60)) == 200
        now += 120
        assert await signed_local(bridge, nonce=nonce, timestamp=str(now - 60)) == 401
        assert await signed_local(bridge, timestamp=str(now - 60)) == 200
        bridge.approvals.nonces = {("default", str(i)): now + 60 for i in range(8192)}
        assert await signed_local(bridge, timestamp=str(now)) == 401
        now += 61
        assert await signed_local(bridge, timestamp=str(now)) == 200


@pytest.mark.parametrize("attack", ["tamper", "old_answer"])
@asynchronous
async def test_plugin_untrusted_answer_keeps_live_entry_pending(tmp_path, plugin, approval_module, attack, caplog):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)
    answer = {"request_id": value["request_id"], "session_key": "sample-session", "choice": "once"}
    async with listener(instance, answer, attack) as received:
        if attack == "old_answer":
            previous = await asyncio.to_thread(instance.request, "GET", "/answers")
            assert previous == {"answers": [answer]}
        instance.on_request(session_key="sample-session", surface="gateway")
        with pytest.raises(OSError):
            await asyncio.to_thread(instance.tick)
        instance.warn()
        instance.warn()
    assert not entry.event.is_set() and entry.result is None
    assert approval_module.list_gateway_approvals("sample-session") == [value]
    assert approval_module.resolved == []
    assert len(caplog.records) == 1
    assert bool(instance.secret not in caplog.text) and value["command"] not in caplog.text


@asynchronous
async def test_plugin_closed_signed_hello_never_reconnects_to_publish(tmp_path, plugin):
    instance = mirror(plugin, tmp_path)
    async with listener(instance, {}, "close_hello") as received:
        with pytest.raises(OSError):
            await asyncio.to_thread(instance.request, "POST", "", payload())
    assert len(received) == 1 and received[0][1].endswith("/hello") and received[0][3] == b""


@asynchronous
async def test_approvals_rechecks_gap_after_every_snapshot(tmp_path, monkeypatch):
    release, blocked, snapshots = asyncio.Event(), asyncio.Event(), []

    async def gated(link, data):
        if data.startswith(b"event: snapshot"):
            snapshots.append(data)
            if len(snapshots) == 2:
                blocked.set()
                await release.wait()
        await chunk(link, data)

    monkeypatch.setattr("rasul_bridge.approvals.chunk", gated)
    async with running(tmp_path, approvals=True) as (bridge, _):
        bridge.approvals.history = deque(maxlen=4)
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/approvals/events")
            await reader.until(b"\r\n\r\n")
            await sse(reader)
            for _ in range(5):
                bridge.approvals.publish("default", payload())
            async with asyncio.timeout(2):
                await blocked.wait()
            late = payload()
            bridge.approvals.publish("default", late)
            for _ in range(bridge.approvals.history.maxlen):
                bridge.approvals.publish("default", payload())
            release.set()
            async with asyncio.timeout(2):
                while late["request_id"].encode() not in await sse(reader):
                    pass
            assert late["request_id"].encode() not in snapshots[1]


@pytest.mark.parametrize("failure", [OSError, EOFError])
@asynchronous
async def test_approvals_recorded_answer_audits_success_after_disconnect(tmp_path, failure):
    from rasul_bridge.approvals import PHONE_ROUTE

    class Disconnected:
        async def write(self, data):
            raise failure("sample disconnect")

    async with running(tmp_path, approvals=True) as (bridge, _):
        value, records = payload(), []
        bridge.approvals.publish("default", value)
        bridge.approvals.audit = lambda *args: records.append(args)
        match = PHONE_ROUTE.fullmatch("/v1/profiles/default/approvals/" + value["request_id"] + "/answer")
        with pytest.raises(failure):
            await bridge.approvals.phone(Disconnected(), "sample-device", "POST", match, b'{"choice":"once"}')
        assert bridge.approvals.entries[("default", value["request_id"])]["state"] == "answered"
        assert records[0][-1] == 200


@pytest.mark.parametrize("flags", [{}, {"allow_session": None, "allow_permanent": None},
                                    {"allow_session": 1, "allow_permanent": 1}])
def test_plugin_missing_or_nonboolean_allow_flags_fail_closed(plugin, approval_module, tmp_path, flags):
    instance = mirror(plugin, tmp_path)
    value = payload()
    del value["allow_session"], value["allow_permanent"]
    value.update(flags)
    approval_module.enqueue("sample-session", value)
    instance.on_request(session_key="sample-session", surface="gateway")
    posted = instance.queue.get_nowait()
    assert posted["allow_session"] is False and posted["allow_permanent"] is False


@pytest.mark.parametrize("retry", [False, True])
@asynchronous
async def test_plugin_lost_publish_ack_withdraws_stored_entry(tmp_path, plugin, approval_module, retry):
    async with running(tmp_path, approvals=True) as (bridge, _):
        instance = plugin.Mirror("default", bridge.approvals.secret_path("default"), bridge.approvals.port)
        value = payload()
        entry = approval_module.enqueue("sample-session", value)
        request = instance.request
        attempts = []

        def lost_ack(method, suffix="", value=None):
            attempts.append(suffix)
            if retry and suffix.endswith("/resolved") and attempts.count(suffix) == 1:
                raise OSError("sample withdrawal failure")
            result = request(method, suffix, value)
            if method == "POST" and not suffix:
                raise TimeoutError("sample acknowledgement timeout")
            return result

        instance.request = lost_ack
        instance.on_request(session_key="sample-session", surface="gateway")
        await asyncio.to_thread(instance.tick)
        if retry:
            assert value["request_id"] in instance.tracked
            bridge.approvals.entries[("default", value["request_id"])].update(state="answered", choice="once")
            await asyncio.to_thread(instance.tick)
        assert bridge.approvals.entries[("default", value["request_id"])]["state"] == "resolved"
        assert not entry.event.is_set() and not approval_module.resolved
        assert not instance.tracked
        assert attempts.count("") == 1


def test_plugin_failed_withdrawal_does_not_block_other_entries(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    first, second = payload(), payload()
    deadline = time.time() + 300
    instance.tracked = {v["request_id"]: ("sample-session", deadline) for v in (first, second)}
    calls = []

    def request(method, suffix="", value=None):
        calls.append(suffix)
        if suffix == "/" + first["request_id"] + "/resolved":
            raise OSError("sample failure")
        return {"answers": []} if method == "GET" else {}

    instance.request = request
    instance.tick()
    assert "/" + second["request_id"] + "/resolved" in calls
    assert set(instance.tracked) == {first["request_id"]}


def test_plugin_failed_answer_poll_still_withdraws_closed_entries(plugin, approval_module, tmp_path):
    instance = mirror(plugin, tmp_path)
    value = payload()
    instance.tracked[value["request_id"]] = ("sample-session", time.time() + 300)
    calls = []

    def request(method, suffix="", value=None):
        calls.append(suffix)
        if method == "GET":
            raise OSError("sample poll failure")
        return {}

    instance.request = request
    with pytest.raises(OSError):
        instance.tick()
    assert "/" + value["request_id"] + "/resolved" in calls
    assert not instance.tracked


@asynchronous
async def test_approvals_clamps_far_future_expiry(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        bridge.approvals.clock = lambda: 2000000000
        value = payload(expires_at=2000000000 + 31536000)
        bridge.approvals.publish("default", value)
        entry = bridge.approvals.entries[("default", value["request_id"])]
        assert entry["expires_at"] == 2000003600
        assert entry["payload"]["expires_at"] == 2000003600
        assert value["expires_at"] == 2000000000 + 31536000


@asynchronous
async def test_approvals_closed_entries_do_not_consume_live_capacity(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        for _ in range(1025):
            value = payload(expires_at=time.time() + 3600)
            bridge.approvals.publish("default", value)
            bridge.approvals.clear(bridge.approvals.entries[("default", value["request_id"])])
            assert len(bridge.approvals.entries) <= 1024
        bridge.approvals.sweep()
        assert len(bridge.approvals.entries) <= 1024
        for _ in range(1024):
            bridge.approvals.publish("default", payload())
        from rasul_bridge.http import HTTPError
        with pytest.raises(HTTPError) as failure:
            bridge.approvals.publish("default", payload())
        assert failure.value.status == 429


@asynchronous
async def test_approvals_resolved_tombstone_retires_early(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        now = 2000000000
        bridge.approvals.clock = lambda: now
        value = payload(expires_at=now + 3600)
        bridge.approvals.publish("default", value)
        bridge.approvals.clear(bridge.approvals.entries[("default", value["request_id"])])
        now += 120
        bridge.approvals.sweep()
        assert not bridge.approvals.entries


@asynchronous
async def test_approvals_authentication_uses_startup_credentials_only(tmp_path, monkeypatch):
    async with running(tmp_path, approvals=True) as (bridge, _):
        from rasul_bridge.http import HTTPError
        service = bridge.approvals
        path = "/internal/profiles/default/approvals/answers"
        head = {k.lower(): v for k, v in request_headers(service.credential("default"), "GET", path, b"",
                secrets.token_hex(16), str(int(time.time()))).items()}
        monkeypatch.setattr(service, "credential", lambda profile: pytest.fail("Credential I/O after startup"))
        service.authenticate("GET", path, "default", head, b"")
        (bridge.config.hermes_home / "profiles" / "sample").mkdir(parents=True)
        with pytest.raises(HTTPError) as failure:
            service.authenticate("GET", path, "sample", {}, b"")
        assert failure.value.status == 401
        assert not service.secret_path("sample").exists()


@pytest.mark.parametrize("text", ["x" * 20000, "\u0639" * 20000, "\U0001f600" * 20000, "\x01" * 20000],
                         ids=["ascii", "arabic", "supplementary", "control"])
@asynchronous
async def test_plugin_long_redacted_text_fits_internal_body(tmp_path, plugin, approval_module, text):
    async with running(tmp_path, approvals=True) as (bridge, _):
        instance = plugin.Mirror("default", bridge.approvals.secret_path("default"), bridge.approvals.port)
        value = payload(command="sample-private-value" + text, description=text)
        approval_module.enqueue("sample-session", value)
        instance.on_request(session_key="sample-session", surface="gateway")
        await asyncio.to_thread(instance.tick)
        posted = bridge.approvals.pending("default")["approvals"]
        assert len(posted) == 1
        for key in ("command", "description"):
            assert len(posted[0][key]) <= 4096 and posted[0][key].endswith("[truncated]")
        assert posted[0]["command"].startswith("[redacted]")
        assert "sample-private-value" not in posted[0]["command"]


@asynchronous
async def test_approvals_nonce_saturation_is_profile_scoped(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        bridge.approvals.nonces = {("sample", str(i)): time.time() + 60 for i in range(8192)}
        assert await signed_local(bridge) == 200
        bridge.approvals.nonces.update({("default", str(i)): time.time() + 60 for i in range(2048)})
        assert await signed_local(bridge) == 401


def test_plugin_answer_at_deadline_does_not_resolve(plugin, approval_module, tmp_path, monkeypatch):
    instance = mirror(plugin, tmp_path)
    value = payload()
    entry = approval_module.enqueue("sample-session", value)
    instance.tracked[value["request_id"]] = ("sample-session", 2000000000)
    monkeypatch.setattr(plugin.time, "time", lambda: 2000000000)
    instance.request = lambda method, suffix="", value=None: {"answers": [
        {"request_id": entry.data["request_id"], "session_key": "sample-session", "choice": "once"}]}
    instance.tick()
    assert not entry.event.is_set() and not approval_module.resolved and not instance.tracked


def test_plugin_zero_timeout_does_not_publish(plugin, approval_module, tmp_path, monkeypatch):
    instance = mirror(plugin, tmp_path)
    approval_module.enqueue("sample-session", payload())
    monkeypatch.setattr(sys.modules["tools.approval_context"], "_get_approval_timeout", lambda: 0)
    instance.on_request(session_key="sample-session", surface="gateway")
    assert instance.queue.empty() and not instance.tracked


@asynchronous
async def test_approvals_answered_expiry_emits_resolved_and_removes_answer(tmp_path):
    from rasul_bridge.approvals import INTERNAL_ROUTE
    async with running(tmp_path, approvals=True) as (bridge, _):
        value = payload()
        bridge.approvals.publish("default", value)
        bridge.approvals.entries[("default", value["request_id"])].update(state="answered", choice="once")
        bridge.approvals.clock = lambda: value["expires_at"]
        match = INTERNAL_ROUTE.fullmatch("/internal/profiles/default/approvals/answers")
        assert bridge.approvals.internal("GET", match, b"") == {"answers": []}
        assert bridge.approvals.history[-1][1:] == ("resolved", {"profile": "default", "request_id": value["request_id"]})


@asynchronous
async def test_approvals_nonloopback_peer_is_rejected(tmp_path):
    class RemoteWriter:
        def __init__(self):
            self.data = bytearray()
            self.closed = False

        def get_extra_info(self, key):
            assert key == "peername"
            return ("192.0.2.1", 1234)

        def write(self, data):
            self.data.extend(data)

        async def drain(self):
            pass

        def close(self):
            self.closed = True

        async def wait_closed(self):
            pass

    async with running(tmp_path, approvals=True) as (bridge, _):
        writer = RemoteWriter()
        await bridge.approvals.handle(None, writer)
        assert writer.closed and writer.data.startswith(b"HTTP/1.1 403")
        assert not bridge.approvals.entries and not bridge.approvals.tasks


async def phone_path(bridge, tmp_path, identity, method, path, value=None):
    async with client(bridge, tmp_path, identity) as (link, reader, _):
        await send(link, method, path, value)
        return await read_response(reader)


@asynchronous
async def test_approvals_all_profiles_list_is_ordered_and_per_profile_routes_unchanged(tmp_path):
    async with running_profiles(tmp_path, ("sample",)) as (bridge, _):
        identity, _ = registered(bridge)
        now = time.time()
        late = payload(profile="default", expires_at=now + 300)
        early = payload(profile="sample", expires_at=now + 100)
        tie_a, tie_b = "a" * 32, "b" * 32
        same_b = payload(tie_b, profile="default", expires_at=now + 200)
        same_a = payload(tie_a, profile="sample", expires_at=now + 200)
        for value in (late, same_b, early, same_a):
            assert (await local(bridge, "POST", value=value, profile=value["profile"]))[0] == 200
        assert await phone_path(bridge, tmp_path, identity, "GET", "/v1/approvals") == (
            200, {"approvals": [early, same_a, same_b, late]})
        assert (await phone(bridge, tmp_path, identity, "GET"))[1] == {"approvals": [late, same_b]}
        assert (await phone(bridge, tmp_path, identity, "GET", profile="sample"))[1] == {
            "approvals": [early, same_a]}
        answer = "/" + early["request_id"] + "/answer"
        assert (await phone(bridge, tmp_path, identity, "POST", answer, {"choice": "once"},
                            profile="sample"))[0] == 200
        assert [i["request_id"] for i in (await phone_path(
            bridge, tmp_path, identity, "GET", "/v1/approvals"))[1]["approvals"]] == [
            same_a["request_id"], same_b["request_id"], late["request_id"]]


@asynchronous
async def test_approvals_all_profiles_list_skips_removed_profile(tmp_path):
    async with running_profiles(tmp_path, ("sample",)) as (bridge, _):
        identity, _ = registered(bridge)
        gone, kept = payload(profile="sample"), payload()
        await local(bridge, "POST", value=gone, profile="sample")
        await local(bridge, "POST", value=kept)
        real = bridge.profiles.directories
        bridge.profiles.directories = lambda: {k: v for k, v in real().items() if k != "sample"}
        assert (await phone_path(bridge, tmp_path, identity, "GET", "/v1/approvals"))[1] == {"approvals": [kept]}


@asynchronous
async def test_approvals_all_profiles_sse_snapshot_events_and_removed_profile(tmp_path):
    async with running_profiles(tmp_path, ("sample",)) as (bridge, _):
        identity, _ = registered(bridge)
        first = payload(profile="sample")
        await local(bridge, "POST", value=first, profile="sample")
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/approvals/events")
            assert (await reader.until(b"\r\n\r\n")).startswith(b"HTTP/1.1 200")
            snapshot = await sse(reader)
            assert snapshot.startswith(b"event: snapshot") and first["request_id"].encode() in snapshot
            second = payload()
            assert (await local(bridge, "POST", value=second))[0] == 200
            third = payload(profile="sample")
            assert (await local(bridge, "POST", value=third, profile="sample"))[0] == 200
            for value in (second, third):
                async with asyncio.timeout(2):
                    event = await sse(reader)
                assert b"event: new" in event and value["request_id"].encode() in event
                assert json.dumps({"profile": value["profile"]})[1:-1].encode() in event
                assert value["command"].encode() not in event
            assert (await local(bridge, "POST", "/" + second["request_id"] + "/resolved", {}))[0] == 200
            async with asyncio.timeout(2):
                event = await sse(reader)
            assert b"event: resolved" in event and second["request_id"].encode() in event
            real = bridge.profiles.directories
            bridge.profiles.directories = lambda: {k: v for k, v in real().items() if k != "sample"}
            hidden = payload(profile="sample")
            bridge.approvals.publish("sample", hidden)
            last = payload()
            assert (await local(bridge, "POST", value=last))[0] == 200
            async with asyncio.timeout(2):
                event = await sse(reader)
            assert last["request_id"].encode() in event and hidden["request_id"].encode() not in event


@asynchronous
async def test_approvals_all_profiles_sse_history_overflow_sends_new_snapshot(tmp_path):
    async with running_profiles(tmp_path, ("sample",)) as (bridge, _):
        bridge.approvals.history = deque(maxlen=4)
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/approvals/events")
            await reader.until(b"\r\n\r\n")
            assert b"event: snapshot" in await sse(reader)
            kept = [payload(profile=name) for name in ("default", "sample") * 4]
            for value in kept:
                bridge.approvals.publish(value["profile"], value)
            seen = b""
            async with asyncio.timeout(2):
                while not seen.startswith(b"event: snapshot"):
                    seen = await sse(reader)
            assert all(value["request_id"].encode() in seen for value in kept)


@pytest.mark.parametrize("method,path", [("POST", "/v1/approvals"), ("POST", "/v1/approvals/events"),
                                         ("GET", "/v1/approvals/x"), ("GET", "/v1/approvals/"),
                                         ("DELETE", "/v1/approvals"), ("GET", "/v1/approvals/events/x"),
                                         ("POST", "/v1/approvals/" + "a" * 32 + "/answer")])
@asynchronous
async def test_approvals_all_profiles_other_routes_are_not_found(tmp_path, method, path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, _ = registered(bridge)
        value = {"choice": "once"} if method == "POST" else None
        assert await phone_path(bridge, tmp_path, identity, method, path, value) == (
            404, {"error": "approvals_not_found"})


@asynchronous
async def test_approvals_all_profiles_disabled_is_plain_not_found(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        assert await phone_path(bridge, tmp_path, identity, "GET", "/v1/approvals") == (
            404, {"error": "Route not found"})


@pytest.mark.parametrize("method,path", [("GET", "/v1/approvals"), ("GET", "/v1/approvals/events"),
                                         ("POST", "/v1/approvals")])
@asynchronous
async def test_approvals_all_profiles_use_device_rate_limit(tmp_path, method, path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, device = registered(bridge)
        bridge.approvals.rates[device] = deque([time.time()] * 60)
        assert (await phone_path(bridge, tmp_path, identity, method, path))[0] == 429
        other, _ = registered(bridge)
        assert (await phone_path(bridge, tmp_path, other, "GET", "/v1/approvals"))[0] == 200


@asynchronous
async def test_approvals_all_profiles_audit_route_names(tmp_path):
    async with running(tmp_path, approvals=True) as (bridge, _):
        identity, device = registered(bridge)
        await phone_path(bridge, tmp_path, identity, "GET", "/v1/approvals")
        await phone_path(bridge, tmp_path, identity, "POST", "/v1/approvals/events")
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/approvals/events")
            await reader.until(b"\r\n\r\n")
            await sse(reader)
        await asyncio.sleep(0.2)
        records = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        routes = [(r["method"], r["route"], r["status"]) for r in records
                  if r.get("route", "").startswith("/v1/approvals")]
        assert ("GET", "/v1/approvals", 200) in routes
        assert ("POST", "/v1/approvals/events", 404) in routes
