"""Short-lived enrollment with a local owner decision."""

import asyncio
import base64
import hashlib
import hmac
import json
import re
import secrets
import time

from .tls import b64, device_certificate, fingerprint, spki
from .http import HTTPError

OWNER_TIMEOUT = 120


def secret_hash(secret):
    if not isinstance(secret, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", secret):
        raise ValueError("Invalid invitation secret")
    raw = base64.b64decode(secret + "=", altchars=b"-_", validate=True)
    if len(raw) != 32 or b64(raw) != secret:
        raise ValueError("Noncanonical invitation secret")
    return hashlib.sha256(raw).hexdigest()


def confirmation_code(server_cert, device_cert, invitation):
    digest = hashlib.sha256(spki(server_cert) + spki(device_cert) + invitation.encode()).digest()
    return str(int.from_bytes(digest, "big")).zfill(6)[:6]


class Pairing:
    def __init__(self, store, registry, server_cert, confirm=None, clock=time.time):
        self.store, self.registry, self.server_cert = store, registry, server_cert
        self.confirm, self.clock = confirm, clock

    def issue(self, urls, bridge_id, name):
        inv, secret = secrets.token_hex(16), b64(secrets.token_bytes(32))
        exp = int(self.clock()) + 120
        with self.store.transaction("invitations.json") as invitations:
            invitations.clear()  # one terminal ceremony at a time
            invitations[inv] = {"hash": secret_hash(secret), "exp": exp,
                                "state": "unused"}
        payload = {"v": 1, "bridge_id": bridge_id, "name": name, "urls": urls,
                   "pin": fingerprint(self.server_cert), "inv": inv, "sec": secret, "exp": exp}
        return payload, "rasul-pair:1:" + b64(json.dumps(payload, separators=(",", ":")).encode())

    def consume(self, inv):
        with self.store.transaction("invitations.json") as invitations:
            if inv in invitations:
                invitations[inv]["state"] = "consumed"

    async def enroll(self, body, reserved=None, charge=None):
        if not isinstance(body, dict) or not isinstance(body.get("inv"), str):
            raise ValueError("Invalid pairing request")
        inv = body["inv"]
        with self.store.transaction("invitations.json") as invitations:
            item = invitations.get(inv)
            if not item or item["state"] != "unused" or self.clock() >= item["exp"]:
                raise PermissionError("Invitation denied")
            if charge is not None and not charge():
                raise HTTPError(429, "Pairing rate limit")
            if set(body) != {"inv", "sec", "device_name", "device_cert_pem"}:
                raise ValueError("Invalid pairing request")
            if not hmac.compare_digest(item["hash"], secret_hash(body["sec"])):
                raise PermissionError("Invitation denied")
            if reserved is not None:
                reserved()
            item["state"] = "pending"
        try:
            name = body["device_name"]
            if (not isinstance(name, str) or not 1 <= len(name) <= 64
                    or not name.isprintable()):
                raise ValueError("Device name must be printable text")
            cert = device_certificate(body["device_cert_pem"])
            code = confirmation_code(self.server_cert, cert, inv)
            with self.store.transaction("invitations.json") as invitations:
                invitations[inv].update(code=code, device_name=name)
            async with asyncio.timeout(OWNER_TIMEOUT):
                if self.confirm:
                    approved = await self.confirm(name, code)
                else:
                    approved = await self.wait_decision(inv)
            if not approved:
                raise PermissionError("Pairing declined")
            device_id = self.registry.register(name, cert)
            return {"device_id": device_id}
        finally:
            self.consume(inv)

    async def wait_decision(self, inv):
        while True:
            item = self.store.read("invitations.json", {}).get(inv, {})
            if item.get("state") == "approved":
                return True
            if item.get("state") != "pending":
                return False
            await asyncio.sleep(0.1)
