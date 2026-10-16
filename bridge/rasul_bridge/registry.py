"""Registered public keys and local revocation."""

import uuid
from .tls import fingerprint, pem


class Registry:
    def __init__(self, store):
        self.store = store
        self.revision = 0
        self.snapshot = None

    def devices(self):
        devices = self.store.read("devices.json", {})
        # Observe writes from both this process and the local management CLI.
        if devices != self.snapshot:
            self.snapshot = devices
            self.revision += 1
        return devices

    def register(self, name, cert):
        pin = fingerprint(cert)
        with self.store.transaction("devices.json") as devices:
            for device_id, device in devices.items():
                if device["pin"] == pin:
                    if device["revoked"]:
                        raise ValueError("Revoked key requires a new device key")
                    return device_id
            device_id = uuid.uuid4().hex
            devices[device_id] = {"name": name, "pin": pin, "cert": pem(cert).decode(), "revoked": False}
        return device_id

    def identify(self, cert):
        if cert is None:
            return None
        pin = fingerprint(cert)
        return next((key for key, value in self.devices().items()
                     if value["pin"] == pin and not value["revoked"]), None)

    def active(self, device_id):
        device = self.devices().get(device_id)
        return bool(device and not device["revoked"])

    def revoke(self, device_id):
        with self.store.transaction("devices.json") as devices:
            if device_id not in devices:
                raise ValueError("Unknown device")
            devices[device_id]["revoked"] = True
