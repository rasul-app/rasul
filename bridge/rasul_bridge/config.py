"""Owner-controlled TOML settings."""

from dataclasses import dataclass, field
from pathlib import Path
import re
import tomllib
from urllib.parse import urlsplit

NAME = re.compile(r"[a-z0-9-]{2,32}\Z")
PROFILE_ID = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}\Z")


@dataclass
class Config:
    hermes_url: str = "http://127.0.0.1:8642"
    listen: str = "127.0.0.1"
    port: int = 8787
    approvals_port: int | None = None
    public_urls: list = field(default_factory=lambda: ["ws://127.0.0.1:8787/v1/link"])
    templates: list = field(default_factory=lambda: [{"id": "blank", "label": "Blank"}])
    restart_command: list = field(default_factory=list)
    hermes_home: Path = field(default_factory=lambda: Path.home() / ".hermes")
    name: str = "my-pc"

    @classmethod
    def load(cls, path):
        value = tomllib.loads(Path(path).read_text(encoding="utf-8"))
        config = cls(**value)
        config.hermes_home = Path(config.hermes_home).expanduser()
        parsed = urlsplit(config.hermes_url)
        if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path not in ("", "/")
                or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("Upstream must be an HTTP loopback origin")
        if type(config.port) is not int or not 1 <= config.port <= 65535:
            raise ValueError("Invalid listening port")
        if config.approvals_port is not None and (type(config.approvals_port) is not int
                or not 1 <= config.approvals_port <= 65535 or config.approvals_port == config.port):
            raise ValueError("Invalid approvals listening port")
        if not config.public_urls:
            raise ValueError("Set at least one public URL")
        for url in config.public_urls:
            parsed = urlsplit(url)
            if (parsed.scheme not in ("ws", "wss") or not parsed.hostname or parsed.path != "/v1/link"
                    or parsed.username or parsed.password or parsed.query or parsed.fragment):
                raise ValueError("Public URLs must address the WebSocket link")
        if (not isinstance(config.restart_command, list)
                or any(not isinstance(x, str) for x in config.restart_command)):
            raise ValueError("Restart command must be an argument list")
        ids = set()
        for template in config.templates:
            if (set(template) - {"id", "label", "clone_config"} or not NAME.fullmatch(template["id"])
                    or template["id"] in ids or not isinstance(template["label"], str)):
                raise ValueError("Invalid template")
            ids.add(template["id"])
            source = template.get("clone_config")
            if source is not None and (not isinstance(source, str)
                                       or not PROFILE_ID.fullmatch(source)):
                raise ValueError("Invalid template source")
        return config


DEFAULT_CONFIG = '''hermes_url = "http://127.0.0.1:8642"
listen = "127.0.0.1"
port = 8787
public_urls = ["ws://127.0.0.1:8787/v1/link"]
name = "my-pc"
restart_command = []

[[templates]]
id = "blank"
label = "Blank"
'''
