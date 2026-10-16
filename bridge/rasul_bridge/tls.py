"""TLS 1.3 records inside binary WebSocket messages."""

import asyncio
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import ssl
import tempfile
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from .storage import atomic_write, private_dir, protect


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def spki(cert):
    return cert.public_key().public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)


def fingerprint(cert):
    return b64(hashlib.sha256(spki(cert)).digest())


def new_identity():
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "my-pc")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    return key, cert


def key_bytes(key):
    return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption())


def pem(cert):
    return cert.public_bytes(serialization.Encoding.PEM)


def ensure_identity(store):
    with store.lock():
        if not (store.root / "server.key").exists():
            key, cert = new_identity()
            atomic_write(store.root / "server.key", protect(key_bytes(key)))
            atomic_write(store.root / "server.pem", pem(cert))
    return x509.load_pem_x509_certificate((store.root / "server.pem").read_bytes())


def server_context(store, devices):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_3
    ctx.options |= ssl.OP_NO_TICKET
    ctx.num_tickets = 0
    ctx.verify_mode = ssl.CERT_OPTIONAL
    ctx.verify_flags = ssl.VERIFY_X509_PARTIAL_CHAIN
    # The TLS implementation requires a PEM filename; keep it in an inherited
    # private directory only while loading, then unlink it immediately.
    with tempfile.TemporaryDirectory(dir=store.root) as directory:
        private_dir(directory)
        key = Path(directory) / "key.pem"
        try:
            atomic_write(key, protect((store.root / "server.key").read_bytes(), decrypt=True))
            ctx.load_cert_chain(store.root / "server.pem", key)
        finally:
            key.unlink(missing_ok=True)
    trusted = "".join(d["cert"] for d in devices.values() if not d["revoked"])
    if trusted:
        ctx.load_verify_locations(cadata=trusted)
    return ctx


def device_certificate(value):
    if not isinstance(value, str) or len(value) > 8192:
        raise ValueError("Invalid device certificate")
    cert = x509.load_pem_x509_certificate(value.encode())
    key = cert.public_key()
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("Device key must use P-256")
    now = datetime.now(timezone.utc)
    if not cert.not_valid_before_utc <= now < cert.not_valid_after_utc:
        raise ValueError("Device certificate is expired or not yet valid")
    if cert.subject != cert.issuer:
        raise ValueError("Device certificate must be self-signed")
    key.verify(cert.signature, cert.tbs_certificate_bytes, ec.ECDSA(cert.signature_hash_algorithm))
    return cert


class TLSLink:
    def __init__(self, websocket, context, server_side=True):
        self.websocket = websocket
        self.incoming = ssl.MemoryBIO()
        self.outgoing = ssl.MemoryBIO()
        self.ssl = context.wrap_bio(self.incoming, self.outgoing, server_side=server_side,
                                   server_hostname=None if server_side else "my-pc")

    async def flush(self):
        while self.outgoing.pending:
            await self.websocket.send(self.outgoing.read(16384))

    async def receive(self):
        await self.flush()
        frame = await self.websocket.recv()
        if not isinstance(frame, bytes):
            raise ValueError("Binary TLS records required")
        self.incoming.write(frame)

    async def handshake(self, established=None):
        async with asyncio.timeout(10):
            while True:
                try:
                    self.ssl.do_handshake()
                    if established is not None:
                        established()
                    await self.flush()
                    return
                except ssl.SSLWantReadError:
                    await self.receive()

    async def read(self, size=16384):
        while True:
            try:
                data = self.ssl.read(size)
                await self.flush()
                return data
            except ssl.SSLWantReadError:
                await self.receive()

    async def write(self, data):
        view = memoryview(data)
        while view:
            count = self.ssl.write(view[:16384])
            view = view[count:]
            await self.flush()
