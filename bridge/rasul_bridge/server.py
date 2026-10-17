"""WebSocket admission and authenticated inner HTTP dispatch."""

import asyncio
from contextlib import suppress
from email.message import Message
from http import HTTPStatus
import json
import logging
import random
import re
import ssl
import time
from urllib.parse import urlsplit

from cryptography import x509
from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from .audit import Audit
from .http import HTTPError, Reader, chunk, request, response, stream_start
from .limits import Limits
from .pairing import Pairing
from .profiles import Profiles
from .proxy import StreamAborted, Upstream, allowed
from .registry import Registry
from .tls import TLSLink, ensure_identity, server_context


MAX_PRE_AUTH_CONNECTIONS = 256
MAX_ANONYMOUS_CONNECTIONS = 32
MAX_PAIRING_CONNECTIONS = 1
UPGRADE_TIMEOUT = 3
TLS_TIMEOUT = 10
ANONYMOUS_TIMEOUT = 5
ANONYMOUS_MAX_BODY = 16 * 1024


def _has_unsupported_charset(head):
    content_type = Message()
    content_type["content-type"] = head.get("content-type", "")
    parameters = content_type.get_params(header="content-type", unquote=True) or []
    for name, value in parameters[1:]:
        if name.casefold() == "charset" and (
                not isinstance(value, str) or value.casefold() != "utf-8"):
            return True
    return False


class AcceptedConnection(ServerConnection):
    def __init__(self, *args, bridge, **kwargs):
        super().__init__(*args, **kwargs)
        self.bridge = bridge

    def connection_made(self, transport):
        super().connection_made(transport)
        self.bridge.accept(self)

    def connection_lost(self, exc):
        self.bridge.release(self)
        super().connection_lost(exc)


