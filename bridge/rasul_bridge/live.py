"""Read-only live stream of a chat the desktop-owned backend is answering.

The phone opens ``GET /v1/profiles/{id}/live/{session_id}`` and gets SSE. The bridge dials the
desktop backend's loopback ``/api/ws`` JSON-RPC socket with the record token, attaches to an
already-live session, and relays a fixed projection of its events. The bridge never advertises
client capabilities on that socket, so server-to-client requests (approvals) stay with the
desktop, and it sends only the four methods in ``ALLOWED_METHODS``.
"""

import asyncio
from collections import deque
from contextlib import suppress
import hashlib
import hmac
import json
import logging
import math
import os
from pathlib import Path
import re
import sqlite3
import time
from urllib.parse import quote

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidHandshake

from .desktop import decode, listener_owned, process_times, start_matches
from .http import chunk, stream_start
from .proxy import StreamAborted

ROLE = "desktop-serve"
ALLOWED_METHODS = frozenset(
    {"gateway.ping", "session.active_list", "session.activate", "session.events.since"})
MAX_STREAMS = 4
MAX_STREAMS_PER_DEVICE = 2
MAX_FRAMES = 256
MAX_BYTES = 4 * 1024 * 1024
RETRY_AFTER = 30
OPEN_TIMEOUT = 10
READY_TIMEOUT = 10
CALL_TIMEOUT = 10
MAX_TEXT = 64 * 1024
MAX_SUMMARY = 512
MAX_STATUS = 200
CLOSED = object()


class _Closed(Exception):
    """The backend socket ended, or its buffered data overflowed, while a call waited."""


class _RpcError(Exception):
    """A call was answered by an error frame or a malformed reply."""


class _Stalled(Exception):
    """A write to the phone outlived ``pong_timeout`` outside the monitor's watch."""


def _quiet_logger():
    logger = logging.getLogger("rasul_bridge.live_upstream")
    if not logger.handlers:
        logger.addHandler(logging.NullHandler())
    logger.propagate = False
    logger.disabled = True
    return logger


def _cut(value, limit):
    """Cap ``value`` at ``limit`` UTF-8 bytes, cut only on a character boundary."""
    if not isinstance(value, str):
        return None
    raw = value.encode("utf-8")
    if len(raw) <= limit:
        return value
    return raw[:limit].decode("utf-8", "ignore")


def _hide(value, token):
    """Blank the backend token wherever it appears inside a forwarded string."""
    return value.replace(token, "[hidden]") if token and isinstance(value, str) else value


def _id_prefix(epoch):
    """SSE ``id:`` epoch part: the epoch itself when token-safe, hex-encoded otherwise."""
    text = str(epoch)
    return text if re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", text) else text.encode("utf-8").hex()


def _project(params, token):
    """Map one event ``params`` to ``(name, payload)`` for the phone, or None to drop it."""
    kind = params.get("type")
    payload = params.get("payload")
    if not isinstance(payload, dict):
        payload = {}
    if kind == "message.start":
        return "message.start", {}
    if kind == "message.delta":
        text = payload.get("text")
        return (("message.delta", {"text": _hide(text, token)})
                if isinstance(text, str) else None)
    if kind == "message.complete":
        result = {}
        if isinstance(payload.get("status"), str):
            result["status"] = payload["status"]
        if (text := _cut(_hide(payload.get("text"), token), MAX_TEXT)) is not None:
            result["text"] = text
        if (error := _cut(_hide(payload.get("error"), token), 1000)) is not None:
            result["error"] = error
        return "message.complete", result
    if kind == "tool.start":
        tool_id, name = payload.get("tool_id"), payload.get("name")
        return (("tool.start", {"tool_id": tool_id, "name": name})
                if isinstance(tool_id, str) and isinstance(name, str) else None)
    if kind == "tool.complete":
        tool_id, name = payload.get("tool_id"), payload.get("name")
        if not isinstance(tool_id, str) or not isinstance(name, str):
            return None
        result = {"tool_id": tool_id, "name": name}
        if (summary := _cut(_hide(payload.get("summary"), token), MAX_SUMMARY)) is not None:
            result["summary"] = summary
        return "tool.complete", result
    if kind == "status.update":
        text = _cut(_hide(payload.get("text"), token), MAX_STATUS)
        return ("status.update", {"text": text}) if text is not None else None
    return None


