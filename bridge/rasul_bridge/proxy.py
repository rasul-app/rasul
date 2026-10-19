"""Method and route allowlist, header replacement, and streaming forwarding."""

import asyncio
import json
import re
from urllib.parse import parse_qsl, urlsplit

from .http import HTTPError, Reader, headers, chunk

ID = r"[A-Za-z0-9_:.\-]{1,200}"
RULES = [
    ("GET", r"/health", "/health", set()),
    ("GET", r"/health/detailed", "/health/detailed", set()),
    ("GET", r"/v1/capabilities", "/v1/capabilities", set()),
    ("GET", r"/v1/skills", "/v1/skills", set()),
    ("GET", r"/v1/toolsets", "/v1/toolsets", set()),
    ("GET", r"/v1/models", "/v1/models", set()),
    ("GET", r"/api/model/options", "/api/model/options", {"refresh"}),
    ("POST", r"/v1/runs", "/v1/runs", set()),
    ("GET", rf"/v1/runs/{ID}", "/v1/runs/{id}", set()),
    ("GET", rf"/v1/runs/{ID}/events", "/v1/runs/{id}/events", {"after"}),
    ("POST", rf"/v1/runs/{ID}/(?:stop|steer|approval)", "/v1/runs/{id}/action", set()),
    ("GET", r"/api/sessions", "/api/sessions", {"limit", "offset", "source", "search"}),
    ("GET", rf"/api/sessions/{ID}", "/api/sessions/{id}", set()),
    ("PATCH", rf"/api/sessions/{ID}", "/api/sessions/{id}", set()),
    ("DELETE", rf"/api/sessions/{ID}", "/api/sessions/{id}", set()),
    ("GET", rf"/api/sessions/{ID}/messages", "/api/sessions/{id}/messages", {"limit", "offset", "inline_images"}),
    ("POST", rf"/api/sessions/{ID}/fork", "/api/sessions/{id}/fork", set()),
    ("POST", rf"/api/sessions/{ID}/model", "/api/sessions/{id}/model", set()),
    ("GET", r"/api/jobs", "/api/jobs", {"include_disabled"}),
    ("POST", r"/api/jobs", "/api/jobs", set()),
    ("GET", rf"/api/jobs/{ID}", "/api/jobs/{id}", set()),
    ("PATCH", rf"/api/jobs/{ID}", "/api/jobs/{id}", set()),
    ("DELETE", rf"/api/jobs/{ID}", "/api/jobs/{id}", set()),
    ("POST", rf"/api/jobs/{ID}/(?:pause|resume|run)", "/api/jobs/{id}/action", set()),
]
REQUEST_HEADERS = {"accept", "content-type", "idempotency-key", "last-event-id",
                   "x-hermes-session-id", "x-hermes-session-key"}
RESPONSE_HEADERS = {"content-type", "cache-control", "x-hermes-session-id", "retry-after"}
HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
               "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade"}


def end_to_end(head, allowlist):
    nominated = {name.strip().lower() for name in head.get("connection", "").split(",")}
    return {name: value for name, value in head.items()
            if name in allowlist and name not in HOP_HEADERS and name not in nominated}


MEDIA_NOTE = (
    "This chat is the Rasul app, reached through Rasul Bridge. This note replaces the API-server note "
    "about files. To give the user a file (image, PDF, document, audio, video or archive), put "
    "MEDIA:/absolute/path on its own line in your reply. Images appear in the chat and other files "
    "appear as download cards. Never paste a raw file path for the user to open. Send only files the "
    "user should receive.")


def with_media_note(body):
    """Body with MEDIA_NOTE first in its instructions, or None when it must be forwarded unchanged."""
    try:
        value = json.loads(body.decode("utf-8", errors="strict"))
    except (UnicodeError, ValueError):
        return None
    if not isinstance(value, dict):
        return None
    current = value.get("instructions", "")
    if not isinstance(current, str):
        return None
    value["instructions"] = MEDIA_NOTE + ("\n\n" + current if current else "")
    try:
        return json.dumps(value, ensure_ascii=False).encode("utf-8")
    except UnicodeEncodeError:  # a lone escaped surrogate cannot be re-encoded
        return None


class StreamAborted(Exception):
    """A response already started and the connection must be discarded."""


def allowed(method, target):
    parsed = urlsplit(target)
    if (parsed.scheme or parsed.netloc or parsed.fragment or "%" in parsed.path or "\\" in parsed.path
            or any(segment in (".", "..") for segment in parsed.path.split("/"))):
        return None
    for verb, pattern, template, query_keys in RULES:
        if method == verb and re.fullmatch(pattern, parsed.path):
            query = parse_qsl(parsed.query, keep_blank_values=True)
            if (any(key not in query_keys for key, _ in query)
                    or len({key for key, _ in query}) != len(query)):
                return None
            return template
    return None


