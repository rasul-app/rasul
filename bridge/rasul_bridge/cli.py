"""Local owner commands. Device management has no network endpoint."""

import argparse
import asyncio
from contextlib import suppress
import io
import json
import os
from pathlib import Path
import select
import struct
import subprocess
import sys
import tempfile
import time
import uuid
import zlib
from xml.sax.saxutils import escape

from .config import Config, DEFAULT_CONFIG
from .registry import Registry
from .server import Bridge
from .storage import Store, atomic_write, current_sid, data_dir, private_dir
from .tls import ensure_identity, fingerprint


def initialize(store):
    ensure_identity(store)
    with store.lock():
        if not (store.root / "config.toml").exists():
            atomic_write(store.root / "config.toml", DEFAULT_CONFIG.encode())
        if not store.read("bridge.json"):
            store.write("bridge.json", {"id": uuid.uuid4().hex})


def png(matrix):
    """Encode the QR matrix as PNG using only the standard library."""
    scale = 4
    rows = [bytes(0 if bit else 255 for bit in row for _ in range(scale)) for row in matrix]
    raw = b"".join(b"\0" + row for row in rows for _ in range(scale))

    def block(kind, content):
        return (struct.pack("!I", len(content)) + kind + content
                + struct.pack("!I", zlib.crc32(kind + content)))

    size = len(matrix) * scale
    return (b"\x89PNG\r\n\x1a\n" + block(b"IHDR", struct.pack("!IIBBBBB", size, size, 8, 0, 0, 0, 0))
            + block(b"IDAT", zlib.compress(raw)) + block(b"IEND", b""))


def read_answer(deadline):
    """Bounded terminal input without a thread that survives a timeout."""
    chars = []
    while time.monotonic() < deadline:
        if os.name == "nt":
            import msvcrt
            if not msvcrt.kbhit():
                time.sleep(0.05)
                continue
            char = msvcrt.getwch()
            if char == "\x03":
                raise KeyboardInterrupt
            if char in "\r\n":
                print()
                return "".join(chars).lower() == "y"
            if char in "ynYN" and not chars:
                chars.append(char)
                print(char, end="", flush=True)
        else:
            readable, _, _ = select.select([sys.stdin], [], [], min(0.1, max(0, deadline - time.monotonic())))
            if readable:
                return sys.stdin.readline().strip().lower() == "y"
    return False


def show_qr(code, out=None):
    out = out or sys.stdout
    text = io.StringIO()
    code.print_ascii(out=text, invert=True)
    try:
        out.write(text.getvalue())
    except UnicodeEncodeError:
        # Some Windows consoles cannot encode the block characters; the image below still works.
        out.write("This console cannot draw the QR code. Open the image below to scan it.\n")


def pair(store, config):
    from .pairing import Pairing
    import qrcode
    ceremony = Pairing(store, Registry(store), ensure_identity(store))
    payload, text = ceremony.issue(config.public_urls, store.read("bridge.json")["id"], config.name)
    try:
        with tempfile.TemporaryDirectory(dir=store.root) as directory:
            private_dir(directory)
            code = qrcode.QRCode(border=4)
            code.add_data(text)
            code.make(fit=True)
            atomic_write(Path(directory) / "pair.png", png(code.get_matrix()))
            show_qr(code)
            print("Temporary QR image:", Path(directory) / "pair.png")
            print("Scan using the bridge scanner. Waiting for a device (120 seconds).", flush=True)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                item = store.read("invitations.json", {}).get(payload["inv"], {})
                if item.get("state") == "pending":
                    print(f"Device: {item['device_name']}  Confirmation: {item['code']}")
                    print("Compare all six digits on the phone; approve? [y/n] ", end="", flush=True)
                    answer = read_answer(time.monotonic() + 120)
                    with store.transaction("invitations.json") as invitations:
                        current = invitations.get(payload["inv"], {})
                        if current.get("state") == "pending":
                            current["state"] = "approved" if answer else "declined"
                    # Retain the ceremony until the serving process consumes it.
                    while time.monotonic() < deadline + 120:
                        current = store.read("invitations.json", {}).get(payload["inv"], {})
                        if current.get("state") == "consumed":
                            print("Pairing ceremony finished.")
                            return
                        time.sleep(0.1)
                    return
                if item.get("state") == "consumed":
                    return
                time.sleep(0.1)
    finally:
        ceremony.consume(payload["inv"])


