"""Audit only fields selected by the router, never request text."""

from datetime import datetime, timezone
import json
import os
import sys
import time

from .storage import atomic_write

MAX_LOG_BYTES = 5 * 1024 * 1024
LOG_FILES = 3


class Audit:
    def __init__(self, store, clock=time.monotonic):
        self.store, self.clock = store, clock
        self.path = store.root / "audit.jsonl"
        self.rejected = 0
        self.dropped = 0
        self.warned = False
        self.last_summary = clock()
        # Bound logs left behind by earlier versions, retaining complete tail
        # records only. This also bounds backups before any new traffic arrives.
        try:
            with store.lock():
                for index in range(LOG_FILES):
                    path = self.path if index == 0 else self.path.with_suffix(f".jsonl.{index}")
                    if path.exists() and path.stat().st_size > MAX_LOG_BYTES:
                        with path.open("rb") as stream:
                            stream.seek(-MAX_LOG_BYTES, os.SEEK_END)
                            tail = stream.read()
                        atomic_write(path, tail.partition(b"\n")[2])
        except Exception:
            self.drop()

    def drop(self):
        self.dropped += 1
        if not self.warned:
            self.warned = True
            print("Audit records dropped", file=sys.stderr)

    def reject(self):
        self.rejected += 1
        self.flush_rejections()

    def flush_rejections(self):
        now = self.clock()
        if self.rejected and now - self.last_summary >= 60:
            if self.write({"time": datetime.now(timezone.utc).isoformat(),
                           "rejected": self.rejected}):
                self.rejected = 0
                self.last_summary = now

    def record(self, device_id, method, route, profile_id, status):
        value = {"time": datetime.now(timezone.utc).isoformat(), "device_id": device_id,
                 "method": method, "route": route, "profile_id": profile_id, "status": status}
        self.write(value)

    def approval(self, device_id, profile_id, request_id, choice, status):
        self.write({"time": datetime.now(timezone.utc).isoformat(), "device_id": device_id,
                    "profile_id": profile_id, "request_id": request_id, "choice": choice, "status": status})

    def write(self, value):
        try:
            self._write(value)
        except Exception:
            self.drop()
            return False
        if self.dropped:
            try:
                self._write({"time": datetime.now(timezone.utc).isoformat(),
                             "dropped_records": self.dropped})
            except Exception:
                return True
            self.dropped = 0
        return True

    @staticmethod
    def replace(source, target):
        for attempt in range(3):
            try:
                os.replace(source, target)
                return
            except OSError as error:
                if getattr(error, "winerror", None) not in (32, 33) or attempt == 2:
                    raise
                time.sleep(0.01 * (attempt + 1))

    def _write(self, value):
        data = (json.dumps(value) + "\n").encode("utf-8")
        if len(data) > MAX_LOG_BYTES:
            raise ValueError("Audit record too large")
        with self.store.lock():
            if self.path.exists() and self.path.stat().st_size + len(data) > MAX_LOG_BYTES:
                for index in range(LOG_FILES - 1, 0, -1):
                    source = self.path if index == 1 else self.path.with_suffix(f".jsonl.{index - 1}")
                    if source.exists():
                        self.replace(source, self.path.with_suffix(f".jsonl.{index}"))
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
