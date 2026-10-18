import asyncio
import base64
from contextlib import asynccontextmanager, suppress
from email.message import Message
from functools import wraps
import hashlib
import json
import os
import random
from pathlib import Path
import secrets
import ssl
import sys
import struct
import uuid
import zlib

from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.x509.oid import NameOID
from datetime import datetime, timedelta, timezone
import pytest
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidMessage, InvalidStatus

from rasul_bridge.cli import initialize, install_task, png, show_qr
from rasul_bridge.audit import Audit, LOG_FILES, MAX_LOG_BYTES
from rasul_bridge.config import Config
from rasul_bridge.http import HTTPError, Reader, headers
from rasul_bridge.limits import Limits, MAX_BODY
from rasul_bridge.pairing import Pairing
from rasul_bridge.profiles import Profiles
from rasul_bridge.proxy import HOP_HEADERS, allowed, end_to_end, response_body
from rasul_bridge.registry import Registry
import rasul_bridge.server as server_module
from rasul_bridge.server import (ANONYMOUS_MAX_BODY, ANONYMOUS_TIMEOUT,
                                 MAX_ANONYMOUS_CONNECTIONS, UPGRADE_TIMEOUT, Bridge)
from rasul_bridge.storage import Store, protect
from rasul_bridge.tls import TLSLink, ensure_identity, fingerprint, key_bytes, new_identity, pem, spki


def asynchronous(function):
    @wraps(function)
    def invoke(*args, **kwargs):
        return asyncio.run(function(*args, **kwargs))
    return invoke


class FakeUpstream:
    def __init__(self):
        self.requests = []
        self.decoded_requests = []
        self.finish_stream = asyncio.Event()
        self.stream_closed = asyncio.Event()
        self.tasks = set()
        self.active_agents = 0
        self.broken_stream = False
        self.expected_key = None
        self.response_headers = b""

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        try:
            raw = (await reader.readuntil(b"\r\n\r\n")).split(b"\r\n")
            method, target, _ = raw[0].decode().split(" ")
            head = headers(raw[1:-2])
            body = await reader.readexactly(int(head.get("content-length", "0")))
            self.requests.append((method, target, head, body))
            if body:
                content_type = Message()
                content_type["content-type"] = head.get("content-type", "application/json")
                charset = content_type.get_content_charset() or "utf-8"
                self.decoded_requests.append((method, target, body.decode(charset)))
            if (not target.endswith("/health")
                    and head.get("authorization") != "Bearer " + self.expected_key(target)):
                writer.write(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\n{}")
                await writer.drain()
                return
            if target.endswith("/events"):
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                             b"Transfer-Encoding: chunked\r\n\r\n")
                data = b"event: update\ndata: first\n\n"
                writer.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
                await writer.drain()
                complete = asyncio.create_task(self.finish_stream.wait())
                closed = asyncio.create_task(reader.read())
                try:
                    await asyncio.wait([complete, closed], return_when=asyncio.FIRST_COMPLETED)
                    if closed.done():
                        self.stream_closed.set()
                    else:
                        writer.write(b"2\r\nx" if self.broken_stream else b"0\r\n\r\n")
                        await writer.drain()
                finally:
                    complete.cancel()
                    closed.cancel()
                    await asyncio.gather(complete, closed, return_exceptions=True)
            else:
                value = {"active_agents": self.active_agents} if target == "/health/detailed" else {"status": "ok"}
                data = json.dumps(value).encode()
                writer.write(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\n".encode()
                             + self.response_headers + b"\r\n" + data)
                await writer.drain()
        finally:
            writer.close()
            with suppress(OSError):
                await writer.wait_closed()
            self.tasks.discard(task)

    async def start(self):
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def close(self):
        self.server.close()
        await self.server.wait_closed()
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


@asynccontextmanager
async def running(tmp_path, confirm=None, run=None, random_source=None, approvals=False):
    upstream = FakeUpstream()
    await upstream.start()
    store = Store(tmp_path / "data")
    initialize(store)
    config = Config(hermes_url=f"http://127.0.0.1:{upstream.port}", port=0,
                    hermes_home=tmp_path / "upstream", approvals_port=0 if approvals else None)
    config.hermes_home.mkdir()
    bridge = Bridge(store, config, confirm=confirm, run=run,
                    random_source=random_source if random_source is not None else random.Random(148))
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


class RecordedSocket:
    def __init__(self, socket):
        self.socket = socket
        self.frames = []

    async def send(self, data):
        self.frames.append(data)
        await self.socket.send(data)

    async def recv(self):
        data = await self.socket.recv()
        self.frames.append(data)
        return data


@asynccontextmanager
async def client(bridge, tmp_path, identity=None, trust=None, version=ssl.TLSVersion.TLSv1_3):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.minimum_version = context.maximum_version = version
    context.load_verify_locations(cadata=pem(trust or bridge.cert).decode())
    if identity:
        key, cert = identity
        key_file, cert_file = tmp_path / (uuid.uuid4().hex + ".key"), tmp_path / (uuid.uuid4().hex + ".pem")
        key_file.write_bytes(key_bytes(key))
        cert_file.write_bytes(pem(cert))
        context.load_cert_chain(cert_file, key_file)
        key_file.unlink()
        cert_file.unlink()
    async with connect(f"ws://127.0.0.1:{bridge.port}/v1/link", compression=None, proxy=None) as socket:
        recorded = RecordedSocket(socket)
        link = TLSLink(recorded, context, server_side=False)
        await link.handshake()
        assert fingerprint(x509.load_der_x509_certificate(link.ssl.getpeercert(True))) == fingerprint(trust or bridge.cert)
        yield link, Reader(link), recorded


async def send(link, method, target, value=None, extra=""):
    data = (value if isinstance(value, bytes) else json.dumps(value).encode()) if value is not None else b""
    await link.write((f"{method} {target} HTTP/1.1\r\nHost: my-pc\r\nContent-Length: {len(data)}\r\n"
                      + extra + "\r\n").encode() + data)


async def read_response(reader):
    raw = (await reader.until(b"\r\n\r\n")).split(b"\r\n")
    status = int(raw[0].split()[1])
    head = headers(raw[1:])
    body = bytearray()
    async for data in response_body(reader, head):
        body.extend(data)
    return status, json.loads(body)


def pair_body(payload, cert):
    return {"inv": payload["inv"], "sec": payload["sec"], "device_name": "my-pc",
            "device_cert_pem": pem(cert).decode()}


def registered(bridge):
    identity = new_identity()
    device = bridge.registry.register("my-pc", identity[1])
    return identity, device


@asynchronous
async def test_pairing_happy_path_with_confirmation_and_real_tls(tmp_path):
    identity = new_identity()
    seen = []

    async def confirm(name, code):
        seen.append((name, code))
        return True

    async with running(tmp_path, confirm) as (bridge, upstream):
        bridge.pairing.clock = lambda: 1000
        payload, qr = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        expected = str(int.from_bytes(hashlib.sha256(spki(bridge.cert) + spki(identity[1])
                                                     + payload["inv"].encode()).digest(), "big"))[:6]
        assert len(expected) == 6 and expected.isdecimal()
        assert qr.startswith("rasul-pair:1:")
        assert payload["exp"] - int(bridge.pairing.clock()) == 120
        stored = bridge.store.read("invitations.json")[payload["inv"]]
        assert payload["sec"] not in json.dumps(stored)
        assert stored["hash"] == hashlib.sha256(base64.urlsafe_b64decode(payload["sec"] + "=")).hexdigest()
        async with client(bridge, tmp_path) as (link, reader, recorded):
            await send(link, "POST", "/v1/pair", pair_body(payload, identity[1]))
            status, value = await read_response(reader)
            assert status == 200 and bridge.registry.active(value["device_id"])
            assert seen == [("my-pc", expected)]
            async with asyncio.timeout(2):
                await recorded.socket.wait_closed()
            with pytest.raises(ConnectionClosed):
                await send(link, "GET", "/v1/profiles")
            assert all(isinstance(frame, bytes) for frame in recorded.frames)
            assert all(payload["sec"].encode() not in frame for frame in recorded.frames)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles")
            status, value = await read_response(reader)
            assert status == 200
            assert value["profiles"] == [{"id": "default", "name": "default", "display_name": "default",
                                           "ready": True, "default": True}]
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert any(row.get("route") == "/v1/pair" and row["status"] == 200 for row in rows)


