"""Live SSE relay exercised against a disposable loopback WebSocket backend."""

import asyncio
from contextlib import AsyncExitStack, asynccontextmanager
import hashlib
import json
import os
import secrets
import sqlite3
import subprocess
import sys

import pytest
import time
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

from rasul_bridge import live as live_module
from rasul_bridge.desktop import process_times
from rasul_bridge.http import headers
from rasul_bridge.live import ALLOWED_METHODS
from rasul_bridge.proxy import response_body
from test_bridge import asynchronous, client, registered, running, send
from test_desktop import process_start_time


TARGET = "/v1/profiles/default/live/chat-1"
SESSION = {"id": "rt-1", "session_key": "chat-1", "status": "working", "current": False,
           "last_active": 1.0, "message_count": 2, "model": "m", "preview": "hi",
           "started_at": 1.0, "title": "chat"}


class FakeBackend:
    """Minimal JSON-RPC endpoint on a real WebSocket: ready, ping replies, canned RPC results."""

    def __init__(self, directory):
        self.directory = directory
        self.token = secrets.token_urlsafe(32)
        self.pid = os.getpid()
        self.epoch = "e" + secrets.token_hex(8)
        self.sessions = []
        self.activate_result = {"session_id": "rt-1", "session_key": "chat-1", "running": True,
                                "turn_started_at": 1700.5, "status": "working",
                                "messages_omitted": True, "message_count": 2}
        self.activate_error = None
        self.since_result = None          # dict or callable(params) -> result payload
        self.since_error = None
        self.answer_ping = True
        self.serve_ready = True
        self.events = []                  # frames pushed right after a successful activate reply
        self.before_active_list = []      # frames pushed before the active_list reply
        self.push_gap = 0                 # seconds between those pushed frames
        self.traffic = False              # keep pushing deltas until the socket dies
        self.close_after_events = None    # close code sent after the scripted events
        self.frames = []                  # every decoded frame received from the bridge
        self.handshakes = []              # raw header items of each accepted upgrade
        self.since_calls = []
        self.resume_calls = []
        self.activate_calls = []
        self.connections = 0
        self.closed = asyncio.Event()

    def event(self, kind, seq, payload=None, session="rt-1"):
        params = {"type": kind, "session_id": session, "seq": seq}
        if payload is not None:
            params["payload"] = payload
        return {"jsonrpc": "2.0", "method": "event", "params": params}

    def global_event(self, kind, payload=None):
        """Session-less broadcast frame: the real bus fans these out with ``session_id`` ""."""
        params = {"type": kind, "session_id": ""}
        if payload is not None:
            params["payload"] = payload
        return {"jsonrpc": "2.0", "method": "event", "params": params}

    async def handle(self, ws):
        self.connections += 1
        self.handshakes.append(list(ws.request.headers.raw_items()))
        try:
            if self.serve_ready:
                await ws.send(json.dumps({"jsonrpc": "2.0", "method": "event",
                                          "params": {"type": "gateway.ready",
                                                     "payload": {"replay_epoch": self.epoch}}}))
            async for raw in ws:
                try:
                    message = json.loads(raw)
                except ValueError:
                    continue
                self.frames.append(message)
                method, rid = message.get("method"), message.get("id")
                if method == "gateway.ping":
                    if self.answer_ping:
                        await ws.send(json.dumps({"jsonrpc": "2.0", "result": {"ok": True}, "id": rid}))
                elif method == "session.active_list":
                    for frame in self.before_active_list:
                        await ws.send(json.dumps(frame))
                        await asyncio.sleep(self.push_gap)
                    await ws.send(json.dumps({"jsonrpc": "2.0",
                                              "result": {"sessions": self.sessions}, "id": rid}))
                elif method == "session.resume":
                    self.resume_calls.append(message.get("params"))
                    await ws.send(json.dumps({"jsonrpc": "2.0",
                                              "error": {"code": 4001, "message": "unexpected"},
                                              "id": rid}))
                elif method == "session.activate":
                    self.activate_calls.append(message.get("params"))
                    if self.activate_error is not None:
                        await ws.send(json.dumps({"jsonrpc": "2.0", "error": self.activate_error,
                                                  "id": rid}))
                    else:
                        await ws.send(json.dumps({"jsonrpc": "2.0", "result": self.activate_result,
                                                  "id": rid}))
                        for frame in self.events:
                            await ws.send(json.dumps(frame))
                        if self.traffic:
                            count = 0
                            while True:
                                count += 1
                                await ws.send(json.dumps(
                                    self.event("message.delta", 10000 + count, {"text": "x"})))
                        if self.close_after_events is not None:
                            await ws.close(code=self.close_after_events)
                elif method == "session.events.since":
                    self.since_calls.append(message.get("params"))
                    if self.since_error is not None:
                        await ws.send(json.dumps({"jsonrpc": "2.0", "error": self.since_error, "id": rid}))
                    else:
                        payload = (self.since_result(message.get("params"))
                                   if callable(self.since_result) else self.since_result)
                        await ws.send(json.dumps({"jsonrpc": "2.0", "result": payload, "id": rid}))
        except ConnectionClosed:
            pass
        finally:
            self.connections -= 1
            self.closed.set()

    def publish(self, profiles=("default",)):
        record = {"role": "desktop-serve", "pid": self.pid, "host": "127.0.0.1", "port": self.port,
                  "protocolVersion": 1,
                  "tokenFingerprint": hashlib.sha256(self.token.encode()).hexdigest()[:16],
                  "profiles": list(profiles), "startTime": process_start_time(self.pid),
                  "createTime": process_times(self.pid)[1], "updatedAt": "2026-10-20T00:00:00+00:00",
                  "home": ""}
        (self.directory / "host-desktop-serve.json").write_text(json.dumps(record))
        token_file = self.directory / "host-desktop-serve.token"
        token_file.write_text(self.token)
        token_file.chmod(0o600)


