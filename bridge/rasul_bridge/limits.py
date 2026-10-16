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