@asynchronous
async def test_anonymous_connection_cannot_use_new_route(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/hermes/v1/skills")
            assert (await read_response(reader)) == (403, {"error": "Registered device certificate required"})
        assert not upstream.requests


@pytest.mark.parametrize("failure", ["expired", "reused", "wrong-secret", "declined", "timeout"])
@asynchronous
async def test_invitation_failures(tmp_path, failure):
    store = Store(tmp_path / "data")
    cert = ensure_identity(store)
    clock = [1000]

    async def confirm(name, code):
        if failure == "timeout":
            raise TimeoutError
        return failure != "declined"

    pairing = Pairing(store, Registry(store), cert, confirm, clock=lambda: clock[0])
    payload, _ = pairing.issue(["wss://example.com/v1/link"], "my-pc", "my-pc")
    body = pair_body(payload, new_identity()[1])
    if failure == "expired":
        clock[0] += 120
    elif failure == "reused":
        await pairing.enroll(body)
    elif failure == "wrong-secret":
        body["sec"] = secrets.token_urlsafe(32)
    with pytest.raises((PermissionError, TimeoutError)):
        await pairing.enroll(body)
    if failure in ("declined", "timeout", "reused"):
        assert store.read("invitations.json")[payload["inv"]]["state"] == "consumed"


@pytest.mark.parametrize("revoked", [False, True])
@pytest.mark.parametrize("failure", [None, "send", "close"])
@asynchronous
async def test_unregistered_device_certificate_rejected(tmp_path, monkeypatch, revoked, failure):
    async with running(tmp_path) as (bridge, upstream):
        handshake = TLSLink.handshake
        failed = asyncio.Event()
        started = []

        async def suspended():
            await asyncio.Event().wait()

        async def rejected(link, *args, **kwargs):
            try:
                await handshake(link, *args, **kwargs)
            except ssl.SSLError:
                if isinstance(link.websocket, server_module.AcceptedConnection):
                    started.append(asyncio.get_running_loop().time())
                    if failure == "send":
                        monkeypatch.setattr(link, "flush", suspended)
                    elif failure == "close":
                        monkeypatch.setattr(link.websocket, "close", suspended)
                    failed.set()
                raise

        monkeypatch.setattr(TLSLink, "handshake", rejected)
        identity = new_identity()
        if revoked:
            device = bridge.registry.register("my-pc", identity[1])
            bridge.registry.revoke(device)
        async with asyncio.timeout(2):
            with pytest.raises(ConnectionClosed if failure == "send" else ssl.SSLError) as error:
                async with client(bridge, tmp_path, identity) as (link, _, _):
                    if failure:
                        await failed.wait()
                        async with client(bridge, tmp_path) as (other, reader, _):
                            await send(other, "GET", "/v1/profiles")
                            assert (await read_response(reader))[0] == 403
                    await link.read()
        if failure != "send":
            assert error.value.reason == "TLSV1_ALERT_UNKNOWN_CA"
            print(f"revoked={revoked}, failure={failure}: {error.value.reason}")
        await wait_until(lambda: not bridge.link_tasks)
        assert asyncio.get_running_loop().time() - started[0] < 1.5
        assert not bridge.connections and not bridge.pending and not bridge.anonymous
        assert not bridge.pairing_reserved and not bridge.deadlines
        assert bridge.limits.connections == 0 and not bridge.limits.devices
        assert upstream.requests == []


@asynchronous
async def test_non_pair_route_requires_client_certificate(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", "/v1/profile-templates")
            assert (await read_response(reader))[0] == 403
        assert upstream.requests == []


@asynchronous
async def test_revocation_closes_open_stream_and_rejects_reconnect(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, device = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            await send(link, "GET", "/v1/profiles/default/hermes/v1/runs/one/events")
            await reader.until(b"\r\n\r\n")
            assert await reader.until()  # first chunk length
            assert b"first" in await reader.until(b"\r\n")
            bridge.registry.revoke(device)
            async with asyncio.timeout(2):
                await recorded.socket.wait_closed()
                await upstream.stream_closed.wait()
            assert recorded.socket.close_code == 1008
        with pytest.raises((ssl.SSLError, ConnectionClosed)):
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                await read_response(reader)


@asynchronous
async def test_phone_authorization_dropped_route_allowlist_and_audit(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, device = registered(bridge)
        phone_secret = secrets.token_urlsafe(32)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            await send(link, "GET", "/v1/profiles/default/hermes/health", extra=f"Authorization: Bearer {phone_secret}\r\n")
            assert (await read_response(reader)) == (200, {"status": "ok"})
            assert upstream.requests[-1][2]["authorization"] == "Bearer " + bridge.profiles.key("default")
            for target in ("/v1/devices", "/v1/profiles/default/hermes/api/jobs/new/extra", "/v1/profiles/default/hermes/v1/browser-control/ws",
                           "/v1/profiles/default/hermes/health?secret=" + phone_secret):
                await send(link, "GET", target)
                assert (await read_response(reader))[0] == 404
            await send(link, "POST", "/v1/profiles/default/hermes/api/sessions/one/fork", {"title": "one fork"})
            assert (await read_response(reader))[0] == 200
            assert upstream.requests[-1][0:2] == ("POST", "/api/sessions/one/fork")
            await send(link, "POST", "/v1/profiles/default/hermes/api/sessions/one/model",
                       {"model": "sample-model", "provider": "sample-provider", "require_model_lock": True})
            assert (await read_response(reader))[0] == 200
            assert upstream.requests[-1][0:2] == ("POST", "/api/sessions/one/model")
            await send(link, "DELETE", "/v1/devices/" + device)
            assert (await read_response(reader))[0] == 404
            assert len(upstream.requests) == 3
            assert link.ssl.version() == "TLSv1.3"
            assert not link.ssl.session_reused
            assert not link.ssl.session.has_ticket
            assert all(b"Authorization" not in frame and phone_secret.encode() not in frame for frame in recorded.frames)
        log = bridge.audit.path.read_text()
        for secret in (phone_secret, bridge.profiles.key("default"), "browser-control", "jobs/new"):
            assert secret not in log
        values = [json.loads(line) for line in log.splitlines()]
        assert all(set(row) == {"time", "device_id", "method", "route", "profile_id", "status"} for row in values)
        assert any(row["route"] == "/v1/profiles/{id}/hermes/api/sessions/{id}/fork" and row["status"] == 200 for row in values)
        assert any(row["route"] == "/v1/profiles/{id}/hermes/api/sessions/{id}/model" and row["status"] == 200 for row in values)


@asynchronous
async def test_fork_and_model_route_limits_return_specific_codes(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            fork = "/v1/profiles/default/hermes/api/sessions/one/fork"
            for _ in range(6):
                await send(link, "POST", fork, {"title": "sample fork"})
                assert (await read_response(reader))[0] == 200
            await send(link, "POST", fork, {"title": "sample fork"})
            assert await read_response(reader) == (429, {"error": "fork_rate_limited"})

            model = "/v1/profiles/default/hermes/api/sessions/one/model"
            for _ in range(20):
                await send(link, "POST", model, {"model": "sample-model", "provider": "sample-provider"})
                assert (await read_response(reader))[0] == 200
            await send(link, "POST", model, {"model": "sample-model", "provider": "sample-provider"})
            assert await read_response(reader) == (429, {"error": "model_rate_limited"})


@asynchronous
async def test_session_patch_limits_fields_and_delete_replaces_authorization(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        phone_secret = secrets.token_urlsafe(32)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                       {"title": "renamed"}, extra=f"Authorization: Bearer {phone_secret}\r\n")
            assert (await read_response(reader)) == (200, {"status": "ok"})
            method, target, head, body = upstream.requests[-1]
            assert (method, target) == ("PATCH", "/api/sessions/one")
            assert head["authorization"] == "Bearer " + bridge.profiles.key("default")
            assert head["content-type"] == "application/json"
            assert head["content-length"] == str(len(body))
            assert body == json.dumps({"title": "renamed"}, ensure_ascii=False).encode("utf-8")
            assert upstream.decoded_requests[-1] == (
                "PATCH", "/api/sessions/one", '{"title": "renamed"}')

            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                       {"title": "utf8"},
                       extra="Content-Type: application/json; charset=utf-8\r\n")
            assert (await read_response(reader)) == (200, {"status": "ok"})
            assert upstream.decoded_requests[-1] == (
                "PATCH", "/api/sessions/one", '{"title": "utf8"}')

            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                       {"title": "utf8 quoted"},
                       extra='Content-Type: application/json; charset="UTF-8"\r\n')
            assert (await read_response(reader)) == (200, {"status": "ok"})
            assert upstream.decoded_requests[-1] == (
                "PATCH", "/api/sessions/one", '{"title": "utf8 quoted"}')

            forwarded = len(upstream.requests)
            attack = b'{"title":"safe+ACI-,+ACI-end_reason+ACI-:+ACI-done"}'
            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one", attack,
                       extra="Content-Type: application/json; charset=utf-7\r\n")
            assert (await read_response(reader)) == (400, {"error": "unsupported_charset"})
            assert len(upstream.requests) == forwarded

            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                       b'{"title":"\xff"}',
                       extra="Content-Type: application/json; charset=utf-8\r\n")
            assert (await read_response(reader)) == (400, {"error": "session_patch_invalid_fields"})
            assert len(upstream.requests) == forwarded

            for invalid_title in (1, {"nested": "value"}):
                await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                           {"title": invalid_title})
                assert (await read_response(reader)) == (
                    400, {"error": "session_patch_invalid_fields"})
                assert len(upstream.requests) == forwarded

            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                       {"title": None})
            assert (await read_response(reader)) == (200, {"status": "ok"})
            assert upstream.decoded_requests[-1] == (
                "PATCH", "/api/sessions/one", '{"title": null}')

            forwarded = len(upstream.requests)
            await send(link, "POST", "/v1/profiles/default/hermes/api/jobs", {"name": "job"},
                       extra="Content-Type: application/json; charset=utf-16\r\n")
            assert (await read_response(reader)) == (400, {"error": "unsupported_charset"})
            assert len(upstream.requests) == forwarded

            await send(link, "PATCH", "/v1/profiles/default/hermes/api/sessions/one",
                       {"title": "renamed", "end_reason": "done"})
            assert (await read_response(reader)) == (400, {"error": "session_patch_invalid_fields"})
            assert len(upstream.requests) == forwarded
            await send(link, "DELETE", "/v1/profiles/default/hermes/api/jobs/job",
                       extra=f"Authorization: Bearer {phone_secret}\r\n")
            assert (await read_response(reader)) == (200, {"status": "ok"})
            method, target, head, _ = upstream.requests[-1]
            assert (method, target) == ("DELETE", "/api/jobs/job")
            assert head["authorization"] == "Bearer " + bridge.profiles.key("default")
            forwarded = len(upstream.requests)
            await link.write((f"POST /v1/profiles/default/hermes/api/sessions/one/fork HTTP/1.1\r\n"
                              f"Host: my-pc\r\nContent-Length: {MAX_BODY + 1}\r\n\r\n").encode())
            assert (await read_response(reader)) == (413, {"error": "Request body too large"})
            assert len(upstream.requests) == forwarded
        audit = bridge.audit.path.read_text()
        assert "renamed" not in audit and "end_reason" not in audit
        rows = [json.loads(line) for line in audit.splitlines()]
        assert any(row["route"] == "/v1/profiles/{id}/hermes/api/sessions/{id}"
                   and row["status"] == 200 for row in rows)
        assert any(row["route"] == "/v1/profiles/{id}/hermes/api/jobs/{id}"
                   and row["status"] == 200 for row in rows)


