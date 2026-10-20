"""Profile discovery and durable, constrained creation operations."""

import asyncio
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import uuid

from .config import NAME, PROFILE_ID
from .http import HTTPError
from .storage import atomic_write, protect


PROBE_SECONDS = 3


class Profiles:
    def __init__(self, store, config, upstream, run=None):
        self.store, self.config, self.upstream = store, config, upstream
        self.run = run or self.run_cli
        self.tasks = set()

    def recover(self):
        # Dispatch after a crash is uncertain; never repeat the CLI automatically.
        with self.store.transaction("operations.json") as operations:
            for operation in operations.values():
                if operation["state"] == "running":
                    operation.update(state="failed", error="Outcome uncertain; inspect profiles locally")

    def directories(self):
        root = self.config.hermes_home
        result = {"default": root}
        named = root / "profiles"
        if named.is_dir():
            for path in sorted(named.iterdir()):
                if path.is_dir() and not path.is_symlink() and PROFILE_ID.fullmatch(path.name) and path.name != "default":
                    result[path.name] = path
        return result

    def key(self, profile):
        return self.store.read("profile-keys.json", {}).get(profile)

    def init_key(self, profile, directory, rotate=False):
        """Only called by explicit initialization or a new-profile operation."""
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        env = directory / ".env"
        if env.is_symlink():
            raise ValueError("Environment file must not be a symbolic link")
        with self.store.lock():
            original = env.read_bytes() if env.exists() else b""
            text = original.decode("utf-8")
            found = re.findall(r"(?m)^\s*(?:export\s+)?API_SERVER_KEY\s*=\s*([^\r\n]*)", text)
            current = found[-1].strip().strip("\"'") if found else ""
            key = secrets.token_urlsafe(32) if rotate or not current else current
            if not re.fullmatch(r"[!-~]{16,512}", key):
                raise ValueError("Existing API key is not a supported bearer value")
            if rotate or not current:
                # Backup remains protected in bridge storage, including on Windows.
                if original:
                    backup = self.store.root / ("env-backup-" + profile + "-" + uuid.uuid4().hex)
                    atomic_write(backup, protect(original))
                ending = "\r\n" if b"\r\n" in original else "\n"
                if rotate:
                    text = re.sub(r"(?m)^\s*(?:export\s+)?API_SERVER_KEY\s*=[^\r\n]*", "", text)
                if text and not text.endswith("\n"):
                    text += ending
                text += "API_SERVER_KEY=" + key + ending
                # A new file is owner-only on POSIX. On Windows preserve the
                # owner's existing file ACL and protect its containing profile.
                if os.name == "nt":
                    from .storage import private_dir
                    private_dir(directory)
                atomic_write(env, text.encode())
            keys = self.store.read("profile-keys.json", {})
            keys[profile] = key
            self.store.write("profile-keys.json", keys)
            return not current or rotate

    def init_keys(self):
        changed = 0
        for profile, directory in self.directories().items():
            changed += self.init_key(profile, directory)
        return changed

    def target(self, profile, path):
        return ("" if profile == "default" else "/p/" + profile) + path

    async def ready(self, profile):
        key = self.key(profile)
        if not key:
            return False
        try:
            # One limit for connect, headers and body: a busy profile must not hold up the list.
            async with asyncio.timeout(PROBE_SECONDS):
                status, _ = await self.upstream.collect(self.target(profile, "/v1/models"), key)
            return status == 200
        except TimeoutError:
            previous = self.store.read("profile-snapshot.json", {}).get("profiles", [])
            return next((item["ready"] for item in previous if item["id"] == profile), False)
        except (OSError, HTTPError, EOFError):
            return False

    async def snapshot(self):
        names = list(self.directories())
        readiness = await asyncio.gather(*(self.ready(name) for name in names))
        profiles = [{"id": name, "name": name, "display_name": name, "ready": ready, "default": name == "default"}
                    for name, ready in zip(names, readiness)]
        with self.store.transaction("profile-snapshot.json") as value:
            if value.get("profiles") != profiles:
                value.update(revision=value.get("revision", 0) + 1, profiles=profiles)
            return dict(value)

    def templates(self):
        return [{"id": item["id"], "label": item["label"]} for item in self.config.templates]

    async def create(self, body):
        if not isinstance(body, dict) or set(body) != {"request_id", "name", "template"}:
            raise HTTPError(400, "Invalid profile request")
        request_id, name, template_id = body["request_id"], body["name"], body["template"]
        try:
            if not isinstance(request_id, str) or str(uuid.UUID(request_id)) != request_id:
                raise ValueError
        except (ValueError, AttributeError):
            raise HTTPError(400, "A canonical request UUID is required") from None
        if not isinstance(name, str) or not NAME.fullmatch(name) or name == "default":
            raise HTTPError(400, "Invalid profile name")
        with self.store.transaction("operations.json") as operations:
            for operation_id, operation in operations.items():
                if operation["request_id"] == request_id:
                    if operation["name"] != name or operation["template"] != template_id:
                        raise HTTPError(409, "Request UUID was used for a different operation")
                    return {"operation_id": operation_id}
            template = next((t for t in self.config.templates if t["id"] == template_id), None)
            if template is None:
                raise HTTPError(400, "Unknown template")
            source = template.get("clone_config")
            if source and source not in self.directories():
                raise HTTPError(400, "Template source is unavailable")
            if (name in self.directories()
                    or any(op["name"] == name for op in operations.values())):
                raise HTTPError(409, "Profile name already exists or is reserved")
            operation_id = uuid.uuid4().hex
            operations[operation_id] = {"request_id": request_id, "name": name, "template": template_id,
                                        "state": "running"}
        task = asyncio.create_task(self.perform(operation_id, name, source))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return {"operation_id": operation_id}

    async def run_cli(self, args):
        process = await asyncio.create_subprocess_exec(*args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            async with asyncio.timeout(120):
                if await process.wait() != 0:
                    raise ValueError("Profile creation failed")
        except BaseException:
            if process.returncode is None:
                process.kill()
                await process.wait()
            raise

    async def maybe_restart(self):
        if not self.config.restart_command:
            return
        key = self.key("default")
        if not key:
            return
        status, data = await self.upstream.collect("/health/detailed", key)
        if status == 200:
            value = json.loads(data)
            active = value.get("active_agents")
            if type(active) is int and active == 0:
                await self.run(self.config.restart_command)

    async def perform(self, operation_id, name, source):
        state = {"state": "failed", "error": "Profile creation failed; inspect locally"}
        try:
            args = ["hermes", "profile", "create"]
            if source:
                args += ["--clone-from", source]
            args += ["--", name]
            await self.run(args)
            directory = self.config.hermes_home / "profiles" / name
            if not directory.is_dir() or directory.is_symlink():
                raise ValueError("Profile directory is unavailable")
            # Configuration clones include the source's API key. Replace it so
            # every new profile has a distinct key before it becomes ready.
            self.init_key(name, directory, rotate=bool(source))
            await self.maybe_restart()
            async with asyncio.timeout(120):
                while not await self.ready(name):
                    await asyncio.sleep(1)
            state = {"state": "done", "profile_id": name}
        except asyncio.CancelledError:
            state = {"state": "failed", "error": "Outcome uncertain; inspect profiles locally"}
            raise
        except Exception:
            pass  # subprocess/configuration details must never reach the phone
        finally:
            with self.store.transaction("operations.json") as operations:
                operations[operation_id].update(state)

    def operation(self, operation_id):
        value = self.store.read("operations.json", {}).get(operation_id)
        if not value:
            raise HTTPError(404, "Unknown operation")
        return {key: value[key] for key in ("state", "profile_id", "error") if key in value}

    async def close(self):
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