class _Emitter:
    """SSE blocks to the phone: named events, an optional ``id:`` line, and keepalive comments."""

    def __init__(self, link, clock, interval):
        self.link, self.clock, self.interval = link, clock, interval
        self.id_prefix = ""
        self.due = clock() + interval
        self.writing = None      # clock tick of the in-flight phone write, if any
        self.stalled = False     # a phone write outlived the deadline

    async def event(self, name, payload, seq=None):
        block = b""
        if type(seq) is int and not isinstance(seq, bool) and seq >= 0:
            block += b"id: " + self.id_prefix.encode() + b":" + str(seq).encode() + b"\n"
        data = json.dumps(payload, separators=(",", ":")).encode()
        await self._write(block + b"event: " + name.encode("ascii") + b"\ndata: " + data + b"\n\n")

    async def comment(self):
        await self._write(b": keepalive\n\n")

    async def _write(self, data):
        self.writing = self.clock()
        try:
            await chunk(self.link, data)
        finally:
            self.writing = None
        self.due = self.clock() + self.interval


class _Feed:
    """One backend socket: a reader task fills a queue; ``call`` sends allowlisted methods only.

    ``stash`` holds event frames that arrive while an RPC reply is awaited so nothing between
    attach and replay is lost; it counts against the same frame and byte budget as the queue.
    ``last_pong`` moves only on replies to our ``gateway.ping``.
    """

    def __init__(self, ws, token, clock):
        self.ws, self.token, self.clock = ws, token, clock
        self.queue = asyncio.Queue(MAX_FRAMES)
        self.queued = 0
        self.stash = deque()
        self.stash_bytes = 0
        self.reader = asyncio.create_task(self._read())
        self.request_id = 0
        self.pings = set()
        self.last_pong = clock()
        self.epoch = None

    async def _read(self):
        """Backend -> queue; stash + queue share one bound and overflow closes the socket."""
        try:
            async for raw in self.ws:
                size = len(raw) if isinstance(raw, (bytes, bytearray)) else len(raw.encode("utf-8"))
                try:
                    frame = json.loads(raw)
                except ValueError:
                    frame = None
                if (self.queued + size > MAX_BYTES
                        or self.queue.qsize() + len(self.stash) >= MAX_FRAMES):
                    break
                self.queued += size
                try:
                    self.queue.put_nowait((frame, size))
                except asyncio.QueueFull:
                    self.queued -= size
                    break
            with suppress(OSError, ConnectionClosed):
                await self.ws.close()
        finally:
            if self.queue.full():
                # Make room for the close marker; the consumer is resetting anyway.
                with suppress(asyncio.QueueEmpty):
                    self.queued -= self.queue.get_nowait()[1]
            self.queue.put_nowait((CLOSED, 0))

    async def recv(self, timeout):
        """Next frame for the relay loop: stashed event frames first, then the live queue."""
        if self.stash:
            frame, size = self.stash.popleft()
            self.stash_bytes -= size
            self.queued -= size
            return frame
        frame, _size = await self._take(timeout)
        return frame

    async def _take(self, timeout):
        """Next ``(frame, size)`` the reader delivered, or ``(None, 0)`` when ``timeout`` ends."""
        try:
            frame, size = await asyncio.wait_for(self.queue.get(), max(0.001, timeout))
            self.queued -= size
            return frame, size
        except TimeoutError:
            return None, 0

    def _absorb(self, frame, size):
        """Stash session events; count pong replies; silently drop server requests."""
        if not isinstance(frame, dict):
            return
        if frame.get("method") == "event":
            if (len(self.stash) + self.queue.qsize() >= MAX_FRAMES
                    or self.queued + size > MAX_BYTES):
                raise _Closed     # the same close path the reader's own bound takes
            self.stash.append((frame, size))
            self.stash_bytes += size
            self.queued += size   # a stashed frame still counts against the shared budget
        elif frame.get("id") in self.pings:
            self.pings.discard(frame["id"])
            self.last_pong = self.clock()

    async def send(self, method, params):
        if method not in ALLOWED_METHODS:
            raise ValueError("method not in allowlist")
        self.request_id += 1
        rid = "b" + str(self.request_id)
        await self.ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}))
        return rid

    async def call(self, method, params, timeout=CALL_TIMEOUT):
        # Only the live queue is read here: stashed event frames stay for the relay phase.
        rid = await self.send(method, params)
        deadline = self.clock() + timeout
        while True:
            frame, size = await self._take(deadline - self.clock())
            if frame is None:
                raise TimeoutError("backend did not answer")
            if frame is CLOSED:
                raise _Closed
            if isinstance(frame, dict) and frame.get("id") == rid:
                if "error" in frame or "result" not in frame:
                    raise _RpcError
                return frame["result"]
            self._absorb(frame, size)

    async def ready(self, timeout=READY_TIMEOUT):
        """Wait for ``gateway.ready`` and keep its ``replay_epoch``; False on timeout/close."""
        deadline = self.clock() + timeout
        while self.clock() < deadline:
            frame, size = await self._take(deadline - self.clock())
            if frame is None or frame is CLOSED:
                return False
            if (isinstance(frame, dict) and frame.get("method") == "event"
                    and isinstance(frame.get("params"), dict)
                    and frame["params"].get("type") == "gateway.ready"):
                payload = frame["params"].get("payload")
                self.epoch = payload.get("replay_epoch") if isinstance(payload, dict) else None
                self.last_pong = self.clock()
                return True
            try:
                self._absorb(frame, size)
            except _Closed:
                return False
        return False

    async def ping(self):
        self.pings.add(await self.send("gateway.ping", {}))

    async def close(self):
        self.reader.cancel()
        try:
            await self.reader
        except asyncio.CancelledError:
            task = asyncio.current_task()
            if task is not None and task.cancelling():
                raise   # our own task was cancelled; the finally below still runs
        except Exception:
            pass    # a dead reader (closed socket, bad frame) is expected here
        finally:
            with suppress(ConnectionClosed, OSError, TimeoutError, asyncio.CancelledError):
                await self.ws.close()