@asynchronous
async def test_sse_passed_through_before_upstream_finishes(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/hermes/v1/runs/one/events")
            async with asyncio.timeout(2):
                assert b"text/event-stream" in await reader.until(b"\r\n\r\n")
                assert await reader.until() == b"1b"
                assert await reader.exact(27) == b"event: update\ndata: first\n\n"
            assert not upstream.finish_stream.is_set()
            upstream.finish_stream.set()
            assert await reader.exact(7) == b"\r\n0\r\n\r\n"


@asynchronous
async def test_broken_stream_closes_without_a_second_http_response(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/hermes/v1/runs/one/events")
            assert (await reader.until(b"\r\n\r\n")).startswith(b"HTTP/1.1 200")
            length = int(await reader.until(), 16)
            await reader.exact(length + 2)
            upstream.broken_stream = True
            upstream.finish_stream.set()
            data = b""
            async with asyncio.timeout(2):
                with suppress(ConnectionClosed, EOFError):
                    while part := await reader.read(16384):
                        data += part
                        if b"HTTP/1.1" in data:
                            break
            assert b"HTTP/1.1" not in data
            assert json.loads(bridge.audit.path.read_text().splitlines()[-1])["status"] == 502


@asynchronous
async def test_profile_list_events_and_new_directory(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profile-events?after=999")
            assert b"text/event-stream" in await reader.until(b"\r\n\r\n")

            async def event():
                length = int(await reader.until(), 16)
                data = await reader.exact(length)
                assert await reader.exact(2) == b"\r\n"
                assert data.startswith(b"event: profiles\ndata: ")
                return json.loads(data.split(b"data: ")[1])

            first = await event()
            directory = bridge.config.hermes_home / "profiles" / "new-profile"
            directory.mkdir(parents=True)
            second = await event()
            assert second["revision"] > first["revision"]
            assert second["profiles"][1]["name"] == "new-profile"
            assert not second["profiles"][1]["ready"]
            bridge.profiles.init_key("new-profile", directory)
            third = await event()
            assert third["revision"] > second["revision"] and third["profiles"][1]["ready"]


@pytest.mark.parametrize("name", ["x", "default", "../bad", "Bad", "two words", "ab&cd", "x" * 33, "ab\n"])
@asynchronous
async def test_create_profile_bad_names(tmp_path, name):
    async with running(tmp_path) as (bridge, _):
        with pytest.raises(HTTPError) as error:
            await bridge.profiles.create({"request_id": str(uuid.uuid4()), "name": name, "template": "blank"})
        assert error.value.status == 400


@asynchronous
async def test_create_profile_unknown_template_and_request_uuid(tmp_path):
    async with running(tmp_path) as (bridge, _):
        for request in ({"request_id": str(uuid.uuid4()), "name": "new-profile", "template": "unknown"},
                        {"request_id": "123", "name": "new-profile", "template": "blank"}):
            with pytest.raises(HTTPError) as error:
                await bridge.profiles.create(request)
            assert error.value.status == 400


@pytest.mark.parametrize("clone", [False, True])
@asynchronous
async def test_profile_creation_argument_list_and_idempotency(tmp_path, clone):
    calls = []
    release = asyncio.Event()

    async def run(args):
        calls.append(args)
        await release.wait()
        directory = tmp_path / "upstream" / "profiles" / args[-1]
        directory.mkdir(parents=True)
        if clone:
            source = tmp_path / "upstream" / ".env"
            (directory / ".env").write_bytes(source.read_bytes())

    async with running(tmp_path, run=run) as (bridge, _):
        if clone:
            bridge.config.templates = [{"id": "approved", "label": "Approved", "clone_config": "default"}]
        request_id = str(uuid.uuid4())
        value = {"request_id": request_id, "name": "new-profile", "template": "approved" if clone else "blank"}
        first = await bridge.profiles.create(value)
        assert await bridge.profiles.create(value) == first
        await asyncio.sleep(0)
        assert calls == [["hermes", "profile", "create"] + (["--clone-from", "default"] if clone else []) + ["--", "new-profile"]]
        assert bridge.profiles.operation(first["operation_id"]) == {"state": "running"}
        release.set()
        await asyncio.gather(*list(bridge.profiles.tasks))
        assert bridge.profiles.operation(first["operation_id"]) == {"state": "done", "profile_id": "new-profile"}
        assert await bridge.profiles.create(value) == first
        assert len(calls) == 1
        assert bridge.profiles.key("new-profile") != bridge.profiles.key("default")
        with pytest.raises(HTTPError) as error:
            await bridge.profiles.create(dict(value, name="other-profile"))
        assert error.value.status == 409


def test_connection_device_and_pair_limits():
    now = [0]
    limits = Limits(clock=lambda: now[0])
    assert all(limits.connect() for _ in range(32))
    assert not limits.connect()
    assert all(limits.authenticate("my-pc") for _ in range(16))
    assert not limits.authenticate("my-pc")
    limits.disconnect("my-pc")
    assert limits.connect() and limits.authenticate("my-pc")
    assert all(limits.pair() for _ in range(5))
    assert not limits.pair()
    now[0] = 60
    assert limits.pair()


def test_fork_and_model_limits_are_scoped_by_device_and_profile():
    now = [0]
    limits = Limits(clock=lambda: now[0])
    assert all(limits.fork("device-one", "profile-one") for _ in range(6))
    assert not limits.fork("device-one", "profile-one")
    assert limits.fork("device-one", "profile-two")
    assert limits.fork("device-two", "profile-one")
    assert all(limits.model("device-one", "profile-one") for _ in range(20))
    assert not limits.model("device-one", "profile-one")
    assert limits.model("device-one", "profile-two")
    assert limits.model("device-two", "profile-one")
    now[0] = 60
    assert limits.fork("device-one", "profile-one")
    assert limits.model("device-one", "profile-one")


@asynchronous
async def test_wrong_server_pin_rejected(tmp_path):
    async with running(tmp_path) as (bridge, _):
        with pytest.raises(ssl.SSLError):
            async with client(bridge, tmp_path, trust=new_identity()[1]):
                pass


@asynchronous
async def test_outer_route_and_plaintext_rejected(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        with pytest.raises(InvalidStatus) as error:
            async with connect(f"ws://127.0.0.1:{bridge.port}/v1/devices", proxy=None):
                pass
        assert error.value.response.status_code == 404
        async with connect(f"ws://127.0.0.1:{bridge.port}/v1/link", proxy=None) as socket:
            await socket.send(b"GET /v1/profiles HTTP/1.1\r\n\r\n")
            with pytest.raises(ConnectionClosed):
                await socket.recv()
        assert upstream.requests == []


@asynchronous
async def test_restart_command_off_and_idle_guard(tmp_path):
    calls = []

    async def run(args):
        calls.append(args)

    async with running(tmp_path, run=run) as (bridge, upstream):
        await bridge.profiles.maybe_restart()
        assert calls == [] and upstream.requests == []
        bridge.config.restart_command = ["example", "restart"]
        upstream.active_agents = 1
        await bridge.profiles.maybe_restart()
        assert calls == []
        upstream.active_agents = 0
        await bridge.profiles.maybe_restart()
        assert calls == [["example", "restart"]]


def test_private_storage_and_png(tmp_path):
    store = Store(tmp_path / "data")
    secret = secrets.token_urlsafe(32)
    store.write("sample.json", {"value": secret})
    assert store.read("sample.json") == {"value": secret}
    raw = (store.root / "sample.json").read_bytes()
    if os.name == "nt":
        assert secret.encode() not in raw
    else:
        assert (store.root / "sample.json").stat().st_mode & 0o777 == 0o600
    assert protect(protect(secret.encode()), decrypt=True) == secret.encode()
    image = png([[True, False], [False, True]])
    assert image[:8] == b"\x89PNG\r\n\x1a\n"
    assert struct.unpack("!II", image[16:24]) == (8, 8)


def test_route_boundaries_and_query_names():
    assert allowed("GET", "/api/sessions/one/messages?inline_images=false")
    for target in ("/health?next=/v1/runs", "/api/jobs?include_disabled=true&include_disabled=false",
                   "/v1/runs/../events", "/v1/runs/%2e%2e/events", "//example.com/health", "/health#x"):
        assert allowed("GET", target) is None


def test_new_routes_allow_only_their_methods_and_query_keys():
    routes = [
        ("GET", "/v1/skills", "/v1/skills"),
        ("GET", "/v1/toolsets", "/v1/toolsets"),
        ("GET", "/api/model/options", "/api/model/options"),
        ("GET", "/api/sessions/one", "/api/sessions/{id}"),
        ("PATCH", "/api/sessions/one", "/api/sessions/{id}"),
        ("DELETE", "/api/sessions/one", "/api/sessions/{id}"),
        ("POST", "/api/sessions/one/fork", "/api/sessions/{id}/fork"),
        ("POST", "/api/sessions/one/model", "/api/sessions/{id}/model"),
        ("POST", "/api/jobs", "/api/jobs"),
        ("GET", "/api/jobs/one", "/api/jobs/{id}"),
        ("PATCH", "/api/jobs/one", "/api/jobs/{id}"),
        ("DELETE", "/api/jobs/one", "/api/jobs/{id}"),
    ]
    for method, target, template in routes:
        assert allowed(method, target) == template
        assert allowed("PUT", target) is None
        assert allowed(method, target + "?unexpected=value") is None
    assert allowed("GET", "/api/model/options?refresh=true") == "/api/model/options"
    assert allowed("GET", "/api/model/options?refresh=true&unexpected=value") is None


def test_fork_and_model_routes_allow_only_post_on_exactly_those_paths():
    for suffix in ("fork", "model"):
        base = f"/api/sessions/one/{suffix}"
        assert allowed("POST", base) == f"/api/sessions/{{id}}/{suffix}"
        for method in ("GET", "PUT", "PATCH", "DELETE", "HEAD"):
            assert allowed(method, base) is None
        for target in (base + "/", base + "/extra", base + "x", f"/api/sessions/{suffix}", f"/api/sessions/one/two/{suffix}",
                       f"/api/sessions/%2e%2e/{suffix}", base + "?title=a", base + "#x"):
            assert allowed("POST", target) is None
    # The other session sub-routes stay blocked.
    for target in ("/api/sessions/one/chat", "/api/sessions/one/chat/stream", "/api/sessions/one/messages/extra"):
        assert allowed("POST", target) is None


@asynchronous
async def test_existing_profile_names_and_named_routing(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        for name in ("a", "my_profile", "x" * 64):
            directory = bridge.config.hermes_home / "profiles" / name
            directory.mkdir(parents=True)
            bridge.profiles.init_key(name, directory)
        snapshot = await bridge.profiles.snapshot()
        assert {value["id"] for value in snapshot["profiles"]} == {"default", "a", "my_profile", "x" * 64}
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/my_profile/hermes/health")
            assert (await read_response(reader))[0] == 200
            assert upstream.requests[-1][1] == "/p/my_profile/health"
            assert upstream.requests[-1][2]["authorization"] == "Bearer " + bridge.profiles.key("my_profile")


@asynchronous
async def test_owner_commands_do_not_change_live_operations(tmp_path):
    async with running(tmp_path) as (bridge, _):
        operation_id = uuid.uuid4().hex
        pending = {"request_id": str(uuid.uuid4()), "name": "new-profile", "template": "blank", "state": "running"}
        bridge.store.write("operations.json", {operation_id: pending})
        other = Bridge(bridge.store, bridge.config)
        assert other.profiles.operation(operation_id) == {"state": "running"}
        assert bridge.profiles.operation(operation_id) == {"state": "running"}
        other.profiles.recover()
        assert other.profiles.operation(operation_id)["state"] == "failed"


@asynchronous
async def test_leading_hyphen_is_a_name_not_cli_options(tmp_path):
    calls = []

    async def run(args):
        calls.append(args)
        raise ValueError("Unsupported name")

    async with running(tmp_path, run=run) as (bridge, _):
        value = await bridge.profiles.create({"request_id": str(uuid.uuid4()), "name": "--clone-all", "template": "blank"})
        await asyncio.gather(*list(bridge.profiles.tasks))
        assert calls == [["hermes", "profile", "create", "--", "--clone-all"]]
        assert bridge.profiles.operation(value["operation_id"])["state"] == "failed"


@asynchronous
async def test_operation_uuid_survives_new_service_object(tmp_path):
    calls = []

    async def run(args):
        calls.append(args)
        raise ValueError("Example failure")

    async with running(tmp_path, run=run) as (bridge, _):
        value = {"request_id": str(uuid.uuid4()), "name": "new-profile", "template": "blank"}
        operation = await bridge.profiles.create(value)
        await asyncio.gather(*list(bridge.profiles.tasks))
        other = Profiles(bridge.store, bridge.config, bridge.upstream, run=run)
        assert await other.create(value) == operation
        assert len(calls) == 1


@asynchronous
async def test_global_and_per_device_limit_at_transport(tmp_path):
    async with running(tmp_path) as (bridge, _):
        bridge.limits.connections = 32
        identity, device = registered(bridge)
        with pytest.raises(ConnectionClosed):
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                await read_response(reader)
        bridge.limits.connections = 0
        bridge.limits.devices[device] = 16
        with pytest.raises(ConnectionClosed):
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                await read_response(reader)
        assert bridge.limits.devices[device] == 16


@asynchronous
async def test_pending_owner_decision_via_shared_store(tmp_path):
    async with running(tmp_path) as (bridge, _):
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        operation = asyncio.create_task(bridge.pairing.enroll(pair_body(payload, new_identity()[1])))
        await asyncio.sleep(0)
        assert bridge.store.read("invitations.json")[payload["inv"]]["state"] == "pending"
        with bridge.store.transaction("invitations.json") as invitations:
            invitations[payload["inv"]]["state"] = "approved"
        result = await operation
        assert bridge.registry.active(result["device_id"])


@asynchronous
async def test_initialization_preserves_environment_and_protects_backup(tmp_path):
    async with running(tmp_path) as (bridge, _):
        directory = bridge.config.hermes_home / "profiles" / "new-profile"
        directory.mkdir(parents=True)
        original = b"EXAMPLE_SETTING=true\r\n# owner configuration\r\n"
        env = directory / ".env"
        env.write_bytes(original)
        assert bridge.profiles.init_key("new-profile", directory)
        updated = env.read_bytes()
        assert updated.startswith(original)
        assert updated.count(b"\r\n") == 3
        backups = list(bridge.store.root.glob("env-backup-new-profile-*"))
        assert len(backups) == 1
        assert protect(backups[0].read_bytes(), decrypt=True) == original
        assert not bridge.profiles.init_key("new-profile", directory)
        assert env.read_bytes() == updated


@asynchronous
async def test_idempotent_operation_after_owner_removes_template(tmp_path):
    async def run(args):
        raise ValueError("Example failure")

    async with running(tmp_path, run=run) as (bridge, _):
        request = {"request_id": str(uuid.uuid4()), "name": "new-profile", "template": "blank"}
        first = await bridge.profiles.create(request)
        await asyncio.gather(*list(bridge.profiles.tasks))
        bridge.config.templates = []
        assert await bridge.profiles.create(request) == first


@asynchronous
async def test_profile_event_keepalive_during_suspended_probe(tmp_path):
    release = asyncio.Event()
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        original = bridge.profiles.snapshot

        async def suspended():
            await release.wait()
            return await original()

        bridge.profiles.snapshot = suspended
        bridge.heartbeat_interval = 0.02
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profile-events")
            await reader.until(b"\r\n\r\n")
            async with asyncio.timeout(0.5):
                length = int(await reader.until(), 16)
                assert await reader.exact(length) == b": keepalive\n\n"
                assert await reader.exact(2) == b"\r\n"
            release.set()
            async with asyncio.timeout(2):
                while True:
                    length = int(await reader.until(), 16)
                    data = await reader.exact(length)
                    assert await reader.exact(2) == b"\r\n"
                    if data.startswith(b"event: profiles\ndata: "):
                        break
                    assert data == b": keepalive\n\n"


@asynchronous
async def test_printable_device_name_and_control_rejection(tmp_path):
    async def confirm(name, code):
        return True

    async with running(tmp_path, confirm) as (bridge, _):
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        body = pair_body(payload, new_identity()[1])
        body["device_name"] = "هاتف"
        value = await bridge.pairing.enroll(body)
        assert bridge.registry.devices()[value["device_id"]]["name"] == body["device_name"]
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        body = pair_body(payload, new_identity()[1])
        body["device_name"] = "my-pc\x1b[2J"
        with pytest.raises(ValueError):
            await bridge.pairing.enroll(body)


@pytest.mark.skipif(os.name != "nt", reason="User task registration is platform-specific")
def test_task_registration_uses_argument_lists(tmp_path):
    calls = []
    descriptors = []

    def run(args, **kwargs):
        calls.append((args, kwargs))
        if "/XML" in args:
            descriptors.append(Path(args[args.index("/XML") + 1]).read_text(encoding="utf-16"))

    install_task(tmp_path, run=run)
    install_task(tmp_path, uninstall=True, run=run)
    assert calls[0][0][:4] == ["schtasks", "/Create", "/TN", "RasulBridge"]
    assert "/XML" in calls[0][0]
    assert "LeastPrivilege" in descriptors[0] and "InteractiveToken" in descriptors[0]
    assert descriptors[0].count("<UserId>") == 2
    assert not Path(calls[0][0][calls[0][0].index("/XML") + 1]).exists()
    assert calls[1][0] == ["schtasks", "/Delete", "/TN", "RasulBridge", "/F"]
    assert all("shell" not in kwargs for _, kwargs in calls)


@asynchronous
async def test_anonymous_flood_cannot_hold_authenticated_slots(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        denied = []
        repeated = []
        attempts = [asyncio.Event() for _ in range(40)]
        repeat = asyncio.Event()

        async def attack(attempt):
            try:
                async with client(bridge, tmp_path) as (link, reader, recorded):
                    for _ in range(3):
                        await send(link, "GET", "/v1/profiles")
                        denied.append((await read_response(reader))[0])
                        repeated.append(recorded)
                        attempt.set()
                        await repeat.wait()
            except (ConnectionClosed, InvalidMessage, EOFError, OSError):
                pass
            finally:
                attempt.set()

        attacks = [asyncio.create_task(attack(attempt)) for attempt in attempts]
        try:
            # Reproduce the denied keep-alive attack after its initial requests.
            await asyncio.gather(*(attempt.wait() for attempt in attempts))
            async with asyncio.timeout(2):
                while bridge.pending:
                    await asyncio.sleep(0.01)
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 200
                repeat.set()
                await asyncio.gather(*attacks)
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 200
            assert denied and set(denied) == {403}
            assert len({id(item) for item in repeated}) == len(repeated)
            assert all(target == "/v1/models" for _, target, _, _ in upstream.requests)
            assert bridge.limits.connections <= 1
        finally:
            for task in attacks:
                task.cancel()
            await asyncio.gather(*attacks, return_exceptions=True)


@asynchronous
async def test_anonymous_cap_is_separate_and_lifetime_is_absolute(tmp_path):
    from contextlib import AsyncExitStack

    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with AsyncExitStack() as stack:
            started = asyncio.get_running_loop().time()
            anonymous = [await stack.enter_async_context(client(bridge, tmp_path))
                         for _ in range(MAX_ANONYMOUS_CONNECTIONS)]
            # A completed client handshake can precede server classification.
            for link, _, _ in anonymous:
                await link.write(b"G")
            async with asyncio.timeout(2):
                while len(bridge.anonymous) != MAX_ANONYMOUS_CONNECTIONS:
                    await asyncio.sleep(0.01)
            assert bridge.limits.connections == 0
            async with client(bridge, tmp_path) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 403
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 200
            # Dribbling cannot renew the deadline measured at TLS completion.
            async with asyncio.timeout(ANONYMOUS_TIMEOUT + 1):
                await asyncio.gather(*(recorded.socket.wait_closed() for _, _, recorded in anonymous))
            assert asyncio.get_running_loop().time() - started <= ANONYMOUS_TIMEOUT + 0.5
            assert not bridge.anonymous


@asynchronous
async def test_anonymous_close_handshakes_still_count_against_cap(tmp_path):
    from contextlib import AsyncExitStack

    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with AsyncExitStack() as stack:
            sockets = []
            try:
                for _ in range(MAX_ANONYMOUS_CONNECTIONS):
                    link, _, recorded = await stack.enter_async_context(client(bridge, tmp_path))
                    sockets.append(recorded.socket)
                    # A hostile peer doesn't acknowledge the close frame.
                    recorded.socket.transport.pause_reading()
                    await send(link, "GET", "/v1/profiles")
                await asyncio.sleep(0.05)
                assert len(bridge.anonymous) == MAX_ANONYMOUS_CONNECTIONS
                async with client(bridge, tmp_path) as (link, reader, _):
                    await send(link, "GET", "/v1/profiles")
                    assert (await read_response(reader))[0] == 403
                assert len(bridge.anonymous) <= MAX_ANONYMOUS_CONNECTIONS
                async with client(bridge, tmp_path, identity) as (link, reader, _):
                    await send(link, "GET", "/v1/profiles")
                    assert (await read_response(reader))[0] == 200
            finally:
                for socket in sockets:
                    socket.transport.resume_reading()


@asynchronous
async def test_anonymous_audit_flood_is_aggregated_and_size_bounded(tmp_path):
    async with running(tmp_path) as (bridge, _):
        clock = [0]
        bridge.audit = Audit(bridge.store, clock=lambda: clock[0])
        # Start at the real on-disk bound so the first counter forces rotation.
        for index in range(LOG_FILES):
            path = bridge.audit.path if index == 0 else bridge.audit.path.with_suffix(f".jsonl.{index}")
            path.write_bytes(b"\n" * MAX_LOG_BYTES)
        for minute in range(1, 5):
            for _ in range(20):
                async with client(bridge, tmp_path) as (link, reader, recorded):
                    await send(link, "GET", "/v1/profiles")
                    assert (await read_response(reader))[0] == 403
                    await recorded.socket.wait_closed()
            clock[0] = 60 * minute
            bridge.audit.flush_rejections()
            files = list(bridge.store.root.glob("audit.jsonl*"))
            assert len(files) == LOG_FILES
            assert all(path.stat().st_size <= MAX_LOG_BYTES for path in files)
            assert sum(path.stat().st_size for path in files) <= MAX_LOG_BYTES * LOG_FILES
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert len(rows) == 4
        assert all(row["rejected"] == 20 for row in rows)
        assert all(set(row) == {"time", "rejected"} for row in rows)


def test_audit_rotation_retains_only_three_files(tmp_path, monkeypatch):
    import rasul_bridge.audit as audit_module

    monkeypatch.setattr(audit_module, "MAX_LOG_BYTES", 512)
    audit = Audit(Store(tmp_path / "data"))
    for _ in range(100):
        audit.record("my-pc", "GET", "/v1/profiles", None, 200)
    files = list(audit.path.parent.glob("audit.jsonl*"))
    assert len(files) == LOG_FILES
    assert all(path.stat().st_size <= 512 for path in files)
    assert all(json.loads(line)["status"] == 200 for path in files for line in path.read_text().splitlines())


def test_existing_oversized_audit_is_bounded_on_open(tmp_path):
    store = Store(tmp_path / "data")
    path = store.root / "audit.jsonl"
    row = b'{"status":200}\n'
    path.write_bytes(row * (MAX_LOG_BYTES // len(row) + 2))
    audit = Audit(store)
    assert audit.path.stat().st_size <= MAX_LOG_BYTES
    assert all(json.loads(line)["status"] == 200 for line in audit.path.read_text().splitlines())


@asynchronous
async def test_readiness_requires_correct_bearer_over_real_socket(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        key = bridge.profiles.key("default")
        upstream.expected_key = lambda target: key
        assert await bridge.profiles.ready("default")
        assert upstream.requests[-1][1] == "/v1/models"
        assert upstream.requests[-1][2]["authorization"] == "Bearer " + key
        bridge.store.write("profile-keys.json", {"default": secrets.token_urlsafe(32)})
        assert not await bridge.profiles.ready("default")
        assert not (await bridge.profiles.snapshot())["profiles"][0]["ready"]


@asynchronous
async def test_two_phones_reserve_exactly_one_pending_invitation(tmp_path):
    entered, release = asyncio.Event(), asyncio.Event()
    confirmations = []

    async def confirm(name, code):
        confirmations.append(code)
        entered.set()
        await release.wait()
        return True

    async with running(tmp_path, confirm) as (bridge, _):
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        start = asyncio.Event()

        async def enroll(identity):
            async with client(bridge, tmp_path) as (link, reader, recorded):
                await start.wait()
                await send(link, "POST", "/v1/pair", pair_body(payload, identity[1]))
                result = await read_response(reader)
                await recorded.socket.wait_closed()
                return result

        tasks = [asyncio.create_task(enroll(new_identity())) for _ in range(2)]
        try:
            start.set()
            await entered.wait()
            async with asyncio.timeout(2):
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            assert [task.result()[0] for task in done] == [403]
            invitations = bridge.store.read("invitations.json")
            assert sum(item["state"] == "pending" for item in invitations.values()) == 1
            assert len(confirmations) == 1
            release.set()
            results = await asyncio.gather(*tasks)
            assert sorted(status for status, _ in results) == [200, 403]
            assert len(bridge.registry.devices()) == 1
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)


@asynchronous
async def test_registry_rejects_ca_valid_unregistered_spki(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        ca_key, seed = new_identity()
        now = datetime.now(timezone.utc)
        ca = (x509.CertificateBuilder().subject_name(seed.subject).issuer_name(seed.subject)
              .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
              .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=1))
              .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
              .sign(ca_key, hashes.SHA256()))
        bridge.registry.register("my-pc", ca)
        leaf_key, seed = new_identity()
        leaf = (x509.CertificateBuilder()
                .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "default")]))
                .issuer_name(ca.subject)
                .public_key(leaf_key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=1))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .sign(ca_key, hashes.SHA256()))
        assert fingerprint(leaf) != fingerprint(ca)
        assert bridge.registry.identify(leaf) is None
        identified = []
        identify = bridge.registry.identify

        def track(cert):
            identified.append(fingerprint(cert))
            return identify(cert)

        bridge.registry.identify = track
        async with client(bridge, tmp_path, (leaf_key, leaf)) as (_, _, recorded):
            async with asyncio.timeout(2):
                await recorded.socket.wait_closed()
            # Reaching registry identification proves TLS accepted the CA chain.
            assert identified == [fingerprint(leaf)]
            assert recorded.socket.close_code == 1008
        assert upstream.requests == []


@asynchronous
async def test_tls_12_client_refused_over_real_link(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        with pytest.raises((ssl.SSLError, ConnectionClosed)):
            async with client(bridge, tmp_path, identity, version=ssl.TLSVersion.TLSv1_2):
                pytest.fail("Unsupported protocol was accepted")
        assert upstream.requests == []


@asynchronous
async def test_proxy_strips_connection_nominated_headers_both_directions(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        upstream.response_headers = b"Connection: Content-Type, X-Hermes-Session-Id\r\nX-Hermes-Session-Id: my-pc\r\n"
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/hermes/v1/models", extra=(
                "Connection: Accept, X-Hermes-Session-Id\r\nAccept: application/json\r\n"
                "X-Hermes-Session-Id: my-pc\r\nIdempotency-Key: my-pc\r\n"
                "Keep-Alive: timeout=30\r\nTE: trailers\r\nUpgrade: other\r\n"))
            raw = (await reader.until(b"\r\n\r\n")).split(b"\r\n")
            head = headers(raw[1:])
            assert raw[0].startswith(b"HTTP/1.1 200")
            assert "content-type" not in head and "x-hermes-session-id" not in head
            assert not (set(head) & (HOP_HEADERS - {"transfer-encoding"}))
            received = upstream.requests[-1][2]
            assert "accept" not in received and "x-hermes-session-id" not in received
            assert received["idempotency-key"] == "my-pc"
            assert not (set(received) & (HOP_HEADERS - {"connection"}))
            assert received["authorization"] == "Bearer " + bridge.profiles.key("default")


def test_explicit_hop_headers_are_removed_even_if_allowlisted():
    head = {name: "my-pc" for name in HOP_HEADERS}
    assert end_to_end(head, HOP_HEADERS) == {}


@pytest.mark.parametrize("failure", ["write", "rename", "reject", "flush_rejections", "record"])
@asynchronous
async def test_audit_failures_preserve_revocation_and_admission(tmp_path, monkeypatch, failure):
    monkeypatch.setattr(server_module, "MAX_PRE_AUTH_CONNECTIONS", 8)
    import rasul_bridge.audit as audit_module

    async with running(tmp_path) as (bridge, upstream):
        identity, device = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            await send(link, "GET", "/v1/profiles/default/hermes/v1/runs/one/events")
            await reader.until(b"\r\n\r\n")
            length = int(await reader.until(), 16)
            await reader.exact(length + 2)
            calls = []

            def fail(*args, **kwargs):
                calls.append(True)
                raise OSError("Audit unavailable")

            bridge.audit.clock = lambda: bridge.audit.last_summary + 61
            if failure == "write":
                monkeypatch.setattr(bridge.audit, "_write", fail)
            elif failure == "rename":
                bridge.audit.path.write_bytes(b"\n" * MAX_LOG_BYTES)
                replace = audit_module.os.replace

                def fail_audit_replace(source, target):
                    if Path(source).name.startswith("audit.jsonl"):
                        return fail()
                    return replace(source, target)

                monkeypatch.setattr(audit_module.os, "replace", fail_audit_replace)
            else:
                monkeypatch.setattr(bridge.audit, failure, fail)
            async with client(bridge, tmp_path, identity) as (other, response_reader, _):
                await send(other, "GET", "/v1/profiles")
                assert (await read_response(response_reader))[0] == 200
            async with client(bridge, tmp_path) as (other, response_reader, _):
                await send(other, "GET", "/v1/profiles")
                assert (await read_response(response_reader))[0] == 403
            sockets = []
            try:
                for _ in range(server_module.MAX_PRE_AUTH_CONNECTIONS + 2):
                    sockets.append(await asyncio.open_connection(bridge.config.listen, bridge.port))
                    await asyncio.sleep(0.01)
                assert len(bridge.pending) == server_module.MAX_PRE_AUTH_CONNECTIONS
                assert len(bridge.connections) == len(bridge.pending) + 1
                assert bridge.limits.connections == 1
                bridge.registry.revoke(device)
                async with asyncio.timeout(2):
                    await recorded.socket.wait_closed()
                    await upstream.stream_closed.wait()
                assert recorded.socket.close_code == 1008
                assert not bridge.monitor_task.done()
                assert calls
                assert bridge.audit.dropped > 0 or any(
                    json.loads(line).get("dropped_records", 0) > 0
                    for line in bridge.audit.path.read_text().splitlines() if line)
            finally:
                for _, writer in sockets:
                    writer.close()
                await asyncio.gather(*(writer.wait_closed() for _, writer in sockets), return_exceptions=True)
        async with asyncio.timeout(2):
            while bridge.connections:
                await asyncio.sleep(0.01)
        assert not bridge.pending and not bridge.deadlines and not bridge.anonymous
        assert not bridge.pairing_reserved
        assert bridge.limits.connections == 0 and bridge.limits.devices == {}


@pytest.mark.parametrize("recover", [True, False])
def test_audit_rotation_retries_windows_sharing_violation(tmp_path, monkeypatch, recover):
    import rasul_bridge.audit as audit_module

    monkeypatch.setattr(audit_module, "MAX_LOG_BYTES", 512)
    audit = Audit(Store(tmp_path / "data"))
    audit.path.write_bytes(b"\n" * 512)
    replace, calls = audit_module.os.replace, []

    def sharing_violation(source, target):
        calls.append(True)
        if recover and len(calls) == 3:
            return replace(source, target)
        error = PermissionError("Audit busy")
        error.winerror = 32
        raise error

    monkeypatch.setattr(audit_module.os, "replace", sharing_violation)
    audit.record("my-pc", "GET", "/v1/profiles", None, 200)
    assert len(calls) == 3
    assert audit.dropped == (0 if recover else 1)
    assert audit.path.stat().st_size <= 512
    monkeypatch.setattr(audit_module.os, "replace", replace)
    audit.record("my-pc", "GET", "/v1/profiles", None, 200)
    assert any(json.loads(line).get("status") == 200 for line in audit.path.read_text().splitlines())


def test_build_backend_is_pinned():
    import tomllib

    project = tomllib.loads((Path(__file__).parents[1] / "pyproject.toml").read_text())
    assert project["build-system"] == {"requires": ["setuptools==84.0.0"],
                                      "build-backend": "setuptools.build_meta"}


async def wait_until(predicate, timeout=3):
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.002)


@asynchronous
async def test_registered_handshake_survives_continuous_accept_flood(tmp_path, monkeypatch):
    # Scaled T1: fill before starting the phone; 6 accepts/s, 32 slots.
    monkeypatch.setattr(server_module, "MAX_PRE_AUTH_CONNECTIONS", 32)
    async with running(tmp_path, random_source=random.Random(314159)) as (bridge, _):
        identity, _ = registered(bridge)
        sockets, peak = [], [0]
        receive = TLSLink.receive

        async def paused_receive(link):
            await receive(link)
            if getattr(link.websocket, "bridge", None) is bridge and not getattr(link, "paused", False):
                link.paused = True
                await asyncio.sleep(1)

        monkeypatch.setattr(TLSLink, "receive", paused_receive)

        async def accept_idle():
            sockets.append(await asyncio.open_connection(bridge.config.listen, bridge.port))
            await asyncio.sleep(0)
            peak[0] = max(peak[0], len(bridge.pending))
            assert len(bridge.pending) <= 32

        for _ in range(32):
            await accept_idle()
        await wait_until(lambda: len(bridge.pending) == 32)

        async def flood():
            due = asyncio.get_running_loop().time()
            while True:
                await accept_idle()
                due += 1 / 6
                await asyncio.sleep(max(0, due - asyncio.get_running_loop().time()))

        attack = asyncio.create_task(flood())
        successes = 0
        try:
            for _ in range(20):
                for _ in range(3):
                    try:
                        async with client(bridge, tmp_path, identity) as (link, reader, _):
                            await send(link, "GET", "/v1/profiles")
                            assert (await read_response(reader))[0] == 200
                            successes += 1
                            break
                    except (ConnectionClosed, InvalidMessage, EOFError, OSError, TimeoutError):
                        pass
            assert successes >= 19
            assert peak[0] == 32
            print(f"T1 scaled: {successes}/20; peak={peak[0]}; rate=6/s; pause=1s")
        finally:
            attack.cancel()
            result = await asyncio.gather(attack, return_exceptions=True)
            for _, writer in sockets:
                writer.close()
            await asyncio.gather(*(writer.wait_closed() for _, writer in sockets), return_exceptions=True)
        assert isinstance(result[0], asyncio.CancelledError)


@asynchronous
async def test_pairing_survives_replenished_idle_anonymous_connections(tmp_path):
    from contextlib import AsyncExitStack

    async def confirm(name, code):
        return True

    # T2: full stage B before the proof, with 20 TLS completions/s.
    async with running(tmp_path, confirm, random_source=random.Random(271828)) as (bridge, _):
        async with AsyncExitStack() as stack:
            completions, peak = [0], [0]

            async def anonymous():
                link, _, _ = await stack.enter_async_context(client(bridge, tmp_path))
                await link.write(b"P")
                completions[0] += 1
                await asyncio.sleep(0)
                peak[0] = max(peak[0], len(bridge.anonymous))
                assert len(bridge.anonymous) <= MAX_ANONYMOUS_CONNECTIONS

            for _ in range(MAX_ANONYMOUS_CONNECTIONS):
                await anonymous()
            await wait_until(lambda: len(bridge.anonymous) == MAX_ANONYMOUS_CONNECTIONS)

            async def flood():
                due = asyncio.get_running_loop().time()
                while True:
                    try:
                        await anonymous()
                    except (ConnectionClosed, InvalidMessage, EOFError, OSError):
                        pass
                    due += 1 / 20
                    await asyncio.sleep(max(0, due - asyncio.get_running_loop().time()))

            attack = asyncio.create_task(flood())
            successes = 0
            try:
                for trial in range(20):
                    bridge.limits.clock = lambda trial=trial: trial * 61
                    payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
                    identity = new_identity()
                    data = json.dumps(pair_body(payload, identity[1])).encode()
                    head = (f"POST /v1/pair HTTP/1.1\r\nHost: my-pc\r\n"
                            f"Content-Length: {len(data)}\r\n\r\n").encode()
                    proof = head + data
                    for _ in range(3):
                        try:
                            async with client(bridge, tmp_path) as (link, reader, _):
                                split = len(proof) // 3
                                for index in range(3):
                                    await link.write(proof[index * split:(index + 1) * split]
                                                     if index < 2 else proof[index * split:])
                                    if index < 2:
                                        await asyncio.sleep(0.1)
                                status, value = await read_response(reader)
                                assert status == 200 and bridge.registry.active(value["device_id"])
                                successes += 1
                                break
                        except (ConnectionClosed, InvalidMessage, EOFError, OSError, TimeoutError):
                            pass
                assert successes >= 19
                assert peak[0] == MAX_ANONYMOUS_CONNECTIONS
                print(f"T2: {successes}/20; peak={peak[0]}; rate=20/s; split=3 records/100ms")
            finally:
                attack.cancel()
                result = await asyncio.gather(attack, return_exceptions=True)
            assert isinstance(result[0], asyncio.CancelledError)


@asynchronous
async def test_protected_pairing_survives_anonymous_completions(tmp_path, monkeypatch):
    from contextlib import AsyncExitStack
    import rasul_bridge.pairing as pairing_module

    entered, approve = asyncio.Event(), asyncio.Event()

    async def confirm(name, code):
        entered.set()
        await approve.wait()
        return True

    async with running(tmp_path, confirm) as (bridge, _):
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        identity = new_identity()
        parse = pairing_module.device_certificate

        def protected_parse(value):
            assert len(bridge.pairing_reserved) == 1
            assert not (bridge.pairing_reserved & bridge.anonymous.keys())
            return parse(value)

        monkeypatch.setattr(pairing_module, "device_certificate", protected_parse)
        async with client(bridge, tmp_path) as (link, reader, recorded):
            await send(link, "POST", "/v1/pair", pair_body(payload, identity[1]))
            await entered.wait()
            async with AsyncExitStack() as stack:
                for _ in range(1000):
                    try:
                        other, _, _ = await stack.enter_async_context(client(bridge, tmp_path))
                        await other.write(b"P")
                    except ConnectionClosed:
                        # A newcomer is an equally eligible eviction victim.
                        pass
                    assert len(bridge.pairing_reserved) == 1
                    assert recorded.socket.close_code is None
                approve.set()
                assert (await read_response(reader))[0] == 200
                print("T3: protected pairing survived 1000 anonymous completions")


@pytest.mark.parametrize(("outcome", "expected_status"), [
    ("invalid-certificate", 400), ("success", 200), ("timeout", 403),
])
@asynchronous
async def test_pairing_reservation_ends_before_authenticated_connection_closes(
        tmp_path, monkeypatch, outcome, expected_status):
    import rasul_bridge.pairing as pairing_module

    confirmations = []
    cancelled = asyncio.Event()

    async def confirm(name, code):
        confirmations.append((name, code))
        if outcome == "timeout" and len(confirmations) == 1:
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        return True

    if outcome == "timeout":
        monkeypatch.setattr(pairing_module, "OWNER_TIMEOUT", 0.1)

    async with running(tmp_path, confirm) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
            body = pair_body(payload, new_identity()[1])
            if outcome == "invalid-certificate":
                body["device_cert_pem"] = ""
            await send(link, "POST", "/v1/pair", body)
            status, value = await read_response(reader)
            assert status == expected_status
            if outcome == "success":
                assert bridge.registry.active(value["device_id"])
            if outcome == "timeout":
                assert cancelled.is_set()
            first_confirmations = 0 if outcome == "invalid-certificate" else 1
            assert len(confirmations) == first_confirmations
            await send(link, "GET", "/v1/profiles")
            assert (await read_response(reader))[0] == 200

            payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
            async with client(bridge, tmp_path) as (other, other_reader, _):
                await send(other, "POST", "/v1/pair", pair_body(payload, new_identity()[1]))
                status, value = await read_response(other_reader)
                assert status == 200, (status, value)
                assert bridge.registry.active(value["device_id"])
            assert len(confirmations) == first_confirmations + 1
            assert recorded.socket.close_code is None
            await send(link, "GET", "/v1/profiles")
            assert (await read_response(reader))[0] == 200


@asynchronous
async def test_body_size_and_pair_rate_over_real_link(tmp_path):
    async def confirm(name, code):
        return True

    async with running(tmp_path, confirm) as (bridge, _):
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        identity = new_identity()
        async with client(bridge, tmp_path) as (link, reader, _):
            await link.write(f"POST /v1/pair HTTP/1.1\r\nHost: my-pc\r\nContent-Length: {ANONYMOUS_MAX_BODY + 1}\r\n\r\n".encode())
            assert (await read_response(reader))[0] == 413
        for index in range(100):
            async with client(bridge, tmp_path) as (link, reader, _):
                await send(link, "POST", "/v1/pair", {} if index % 2 else {"inv": "my-pc"})
                assert (await read_response(reader))[0] in (400, 403)
        assert not bridge.limits.pairs
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "POST", "/v1/pair", pair_body(payload, identity[1]))
            assert (await read_response(reader))[0] == 200
        # Give the next invitation its own rate window, then charge only its id.
        bridge.limits.clock = lambda: 1000000000000
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        wrong = pair_body(payload, identity[1])
        wrong["sec"] = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
        for _ in range(5):
            async with client(bridge, tmp_path) as (link, reader, _):
                await send(link, "POST", "/v1/pair", wrong)
                assert (await read_response(reader))[0] == 403
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "POST", "/v1/pair", pair_body(payload, identity[1]))
            assert (await read_response(reader))[0] == 429
        # Paired requests retain the original 1 MiB limit.
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await link.write(f"POST /v1/profiles HTTP/1.1\r\nHost: my-pc\r\nContent-Length: {MAX_BODY + 1}\r\n\r\n".encode())
            assert (await read_response(reader))[0] == 413