def task_xml(root):
    sid = escape(current_sid())
    arguments = escape(subprocess.list2cmdline(["-m", "rasul_bridge.cli", "--data-dir", str(root), "serve"]))
    command = escape(sys.executable)
    return f'''<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{sid}</UserId></LogonTrigger></Triggers>
  <Principals><Principal id="Owner"><UserId>{sid}</UserId>
    <LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel>
  </Principal></Principals>
  <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit><Enabled>true</Enabled>
  </Settings>
  <Actions Context="Owner"><Exec><Command>{command}</Command><Arguments>{arguments}</Arguments></Exec></Actions>
</Task>'''


def install_task(root, uninstall=False, run=subprocess.run):
    if os.name != "nt":
        raise ValueError("Use the documented user service on this platform")
    if uninstall:
        args = ["schtasks", "/Delete", "/TN", "RasulBridge", "/F"]
        run(args, check=True, capture_output=True)
    else:
        with tempfile.TemporaryDirectory(dir=root) as directory:
            private_dir(directory)
            path = Path(directory) / "task.xml"
            atomic_write(path, task_xml(root).encode("utf-16"))
            run(["schtasks", "/Create", "/TN", "RasulBridge", "/XML", str(path), "/F"],
                check=True, capture_output=True)


async def serve_bridge(store, config):
    # One serving process per data directory; the separate CLI remains usable.
    with serving_lock(store):
        bridge = await Bridge(store, config).start()
        try:
            print("Bridge listening on configured address; inner TLS required.", flush=True)
            await asyncio.Future()
        finally:
            await bridge.close()


class serving_lock:
    def __init__(self, store):
        self.path = store.root / "serve.lock"

    def __enter__(self):
        self.stream = open(self.path, "a+b")
        try:
            if os.name == "nt":
                import msvcrt
                self.stream.write(b"\0")
                self.stream.flush()
                self.stream.seek(0)
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                os.fchmod(self.stream.fileno(), 0o600)
                fcntl.flock(self.stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.stream.close()
            raise ValueError("Another bridge is serving this data directory") from None

    def __exit__(self, *args):
        self.stream.close()


def main():
    parser = argparse.ArgumentParser(prog="rasul-bridge")
    parser.add_argument("--data-dir", type=Path, default=data_dir())
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("init", "serve", "pair", "devices", "init-profiles", "status", "install-task", "uninstall-task"):
        commands.add_parser(command)
    commands.add_parser("revoke").add_argument("id")
    args = parser.parse_args()
    try:
        store = Store(args.data_dir)
        if args.command == "init":
            initialize(store)
            print("Initialized. Edit config.toml before serving or pairing.")
            return
        if not (store.root / "config.toml").exists():
            raise ValueError("Run init first")
        config = Config.load(store.root / "config.toml")
        if args.command == "serve":
            asyncio.run(serve_bridge(store, config))
        elif args.command == "pair":
            pair(store, config)
        elif args.command == "devices":
            print(json.dumps([{ "id": key, "name": value["name"], "revoked": value["revoked"]}
                              for key, value in Registry(store).devices().items()]))
        elif args.command == "revoke":
            Registry(store).revoke(args.id)
            print("Revoked. Open connections are closed by the serving process.")
        elif args.command == "init-profiles":
            bridge = Bridge(store, config)
            count = bridge.profiles.init_keys()
            print(f"Initialized keys; {count} changed. A gateway restart is needed for changed keys.")
        elif args.command == "status":
            bridge = Bridge(store, config)
            print(json.dumps({"pin": fingerprint(bridge.cert), "devices": len(bridge.registry.devices()),
                              "profiles": list(bridge.profiles.directories())}))
        elif args.command in ("install-task", "uninstall-task"):
            install_task(store.root, args.command == "uninstall-task")
            print("User logon task updated.")
    except KeyboardInterrupt:
        pass
    except (ValueError, OSError, KeyError):
        # Do not print exception values: they may contain a secret or child output.
        parser.exit(1, "Command failed. Check local configuration and permissions.\n")


if __name__ == "__main__":
    main()
