"""Self-check for the authenticated link: counts and flags only, never addresses, keys or paths."""

import asyncio
from importlib import metadata
import json

from .http import HTTPError

FALLBACK_VERSION = "0.2.6"
PROBE_TIMEOUT = 1.5
TOTAL_TIMEOUT = 2.5


def bridge_version():
    try:
        return metadata.version("rasul-bridge")
    except metadata.PackageNotFoundError:
        return FALLBACK_VERSION


async def _get(upstream, target, key):
    """JSON object from one gateway route, or None on any failure."""
    try:
        async with asyncio.timeout(PROBE_TIMEOUT):
            status, body = await upstream.collect(target, key)
        value = json.loads(body) if status == 200 else None
        return value if isinstance(value, dict) else None
    except (OSError, TimeoutError, HTTPError, EOFError, ValueError):
        return None


class Status:
    def __init__(self, bridge):
        self.bridge = bridge

    async def profile(self, name):
        bridge = self.bridge
        key = bridge.profiles.key(name)
        health = await _get(bridge.upstream, bridge.profiles.target(name, "/health"), key or "")
        return {"id": name, "api_key_configured": bool(key), "gateway_reachable": health is not None}

    async def hermes(self, key):
        bridge = self.bridge
        health = await _get(bridge.upstream, "/health", key or "")
        detail = await _get(bridge.upstream, "/health/detailed", key or "") if key else None
        version = health.get("version") if health else None
        active = detail.get("active_agents") if detail else None
        return {"version": version if isinstance(version, str) else None,
                "active_agents": active if type(active) is int else None}

    async def dashboard(self):
        try:
            async with asyncio.timeout(PROBE_TIMEOUT):
                await self.bridge.desktop.discover("default")
            return True, "ok"
        except TimeoutError:
            return False, "timeout"
        except (OSError, HTTPError, EOFError, ValueError, KeyError):
            return False, "ok"

    async def snapshot(self):
        bridge, config = self.bridge, self.bridge.config
        names = list(bridge.profiles.directories())
        try:
            async with asyncio.timeout(TOTAL_TIMEOUT):
                rows, hermes, (dashboard, check) = await asyncio.gather(
                    asyncio.gather(*(self.profile(n) for n in names)),
                    self.hermes(bridge.profiles.key("default")), self.dashboard())
        except TimeoutError:
            rows = [{"id": n, "api_key_configured": bool(bridge.profiles.key(n)),
                     "gateway_reachable": False} for n in names]
            hermes, dashboard, check = {"version": None, "active_agents": None}, False, "timeout"
        return {
            "bridge_version": bridge_version(),
            "hermes_home_found": config.hermes_home.is_dir(),
            "profiles": rows,
            "hermes": hermes,
            "dashboard_found": dashboard,
            "dashboard_check": check,
            "approvals_listener": bool(bridge.approvals is not None and bridge.approvals.server),
            "urls": {"lan": bridge.bound["lan"], "tailscale": bridge.bound["tailscale"],
                     "public": len(config.public_urls)},
        }