@asynchronous
async def test_admission_deadlines_are_absolute(tmp_path, monkeypatch):
    monkeypatch.setattr(server_module, "UPGRADE_TIMEOUT", 0.25)
    monkeypatch.setattr(server_module, "TLS_TIMEOUT", 0.6)
    monkeypatch.setattr(server_module, "ANONYMOUS_TIMEOUT", 0.35)
    async with running(tmp_path) as (bridge, _):
        async with connect(f"ws://{bridge.config.listen}:{bridge.port}/v1/link", proxy=None) as socket:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.load_verify_locations(cadata=pem(bridge.cert).decode())
            link = TLSLink(socket, context, server_side=False)
            with pytest.raises(ssl.SSLWantReadError):
                link.ssl.do_handshake()
            await link.flush()
            async with asyncio.timeout(1):
                assert isinstance(await socket.recv(), bytes)
            socket.transport.abort()
            await socket.wait_closed()
        await wait_until(lambda: not bridge.link_tasks)
        assert not bridge.connections and not bridge.pending and not bridge.anonymous
        assert not bridge.pairing_reserved and not bridge.deadlines
        assert bridge.limits.connections == 0 and not bridge.limits.devices
        for prefix in (b"", b"GET /v1/link HTTP/1.1\r\n"):
            started = asyncio.get_running_loop().time()
            reader, writer = await asyncio.open_connection(bridge.config.listen, bridge.port)
            try:
                writer.write(prefix)
                await writer.drain()
                async with asyncio.timeout(1):
                    assert await reader.read() == b""
                assert 0.2 <= asyncio.get_running_loop().time() - started < 0.8
            finally:
                writer.close()
                await writer.wait_closed()
        started = asyncio.get_running_loop().time()
        async with connect(f"ws://127.0.0.1:{bridge.port}/v1/link", proxy=None) as socket:
            await socket.wait_closed()
            assert 0.5 <= asyncio.get_running_loop().time() - started < 1.1
        for trickle in (False, True):
            async with client(bridge, tmp_path) as (link, _, recorded):
                started = asyncio.get_running_loop().time()
                if trickle:
                    while recorded.socket.close_code is None:
                        try:
                            await link.write(b"P")
                        except ConnectionClosed:
                            break
                        await asyncio.sleep(0.03)
                await recorded.socket.wait_closed()
                assert 0.25 <= asyncio.get_running_loop().time() - started < 0.9