def store_session(directory, key):
    """Insert ``key`` into ``directory``'s state.db as a minimal sessions row."""
    directory.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(str(directory / "state.db"))
    try:
        connection.execute("CREATE TABLE IF NOT EXISTS sessions ("
                           "id TEXT PRIMARY KEY, source TEXT NOT NULL, started_at REAL NOT NULL)")
        connection.execute("INSERT OR IGNORE INTO sessions (id, source, started_at) "
                           "VALUES (?, 'test', 1.0)", (key,))
        connection.commit()
    finally:
        connection.close()


@asynccontextmanager
async def backend(tmp_path, monkeypatch, publish=True):
    directory = tmp_path / "locks"
    directory.mkdir(mode=0o700)
    monkeypatch.setenv("HERMES_GATEWAY_LOCK_DIR", str(directory))
    fake = FakeBackend(directory)
    server = await serve(fake.handle, "127.0.0.1", 0)
    fake.port = server.sockets[0].getsockname()[1]
    if publish:
        fake.publish()
    try:
        async with running(tmp_path) as (bridge, _):
            store_session(bridge.config.hermes_home, "chat-1")
            yield bridge, fake
    finally:
        server.close()
        await server.wait_closed()


def parse_block(raw):
    if raw.startswith(b":"):
        return ("comment", None, None)
    fields = {}
    for line in raw.decode().strip().split("\n"):
        name, _, value = line.partition(":")
        fields[name.strip()] = value.strip()
    data = fields.get("data")
    seq = fields.get("id")
    if seq is not None:
        seq = seq.rsplit(":", 1)[-1]
    return (fields.get("event"), json.loads(data) if data is not None else None,
            int(seq) if seq is not None and seq.isdigit() else None)


class Sse:
    """Sequential SSE reader over the chunked body; survives split and coalesced blocks."""

    def __init__(self, reader, head):
        self.gen = response_body(reader, head)
        self.buf = b""
        self.ended = False
        self.raw_blocks = []

    async def next(self, timeout=5):
        while True:
            index = self.buf.find(b"\n\n")
            if index >= 0:
                block, self.buf = self.buf[:index + 2], self.buf[index + 2:]
                self.raw_blocks.append(block)
                return parse_block(block)
            if self.ended:
                if self.buf:
                    raise AssertionError("partial trailing block " + repr(self.buf))
                raise StopAsyncIteration
            try:
                data = await asyncio.wait_for(self.gen.__anext__(), timeout)
            except StopAsyncIteration:
                self.ended = True
                continue
            except TimeoutError:
                raise TimeoutError("no SSE block within %ss" % timeout) from None
            self.buf += data

    async def rest(self, timeout=5):
        result = []
        while True:
            try:
                result.append(await self.next(timeout))
            except StopAsyncIteration:
                return result


async def open_stream(reader, timeout=5):
    raw = (await asyncio.wait_for(reader.until(b"\r\n\r\n"), timeout)).split(b"\r\n")
    return int(raw[0].split()[1]), headers(raw[1:])


async def read_full(reader, timeout=5):
    status, head = await open_stream(reader, timeout)
    body = bytearray()
    async for data in response_body(reader, head):
        body.extend(data)
    return status, head, bytes(body)