class Upstream:
    def __init__(self, url):
        origin = urlsplit(url)
        if origin.scheme != "http" or origin.hostname != "127.0.0.1":
            raise ValueError("Upstream must use loopback")
        self.host, self.port = origin.hostname, origin.port or 80

    async def connect(self):
        return await asyncio.open_connection(self.host, self.port)

    async def open(self, method, target, key, head=None, body=b""):
        async with asyncio.timeout(10):
            reader, writer = await self.connect()
            try:
                lines = [f"{method} {target} HTTP/1.1", f"Host: {self.host}:{self.port}",
                         f"Authorization: Bearer {key}", "Connection: close", f"Content-Length: {len(body)}"]
                for name, value in end_to_end(head or {}, REQUEST_HEADERS).items():
                    lines.append(f"{name}: {value}")
                writer.write(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1") + body)
                await writer.drain()
                wrapped = Reader(reader)
                raw = (await wrapped.until(b"\r\n\r\n")).split(b"\r\n")
                status_line = raw[0].split(b" ", 2)
                if (len(status_line) < 2 or status_line[0] not in (b"HTTP/1.0", b"HTTP/1.1")
                        or not re.fullmatch(rb"[1-5][0-9]{2}", status_line[1])):
                    raise HTTPError(502, "Invalid upstream response")
                return int(status_line[1]), headers(raw[1:]), wrapped, writer
            except BaseException:
                writer.close()
                raise

    async def collect(self, target, key):
        status, head, reader, writer = await self.open("GET", target, key)
        try:
            result = bytearray()
            async with asyncio.timeout(10):
                async for data in response_body(reader, head):
                    result.extend(data)
                    if len(result) > 1024 * 1024:
                        raise HTTPError(502, "Upstream response too large")
            return status, bytes(result)
        finally:
            writer.close()
            await writer.wait_closed()

    async def forward(self, link, method, target, key, head, body, rewrite=None):
        status, upstream_head, reader, writer = await self.open(method, target, key, head, body)
        started = False
        try:
            lines = [f"HTTP/1.1 {status} Response", "Transfer-Encoding: chunked"]
            for name, value in end_to_end(upstream_head, RESPONSE_HEADERS).items():
                lines.append(f"{name}: {value}")
            head_bytes = ("\r\n".join(lines) + "\r\n\r\n").encode("latin-1")
            if rewrite is not None and rewrite.buffered:
                # The whole body is rewritten first, so an oversize or broken body is still a clean error.
                async for data in response_body(reader, upstream_head):
                    rewrite.feed(data)
                await link.write(head_bytes)
                started = True
                for data in rewrite.finish():
                    await chunk(link, data)
                await link.write(b"0\r\n\r\n")
                return status
            await link.write(head_bytes)
            started = True
            async for data in response_body(reader, upstream_head):
                for part in (data,) if rewrite is None else rewrite.feed(data):
                    if part:
                        await chunk(link, part)
            if rewrite is not None:
                for part in rewrite.finish():
                    await chunk(link, part)
            await link.write(b"0\r\n\r\n")
            return status
        except (HTTPError, OSError, TimeoutError, EOFError):
            if started:
                raise StreamAborted from None
            raise
        finally:
            writer.close()
            await writer.wait_closed()


async def response_body(reader, head):
    transfer = head.get("transfer-encoding")
    if transfer:
        if transfer.lower() != "chunked" or "content-length" in head:
            raise HTTPError(502, "Invalid upstream framing")
        while True:
            line = await reader.until(maximum=128)
            if not re.fullmatch(rb"[0-9a-fA-F]{1,16}", line):
                raise HTTPError(502, "Invalid upstream chunk")
            remaining = int(line, 16)
            if remaining == 0:
                # No trailers are forwarded.
                while await reader.until():
                    pass
                return
            while remaining:
                data = await reader.read(min(16384, remaining))
                if not data:
                    raise EOFError
                remaining -= len(data)
                yield data
            if await reader.exact(2) != b"\r\n":
                raise HTTPError(502, "Invalid upstream chunk")
    elif "content-length" in head:
        length = head["content-length"]
        if not re.fullmatch(r"0|[1-9][0-9]{0,18}", length):
            raise HTTPError(502, "Invalid upstream length")
        remaining = int(length)
        while remaining:
            data = await reader.read(min(16384, remaining))
            if not data:
                raise EOFError
            remaining -= len(data)
            yield data
    else:
        while data := await reader.read(16384):
            yield data