@asynchronous
async def test_eviction_is_seeded_and_includes_newcomer(tmp_path, monkeypatch):
    from contextlib import AsyncExitStack

    monkeypatch.setattr(server_module, "MAX_PRE_AUTH_CONNECTIONS", 4)
    monkeypatch.setattr(server_module, "MAX_ANONYMOUS_CONNECTIONS", 4)
    async with running(tmp_path, random_source=random.Random(42)) as (bridge, _):
        choose = bridge.random.choice
        victims = []

        class TraceRandom:
            def choice(self, pool):
                victim = choose(pool)
                victims.append(pool.index(victim))
                return victim

        bridge.random = TraceRandom()
        sockets = []
        try:
            for _ in range(40):
                sockets.append(await asyncio.open_connection(bridge.config.listen, bridge.port))
                await asyncio.sleep(0.002)
                assert len(bridge.pending) <= 4
            assert 4 in victims and any(0 < index < 4 for index in victims)
        finally:
            for _, writer in sockets:
                writer.close()
            await asyncio.gather(*(writer.wait_closed() for _, writer in sockets), return_exceptions=True)
        await wait_until(lambda: not bridge.pending)
        victims.clear()
        async with AsyncExitStack() as stack:
            for _ in range(40):
                try:
                    link, _, _ = await stack.enter_async_context(client(bridge, tmp_path))
                    await link.write(b"P")
                except ConnectionClosed:
                    pass
                await asyncio.sleep(0.002)
                assert len(bridge.anonymous) <= 4
            assert 4 in victims and any(0 < index < 4 for index in victims)