class Bridge:
    def __init__(self, store, config, confirm=None, run=None, random_source=None):
        self.store, self.config = store, config
        self.registry = Registry(store)
        self.cert = ensure_identity(store)
        self.pairing = Pairing(store, self.registry, self.cert, confirm=confirm)
        self.limits = Limits()
        self.audit = Audit(store)
        self.upstream = Upstream(config.hermes_url)
        self.profiles = Profiles(store, config, self.upstream, run=run)
        self.connections = {}
        self.pending = {}
        self.anonymous = {}
        self.pairing_reserved = set()
        self.random = random_source if random_source is not None else random.SystemRandom()
        self.context_task = None
        self.context_revision = -1
        self.context_lock = asyncio.Lock()
        self.deadlines = {}
        self.http_tasks = set()
        self.link_tasks = {}
        self.server = None
        self.heartbeat_interval = 15

    async def admission(self, connection, request):
        if request.path != "/v1/link":
            return connection.respond(HTTPStatus.NOT_FOUND, "Not found\n")
        deadline = self.deadlines.pop(connection, None)
        if deadline is not None:
            deadline.cancel()
        if connection in self.pending:
            remaining = max(0, self.pending[connection] + TLS_TIMEOUT
                            - asyncio.get_running_loop().time())
            self.deadlines[connection] = asyncio.get_running_loop().call_later(
                remaining, self.expire, connection)

    def accept(self, connection):
        # Account first, including sockets rejected before an upgrade.
        now = asyncio.get_running_loop().time()
        self.pending[connection] = now
        self.connections[connection] = None
        self.deadlines[connection] = asyncio.get_running_loop().call_later(
            UPGRADE_TIMEOUT, self.expire, connection)
        self.evict(self.pending, MAX_PRE_AUTH_CONNECTIONS)

    def evict(self, pool, cap):
        if len(pool) > cap:
            self.expire(self.random.choice(tuple(pool)))
            self.audit_call("reject")

    def audit_call(self, method, *args):
        try:
            getattr(self.audit, method)(*args)
        except Exception:
            # Audit failures must not change admission, requests, or revocation.
            self.audit.drop()

    def certificate_received(self, connection, link):
        # Classify before the final TLS send can suspend.
        raw_cert = link.ssl.getpeercert(binary_form=True)
        cert = x509.load_der_x509_certificate(raw_cert) if raw_cert else None
        device = self.registry.identify(cert)
        if cert is not None and device is None:
            raise ValueError("Registered device certificate required")
        self.pending.pop(connection, None)
        deadline = self.deadlines.pop(connection, None)
        if deadline is not None:
            deadline.cancel()
        if device is not None:
            if not self.limits.connect():
                raise ValueError("Connection limit")
            if not self.limits.authenticate(device):
                self.limits.disconnect()
                raise ValueError("Device connection limit")
            self.connections[connection] = device
        else:
            self.anonymous[connection] = None
            self.deadlines[connection] = asyncio.get_running_loop().call_later(
                ANONYMOUS_TIMEOUT, self.expire, connection)
            self.evict(self.anonymous, MAX_ANONYMOUS_CONNECTIONS)
            if connection not in self.connections:
                raise ValueError("Pairing connection limit")

    def reserve_pairing(self, connection):
        if len(self.pairing_reserved) >= MAX_PAIRING_CONNECTIONS:
            raise PermissionError("Pairing already in progress")
        self.pairing_reserved.add(connection)
        self.anonymous.pop(connection, None)
        deadline = self.deadlines.pop(connection, None)
        if deadline is not None:
            deadline.cancel()

    def expire(self, connection):
        connection.transport.abort()
        self.release(connection)

    async def start(self):
        self.profiles.recover()
        await self.refresh_context()
        logger = logging.getLogger("rasul_bridge.transport")
        logger.addHandler(logging.NullHandler())
        logger.propagate = False
        logger.disabled = True
        self.server = await serve(self.handle, self.config.listen, self.config.port,
                                  create_connection=lambda *a, **kw: AcceptedConnection(*a, bridge=self, **kw),
                                  process_request=self.admission, compression=None,
                                  server_header=None, max_size=65536, max_queue=2,
                                  open_timeout=UPGRADE_TIMEOUT, close_timeout=1, logger=logger)
        self.port = self.server.sockets[0].getsockname()[1]
        self.monitor_task = asyncio.create_task(self.monitor())
        return self

    async def refresh_context(self):
        async with self.context_lock:
            devices = self.registry.devices()
            revision = self.registry.revision
            if (revision != self.context_revision or self.context_task is None
                    or self.context_task.cancelled()
                    or (self.context_task.done() and self.context_task.exception() is not None)):
                self.context_task = asyncio.create_task(
                    asyncio.to_thread(server_context, self.store, devices))
                self.context_revision = revision
            task = self.context_task
        # An evicted requester must not cancel or duplicate a registry rebuild.
        return await asyncio.shield(task)

    async def monitor(self):
        while True:
            await asyncio.sleep(0.1)
            self.audit_call("flush_rejections")
            for connection, device in list(self.connections.items()):
                # Failed WebSocket opening handshakes never invoke handle().
                if connection.transport.is_closing():
                    if connection in self.link_tasks:
                        self.link_tasks[connection].cancel()
                    self.release(connection)
                elif device is not None and not self.registry.active(device):
                    await connection.close(code=1008, reason="Device revoked")
                    if connection in self.link_tasks:
                        self.link_tasks[connection].cancel()

    def release(self, connection):
        deadline = self.deadlines.pop(connection, None)
        if deadline is not None:
            deadline.cancel()
        self.pending.pop(connection, None)
        self.anonymous.pop(connection, None)
        self.pairing_reserved.discard(connection)
        if connection in self.connections:
            device = self.connections.pop(connection)
            if device is not None:
                self.limits.disconnect(device)
        task = self.link_tasks.get(connection)
        if task is not None and task is not asyncio.current_task():
            task.cancel()

    async def handle(self, connection):
        task = asyncio.current_task()
        self.http_tasks.add(task)
        self.link_tasks[connection] = task
        link = None
        rejected = False
        try:
            context = await self.refresh_context()
            if connection not in self.pending:
                return
            link = TLSLink(connection, context)
            try:
                await link.handshake(established=lambda: self.certificate_received(connection, link))
            except ssl.SSLError:
                rejected = True
                try:
                    async with asyncio.timeout(1):
                        await link.flush()
                        await connection.close()
                except (ConnectionClosed, TimeoutError, OSError):
                    pass
                finally:
                    connection.transport.abort()
                return
            device = self.connections[connection]
            reader = Reader(link)
            while True:
                method, target, head, body = await request(
                    reader, maximum_body=ANONYMOUS_MAX_BODY if device is None else None)
                if device and not self.registry.active(device):
                    await connection.close(code=1008)
                    return
                try:
                    await self.dispatch(link, device, method, target, head, body,
                                        reserved=lambda: self.reserve_pairing(connection))
                finally:
                    self.pairing_reserved.discard(connection)
                if device is None or head.get("connection", "").lower() == "close":
                    return
        except HTTPError as error:
            if self.connections.get(connection) is None:
                self.audit_call("reject")
            with suppress(Exception):
                await response(link, error.status, {"error": error.message})
        except ValueError:
            with suppress(Exception):
                await connection.close(code=1008)
        except (ConnectionClosed, EOFError, ssl.SSLError, TimeoutError, OSError):
            pass
        finally:
            try:
                if not rejected:
                    await connection.close()
            finally:
                self.release(connection)
                self.http_tasks.discard(task)
                self.link_tasks.pop(connection, None)

    async def dispatch(self, link, device, method, target, head, body, reserved=None):
        route, profile, status = "unmatched", None, 500
        try:
            path = urlsplit(target).path
            if device is None and not (method == "POST" and target == "/v1/pair"):
                route = "unauthenticated"
                raise HTTPError(403, "Registered device certificate required")
            if method == "POST" and target == "/v1/pair":
                route = "/v1/pair"
                try:
                    value = await self.pairing.enroll(json.loads(body), reserved=reserved,
                                                     charge=self.limits.pair)
                except (ValueError, TypeError, KeyError):
                    raise HTTPError(400, "Invalid pairing request") from None
                except (PermissionError, TimeoutError):
                    raise HTTPError(403, "Pairing denied") from None
                status = 200
                await response(link, status, value)
            elif method == "GET" and target == "/v1/profiles":
                route, status = "/v1/profiles", 200
                await response(link, status, await self.profiles.snapshot())
            elif method == "GET" and re.fullmatch(r"/v1/profile-events(?:\?after=(?:0|[1-9][0-9]*))?", target):
                route, status = "/v1/profile-events", 200
                await self.events(link)
            elif method == "GET" and target == "/v1/profile-templates":
                route, status = "/v1/profile-templates", 200
                await response(link, status, self.profiles.templates())
            elif method == "POST" and target == "/v1/profiles":
                route = "/v1/profiles"
                try:
                    value = await self.profiles.create(json.loads(body))
                except (ValueError, TypeError):
                    raise HTTPError(400, "Invalid profile request") from None
                status = 202
                await response(link, status, value)
            elif method == "GET" and re.fullmatch(r"/v1/operations/[0-9a-f]{32}", target):
                route, status = "/v1/operations/{id}", 200
                await response(link, status, self.profiles.operation(path.rsplit("/", 1)[1]))
            else:
                match = re.fullmatch(r"/v1/profiles/([a-z0-9][a-z0-9_-]{0,63})/hermes(/.+)", path)
                if not match:
                    raise HTTPError(404, "Route not found")
                profile = match[1]
                suffix = match[2] + ("?" + target.split("?", 1)[1] if "?" in target else "")
                template = allowed(method, suffix)
                if not template:
                    profile = None
                    raise HTTPError(404, "Route not found")
                route = "/v1/profiles/{id}/hermes" + template
                forward_head, forward_body = head, body
                if body and _has_unsupported_charset(head):
                    raise HTTPError(400, "unsupported_charset")
                if method == "PATCH" and template == "/api/sessions/{id}":
                    try:
                        patch_text = body.decode("utf-8", errors="strict")
                        patch = json.loads(patch_text)
                    except (UnicodeError, ValueError, TypeError):
                        raise HTTPError(400, "session_patch_invalid_fields") from None
                    if not isinstance(patch, dict) or set(patch) - {"title"}:
                        raise HTTPError(400, "session_patch_invalid_fields")
                    if ("title" in patch and patch["title"] is not None
                            and not isinstance(patch["title"], str)):
                        raise HTTPError(400, "session_patch_invalid_fields")
                    forward_head = {**head, "content-type": "application/json"}
                    forward_body = json.dumps(patch, ensure_ascii=False).encode("utf-8")
                if profile not in self.profiles.directories():
                    profile = None
                    raise HTTPError(404, "Profile not found")
                key = self.profiles.key(profile)
                if not key:
                    raise HTTPError(503, "Profile key needs local initialization")
                status = await self.upstream.forward(link, method, self.profiles.target(profile, suffix),
                                                     key, forward_head, forward_body)
        except StreamAborted:
            status = 502
            raise EOFError from None
        except HTTPError as error:
            status = error.status
            await response(link, status, {"error": error.message})
        except (OSError, TimeoutError, EOFError):
            status = 502
            await response(link, status, {"error": "Upstream unavailable"})
        finally:
            # Method is parser-validated; route is always a static template.
            if (device is None and status >= 400) or status == 429:
                self.audit_call("reject")
            else:
                self.audit_call("record", device, method, route, profile, status)

    async def events(self, link):
        await stream_start(link)
        previous, heartbeat = None, time.monotonic()
        task = asyncio.create_task(self.profiles.snapshot())
        try:
            while True:
                timeout = max(0.001, self.heartbeat_interval - (time.monotonic() - heartbeat))
                try:
                    snapshot = await asyncio.wait_for(asyncio.shield(task), timeout)
                except TimeoutError:
                    await chunk(link, b": keepalive\n\n")
                    heartbeat = time.monotonic()
                    continue
                if snapshot["revision"] != previous:
                    await chunk(link, b"event: profiles\ndata: " + json.dumps(snapshot).encode() + b"\n\n")
                    previous = snapshot["revision"]
                if time.monotonic() - heartbeat >= self.heartbeat_interval:
                    await chunk(link, b": keepalive\n\n")
                    heartbeat = time.monotonic()
                await asyncio.sleep(min(1, self.heartbeat_interval))
                task = asyncio.create_task(self.profiles.snapshot())
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def close(self):
        self.monitor_task.cancel()
        with suppress(asyncio.CancelledError):
            await self.monitor_task
        tasks = list(self.http_tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.server.close()
        await self.server.wait_closed()
        if self.context_task is not None:
            with suppress(Exception):
                await self.context_task
        await self.profiles.close()
