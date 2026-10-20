"""WebSocket admission and authenticated inner HTTP dispatch."""

import asyncio
from contextlib import suppress
from datetime import datetime, timezone
from email.message import Message
from http import HTTPStatus
import json
import logging
import random
import re
import ssl
import sys
import time
from urllib.parse import urlsplit

from cryptography import x509
from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from .audit import Audit
from .approvals import ALL_ROUTE, Approvals, PHONE_ROUTE
from .desktop import Desktop, RULES as DESKTOP_RULES
from .http import HTTPError, Reader, chunk, no_content, request, response, stream_start
from .limits import Limits, MAX_BODY
from .instructions import Instructions
from .processes import running as running_processes
from .network import extra_plan, lan_addresses, tailscale_addresses
from .status import Status
from .pairing import Pairing
from .profiles import Profiles
from .files import Files, TOKEN
from .proxy import StreamAborted, Upstream, allowed, with_media_note
from .registry import Registry
from .uploads import Uploads
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
        self.instructions = Instructions(self.profiles)
        self.desktop = Desktop(self.profiles)
        self.status = Status(self)
        self.files = Files(config.hermes_home, store.root, self.file_audit)
        self.uploads = Uploads(store.root, lambda profile: self.profiles.directories().get(profile))
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
        self.extra_servers = []
        self.bound = {"lan": 0, "tailscale": 0}
        self.heartbeat_interval = 15
        self.approvals = (Approvals(store, self.profiles, self.audit_call)
                          if config.approvals_port is not None else None)

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

    def file_audit(self, event, device, profile, name, size):
        self.audit_call("write", {"time": datetime.now(timezone.utc).isoformat(), "device_id": device,
                                  "profile_id": profile, "event": event, "filename": name, "size": size})

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
                self.audit_call("rejected_link", "global_limit", device)
                raise ValueError("Connection limit")
            if not self.limits.authenticate(device):
                self.limits.disconnect()
                self.audit_call("rejected_link", "device_limit", device)
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
        options = dict(create_connection=lambda *a, **kw: AcceptedConnection(*a, bridge=self, **kw),
                       process_request=self.admission, compression=None,
                       server_header=None, max_size=65536, max_queue=2,
                       open_timeout=UPGRADE_TIMEOUT, close_timeout=1, logger=logger)
        # The configured address stays fatal on failure; detected addresses are best-effort.
        self.server = await serve(self.handle, self.config.listen, self.config.port, **options)
        self.port = self.server.sockets[0].getsockname()[1]
        plan = extra_plan(self.config)
        for kind, address in plan:
            try:
                self.extra_servers.append(await serve(self.handle, address, self.port, **options))
                self.bound[kind] += 1
            except OSError:
                pass
        if len(self.extra_servers) < len(plan):
            print(f"{len(plan) - len(self.extra_servers)} of {len(plan)} extra addresses failed to bind.",
                  file=sys.stderr, flush=True)
        if self.config.listen in ("0.0.0.0", "::"):
            # Listening on every address already covers the detected ones.
            self.bound = {"lan": len(lan_addresses()) if self.config.lan else 0,
                          "tailscale": len(tailscale_addresses()) if self.config.tailscale else 0}
        if self.approvals is not None:
            try:
                await self.approvals.start(self.config.approvals_port)
            except Exception:
                await self.close_servers()
                raise
        self.monitor_task = asyncio.create_task(self.monitor())
        return self

    async def close_servers(self):
        servers = [self.server, *self.extra_servers]
        for server in servers:
            server.close()
        for server in servers:
            await server.wait_closed()

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
            self.uploads.sweep()
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
            elif self.approvals is not None and ALL_ROUTE.fullmatch(target):
                route = "/v1/approvals/events" if target == "/v1/approvals/events" else "/v1/approvals"
                await self.approvals.phone_all(link, device, method, target)
                status = 200
            elif self.approvals is not None and PHONE_ROUTE.fullmatch(target):
                match = PHONE_ROUTE.fullmatch(target)
                route, profile = "/v1/profiles/{id}/approvals", match[1]
                await self.approvals.phone(link, device, method, match, body)
                status = 200
            elif method == "GET" and target == "/v1/status":
                route, status = "/v1/status", 200
                if not self.limits.status(device):
                    raise HTTPError(429, "status_rate_limited")
                await response(link, status, await self.status.snapshot())
            elif method == "GET" and re.fullmatch(r"/v1/files/" + TOKEN.pattern, target):
                route = "/v1/files/{token}"
                await self.files.download(link, device, target.rsplit("/", 1)[1])
                status = 200
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
            elif method in ("GET", "PUT") and re.fullmatch(
                    r"/v1/profiles/([a-z0-9][a-z0-9_-]{0,63})/instructions", target):
                route = "/v1/profiles/{id}/instructions"
                profile = target.split("/")[3]
                if not self.limits.instructions(device):
                    raise HTTPError(429, "instructions_rate_limited")
                value = None
                if method == "PUT":
                    try:
                        if _has_unsupported_charset(head):
                            raise ValueError
                        value = json.loads(body.decode("utf-8", errors="strict"))
                        if value is None:
                            raise ValueError
                    except (UnicodeError, ValueError, TypeError):
                        raise HTTPError(400, "instructions_invalid") from None
                value = self.instructions.call(profile, value)
                status = 200
                await response(link, status, value)
            elif method == "GET" and re.fullmatch(r"/v1/profiles/[a-z0-9][a-z0-9_-]{0,63}/processes", target):
                route = "/v1/profiles/{id}/processes"
                directory = self.profiles.directories().get(target.split("/")[3])
                if directory is None:
                    raise HTTPError(404, "Profile not found")
                profile = target.split("/")[3]
                status = 200
                await response(link, status, running_processes(directory))
            elif method == "POST" and re.fullmatch(r"/v1/profiles/[a-z0-9][a-z0-9_-]{0,63}/uploads", target):
                route, profile = "/v1/profiles/{id}/uploads", target.split("/")[3]
                value = self.uploads.start(device, profile, body)
                status = 201
                await response(link, status, value)
            elif method == "PUT" and re.fullmatch(r"/v1/uploads/[0-9a-f]{32}/chunks/(?:0|[1-9][0-9]{0,5})", target):
                route = "/v1/uploads/{id}/chunks/{index}"
                _, _, _, key, _, index = target.split("/")
                self.uploads.chunk(device, key, int(index), body)
                status = 204
                await no_content(link)
            elif method == "POST" and re.fullmatch(r"/v1/uploads/[0-9a-f]{32}/complete", target):
                route = "/v1/uploads/{id}/complete"
                value = self.uploads.complete(device, target.split("/")[3], body)
                status = 200
                await response(link, status, value)
            elif method == "DELETE" and re.fullmatch(r"/v1/uploads/[0-9a-f]{32}", target):
                route = "/v1/uploads/{id}"
                self.uploads.delete(device, target.rsplit("/", 1)[1])
                status = 204
                await no_content(link)
            elif re.match(r"/v1/profiles/[a-z0-9][a-z0-9_-]{0,63}/desktop(?:/|$)", path):
                route = "/v1/profiles/{id}/desktop/unknown"
                profile = path.split("/")[3]
                suffix = target[len("/v1/profiles/" + profile + "/desktop"): ]
                for verb, pattern, name, cap in DESKTOP_RULES:
                    if method == verb and re.fullmatch(pattern, urlsplit(suffix).path):
                        route = "/v1/profiles/{id}/desktop/" + name
                        break
                if body and _has_unsupported_charset(head):
                    raise HTTPError(400, "desktop_invalid")
                status, value = await self.desktop.call(device, profile, method, suffix, body)
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
                if template in ("/api/sessions/{id}/fork", "/api/sessions/{id}/model"):
                    action = template.rsplit("/", 1)[1]
                    fields = {"title"} if action == "fork" else {"model", "provider", "require_model_lock"}
                    try:
                        value = json.loads(body.decode("utf-8", errors="strict"))
                        if not isinstance(value, dict) or set(value) - fields:
                            raise ValueError
                        for field, item in value.items():
                            if field == "require_model_lock":
                                if not isinstance(item, bool):
                                    raise ValueError
                            elif item is not None and not isinstance(item, str):
                                raise ValueError
                    except (UnicodeError, ValueError, TypeError):
                        raise HTTPError(400, action + "_invalid_fields") from None
                    forward_head = {**head, "content-type": "application/json"}
                    forward_body = json.dumps(value, ensure_ascii=False).encode("utf-8")
                run_body = body
                if template == "/v1/runs" and method == "POST" and body:
                    run_body = self.uploads.attach(device, profile, body)
                    if run_body is not body:
                        forward_head, forward_body = {**head, "content-type": "application/json"}, run_body
                if template == "/v1/runs" and method == "POST" and self.config.media_note and run_body:
                    noted = with_media_note(run_body)
                    # A body already at the limit is forwarded as sent rather than grown past it.
                    if noted is not None and len(noted) <= MAX_BODY:
                        forward_head = {**head, "content-type": "application/json"}
                        forward_body = noted
                if profile not in self.profiles.directories():
                    profile = None
                    raise HTTPError(404, "Profile not found")
                key = self.profiles.key(profile)
                if not key:
                    raise HTTPError(503, "Profile key needs local initialization")
                if template == "/api/sessions/{id}/fork" and not self.limits.fork(device, profile):
                    raise HTTPError(429, "fork_rate_limited")
                if template == "/api/sessions/{id}/model" and not self.limits.model(device, profile):
                    raise HTTPError(429, "model_rate_limited")
                status = await self.upstream.forward(link, method, self.profiles.target(profile, suffix),
                                                     key, forward_head, forward_body,
                                                     self.files.filter(device, profile, template)
                                                     if method == "GET" else None)
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
            if route.startswith("/v1/profiles/{id}/desktop/"):
                self.audit_call("write", {"route": route, "status": status})
            summarized = (device is None and status >= 400) or status == 429
            if summarized and not route.startswith("/v1/profiles/{id}/desktop/"):
                self.audit_call("reject")
            if (not route.startswith("/v1/profiles/{id}/desktop/")
                    and (not summarized or (device is not None and route in (
                        "/v1/profiles/{id}/instructions",
                        "/v1/profiles/{id}/hermes/api/sessions/{id}/fork",
                        "/v1/profiles/{id}/hermes/api/sessions/{id}/model")))):
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
        if self.approvals is not None:
            await self.approvals.close()
        self.monitor_task.cancel()
        with suppress(asyncio.CancelledError):
            await self.monitor_task
        tasks = list(self.http_tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await self.close_servers()
        if self.context_task is not None:
            with suppress(Exception):
                await self.context_task
        await self.profiles.close()
