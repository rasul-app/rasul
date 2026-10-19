"""Small strict HTTP/1.1 framing; each upstream request gets a new socket."""

import asyncio
from http import HTTPStatus
import json
import re

from .limits import MAX_BODY


class HTTPError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Reader:
    def __init__(self, source):
        self.source, self.buffer = source, bytearray()

    async def until(self, marker=b"\r\n", maximum=32768):
        while True:
            index = self.buffer.find(marker)
            if index >= 0:
                if index + len(marker) > maximum:
                    raise HTTPError(431, "Headers too large")
                data = bytes(self.buffer[:index])
                del self.buffer[:index + len(marker)]
                return data
            if len(self.buffer) >= maximum:
                raise HTTPError(431, "Headers too large")
            data = await self.source.read(16384)
            if not data:
                raise EOFError
            self.buffer.extend(data)

    async def read(self, size):
        if self.buffer:
            data = bytes(self.buffer[:size])
            del self.buffer[:size]
            return data
        return await self.source.read(size)

    async def exact(self, size, idle=None):
        """``size`` bytes; with ``idle``, the wait for each read may last that long at most."""
        result = bytearray()
        while len(result) < size:
            if idle is None:
                data = await self.read(size - len(result))
            else:
                async with asyncio.timeout(idle):
                    data = await self.read(size - len(result))
            if not data:
                raise EOFError
            result.extend(data)
        return bytes(result)


def headers(raw):
    result = {}
    for line in raw:
        name, separator, value = line.partition(b":")
        if (not separator or not re.fullmatch(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name)
                or any(c < 32 and c != 9 or c == 127 for c in value)):
            raise HTTPError(400, "Invalid header")
        key = name.decode("ascii").lower()
        if key in result:
            raise HTTPError(400, "Duplicate header")
        result[key] = value.strip().decode("latin-1")
    return result


HEAD_TIMEOUT = 30       # the request line and the headers (also the wait for a next request on a kept-open link)
BODY_IDLE_TIMEOUT = 30  # the body may arrive slowly, but not stop for this long
BODY_TIMEOUT = 600      # the whole body: a 768 KiB upload chunk at about 20 kbit/s takes roughly five minutes


async def request(reader, maximum_body=None, head_timeout=HEAD_TIMEOUT, body_idle=BODY_IDLE_TIMEOUT,
                  body_timeout=BODY_TIMEOUT):
    async with asyncio.timeout(head_timeout):
        raw = (await reader.until(b"\r\n\r\n")).split(b"\r\n")
        try:
            method, target, version = raw[0].decode("ascii").split(" ")
        except ValueError:
            raise HTTPError(400, "Invalid request") from None
        if (version != "HTTP/1.1" or method not in {"GET", "POST", "DELETE", "PUT", "PATCH", "HEAD", "OPTIONS"}
                or not target.startswith("/") or any(ord(c) < 33 or ord(c) > 126 for c in target)):
            raise HTTPError(400, "Invalid request")
        head = headers(raw[1:])
        if "host" not in head or "transfer-encoding" in head or "expect" in head:
            raise HTTPError(400, "Unsupported framing")
        value = head.get("content-length", "0")
        if not re.fullmatch(r"0|[1-9][0-9]{0,9}", value):
            raise HTTPError(400, "Invalid content length")
        length = int(value)
        if length > (MAX_BODY if maximum_body is None else maximum_body):
            raise HTTPError(413, "Request body too large")
    async with asyncio.timeout(body_timeout):
        return method, target, head, await reader.exact(length, body_idle)


async def response(link, status, value):
    body = json.dumps(value, separators=(",", ":")).encode()
    await link.write((f"HTTP/1.1 {status} {HTTPStatus(status).phrase}\r\n"
                      f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\n"
                      "X-Content-Type-Options: nosniff\r\n\r\n").encode() + body)


async def no_content(link):
    # Content-Length: 0 keeps clients that read a body by its length from waiting for one.
    await link.write(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n"
                     b"X-Content-Type-Options: nosniff\r\n\r\n")


async def stream_start(link):
    await link.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                     b"Cache-Control: no-cache\r\nTransfer-Encoding: chunked\r\n\r\n")


async def chunk(link, data):
    await link.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