@asynchronous
async def test_no_per_connection_key_write_and_registry_rebuild(tmp_path, monkeypatch):
    import threading

    build, calls = server_module.server_context, []
    loop_thread = threading.get_ident()

    def counted(*args):
        assert threading.get_ident() != loop_thread
        calls.append(True)
        return build(*args)

    monkeypatch.setattr(server_module, "server_context", counted)
    async with running(tmp_path) as (bridge, _):
        assert len(calls) == 1
        for _ in range(100):
            async with client(bridge, tmp_path) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 403
        assert len(calls) == 1
        identity, device = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles")
            assert (await read_response(reader))[0] == 200
        assert len(calls) == 2
        Registry(bridge.store).revoke(device)
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", "/v1/profiles")
            assert (await read_response(reader))[0] == 403
        assert len(calls) == 3
        assert not list(bridge.store.root.rglob("key.pem"))


@asynchronous
async def test_context_rebuild_survives_requester_disconnect(tmp_path, monkeypatch):
    import threading

    build, calls = server_module.server_context, []
    entered, finish = threading.Event(), threading.Event()

    def slow_build(*args):
        calls.append(True)
        if len(calls) > 1:
            entered.set()
            assert finish.wait(5)
        return build(*args)

    monkeypatch.setattr(server_module, "server_context", slow_build)
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        socket = await connect(f"ws://127.0.0.1:{bridge.port}/v1/link", proxy=None)
        other = None

        async def legitimate():
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 200

        try:
            await wait_until(entered.is_set)
            socket.transport.abort()
            await wait_until(lambda: not bridge.link_tasks)
            other = asyncio.create_task(legitimate())
            await asyncio.sleep(0.05)
            assert len(calls) == 2
        finally:
            finish.set()
            await socket.wait_closed()
            if other is not None:
                await other
        assert len(calls) == 2


def test_context_key_cleanup_on_load_failure(tmp_path):
    store = Store(tmp_path / "data")
    ensure_identity(store)
    (store.root / "server.pem").write_bytes(b"invalid")
    before = set(store.root.iterdir())
    with pytest.raises(ssl.SSLError):
        server_module.server_context(store, {})
    assert set(store.root.iterdir()) == before
    assert not list(store.root.rglob("key.pem"))


@asynchronous
async def test_validated_certificate_handshakes_leave_pre_auth_immediately(tmp_path, monkeypatch):
    monkeypatch.setattr(server_module, "MAX_PRE_AUTH_CONNECTIONS", 4)
    async with running(tmp_path) as (bridge, _):
        identity, device = registered(bridge)
        full, resume = asyncio.Event(), asyncio.Event()
        flush = TLSLink.flush
        sockets, clients = [], []

        async def paused_flush(link):
            connection = link.websocket
            if bridge.connections.get(connection) == device:
                assert connection not in bridge.pending
                if bridge.limits.connections == 3:
                    full.set()
                await resume.wait()
            await flush(link)

        monkeypatch.setattr(TLSLink, "flush", paused_flush)

        async def legitimate():
            async with client(bridge, tmp_path, identity) as (link, reader, _):
                await send(link, "GET", "/v1/profiles")
                assert (await read_response(reader))[0] == 200

        try:
            clients = [asyncio.create_task(legitimate()) for _ in range(3)]
            await full.wait()
            assert not bridge.pending and bridge.limits.connections == 3
            for _ in range(40):
                sockets.append(await asyncio.open_connection(bridge.config.listen, bridge.port))
                await asyncio.sleep(0.002)
                assert len(bridge.pending) <= 4
                assert bridge.limits.connections == 3
            resume.set()
            await asyncio.gather(*clients)
        finally:
            resume.set()
            for task in clients:
                task.cancel()
            await asyncio.gather(*clients, return_exceptions=True)
            for _, writer in sockets:
                writer.close()
            await asyncio.gather(*(writer.wait_closed() for _, writer in sockets), return_exceptions=True)