@pytest.mark.parametrize("failure", ["none", "stale-pid", "fingerprint", "profile"])
@asynchronous
async def test_live_unavailable_variants(tmp_path, monkeypatch, failure):
    async with backend(tmp_path, monkeypatch, publish=False) as (bridge, fake):
        if failure != "none":
            fake.publish()
            record = json.loads((fake.directory / "host-desktop-serve.json").read_text())
            if failure == "stale-pid":
                dead = subprocess.Popen([sys.executable, "-c", "pass"])
                dead.wait()
                record["pid"] = dead.pid
            elif failure == "fingerprint":
                record["tokenFingerprint"] = "0" * 16
            elif failure == "profile":
                record["profiles"] = ["other"]
            (fake.directory / "host-desktop-serve.json").write_text(json.dumps(record))
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200 and head["content-type"] == "text/event-stream"
            reason = "profile-not-served" if failure == "profile" else "desktop-not-running"
            assert await Sse(reader, head).rest() == [("unavailable", {"reason": reason}, None)]
            assert fake.frames == []


@asynchronous
async def test_live_idle_when_not_live_and_never_resumes(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [("idle", {}, None)]
            assert [f.get("method") for f in fake.frames] == ["session.active_list"]


@asynchronous
async def test_live_relays_only_allowed_fields_in_order(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [
            fake.event("message.start", 1),
            fake.event("message.delta", 2, {"text": "he", "rendered": "<b>he</b>", "verbose": True}),
            fake.event("tool.start", 3, {"tool_id": "t1", "name": "shell",
                                         "args": {"cmd": "secret"}, "args_text": "secret"}),
            fake.event("tool.complete", 4, {"tool_id": "t1", "name": "shell",
                                            "result": {"out": "secret"}, "summary": "s" * 600}),
            fake.event("status.update", 5, {"kind": "status", "text": "x" * 240}),
            fake.event("tool.generating", 6, {"name": "shell"}),
            fake.event("message.delta", 7, {"text": "other"}, session="rt-other"),
            {"jsonrpc": "2.0", "id": "srq-1", "method": "approval.request",
             "params": {"session_id": "rt-1", "prompt": "allow?"}},
            fake.event("message.complete", 8, {"status": "complete", "text": "done",
                                               "usage": {"input_tokens": 9}, "error": None}),
        ]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            seen = [await sse.next() for _ in range(7)]
            assert seen == [
                ("snapshot", {"running": True, "turn_started_at": 1700.5}, None),
                ("message.start", {}, 1),
                ("message.delta", {"text": "he"}, 2),
                ("tool.start", {"tool_id": "t1", "name": "shell"}, 3),
                ("tool.complete", {"tool_id": "t1", "name": "shell", "summary": "s" * 512}, 4),
                ("status.update", {"text": "x" * 200}, 5),
                ("message.complete", {"status": "complete", "text": "done"}, 8)]
            with pytest.raises(TimeoutError):
                await sse.next(0.5)
            raw = b"".join(sse.raw_blocks).decode()
            assert fake.connections == 1
            await recorded.socket.close()
            await asyncio.wait_for(fake.closed.wait(), 5)
        assert fake.connections == 0
        for forbidden in ("secret", "rendered", "args", "result", "usage", "approval",
                          "prompt", "srq-1", "other", "verbose"):
            assert forbidden not in raw


@asynchronous
async def test_live_text_limits_are_utf8_bytes(tmp_path, monkeypatch):
    """Every capped field is cut at its byte budget on a character boundary."""
    arabic = "م" * 400                      # 2 bytes each -> 800 bytes > 512
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [
            fake.event("tool.complete", 1,
                       {"tool_id": "t1", "name": "Read", "summary": arabic}),
            fake.event("status.update", 2, {"text": arabic}),
            fake.global_event("session.reclaimed",
                              {"session_id": "rt-1", "stored_session_id": "chat-1"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            blocks = await Sse(reader, head).rest()
            assert blocks[-1] == ("idle", {}, None)
            summary = next(data["summary"] for name, data, _ in blocks
                           if name == "tool.complete")
            status_text = next(data["text"] for name, data, _ in blocks
                               if name == "status.update")
            assert len(summary.encode("utf-8")) <= 512
            assert len(status_text.encode("utf-8")) <= 200
            summary.encode("utf-8").decode("utf-8")   # never a split code point
            status_text.encode("utf-8").decode("utf-8")


@asynchronous
async def test_live_backend_token_is_hidden_in_forwarded_fields(tmp_path, monkeypatch):
    """A token embedded in any forwarded field reaches the phone only as [hidden]."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        token = fake.token
        fake.sessions = [SESSION]
        fake.events = [
            fake.event("message.delta", 1, {"text": "left " + token + " right"}),
            fake.event("message.complete", 2,
                       {"status": "done", "text": "c" + token, "error": "e" + token}),
            fake.event("tool.complete", 3,
                       {"tool_id": "t", "name": "Read", "summary": "s" + token}),
            fake.event("status.update", 4, {"text": "u" + token}),
            fake.global_event("session.reclaimed", {"session_id": "rt-1"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            await sse.rest()
            raw = b"".join(sse.raw_blocks)
            assert token.encode() not in raw
            assert raw.count(b"[hidden]") == 5


@asynchronous
async def test_live_reconnect_replays_missed_events(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.since_result = {"events": [{"type": "message.delta", "session_id": "rt-1",
                                         "payload": {"text": "missed"}, "seq": 6},
                                        {"type": "tool.generating", "session_id": "rt-1",
                                         "payload": {"name": "shell"}, "seq": 7}],
                             "latest_seq": 7, "truncated": False, "count": 2,
                             "epoch": fake.epoch, "open_requests": []}
        fake.events = [fake.event("message.delta", 6, {"text": "missed"}),
                       fake.event("message.delta", 8, {"text": "live"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra="Last-Event-ID: " + fake.epoch + ":5\r\n")
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert await sse.next() == ("snapshot", {"running": True, "turn_started_at": 1700.5}, None)
            assert await sse.next() == ("message.delta", {"text": "missed"}, 6)
            assert await sse.next() == ("message.delta", {"text": "live"}, 8)
            assert fake.since_calls == [{"session_id": "rt-1", "last_seen": 5}]
            assert b"id: " + fake.epoch.encode() + b":6\n" in b"".join(sse.raw_blocks)
            with pytest.raises(TimeoutError):
                await sse.next(0.5)


@pytest.mark.parametrize("mode", ["truncated", "epoch", "malformed", "since-error", "foreign-epoch"])
@asynchronous
async def test_live_reconnect_reset_on_bad_replay(tmp_path, monkeypatch, mode):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        if mode == "truncated":
            fake.since_result = {"events": [], "latest_seq": 9, "truncated": True, "count": 0,
                                 "epoch": fake.epoch, "open_requests": []}
        elif mode == "epoch":
            fake.since_result = {"events": [], "latest_seq": 9, "truncated": False, "count": 0,
                                 "epoch": "earlier-epoch", "open_requests": []}
        elif mode == "since-error":
            fake.since_error = {"code": 4007, "message": "gone"}
        cursor = {"malformed": "notanumber", "foreign-epoch": "older-epoch-7:5"}.get(
            mode, fake.epoch + ":5")
        extra = "Last-Event-ID: " + cursor + "\r\n"
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra=extra)
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert await sse.next() == ("snapshot", {"running": True, "turn_started_at": 1700.5}, None)
            assert await sse.next() == ("reset", {}, None)
            if mode in ("malformed", "foreign-epoch"):
                assert fake.since_calls == []
            else:
                assert fake.since_calls == [{"session_id": "rt-1", "last_seen": 5}]
            with pytest.raises(TimeoutError):
                await sse.next(0.5)


@asynchronous
async def test_live_reconnect_stale_cursor_resets_and_continues(tmp_path, monkeypatch):
    """A Last-Event-ID past the backend's latest seq resets, then live events still flow."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.since_result = {"events": [], "latest_seq": 7, "truncated": False, "count": 0,
                             "epoch": fake.epoch, "open_requests": []}
        fake.events = [fake.event("message.delta", 1, {"text": "fresh"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra="Last-Event-ID: " + fake.epoch + ":99\r\n")
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert await sse.next() == ("snapshot", {"running": True, "turn_started_at": 1700.5}, None)
            assert await sse.next() == ("reset", {}, None)
            assert await sse.next() == ("message.delta", {"text": "fresh"}, 1)
            assert fake.since_calls == [{"session_id": "rt-1", "last_seen": 99}]
            with pytest.raises(TimeoutError):
                await sse.next(0.5)


@asynchronous
async def test_live_reconnect_after_restart_resets_then_delivers(tmp_path, monkeypatch):
    """Old epoch in Last-Event-ID after a restart resets, then fresh seq 1 events arrive."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [fake.event("message.delta", 1, {"text": "post-restart"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra="Last-Event-ID: dead-epoch-1:42\r\n")
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert await sse.next() == ("snapshot", {"running": True, "turn_started_at": 1700.5}, None)
            assert await sse.next() == ("reset", {}, None)
            assert await sse.next() == ("message.delta", {"text": "post-restart"}, 1)
            assert fake.since_calls == []
            assert b"id: " + fake.epoch.encode() + b":1\n" in b"".join(sse.raw_blocks)
            with pytest.raises(TimeoutError):
                await sse.next(0.5)


@asynchronous
async def test_live_backend_close_emits_reset_and_ends(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [fake.event("message.delta", 1, {"text": "hi"})]
        fake.close_after_events = 1011
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [
                ("snapshot", {"running": True, "turn_started_at": 1700.5}, None),
                ("message.delta", {"text": "hi"}, 1),
                ("reset", {}, None)]


@asynchronous
async def test_live_reclaimed_emits_idle_and_ends(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [fake.event("message.delta", 1, {"text": "hi"}),
                       fake.global_event("session.reclaimed",
                                         {"session_id": "rt-other", "stored_session_id": "c2",
                                          "reason": "lru_evict"}),
                       fake.event("message.delta", 2, {"text": "still"}),
                       fake.global_event("session.reclaimed",
                                         {"session_id": "rt-1", "stored_session_id": "chat-1",
                                          "reason": "idle_timeout"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [
                ("snapshot", {"running": True, "turn_started_at": 1700.5}, None),
                ("message.delta", {"text": "hi"}, 1),
                ("message.delta", {"text": "still"}, 2),
                ("idle", {}, None)]
            await asyncio.wait_for(fake.closed.wait(), 5)


@asynchronous
async def test_live_activate_error_is_idle(tmp_path, monkeypatch):
    """A runtime id gone between active_list and activate ends idle; nothing is built."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.activate_error = {"code": 4001, "message": "session not found"}
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [("idle", {}, None)]
            methods = [frame.get("method") for frame in fake.frames]
            assert methods == ["session.active_list", "session.activate"]
            assert fake.activate_calls == [{"session_id": "rt-1", "omit_messages": True}]
            assert fake.resume_calls == []


@asynchronous
async def test_live_attach_rejects_ambiguous_session_id(tmp_path, monkeypatch):
    """The stored id also existing in another served profile's store makes the match unsafe."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        home = bridge.config.hermes_home
        store_session(home / "profiles" / "other", "chat-1")
        fake.publish(("default", "other"))
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [
                ("unavailable", {"reason": "profile-not-served"}, None)]
            assert [frame.get("method") for frame in fake.frames] == ["session.active_list"]
            assert fake.activate_calls == []


@asynchronous
async def test_live_attach_rejects_session_absent_from_profile_store(tmp_path, monkeypatch):
    """A live id the requested profile's own state.db does not hold is refused."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [dict(SESSION, id="rt-2", session_key="chat-2")]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/profiles/default/live/chat-2")
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [
                ("unavailable", {"reason": "profile-not-served"}, None)]
            assert [frame.get("method") for frame in fake.frames] == ["session.active_list"]
            assert fake.activate_calls == []


@asynchronous
async def test_live_attach_rejects_profile_the_bridge_cannot_check(tmp_path, monkeypatch):
    """A record profile with no bridge directory cannot be checked: the attach is refused."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.publish(("default", "ghost"))
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest() == [
                ("unavailable", {"reason": "profile-not-served"}, None)]
            assert [frame.get("method") for frame in fake.frames] == ["session.active_list"]
            assert fake.activate_calls == []


@asynchronous
async def test_live_phone_disconnect_closes_backend_socket(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert (await sse.next())[0] == "snapshot"
            assert fake.connections == 1
            await recorded.socket.close()
            await asyncio.wait_for(fake.closed.wait(), 5)
        assert fake.connections == 0


@asynchronous
async def test_live_dead_backend_without_pong_resets(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.answer_ping = False
        bridge.live.ping_interval, bridge.live.pong_timeout = 0.05, 0.2
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            assert await Sse(reader, head).rest(15) == [
                ("snapshot", {"running": True, "turn_started_at": 1700.5}, None),
                ("reset", {}, None)]
            assert any(f.get("method") == "gateway.ping" for f in fake.frames)
            await asyncio.wait_for(fake.closed.wait(), 5)


@asynchronous
async def test_live_deadline_holds_under_continuous_traffic(tmp_path, monkeypatch):
    """Non-stop backend events cannot starve the pong deadline check."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.answer_ping = False
        fake.traffic = True
        bridge.live.ping_interval, bridge.live.pong_timeout = 0.05, 0.3
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            blocks = await Sse(reader, head).rest(15)
            assert blocks[0] == ("snapshot", {"running": True, "turn_started_at": 1700.5}, None)
            assert any(name == "message.delta" for name, _data, _seq in blocks)
            assert blocks[-1] == ("reset", {}, None)
            await asyncio.wait_for(fake.closed.wait(), 5)


@asynchronous
async def test_live_stalled_phone_write_ends_stream(tmp_path, monkeypatch):
    """A phone write that outlives the deadline ends the stream and frees the slot.

    The fake answers every ping, and the poll loop keeps ``last_pong`` current the way a
    consumed pong would, so the pong deadline can never fire: only the stalled-write
    check can end the stream."""
    feeds = []
    original = live_module._Feed

    class _Observed(original):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            feeds.append(self)

    monkeypatch.setattr(live_module, "_Feed", _Observed)
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [fake.event("message.delta", i, {"text": "x" * 4000})
                       for i in range(1, 3000)]
        bridge.live.ping_interval, bridge.live.pong_timeout = 60, 0.3
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            # The body is never read: transport buffers fill and the write stalls.
            for _ in range(200):
                if bridge.live.total == 0:
                    break
                for feed in feeds:
                    feed.last_pong = feed.clock()
                await asyncio.sleep(0.05)
            assert bridge.live.total == 0
            await asyncio.wait_for(fake.closed.wait(), 5)


@asynchronous
async def test_live_backend_flood_stays_bounded_and_ends(tmp_path, monkeypatch):
    """10,000 backend frames with a phone that never reads stays inside the queue bounds."""
    feeds = []
    original = live_module._Feed

    class _Observed(original):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            feeds.append(self)

    monkeypatch.setattr(live_module, "_Feed", _Observed)
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [fake.event("message.delta", i, {"text": "flood"})
                       for i in range(1, 10001)]
        bridge.live.ping_interval, bridge.live.pong_timeout = 0.05, 0.3
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            # The body is never read: the overflow closes the backend socket itself.
            await asyncio.wait_for(fake.closed.wait(), 15)
            feed = feeds[0]
            assert feed.queue.qsize() <= live_module.MAX_FRAMES
            assert feed.queued <= live_module.MAX_BYTES
            for _ in range(200):
                if bridge.live.total == 0:
                    break
                await asyncio.sleep(0.05)
            assert bridge.live.total == 0


@asynchronous
async def test_live_replay_overflow_resets_and_ends(tmp_path, monkeypatch):
    """More events than the stash bound arriving while ``events.since`` is unanswered lose a
    frame: relaying the rest would skip it, so the stream must reset and end instead."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.since_result = {"events": [], "latest_seq": 300, "truncated": False, "count": 0,
                             "epoch": fake.epoch, "open_requests": []}
        fake.events = [fake.event("message.delta", i, {"text": "x"})
                       for i in range(1, 301)]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra="Last-Event-ID: " + fake.epoch + ":0\r\n")
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert await sse.next() == ("snapshot",
                                        {"running": True, "turn_started_at": 1700.5}, None)
            assert await sse.next() == ("reset", {}, None)
            with pytest.raises(StopAsyncIteration):
                await sse.next(15)
            await asyncio.wait_for(fake.closed.wait(), 5)
            assert fake.connections == 0
            assert bridge.live.total == 0


@asynchronous
async def test_live_replay_write_deadline_releases_slot(tmp_path, monkeypatch):
    """65 replayed ~60 KB events to a phone that never reads: the write deadline ends the
    attach before the relay even starts, closing the backend socket and freeing the slot."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.since_result = {"events": [{"type": "message.delta", "session_id": "rt-1",
                                         "payload": {"text": "x" * 60000}, "seq": seq}
                                        for seq in range(1, 66)],
                             "latest_seq": 65, "truncated": False, "count": 65,
                             "epoch": fake.epoch, "open_requests": []}
        bridge.live.ping_interval, bridge.live.pong_timeout = 60, 0.3
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra="Last-Event-ID: " + fake.epoch + ":0\r\n")
            status, head = await open_stream(reader)
            assert status == 200
            # The body is never read: the replayed events fill the transport buffers.
            deadline = bridge.live.clock() + bridge.live.pong_timeout + 1.0
            while bridge.live.total != 0 and bridge.live.clock() < deadline:
                await asyncio.sleep(0.02)
            assert bridge.live.total == 0
            assert fake.connections == 0


@pytest.mark.parametrize("publish", [True, False])
@asynchronous
async def test_live_blocked_headers_write_releases_slot(tmp_path, monkeypatch, publish):
    """The 200-line write is inside the write deadline too: a phone that never reads
    keeps neither the response nor the stream slot."""
    async def blocked(link):
        await asyncio.Event().wait()
    monkeypatch.setattr(live_module, "stream_start", blocked)
    async with backend(tmp_path, monkeypatch, publish=publish) as (bridge, fake):
        fake.sessions = [SESSION]
        bridge.live.pong_timeout = 0.2
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            # Wait for the request to reach the blocked header write first: the slot is
            # taken before stream_start runs, so live.total == 0 before then proves nothing.
            for _ in range(200):
                if bridge.live.total == 1:
                    break
                await asyncio.sleep(0.05)
            assert bridge.live.total == 1
            for _ in range(200):
                if bridge.live.total == 0:
                    break
                await asyncio.sleep(0.05)
            assert bridge.live.total == 0
            assert fake.connections == 0


@pytest.mark.parametrize("publish", [True, False])
@asynchronous
async def test_live_blocked_event_write_releases_slot(tmp_path, monkeypatch, publish):
    """A stalled write of the first event ends the response under the same deadline:
    the stream slot and any backend socket are released."""
    async def blocked(link, data):
        await asyncio.Event().wait()
    monkeypatch.setattr(live_module, "chunk", blocked)
    async with backend(tmp_path, monkeypatch, publish=publish) as (bridge, fake):
        fake.sessions = [SESSION]
        bridge.live.pong_timeout = 0.2
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            for _ in range(200):
                if bridge.live.total == 0:
                    break
                await asyncio.sleep(0.05)
            assert bridge.live.total == 0
            for _ in range(200):
                if fake.connections == 0:
                    break
                await asyncio.sleep(0.05)
            assert fake.connections == 0


@asynchronous
async def test_live_stash_is_bounded(tmp_path, monkeypatch):
    """Events pushed while active_list is unanswered stay inside the frame/byte budget:
    overflow closes the backend socket and frees the slot."""
    feeds = []
    original = live_module._Feed

    class _Observed(original):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            feeds.append(self)

    monkeypatch.setattr(live_module, "_Feed", _Observed)
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.before_active_list = [fake.event("message.delta", i, {"text": "x"})
                                   for i in range(1, 10001)]
        fake.push_gap = 0.001
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            await asyncio.wait_for(fake.closed.wait(), 15)
            assert len(feeds[0].stash) <= live_module.MAX_FRAMES
            assert feeds[0].stash_bytes <= live_module.MAX_BYTES
            for _ in range(200):
                if bridge.live.total == 0:
                    break
                await asyncio.sleep(0.05)
            assert bridge.live.total == 0
            assert fake.connections == 0


class _WsStub:
    """A backend socket that yields only the frames it is fed, exactly when they arrive."""

    def __init__(self):
        self.incoming = asyncio.Queue()
        self.sent = []
        self.close_calls = 0

    def __aiter__(self):
        return self._frames()

    async def _frames(self):
        while True:
            yield await self.incoming.get()

    async def send(self, data):
        self.sent.append(json.loads(data))

    async def close(self):
        self.close_calls += 1


def ws_event(seq):
    return json.dumps({"jsonrpc": "2.0", "method": "event",
                       "params": {"type": "message.delta", "session_id": "rt-1",
                                  "seq": seq, "payload": {"text": "x"}}})


@asynchronous
async def test_live_stash_and_queue_share_one_budget():
    """Frames stashed behind a pending call and frames still queued count against one
    bound: stash + queue never hold more than MAX_FRAMES frames or MAX_BYTES bytes."""
    ws = _WsStub()
    feed = live_module._Feed(ws, "t", time.monotonic)
    try:
        for seq in range(1, 201):
            ws.incoming.put_nowait(ws_event(seq))
        for _ in range(500):
            if feed.queue.qsize() == 200:
                break
            await asyncio.sleep(0.01)
        call = asyncio.ensure_future(feed.call("session.active_list", {}, timeout=30))
        for _ in range(500):
            if len(feed.stash) == 200:
                break
            await asyncio.sleep(0.01)
        for seq in range(201, 301):
            ws.incoming.put_nowait(ws_event(seq))
        with pytest.raises(live_module._Closed):
            await call
        for _ in range(500):
            if feed.reader.done():
                break
            await asyncio.sleep(0.01)
        # One CLOSED marker may sit behind the real frames; the rest is the shared budget.
        assert len(feed.stash) + feed.queue.qsize() <= live_module.MAX_FRAMES + 1
        assert feed.queued <= live_module.MAX_BYTES
        assert feed.stash_bytes <= live_module.MAX_BYTES
    finally:
        await feed.close()


@asynchronous
async def test_live_stream_limits(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        identities = [registered(bridge) for _ in range(3)]
        async with AsyncExitStack() as stack:
            open_links = []
            for identity, _device in identities[:2]:
                for _ in range(2):
                    link_ctx = await stack.enter_async_context(client(bridge, tmp_path, identity))
                    link, reader = link_ctx[0], link_ctx[1]
                    await send(link, "GET", TARGET)
                    status, head = await open_stream(reader)
                    assert status == 200
                    assert (await Sse(reader, head).next())[0] == "snapshot"
                    open_links.append(link_ctx)
            assert fake.connections == 4 and bridge.live.total == 4
            extra_a = await stack.enter_async_context(client(bridge, tmp_path, identities[0][0]))
            await send(extra_a[0], "GET", TARGET)
            status, head, body = await read_full(extra_a[1])
            assert status == 429 and head["retry-after"] == "30"
            assert json.loads(body) == {"error": "live_stream_limited"}
            extra_c = await stack.enter_async_context(client(bridge, tmp_path, identities[2][0]))
            await send(extra_c[0], "GET", TARGET)
            status, head, body = await read_full(extra_c[1])
            assert status == 429 and head["retry-after"] == "30"
            assert fake.connections == 4


@asynchronous
async def test_live_per_device_limit_alone(tmp_path, monkeypatch):
    """The third stream of one device is refused while only two global slots are in use."""
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        identity, _ = registered(bridge)
        async with AsyncExitStack() as stack:
            for _ in range(2):
                link_ctx = await stack.enter_async_context(client(bridge, tmp_path, identity))
                await send(link_ctx[0], "GET", TARGET)
                status, head = await open_stream(link_ctx[1])
                assert status == 200
                assert (await Sse(link_ctx[1], head).next())[0] == "snapshot"
            assert bridge.live.total == 2
            third = await stack.enter_async_context(client(bridge, tmp_path, identity))
            await send(third[0], "GET", TARGET)
            status, head, body = await read_full(third[1])
            assert status == 429 and head["retry-after"] == "30"
            assert json.loads(body) == {"error": "live_stream_limited"}
            assert fake.connections == 2 and bridge.live.total == 2


@asynchronous
async def test_live_sends_only_allowlisted_methods_and_heartbeats(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        bridge.live.ping_interval = 0.05
        bridge.live.heartbeat_interval = 0.05
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", TARGET, extra="Last-Event-ID: " + fake.epoch + ":5\r\n")
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            assert (await sse.next())[0] == "snapshot"
            seen = []
            for _ in range(20):
                block = await sse.next(2)
                seen.append(block)
                if block[0] == "comment":
                    break
            assert ("comment", None, None) in seen
            await asyncio.sleep(0.2)
            methods = {frame.get("method") for frame in fake.frames}
            assert {"session.active_list", "session.activate", "session.events.since",
                    "gateway.ping"} <= methods
            assert methods <= ALLOWED_METHODS


@asynchronous
async def test_live_handshake_host_no_origin_and_token_secrecy(tmp_path, monkeypatch, capsys):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        fake.events = [fake.event("message.delta", 1, {"text": "hi"})]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, recorded):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 200
            sse = Sse(reader, head)
            await sse.next()
            await sse.next()
            sent = dict((k.lower(), v) for k, v in fake.handshakes[0])
            assert sent["host"] == "127.0.0.1:" + str(fake.port)
            assert "origin" not in sent
            assert all(fake.token not in value for value in sent.values())
            captured = b"".join(sse.raw_blocks)
            await recorded.socket.close()
            await asyncio.wait_for(fake.closed.wait(), 5)
        assert fake.token not in captured.decode()
        for _ in range(50):
            text = bridge.audit.path.read_text() if bridge.audit.path.exists() else ""
            rows = [json.loads(line) for line in text.splitlines()]
            if any(row.get("route") == "/v1/profiles/{id}/live/{id}" for row in rows):
                break
            await asyncio.sleep(0.1)
        assert fake.token not in text
        assert any(row.get("route") == "/v1/profiles/{id}/live/{id}" and row["status"] == 200
                   for row in rows)
        captured_stdio = capsys.readouterr()
        assert fake.token not in captured_stdio.out + captured_stdio.err


@asynchronous
async def test_live_route_needs_a_registered_device(tmp_path, monkeypatch):
    async with backend(tmp_path, monkeypatch) as (bridge, fake):
        fake.sessions = [SESSION]
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", TARGET)
            status, head = await open_stream(reader)
            assert status == 403
            assert fake.frames == []
