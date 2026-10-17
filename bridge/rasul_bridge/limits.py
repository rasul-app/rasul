"""Bound admission, pairing attempts, and request bodies."""

from collections import deque
import time

MAX_BODY = 1024 * 1024
MAX_CONNECTIONS = 32
MAX_DEVICE_CONNECTIONS = 16


class Limits:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.connections = 0
        self.devices = {}
        self.pairs = deque()
        self.instruction_requests = {}

    def connect(self):
        if self.connections >= MAX_CONNECTIONS:
            return False
        self.connections += 1
        return True

    def authenticate(self, device):
        if self.devices.get(device, 0) >= MAX_DEVICE_CONNECTIONS:
            return False
        self.devices[device] = self.devices.get(device, 0) + 1
        return True

    def disconnect(self, device=None):
        self.connections -= 1
        if device is not None:
            self.devices[device] -= 1
            if not self.devices[device]:
                del self.devices[device]

    def pair(self):
        now = self.clock()
        while self.pairs and self.pairs[0] <= now - 60:
            self.pairs.popleft()
        if len(self.pairs) >= 5:
            return False
        self.pairs.append(now)
        return True

    def instructions(self, device):
        now = self.clock()
        # Expire inactive devices so revocation cannot grow the table forever.
        self.instruction_requests = {
            key: deque(t for t in times if t > now - 60)
            for key, times in self.instruction_requests.items() if times and times[-1] > now - 60
        }
        times = self.instruction_requests.setdefault(device, deque())
        if len(times) >= 5 or len(self.instruction_requests) > 256:
            self.instruction_requests.pop(device, None) if not times else None
            return False
        times.append(now)
        return True