def test_audit_recovery_retains_rejections_and_reports_drops(tmp_path, monkeypatch, capsys):
    audit = Audit(Store(tmp_path / "data"), clock=lambda: 61)
    audit.last_summary = 0
    write = audit._write

    def fail(value):
        raise OSError("Audit unavailable")

    monkeypatch.setattr(audit, "_write", fail)
    audit.reject()
    audit.reject()
    assert audit.rejected == 2 and audit.last_summary == 0
    assert audit.dropped == 2
    assert capsys.readouterr().err.splitlines() == ["Audit records dropped"]
    monkeypatch.setattr(audit, "_write", write)
    audit.flush_rejections()
    rows = [json.loads(line) for line in audit.path.read_text().splitlines()]
    assert rows[0]["rejected"] == 2
    assert rows[1]["dropped_records"] == 2
    assert not audit.rejected and not audit.dropped and audit.last_summary == 61
    # A failed recovery-summary append must retain its count for the next write.
    audit.drop()

    def fail_summary(value):
        if "dropped_records" in value:
            raise OSError("Audit unavailable")
        write(value)

    monkeypatch.setattr(audit, "_write", fail_summary)
    assert audit.write({"status": 200}) is True
    assert audit.dropped == 1
    monkeypatch.setattr(audit, "_write", write)
    assert audit.write({"status": 200}) is True
    assert json.loads(audit.path.read_text().splitlines()[-1])["dropped_records"] == 1
    assert audit.dropped == 0 and capsys.readouterr().err == ""


def test_qr_falls_back_to_the_image_on_a_console_without_block_characters():
    import io, qrcode
    code = qrcode.QRCode(border=4)
    code.add_data("rasul-pair:1:test")
    narrow = io.TextIOWrapper(io.BytesIO(), encoding="cp1252")
    show_qr(code, narrow)
    narrow.flush()
    assert b"Open the image below" in narrow.buffer.getvalue()
    wide = io.StringIO()
    show_qr(code, wide)
    assert "█" in wide.getvalue()


@asynchronous
async def test_instructions_load_save_conflict_and_audit(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, device = registered(bridge)
        soul = bridge.config.hermes_home / "SOUL.md"
        original = "Be clear.\r\nأجب بوضوح.\n".encode("utf-8")
        soul.write_bytes(original)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            target = "/v1/profiles/default/instructions"
            await send(link, "GET", target)
            status, value = await read_response(reader)
            assert status == 200
            assert value == {"text": original.decode("utf-8"), "revision": hashlib.sha256(original).hexdigest()}
            soul.write_bytes(b"Changed locally")
            await send(link, "PUT", target, {"text": "Phone edit", "revision": value["revision"]})
            assert await read_response(reader) == (409, {"error": "instructions_conflict"})
            assert soul.read_bytes() == b"Changed locally"
            await send(link, "GET", target)
            _, value = await read_response(reader)
            await send(link, "PUT", target, {"text": "Phone edit", "revision": value["revision"]})
            assert await read_response(reader) == (200, {"revision": hashlib.sha256(b"Phone edit").hexdigest()})
            assert soul.read_bytes() == b"Phone edit"
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert any(row.get("route") == "/v1/profiles/{id}/instructions" and row["profile_id"] == "default"
                   and row["status"] == 409 for row in rows)
        assert "Phone edit" not in bridge.audit.path.read_text()
        assert "Changed locally" not in bridge.audit.path.read_text()
        assert not upstream.requests


@pytest.mark.parametrize("method", ["GET", "PUT"])
@asynchronous
async def test_instructions_unpaired_denied(tmp_path, method):
    async with running(tmp_path) as (bridge, _):
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, method, "/v1/profiles/default/instructions", {})
            assert (await read_response(reader))[0] == 403


@pytest.mark.parametrize("profile", ["missing", "..", "%2e%2e", "default/..", "Default"])
@asynchronous
async def test_instructions_unknown_and_traversal(tmp_path, profile):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/" + profile + "/instructions")
            assert (await read_response(reader))[0] == 404


@asynchronous
async def test_instructions_empty_missing_and_named_home(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        named = bridge.config.hermes_home / "profiles" / "sample"
        named.mkdir(parents=True)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            for profile in ("default", "sample"):
                target = "/v1/profiles/" + profile + "/instructions"
                await send(link, "GET", target)
                status, value = await read_response(reader)
                assert (status, value) == (200, {"text": "", "revision": hashlib.sha256(b"").hexdigest()})
                await send(link, "PUT", target, dict(value, text=""))
                assert (await read_response(reader))[0] == 200
        assert (named / "SOUL.md").read_bytes() == b""


@pytest.mark.parametrize("data,status,token", [pytest.param(b"x" * 20001, 413, "instructions_too_large", id="oversize"),
                                               (b"\xff", 400, "instructions_not_utf8")])
@asynchronous
async def test_instructions_invalid_existing_file(tmp_path, data, status, token):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        (bridge.config.hermes_home / "SOUL.md").write_bytes(data)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/instructions")
            assert await read_response(reader) == (status, {"error": token})


@pytest.mark.parametrize("body,status,token", [
    ({"text": "أ" * 10001, "revision": hashlib.sha256(b"").hexdigest()}, 413, "instructions_too_large"),
    ({"text": "ok", "revision": "bad"}, 400, "instructions_invalid"),
    (b"{\"text\":\"\xff\"}", 400, "instructions_invalid"),
    ({"text": 3, "revision": hashlib.sha256(b"").hexdigest()}, 400, "instructions_invalid"),
])
@asynchronous
async def test_instructions_invalid_save(tmp_path, body, status, token):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "PUT", "/v1/profiles/default/instructions", body)
            assert await read_response(reader) == (status, {"error": token})
        assert not (bridge.config.hermes_home / "SOUL.md").exists()


@pytest.mark.parametrize("parent", [False, True])
@asynchronous
async def test_instructions_symlink_denied(tmp_path, parent):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        outside = tmp_path / "outside"
        outside.mkdir()
        (outside / "SOUL.md").write_bytes(b"Private content")
        home = bridge.config.hermes_home
        if parent:
            (home / "profiles").mkdir()
            link_path = home / "profiles" / "sample"
            link_target = outside
            profile = "sample"
        else:
            link_path = home / "SOUL.md"
            link_target = outside / "SOUL.md"
            profile = "default"
        if os.name == "nt" and parent:
            import _winapi
            _winapi.CreateJunction(str(link_target), str(link_path))
        else:
            try:
                link_path.symlink_to(link_target, target_is_directory=parent)
            except OSError as error:
                if getattr(error, "winerror", None) == 1314:
                    pytest.skip("File symlinks require an operating-system privilege")
                raise
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            for method in ("GET", "PUT"):
                await send(link, method, "/v1/profiles/" + profile + "/instructions",
                           {"text": "edit", "revision": hashlib.sha256(b"Private content").hexdigest()})
                assert (await read_response(reader))[0] in (403, 404)
        assert (outside / "SOUL.md").read_bytes() == b"Private content"


@asynchronous
async def test_instructions_rate_limit(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            for _ in range(5):
                await send(link, "GET", "/v1/profiles/default/instructions")
                assert (await read_response(reader))[0] == 200
            await send(link, "GET", "/v1/profiles/default/instructions")
            assert await read_response(reader) == (429, {"error": "instructions_rate_limited"})
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert any(row.get("route") == "/v1/profiles/{id}/instructions" and row.get("profile_id") == "default"
                   and row.get("status") == 429 for row in rows)


@asynchronous
async def test_instructions_atomic_failure_keeps_original_and_permissions(tmp_path, monkeypatch):
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        if os.name != "nt":
            soul.chmod(0o640)
        before = soul.stat().st_mode
        value = bridge.instructions.call("default")
        bridge.instructions.call("default", dict(value, text="Saved"))
        assert soul.stat().st_mode == before
        def fail(_):
            raise OSError("Storage unavailable")
        monkeypatch.setattr(instructions.os, "fsync", fail)
        with pytest.raises(HTTPError) as error:
            bridge.instructions.call("default", {"text": "Never committed", "revision": hashlib.sha256(b"Saved").hexdigest()})
        assert (error.value.status, error.value.message) == (503, "instructions_unavailable")
        assert soul.read_bytes() == b"Saved"
        assert not list(soul.parent.glob(".instructions-*"))


@asynchronous
async def test_instructions_rechecks_external_edit_before_commit(tmp_path, monkeypatch):
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        original_fsync = instructions.os.fsync
        def edit(fd):
            original_fsync(fd)
            soul.write_bytes(b"Concurrent edit")
        monkeypatch.setattr(instructions.os, "fsync", edit)
        with pytest.raises(HTTPError) as error:
            bridge.instructions.call("default", {"text": "Phone edit", "revision": hashlib.sha256(b"Original").hexdigest()})
        assert (error.value.status, error.value.message) == (409, "instructions_conflict")
        assert soul.read_bytes() == b"Concurrent edit"
        assert not list(soul.parent.glob(".instructions-*"))


def test_instructions_reparse_attribute_denied():
    from types import SimpleNamespace
    import stat
    from rasul_bridge.instructions import _safe
    with pytest.raises(HTTPError) as error:
        _safe(SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_file_attributes=0x400))
    assert (error.value.status, error.value.message) == (403, "instructions_unsafe_path")


@asynchronous
async def test_instructions_replacement_failure_cannot_remove_original(tmp_path, monkeypatch):
    import ctypes
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        real_kernel = instructions._kernel
        class Kernel:
            def __getattr__(self, name):
                return getattr(real_kernel(), name)
            def ReplaceFileW(self, target, replacement, *_):
                # Documented failure 1176 can remove the destination without a backup.
                Path(target).unlink()
                ctypes.set_last_error(1176)
                return 0
        monkeypatch.setattr(instructions, "_kernel", Kernel)
        try:
            bridge.instructions.call("default", {"text": "Replacement", "revision": hashlib.sha256(b"Original").hexdigest()})
        except HTTPError as error:
            assert error.status == 503
            assert soul.read_bytes() == b"Original"
        else:
            assert soul.read_bytes() == b"Replacement"
        assert not list(soul.parent.glob(".instructions-*"))


@asynchronous
async def test_instructions_leaf_junction_denied(tmp_path):
    async with running(tmp_path) as (bridge, _):
        outside = tmp_path / "outside"
        outside.mkdir()
        soul = bridge.config.hermes_home / "SOUL.md"
        if os.name == "nt":
            import _winapi
            _winapi.CreateJunction(str(outside), str(soul))
        else:
            soul.symlink_to(outside, target_is_directory=True)
        for body in (None, {"text": "edit", "revision": hashlib.sha256(b"").hexdigest()}):
            with pytest.raises(HTTPError) as error:
                bridge.instructions.call("default", body)
            assert (error.value.status, error.value.message) == (403, "instructions_unsafe_path")
        assert list(outside.iterdir()) == []


@asynchronous
async def test_instructions_preserves_protected_file_dacl(tmp_path):
    if os.name != "nt":
        async with running(tmp_path) as (bridge, _):
            soul = bridge.config.hermes_home / "SOUL.md"
            soul.write_bytes(b"Original")
            soul.chmod(0o600)
            before = soul.stat().st_mode
            bridge.instructions.call("default", {"text": "Saved", "revision": hashlib.sha256(b"Original").hexdigest()})
            assert soul.stat().st_mode == before
            assert soul.read_bytes() == b"Saved"
        return
    import ctypes
    from ctypes import wintypes as w
    from rasul_bridge.storage import current_sid, windows_api
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        kernel, security = windows_api()
        descriptor = w.LPVOID()
        assert security.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            "D:P(A;;FA;;;" + current_sid() + ")", 1, ctypes.byref(descriptor), None)
        try:
            assert security.SetFileSecurityW(str(soul), 0x80000004, descriptor)
        finally:
            kernel.LocalFree(descriptor)
        security.GetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, w.LPVOID, w.DWORD, ctypes.POINTER(w.DWORD)]
        def permissions():
            length = w.DWORD()
            security.GetFileSecurityW(str(soul), 4, None, 0, ctypes.byref(length))
            data = ctypes.create_string_buffer(length.value)
            assert security.GetFileSecurityW(str(soul), 4, data, length, ctypes.byref(length))
            return data.raw
        before = permissions()
        bridge.instructions.call("default", {"text": "Saved", "revision": hashlib.sha256(b"Original").hexdigest()})
        assert permissions() == before
        assert soul.read_bytes() == b"Saved"