class Live:
    """``GET /v1/profiles/{id}/live/{session_id}``: SSE relay of one live desktop session."""

    def __init__(self, profiles, desktop, clock=time.monotonic):
        self.profiles, self.desktop, self.clock = profiles, desktop, clock
        self.devices = {}
        self.total = 0
        self.ping_interval = 15
        self.pong_timeout = 45
        self.heartbeat_interval = 15

    def acquire(self, device):
        if self.devices.get(device, 0) >= MAX_STREAMS_PER_DEVICE or self.total >= MAX_STREAMS:
            return False
        self.devices[device] = self.devices.get(device, 0) + 1
        self.total += 1
        return True

    def release(self, device):
        self.total -= 1
        self.devices[device] -= 1
        if not self.devices[device]:
            del self.devices[device]

    def record(self):
        """``(record, token, process start tick)`` of the trusted desktop-owned backend, or None."""
        directory = self.desktop.directory()
        try:
            record = decode(self.desktop.read_private(directory / ("host-" + ROLE + ".json")))
            token = (self.desktop.read_private(directory / ("host-" + ROLE + ".token"))
                     .decode("utf-8-sig").strip())
            if (record["role"] != ROLE or type(record["pid"]) is not int or record["pid"] <= 0
                    or type(record["protocolVersion"]) is not int or record["protocolVersion"] != 1
                    or record["host"] != "127.0.0.1" or type(record["port"]) is not int
                    or not 1 <= record["port"] <= 65535 or not re.fullmatch(r"[!-~]{16,512}", token)
                    or not hmac.compare_digest(hashlib.sha256(token.encode()).hexdigest()[:16],
                                               record["tokenFingerprint"])):
                return None
            home = record["home"]
            if (type(home) is not str or (home and (not Path(home).is_absolute()
                    or os.path.normcase(os.path.realpath(home))
                    != os.path.normcase(os.path.realpath(self.profiles.config.hermes_home))))):
                return None
            start, created = process_times(record["pid"])
            expected = record.get("startTime")
            if expected is not None:
                if type(expected) is not int or expected <= 0 or not start_matches(expected, start):
                    return None
            else:
                expected = record["createTime"]
                if (type(expected) not in (int, float) or not math.isfinite(expected)
                        or expected <= 0 or abs(expected - created) > 0.001):
                    return None
            if (type(record["profiles"]) is not list
                    or any(type(profile) is not str for profile in record["profiles"])):
                return None
            if not listener_owned(record["pid"], record["host"], record["port"]):
                return None
            return record, token, start
        except (OSError, ValueError, KeyError, TypeError, IndexError, StopIteration):
            return None

    async def stream(self, link, device, profile, session_id, head):
        """Write the whole response (SSE, or a plain 429) and return its status."""
        if not self.acquire(device):
            body = b'{"error":"live_stream_limited"}'
            await link.write(b"HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n"
                             b"Content-Length: " + str(len(body)).encode()
                             + b"\r\nRetry-After: " + str(RETRY_AFTER).encode()
                             + b"\r\nX-Content-Type-Options: nosniff\r\n\r\n" + body)
            return 429
        try:
            found = self.record()
            if found is None:
                await self._terminal(link, "unavailable", {"reason": "desktop-not-running"})
            elif (profile not in found[0]["profiles"]
                  or profile not in self.profiles.directories()):
                await self._terminal(link, "unavailable", {"reason": "profile-not-served"})
            else:
                await self._open(link, found[0], found[1], found[2], profile, session_id, head)
            return 200
        finally:
            self.release(device)

    async def _terminal(self, link, name, payload):
        try:
            await asyncio.wait_for(stream_start(link), self.pong_timeout)
            await self._emit(_Emitter(link, self.clock, self.heartbeat_interval), name, payload)
            await asyncio.wait_for(link.write(b"0\r\n\r\n"), self.pong_timeout)
        except (OSError, TimeoutError, EOFError, ConnectionClosed, _Stalled) as error:
            raise StreamAborted from error

    async def _emit(self, emitter, name, payload, seq=None):
        """One event to the phone under ``pong_timeout``, the deadline ``_monitor`` enforces
        once the relay starts; a hit marks the write stalled so nothing more is attempted."""
        try:
            await asyncio.wait_for(emitter.event(name, payload, seq), self.pong_timeout)
        except TimeoutError:
            emitter.stalled = True
            raise _Stalled

    async def _open(self, link, record, token, start, profile, session_id, head):
        try:
            await asyncio.wait_for(stream_start(link), self.pong_timeout)
        except (OSError, TimeoutError, EOFError, ConnectionClosed) as error:
            raise StreamAborted from error
        emitter = _Emitter(link, self.clock, self.heartbeat_interval)
        feed = None
        ending = None
        try:
            try:
                ws = await connect("ws://127.0.0.1:" + str(record["port"]) + "/api/ws?token="
                                   + quote(token, safe=""),
                                   open_timeout=OPEN_TIMEOUT, ping_interval=None, close_timeout=1,
                                   max_size=4 * 1024 * 1024, compression=None, proxy=None,
                                   logger=_quiet_logger())
            except (OSError, TimeoutError, ConnectionClosed, InvalidHandshake, ValueError, RuntimeError):
                ending = ("unavailable", {"reason": "desktop-not-running"})
                return
            feed = _Feed(ws, token, self.clock)
            if not await feed.ready():
                ending = ("unavailable", {"reason": "desktop-not-running"})
                return
            emitter.id_prefix = _id_prefix(feed.epoch)
            try:
                stable = start_matches(start, process_times(record["pid"])[0])
            except (OSError, ValueError):
                stable = False
            if not stable:
                # The record's process was replaced between validation and connect.
                ending = ("unavailable", {"reason": "desktop-not-running"})
                return
            try:
                ending = await self._attach(feed, emitter, record, profile, session_id, head)
            except _Stalled:
                ending = "reset", {}
        finally:
            if feed is not None:
                await feed.close()
            # The same write deadline bounds the terminal writes: a phone that stopped reading
            # must never keep the slot (or the backend socket) open.
            if ending is not None and not emitter.stalled:
                with suppress(OSError, EOFError, ConnectionClosed, TimeoutError, _Stalled):
                    await self._emit(emitter, ending[0], ending[1])
            if not emitter.stalled:
                with suppress(OSError, EOFError, ConnectionClosed, TimeoutError):
                    await asyncio.wait_for(link.write(b"0\r\n\r\n"), self.pong_timeout)

    def _stored(self, directory, session_id):
        """``session_id`` appears in ``directory``'s state.db (opened read-only, never created)."""
        if directory is None:
            return False
        path = directory / "state.db"
        try:
            if not path.is_file():
                return False
            connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
            try:
                return connection.execute("SELECT 1 FROM sessions WHERE id = ? LIMIT 1",
                                          (session_id,)).fetchone() is not None
            finally:
                connection.close()
        except (OSError, ValueError, sqlite3.Error):
            return False

    def _unambiguous(self, profile, session_id, record_profiles):
        """The stored id is in ``profile``'s own store and in no other served profile's."""
        directories = self.profiles.directories()
        others = [directories.get(other) for other in record_profiles if other != profile]
        if None in others:
            # A served profile the bridge has no directory for cannot be checked: fail closed.
            return False
        return (self._stored(directories.get(profile), session_id)
                and not any(self._stored(other, session_id) for other in others))

    @staticmethod
    def _reclaimed_for(params, runtime):
        """``session.reclaimed`` carries its runtime id in the payload, not the envelope."""
        if params.get("type") != "session.reclaimed":
            return None
        payload = params.get("payload")
        return isinstance(payload, dict) and payload.get("session_id") == runtime

    async def _attach(self, feed, emitter, record, profile, session_id, head):
        """Attach and relay. Returns the terminal ``(event, payload)``."""
        try:
            listed = await feed.call("session.active_list", {})
        except (TimeoutError, _Closed, _RpcError):
            return "unavailable", {"reason": "desktop-not-running"}
        rows = listed.get("sessions") if isinstance(listed, dict) else None
        if not isinstance(rows, list):
            return "unavailable", {"reason": "desktop-not-running"}
        match = next((row for row in rows
                      if isinstance(row, dict) and row.get("session_key") == session_id), None)
        if match is None or type(match.get("id")) is not str or not match["id"]:
            return "idle", {}
        # active_list rows carry no profile: the live id is only safe to attach when the stored
        # id lives in the requested profile's state.db and in no other served profile's.
        if not self._unambiguous(profile, session_id, record["profiles"]):
            return "unavailable", {"reason": "profile-not-served"}
        # session.activate only ever attaches to a live runtime id; a vanished one is an error
        # and nothing is built, unlike session.resume which cold-resumes a stored chat.
        try:
            attached = await feed.call("session.activate",
                                       {"session_id": match["id"], "omit_messages": True})
        except (TimeoutError, _Closed, _RpcError):
            return "idle", {}
        if not isinstance(attached, dict) or type(attached.get("session_id")) is not str:
            return "idle", {}
        runtime = attached["session_id"]
        turn = attached.get("turn_started_at")
        await self._emit(emitter, "snapshot",
                         {"running": attached.get("running") is True,
                          "turn_started_at": turn if type(turn) in (int, float) else None})
        emitted = 0
        last_seen = head.get("last-event-id")
        if last_seen is not None:
            marker = re.fullmatch(re.escape(emitter.id_prefix) + r":([0-9]{1,18})", last_seen)
            replay = None
            if marker is not None:
                try:
                    replay = await feed.call("session.events.since",
                                             {"session_id": runtime, "last_seen": int(marker[1])})
                except _Closed:
                    # The socket died or a stashed frame overflowed while the reply was
                    # awaited: an event may already be lost, so relaying would skip it.
                    return "reset", {}
                except (TimeoutError, _RpcError):
                    replay = None
            latest = replay.get("latest_seq") if isinstance(replay, dict) else None
            if (marker is None or not isinstance(replay, dict) or replay.get("truncated")
                    or replay.get("epoch") != feed.epoch
                    or type(latest) is int and not isinstance(latest, bool)
                    and int(marker[1]) > latest):
                # A stale id (old epoch, a restart, a forged or truncated cursor) must not
                # suppress later events: reset and continue from the current position.
                await self._emit(emitter, "reset", {})
            else:
                events = replay.get("events")
                if not isinstance(events, list):
                    events = []
                for params in events:
                    if not isinstance(params, dict):
                        continue
                    reclaimed = self._reclaimed_for(params, runtime)
                    if reclaimed:
                        return "idle", {}
                    if reclaimed is False or params.get("session_id") != runtime:
                        continue
                    seq = params.get("seq")
                    if type(seq) is int and not isinstance(seq, bool) and seq > emitted:
                        emitted = seq
                    if (projected := _project(params, feed.token)) is not None:
                        await self._emit(emitter, projected[0], projected[1], seq)
        return await self._relay(feed, emitter, runtime, emitted)

    async def _relay(self, feed, emitter, runtime, emitted):
        """Relay frames while ``_monitor`` runs its own schedule; first finisher ends the stream."""
        frames = asyncio.ensure_future(self._frames(feed, emitter, runtime, emitted))
        monitor = asyncio.ensure_future(self._monitor(feed, emitter))
        try:
            done, _pending = await asyncio.wait({frames, monitor},
                                                return_when=asyncio.FIRST_COMPLETED)
            if frames in done:
                return frames.result()
            return "reset", {}
        finally:
            for task in (frames, monitor):
                task.cancel()
            await asyncio.gather(frames, monitor, return_exceptions=True)

    async def _monitor(self, feed, emitter):
        """Ping on schedule and end on a silent backend or a stalled phone write.

        Runs beside the relay so neither continuous backend traffic nor a blocked write to the
        phone can starve the deadline checks."""
        next_ping = self.clock() + self.ping_interval
        while True:
            now = self.clock()
            wait = max(0.0, min(next_ping, feed.last_pong + self.pong_timeout) - now)
            writing = emitter.writing
            if writing is not None:
                wait = max(0.0, min(wait, writing + self.pong_timeout - now))
            await asyncio.sleep(wait)
            now = self.clock()
            writing = emitter.writing
            if writing is not None and now - writing >= self.pong_timeout:
                # Check the write first: while it is stalled the frames loop cannot consume
                # pongs, so last_pong goes stale as well and must not mask the real cause.
                emitter.stalled = True
                return
            if now - feed.last_pong >= self.pong_timeout:
                return
            if now >= next_ping:
                try:
                    await feed.ping()
                except (ConnectionClosed, OSError):
                    return
                next_ping = now + self.ping_interval

    async def _frames(self, feed, emitter, runtime, emitted):
        while True:
            frame = await feed.recv(max(0.0, emitter.due - self.clock()))
            if frame is None:
                await emitter.comment()
                continue
            if frame is CLOSED:
                return "reset", {}
            if not isinstance(frame, dict):
                continue
            if frame.get("id") in feed.pings:
                feed.pings.discard(frame["id"])
                feed.last_pong = self.clock()
                continue
            if frame.get("method") != "event":
                continue
            params = frame.get("params")
            if not isinstance(params, dict):
                continue
            reclaimed = self._reclaimed_for(params, runtime)
            if reclaimed:
                return "idle", {}
            if reclaimed is False or params.get("session_id") != runtime:
                continue
            seq = params.get("seq")
            valid = type(seq) is int and not isinstance(seq, bool) and seq >= 0
            if valid and seq <= emitted:
                continue
            if valid:
                emitted = seq
            if (projected := _project(params, feed.token)) is not None:
                await emitter.event(projected[0], projected[1], seq if valid else None)
