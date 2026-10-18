"""Bound admission, pairing attempts, and request bodies."""

from collections import deque
import time

MAX_BODY = 1024 * 1024
# One long-lived approvals stream per profile, plus profile/run streams and
# short requests, must fit per device.
MAX_CONNECTIONS = 128
MAX_DEVICE_CONNECTIONS = 64


class Limits:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.connections = 0
        self.devices = {}
        self.pairs = deque()
        self.instruction_requests = {}
        self.session_requests = {}

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

    def _session_action(self, device, profile, action):
        now = self.clock()
        self.session_requests = {
            key: deque(t for t in times if t > now - 60)
            for key, times in self.session_requests.items() if times and times[-1] > now - 60
        }
        key = (device, profile, action)
        times = self.session_requests.get(key)
        if times is None:
            if len(self.session_requests) >= 256:
                oldest = min(self.session_requests, key=lambda saved: self.session_requests[saved][-1])
                del self.session_requests[oldest]
            times = self.session_requests[key] = deque()
        limit = {"fork": 6, "model": 20, "status": 30}[action]
        if len(times) >= limit:
            return False
        times.append(now)
        return True

    def fork(self, device, profile):
        return self._session_action(device, profile, "fork")

    def model(self, device, profile):
        return self._session_action(device, profile, "model")

    def status(self, device):
        return self._session_action(device, None, "status")