@asynchronous
async def test_instructions_replace_error_keeps_original(tmp_path, monkeypatch):
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        def fail(*args, **kwargs):
            raise OSError("Replacement unavailable")
        monkeypatch.setattr(instructions.os, "replace", fail)
        with pytest.raises(HTTPError) as error:
            bridge.instructions.call("default", {"text": "Not saved", "revision": hashlib.sha256(b"Original").hexdigest()})
        assert (error.value.status, error.value.message) == (503, "instructions_unavailable")
        assert soul.read_bytes() == b"Original"
        assert not list(soul.parent.glob(".instructions-*"))


@pytest.mark.parametrize("method", ["GET", "PUT"])
@asynchronous
async def test_instructions_hardlink_denied(tmp_path, method):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        outside = tmp_path / "outside"
        outside.write_bytes(b"Private content")
        soul = bridge.config.hermes_home / "SOUL.md"
        os.link(outside, soul)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, method, "/v1/profiles/default/instructions",
                       {"text": "edit", "revision": hashlib.sha256(b"Private content").hexdigest()})
            assert await read_response(reader) == (403, {"error": "instructions_unsafe_path"})
        assert outside.read_bytes() == soul.read_bytes() == b"Private content"
        assert soul.stat().st_nlink == 2


@asynchronous
async def test_instructions_rechecks_hardlink_before_commit(tmp_path, monkeypatch):
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        outside = tmp_path / "outside"
        original_fsync = instructions.os.fsync
        def link(fd):
            original_fsync(fd)
            if not outside.exists():
                os.link(soul, outside)
        monkeypatch.setattr(instructions.os, "fsync", link)
        with pytest.raises(HTTPError) as error:
            bridge.instructions.call("default", {"text": "edit", "revision": hashlib.sha256(b"Original").hexdigest()})
        assert (error.value.status, error.value.message) == (403, "instructions_unsafe_path")
        assert outside.read_bytes() == soul.read_bytes() == b"Original"
        assert not list(soul.parent.glob(".instructions-*"))


@asynchronous
async def test_instructions_hardlink_handle_denied(tmp_path, monkeypatch):
    from types import SimpleNamespace
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        outside = tmp_path / "outside"
        outside.write_bytes(b"Private content")
        os.link(outside, bridge.config.hermes_home / "SOUL.md")
        original_fstat = os.fstat
        def one_link(fd):
            info = original_fstat(fd)
            return SimpleNamespace(st_mode=info.st_mode, st_file_attributes=getattr(info, "st_file_attributes", 0),
                                   st_nlink=1 if os.name == "nt" else info.st_nlink)
        monkeypatch.setattr(instructions.os, "fstat", one_link)
        with pytest.raises(HTTPError) as error:
            bridge.instructions.call("default")
        assert (error.value.status, error.value.message) == (403, "instructions_unsafe_path")


@pytest.mark.skipif(sys.platform != "linux", reason="Linux access ACL")
@pytest.mark.parametrize("failure", [None, "read", "write"])
@asynchronous
async def test_instructions_preserves_access_acl(tmp_path, monkeypatch, failure):
    import errno
    import shutil
    import subprocess
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        if shutil.which("setfacl"):
            subprocess.run(["setfacl", "-m", "u:65534:r--", str(soul)], check=True)
            expected = os.getxattr(soul, "system.posix_acl_access")
            getxattr, setxattr = os.getxattr, os.setxattr
        else:
            expected = b"access rules"
            stored = {}
            def getxattr(fd, name):
                assert name == "system.posix_acl_access"
                return expected
            def setxattr(fd, name, value):
                assert name == "system.posix_acl_access"
                stored[os.fstat(fd).st_ino] = value
        calls = []
        def read(fd, name):
            calls.append("read")
            if failure == "read":
                raise OSError(errno.EACCES, "Access rules unavailable")
            return getxattr(fd, name)
        def write(fd, name, value):
            calls.append("write")
            if failure == "write":
                raise OSError(errno.EACCES, "Access rules unavailable")
            return setxattr(fd, name, value)
        before = soul.stat()
        monkeypatch.setattr(instructions.os, "getxattr", read)
        monkeypatch.setattr(instructions.os, "setxattr", write)
        body = {"text": "Saved", "revision": hashlib.sha256(b"Original").hexdigest()}
        if failure:
            with pytest.raises(HTTPError) as error:
                bridge.instructions.call("default", body)
            assert (error.value.status, error.value.message) == (503, "instructions_unavailable")
            assert soul.read_bytes() == b"Original"
            assert soul.stat().st_ino == before.st_ino
        else:
            assert bridge.instructions.call("default", body) == {"revision": hashlib.sha256(b"Saved").hexdigest()}
            assert calls == ["read", "write"]
            if shutil.which("setfacl"):
                assert getxattr(soul, "system.posix_acl_access") == expected
            else:
                assert stored[soul.stat().st_ino] == expected
            assert soul.stat().st_mode == before.st_mode
            assert (soul.stat().st_uid, soul.stat().st_gid) == (before.st_uid, before.st_gid)
            assert soul.read_bytes() == b"Saved"
        assert not list(soul.parent.glob(".instructions-*"))


@pytest.mark.skipif(sys.platform != "linux", reason="Linux access ACL")
@pytest.mark.parametrize("failure", [None, "read", "write"])
@asynchronous
async def test_instructions_removes_inherited_access_acl(tmp_path, monkeypatch, failure):
    import errno
    import subprocess
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        subprocess.run(["setfacl", "-m", "d:u:65534:r--", str(soul.parent)], check=True)
        soul.write_bytes(b"Original")
        subprocess.run(["setfacl", "-b", str(soul)], check=True)
        soul.chmod(0o640)
        with pytest.raises(OSError) as error:
            os.getxattr(soul, "system.posix_acl_access")
        assert error.value.errno == errno.ENODATA
        before = soul.stat()
        remove = os.removexattr
        calls = []
        def write(fd, name):
            assert isinstance(fd, int)
            assert name == "system.posix_acl_access"
            calls.append(fd)
            if failure:
                raise OSError(errno.EACCES if failure == "read" else errno.EIO,
                              "Access rules unavailable")
            return remove(fd, name)
        monkeypatch.setattr(instructions.os, "removexattr", write)
        body = {"text": "Saved", "revision": hashlib.sha256(b"Original").hexdigest()}
        if failure:
            with pytest.raises(HTTPError) as error:
                bridge.instructions.call("default", body)
            assert (error.value.status, error.value.message) == (503, "instructions_unavailable")
            assert soul.read_bytes() == b"Original"
            assert soul.stat().st_ino == before.st_ino
        else:
            assert bridge.instructions.call("default", body) == {"revision": hashlib.sha256(b"Saved").hexdigest()}
            assert soul.read_bytes() == b"Saved"
        acl = subprocess.run(["getfacl", "-cpn", str(soul)], check=True,
                             capture_output=True, text=True).stdout
        print(acl, end="")
        print(f"mode={soul.stat().st_mode & 0o777:04o}")
        assert "user:65534:" not in acl
        assert soul.stat().st_mode == before.st_mode
        assert (soul.stat().st_uid, soul.stat().st_gid) == (before.st_uid, before.st_gid)
        assert len(calls) == 1
        assert not list(soul.parent.glob(".instructions-*"))


@pytest.mark.skipif(sys.platform != "linux", reason="Linux access ACL")
@pytest.mark.parametrize("data", ["ENODATA", "ENOTSUP"])
@pytest.mark.parametrize("failure", ["ENODATA", "ENOTSUP"])
@asynchronous
async def test_instructions_absent_access_acl(tmp_path, monkeypatch, data, failure):
    import errno
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        soul.chmod(0o640)
        calls = []
        def read(fd, name):
            raise OSError(getattr(errno, data), "Access rules unavailable")
        def write(fd, name):
            assert isinstance(fd, int)
            assert name == "system.posix_acl_access"
            calls.append(fd)
            raise OSError(getattr(errno, failure), "Access rules unavailable")
        monkeypatch.setattr(instructions.os, "getxattr", read)
        monkeypatch.setattr(instructions.os, "removexattr", write)
        body = {"text": "Saved", "revision": hashlib.sha256(b"Original").hexdigest()}
        assert bridge.instructions.call("default", body) == {"revision": hashlib.sha256(b"Saved").hexdigest()}
        assert soul.read_bytes() == b"Saved"
        assert soul.stat().st_mode & 0o777 == 0o640
        assert len(calls) == 1
        assert not list(soul.parent.glob(".instructions-*"))


@pytest.mark.skipif(os.name == "nt", reason="POSIX directory durability")
@asynchronous
async def test_instructions_directory_fsync_failure_is_committed(tmp_path, monkeypatch):
    import stat
    import rasul_bridge.instructions as instructions
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        soul = bridge.config.hermes_home / "SOUL.md"
        soul.write_bytes(b"Original")
        original_fsync = instructions.os.fsync
        failed = []
        def sync(fd):
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                failed.append(fd)
                raise OSError("Directory durability unavailable")
            original_fsync(fd)
        monkeypatch.setattr(instructions.os, "fsync", sync)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "PUT", "/v1/profiles/default/instructions",
                       {"text": "Saved", "revision": hashlib.sha256(b"Original").hexdigest()})
            assert await read_response(reader) == (200, {"revision": hashlib.sha256(b"Saved").hexdigest()})
        assert len(failed) == 1
        assert soul.read_bytes() == b"Saved"
        assert not list(soul.parent.glob(".instructions-*"))


@asynchronous
async def test_session_actions_reject_unlisted_fields_and_invalid_types(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            cases = [
                ("fork", {"title": "sample", "id": "chosen"}),
                ("fork", {"title": 1}),
                ("fork", []),
                ("model", {"model": "sample", "provider": "sample", "api_key": "sample"}),
                ("model", {"model": "sample", "provider": "sample", "base_url": "https://example.invalid"}),
                ("model", {"model": 1}),
                ("model", {"provider": {}}),
                ("model", {"require_model_lock": "true"}),
                ("model", []),
            ]
            for action, body in cases:
                await send(link, "POST", "/v1/profiles/default/hermes/api/sessions/one/" + action, body)
                assert await read_response(reader) == (400, {"error": action + "_invalid_fields"}), (action, body)
            assert upstream.requests == []


@asynchronous
async def test_session_actions_reserialize_allowed_bodies(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            for action, body in (
                ("fork", {"title": "sample"}),
                ("model", {"model": "sample", "provider": "sample", "require_model_lock": True}),
            ):
                await send(link, "POST", "/v1/profiles/default/hermes/api/sessions/one/" + action, body,
                           extra="Content-Type: text/plain\r\n")
                assert (await read_response(reader))[0] == 200
                assert upstream.requests[-1][2]["content-type"] == "application/json"
                assert json.loads(upstream.requests[-1][3]) == body


@asynchronous
async def test_session_action_audit_records_prefixed_route_and_device_limit(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, device = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            for action, count in (("fork", 6), ("model", 20)):
                for _ in range(count + 1):
                    await send(link, "POST", "/v1/profiles/default/hermes/api/sessions/one/" + action, {})
                    status, _ = await read_response(reader)
                assert status == 429
                rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
                assert any(row.get("route") == "/v1/profiles/{id}/hermes/api/sessions/{id}/" + action
                           and row.get("device_id") == device and row.get("status") == 429 for row in rows)


def test_session_action_global_cap_evicts_oldest_key():
    now = [0]
    limits = Limits(clock=lambda: now[0])
    for i in range(256):
        assert limits.fork(str(i), "default")
        now[0] += 0.01
    assert limits.fork("new", "default")
    assert len(limits.session_requests) == 256
    assert ("0", "default", "fork") not in limits.session_requests
    assert ("1", "default", "fork") in limits.session_requests
