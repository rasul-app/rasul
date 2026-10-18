"""Files the agent wants to send to a paired phone.

The agent names a file with a ``MEDIA:<path>`` tag. While a response is proxied, each tag is
checked against the same delivery rules the agent uses, replaced by a markdown link that
carries an unguessable token, and the phone downloads the bytes from ``/v1/files/<token>``.
The absolute path never leaves this machine.
"""

import asyncio
import codecs
from collections import OrderedDict, deque
import json
import os
from pathlib import Path, PureWindowsPath
import re
import secrets
import tempfile
import time
from urllib.parse import quote

from .http import HTTPError
from .proxy import StreamAborted

MAX_FILE_BYTES = 50 * 1024 * 1024
TOKEN_LIFETIME = 24 * 60 * 60
MAX_TOKENS_PER_DEVICE = 200
DOWNLOADS_PER_MINUTE = 30
CHUNK = 64 * 1024
HOLD_LIMIT = 4096        # longest unfinished tag line held back from a streamed delta
BODY_LIMIT = 64 * 1024 * 1024
DEFAULT_RECENT_SECONDS = 600
TOKEN = re.compile(r"[A-Za-z0-9_-]{43}")
LINK_SCHEME = "rasul-file://"

# Extensions the agent delivers (copied from its delivery list so both sides agree).
EXTENSIONS = (
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tiff", ".svg",
    ".mp4", ".mov", ".avi", ".mkv", ".webm", ".3gp",
    ".mp3", ".m2a", ".wav", ".ogg", ".opus", ".m4a", ".flac",
    ".pdf", ".docx", ".doc", ".odt", ".rtf", ".txt", ".md", ".epub",
    ".xlsx", ".xls", ".ods", ".csv", ".tsv", ".json", ".xml", ".yaml", ".yml",
    ".kmz", ".kml", ".geojson", ".gpx",
    ".pptx", ".ppt", ".odp", ".key",
    ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".apk", ".ipa",
    ".html", ".htm")
_ALTERNATION = "|".join(sorted((e.lstrip(".") for e in EXTENSIONS), key=len, reverse=True))
_TERMINATORS = "（）〈〉《》：，。；！？、“”‘’【】"
TAG = re.compile(
    r'''[`"'*_]{0,3}MEDIA:\s*'''
    r'''(?P<path>`[^`\n]+?`|"[^"\n]+?"|'[^'\n]+?'|'''
    r'''(?:~/|/|[A-Za-z]:[/\\])\S+?(?:[^\S\n]+\S+?)*?\.(?:''' + _ALTERNATION + r'''))'''
    r'''(?=[\s`"'*_,;:)\]}\[''' + _TERMINATORS + r''']|MEDIA:|\.(?:\s|$)|$)[`"'*_]{0,3}\.?''',
    re.IGNORECASE)
# Anything that looks like the start of a path, including an unfinished quote or a backslash (UNC).
LOOSE_TAG = re.compile(r"""[`"'*_]{0,3}MEDIA:\s*(?:~/|/|[A-Za-z]:[/\\]|[`"'\\])[^\n]*""", re.IGNORECASE)
_TAG_START = re.compile(r"[`\"'*_]{0,3}MEDIA:", re.IGNORECASE)
_TAG_PREFIX_AT_END = re.compile(r"[`\"'*_]{0,3}(?:M|ME|MED|MEDI|MEDIA)?\Z", re.IGNORECASE)

MIME = {
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif",
    ".webp": "image/webp", ".bmp": "image/bmp", ".tiff": "image/tiff", ".svg": "image/svg+xml",
    ".mp4": "video/mp4", ".mov": "video/quicktime", ".avi": "video/x-msvideo",
    ".mkv": "video/x-matroska", ".webm": "video/webm", ".3gp": "video/3gpp",
    ".mp3": "audio/mpeg", ".m2a": "audio/mpeg", ".wav": "audio/wav", ".ogg": "audio/ogg",
    ".opus": "audio/opus", ".m4a": "audio/mp4", ".flac": "audio/flac",
    ".pdf": "application/pdf", ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".doc": "application/msword", ".odt": "application/vnd.oasis.opendocument.text",
    ".rtf": "application/rtf", ".txt": "text/plain", ".md": "text/markdown", ".epub": "application/epub+zip",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".xls": "application/vnd.ms-excel", ".ods": "application/vnd.oasis.opendocument.spreadsheet",
    ".csv": "text/csv", ".tsv": "text/tab-separated-values", ".json": "application/json",
    ".xml": "application/xml", ".yaml": "application/yaml", ".yml": "application/yaml",
    ".kmz": "application/vnd.google-earth.kmz", ".kml": "application/vnd.google-earth.kml+xml",
    ".geojson": "application/geo+json", ".gpx": "application/gpx+xml",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".ppt": "application/vnd.ms-powerpoint", ".odp": "application/vnd.oasis.opendocument.presentation",
    ".zip": "application/zip", ".tar": "application/x-tar", ".gz": "application/gzip",
    ".tgz": "application/gzip", ".bz2": "application/x-bzip2", ".xz": "application/x-xz",
    ".7z": "application/x-7z-compressed", ".rar": "application/vnd.rar",
    ".apk": "application/vnd.android.package-archive", ".html": "text/html", ".htm": "text/html"}

# Never delivered, wherever they live: system state and credential stores.
DENIED_PREFIXES = ("/etc", "/proc", "/sys", "/dev", "/root", "/boot", "/var/log", "/var/lib", "/var/run")
DENIED_HOME = ("Library/Keychains", ".ssh", ".aws", ".gnupg", ".kube", ".docker", ".config", ".azure", ".gcloud")
# plus every top-level dot entry of the home, see _denied
RESERVED_DEVICE = re.compile(r"(?i)(?:con|prn|aux|nul|conin\$|conout\$|com[0-9¹²³]|lpt[0-9¹²³])")
# Credential and history stores at the agent's home (and each profile's home).
DENIED_AGENT = (".env", "auth.json", "auth.lock", "credentials", "config.yaml",
                "google_token.json", "google_oauth_pending.json", "auth", "webhook_subscriptions.json",
                "pairing", "mcp-tokens", "sessions", "browser-profile",
                "state.db", "state.db-wal", "state.db-shm", "state.db-journal",
                "kanban.db", "kanban.db-wal", "kanban.db-shm", "kanban.db-journal",
                "cache/bws_cache.json", "cache/bws_cache.enc.json")
KANBAN_DB = ("kanban.db", "kanban.db-wal", "kanban.db-shm", "kanban.db-journal")
# Served as a download, never as a page: a viewer must not run or render these.
ACTIVE_TYPES = (".html", ".htm", ".svg", ".xml", ".js")
CACHE_DIRS = ("images", "audio", "videos", "documents", "screenshots", "generated")
LEGACY_CACHE_DIRS = ("image_cache", "audio_cache", "video_cache", "document_cache", "browser_screenshots")
SECRET_NAME = re.compile(
    r"(?is)^(?:\.env(?:\..*)?|.*\.(?:pem|key|pfx|p12|kdbx|jks|keystore|ppk)|id_(?:rsa|dsa|ecdsa|ed25519).*|"
    r"\.netrc|\.npmrc|\.pypirc|.*credential.*|secrets?(?:\.[a-z]+)?|.*(?:oauth|token).*\.json)\Z")
_TRUE, _FALSE = ("true", "yes", "on"), ("false", "no", "off")


def _scalar(text):
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        return text[1:-1]
    if text.lower() in _TRUE:
        return True
    if text.lower() in _FALSE:
        return False
    return text


_OFF = ("", "null", "Null", "NULL", "~", "false", "False", "FALSE", "no", "No", "NO", "off", "Off", "OFF")
_INT_ZERO = re.compile(r"[-+]?(?:0b([01_]+)|0x([0-9a-fA-F_]+)|0([0-7_]+)|(0))")
_FLOAT = re.compile(r"[-+]?[0-9][0-9_]*\.[0-9_]*(?:[eE][-+][0-9]+)?|\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?"
                    r"|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*")


def _zero_number(value):
    """True when the YAML 1.1 loader reads ``value`` as the number zero (an int or a float)."""
    match = _INT_ZERO.fullmatch(value)
    if match:
        return not "".join(g for g in match.groups() if g).strip("0_")
    if _FLOAT.fullmatch(value):
        return not re.search("[1-9]", re.split("[eE]", value)[0])
    return False


def _on(raw):
    """Truthiness of a raw config value, as the agent's YAML 1.1 loader plus ``bool()`` gives it.

    Only the exact spellings the loader reads as false or null (and numeric zero) are off; mixed
    case such as ``FaLsE`` is a plain string, which is on."""
    value = raw.strip()
    if value == "nested":
        return True
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return True          # any quoted string is a non-empty string, even "false"
    return not (value in _OFF or _zero_number(value))


FLAG_KEYS = ("strict", "trust_recent_files")
_BAD = ({}, {}, False)


def gateway_settings(home):
    """(settings, raw flag values, trusted) for the ``gateway:`` keys of a profile's config.yaml.

    ``trusted`` is False when the file cannot be read or understood; the caller then fails closed.
    A missing file is trusted and empty (the agent's defaults apply)."""
    try:
        with open(Path(home) / "config.yaml", "rb") as stream:
            data = stream.read(1024 * 1024 + 1)
    except FileNotFoundError:
        return {}, {}, True
    except OSError:
        return _BAD
    if len(data) > 1024 * 1024 or data.startswith(codecs.BOM_UTF8) or b"\x00" in data:
        return _BAD
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        return _BAD
    settings, flags, inside, seen, child, last = {}, {}, False, False, None, None
    for line in lines:
        body = re.sub(r"(?:^|\s)#.*$", "", line).rstrip()
        if not body.strip() or body in ("---", "..."):
            continue
        if re.match(r" *\t", body):
            return _BAD
        indent = len(body) - len(body.lstrip())
        body = body.strip()
        if indent == 0:
            if ":" not in body:
                return _BAD
            key, _, rest = body.partition(":")
            if key.strip().strip("\"'") == "<<":
                return _BAD          # a merge key can bring in a gateway section
            if key.strip().strip("\"'") == "gateway":
                if seen or key != "gateway" or rest.strip().lower() not in ("", "null", "~"):
                    return _BAD      # flow mapping, odd spelling or a second section
                seen = True
            inside, child, last = key == "gateway", None, None
            continue
        if not inside:
            continue
        if body.startswith("- "):
            if isinstance(settings.get(last), list):
                settings[last].append(_scalar(body[2:]))
                if flags.get(last) == "":
                    flags[last] = "nested"
            elif last in FLAG_KEYS:  # "- x" after a plain value is more words of that value
                flags[last] = "nested"
            continue
        child = indent if child is None else child
        if indent < child or (indent == child and ":" not in body):
            return _BAD
        if indent > child:
            if last in FLAG_KEYS:    # a continuation line or a nested value: not the plain word it follows
                flags[last] = "nested"
            continue
        key, _, value = body.partition(":")
        last, value = key.strip().strip("\"'"), value.strip()
        if last == "<<":
            flags["strict"] = "nested"   # a merge key can bring in any flag
        if last in FLAG_KEYS:
            flags[last] = value
        if value.startswith("[") and value.endswith("]"):
            settings[last] = [_scalar(v) for v in value[1:-1].split(",") if v.strip()]
        else:
            settings[last] = [] if not value else _scalar(value)
    return settings, flags, True


def unsafe_form(text):
    """Network shares, device namespaces and alternate data streams: never looked up on disk."""
    if text.startswith(("//", "\\\\", "/\\", "\\/")) or PureWindowsPath(text).drive.startswith("\\\\"):
        return True
    if ":" in (text[2:] if re.match(r"[A-Za-z]:", text) else text):
        return True
    return any(RESERVED_DEVICE.fullmatch(part.split(".")[0].strip()) for part in re.split(r"[\\/]", text))


DRIVE_REMOTE = 4
CACHE_SECONDS = 60
_DRIVE_CACHE = {}


def _drive_type(letter):
    try:
        import ctypes
        return ctypes.windll.kernel32.GetDriveTypeW(letter.upper() + ":\\")
    except (ImportError, AttributeError, OSError):
        return 0


def remote_drive(text):
    """True when the path starts with a drive letter that is a mapped network drive (asked once per letter a minute)."""
    match = re.match(r"([A-Za-z]):", text)
    if match is None:
        return False
    letter = match[1].upper()
    now = time.monotonic()
    hit = _DRIVE_CACHE.get(letter)
    if hit is None or now - hit[1] >= CACHE_SECONDS:
        hit = _DRIVE_CACHE[letter] = (_drive_type(letter) == DRIVE_REMOTE, now)
    return hit[0]


def _truthy(name):
    return os.environ.get(name, "").strip().lower() in ("1", "true", "yes", "on")


def _resolve(path, strict=False):
    try:
        return path.expanduser().resolve(strict=strict)
    except (OSError, RuntimeError, ValueError):
        return None


def _within(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def normalize(raw):
    path = str(raw or "").strip()
    if len(path) >= 2 and path[0] == path[-1] and path[0] in "`\"'":
        path = path[1:-1].strip()
    return path.lstrip("`\"'").rstrip("`\"',.;:)}]")


def human_size(size):
    if size < 1024:
        return f"{size} B"
    if size < 1024 ** 2:
        return f"{size / 1024:.1f} KB"
    return f"{size / 1024 ** 2:.1f} MB"


def clean_name(name):
    name = re.sub(r"[\x00-\x1f\x7f  \"\\/<>|?*:]", "_", name).strip(" .")
    return (name or "file")[:120]


def map_strings(value, function):
    if isinstance(value, str):
        return function(value)
    if isinstance(value, list):
        return [map_strings(v, function) for v in value]
    if isinstance(value, dict):
        return {k: map_strings(v, function) for k, v in value.items()}
    return value


def label(name, size):
    return f"{name.replace('[', '(').replace(']', ')')} ({human_size(size)})"


class Files:
    def __init__(self, hermes_home, store_root, audit=None, clock=time.time):
        self.home, self.store_root = Path(hermes_home), Path(store_root)
        self.audit, self.clock = audit or (lambda *a: None), clock
        self.tokens = {}       # device -> OrderedDict[token -> entry]
        self.downloads = {}    # device -> deque of timestamps
        self._dots = {}        # home -> (expiry, resolved dot entries)

    # ---- delivery policy (mirrors the agent's rules) -------------------------------------
    def _homes(self):
        homes = [self.home]
        try:
            homes += [p for p in (self.home / "profiles").iterdir() if p.is_dir()]
        except OSError:
            pass
        return homes

    def _profile_home(self, profile):
        if profile and profile != "default":
            named = self.home / "profiles" / profile
            if named.is_dir():
                return named
        return self.home

    def _policy(self, profile):
        """(strict, recency window in seconds or 0, extra allow roots) from the profile config and the bridge env."""
        cfg, flags, readable = gateway_settings(self._profile_home(profile))
        strict = not readable or _on(flags.get("strict", "false")) or _truthy("HERMES_MEDIA_DELIVERY_STRICT")
        trust = _on(flags.get("trust_recent_files", "true")) and os.environ.get(
            "HERMES_MEDIA_TRUST_RECENT_FILES", "1").strip().lower() not in ("0", "false", "no", "off", "")
        raw = cfg.get("trust_recent_files_seconds") or os.environ.get("HERMES_MEDIA_TRUST_RECENT_SECONDS", "")
        try:
            window = max(0.0, float(raw)) if str(raw).strip() else float(DEFAULT_RECENT_SECONDS)
        except ValueError:
            window = float(DEFAULT_RECENT_SECONDS)
        allow = cfg.get("media_delivery_allow_dirs", "")
        items = allow if isinstance(allow, list) else [allow]
        return strict, (window if trust else 0.0), [i for i in items if isinstance(i, str)]

    def _allowed_roots(self, profile=None):
        roots = []
        for home in self._homes():
            roots += [home / "cache" / d for d in CACHE_DIRS] + [home / d for d in LEGACY_CACHE_DIRS]
            roots.append(home / "cache" / "connector_downloads")
        extra = os.pathsep.join([os.environ.get("HERMES_MEDIA_ALLOW_DIRS", "")] + self._policy(profile)[2])
        for chunk in extra.split(os.pathsep):
            for item in chunk.split(","):
                root = Path(os.path.expanduser(item.strip())) if item.strip() else None
                if root is not None and root.is_absolute():
                    roots.append(root)
        return roots

    def _dot_targets(self, home, own, fresh=False):
        """Resolved dot entries directly under the home (listed at most once a minute), or None when the home cannot be listed."""
        now = time.monotonic()
        hit = None if fresh else self._dots.get(str(home))
        if hit is None or now >= hit[0]:
            try:
                names = [p for p in home.iterdir() if p.name.startswith(".")]
            except OSError:
                return None
            targets = [t for t in (_resolve(p) for p in names) if t is not None and t != own]
            hit = (now + CACHE_SECONDS, targets)
            if not fresh:
                self._dots[str(home)] = hit
        return hit[1]

    @staticmethod
    def _user_home():
        """The user's home as an absolute path, or None when it cannot be told (unset or relative)."""
        raw = os.path.expanduser("~")
        home = Path(raw)
        if not home.anchor or raw.startswith("~"):
            return None
        # a drive-less home (only the path part is set) belongs to the current drive
        return Path(os.path.abspath(home))

    def _lexical_dot(self, expanded, profile):
        """True for a path written under a dot entry of the home (no disk access), unless an agent folder holds it."""
        home = self._user_home()
        if home is None:
            return False
        path = Path(os.path.abspath(expanded))
        if path == home or not _within(path, home) or not path.relative_to(home).parts[0].startswith("."):
            return False
        return not any(_within(path, root) for root in self._allowed_roots(profile))

    def _denied(self, resolved, temp_ok=False, fresh=False):
        """True for system state, credential stores and per-user data folders.

        ``temp_ok`` lifts only the per-user data folders that contain the system temp folder."""
        denied = [Path(p) for p in DENIED_PREFIXES]
        home = self._user_home()
        own = _resolve(home) if home is not None else None
        if home is None:
            home = Path(os.path.expanduser("~"))
        elif own is None:
            # cannot tell where the home really is: nothing under it is offered, the other rules still apply
            if _within(resolved, home):
                return True
        else:
            if resolved != own and _within(resolved, own) and resolved.relative_to(own).parts[0].startswith("."):
                return True
            # resolved before comparing, so a link named like a dot folder cannot lead outside the home
            targets = self._dot_targets(home, own, fresh)
            if targets is None:
                # no listing, no way to tell the dot links apart: nothing under the home is offered
                if _within(resolved, home) or _within(resolved, own):
                    return True
                targets = []
            if any(resolved == t or _within(resolved, t) for t in targets):
                return True
            denied += [home / sub for sub in DENIED_HOME]
        for agent in self._homes():
            denied += [agent / name for name in DENIED_AGENT]
        for base in self._homes() + [Path(os.environ.get("HERMES_KANBAN_HOME") or self.home)]:
            try:
                denied += [board / name for board in (base / "kanban" / "boards").iterdir() for name in KANBAN_DB]
            except OSError:
                pass
        # system folder, and the per-user roaming/local data folders (home fallback when the env names are unset)
        env = {name.lower().replace("_", ""): root for name, root in os.environ.items() if root}
        denied += [Path(env[name]) for name in ("systemroot", "windir") if name in env]
        appdata = [Path(env[name]) if env.get(name) else home / sub
                   for name, sub in (("appdata", "AppData/Roaming"), ("localappdata", "AppData/Local"))
                   if env.get(name) or own is not None]
        temp = _resolve(Path(tempfile.gettempdir())) if temp_ok else None
        if temp is not None and not _within(resolved, temp):
            temp = None
        for item in denied + appdata:
            target = _resolve(item)
            if target is None or target == own or not (resolved == target or _within(resolved, target)):
                continue
            if item in appdata and temp is not None and _within(temp, target):
                continue
            return True
        return False

    def validate(self, raw, profile=None, fresh=False):
        """The resolved path for a tag, or None when it must not be delivered."""
        candidate = normalize(raw)
        if not candidate or "\x00" in candidate or unsafe_form(candidate):
            return None
        try:
            expanded = os.path.expanduser(candidate)
        except (OSError, RuntimeError, ValueError):
            return None
        if unsafe_form(expanded) or remote_drive(candidate) or remote_drive(expanded):
            return None
        expanded = Path(expanded)
        if not expanded.is_absolute() or self._lexical_dot(expanded, profile):
            return None
        resolved = _resolve(expanded, strict=True)
        if resolved is None:
            return None
        text = str(resolved)
        # a link can lead to a share or a mapped drive: nothing on the network is offered
        if text.startswith("\\\\") or unsafe_form(text) or remote_drive(text) or not resolved.is_file():
            return None
        if resolved.suffix.lower() not in EXTENSIONS:
            return None
        if _within(resolved, _resolve(self.store_root) or self.store_root) or SECRET_NAME.match(resolved.name):
            return None
        trusted = any(r is not None and _within(resolved, r)
                      for r in (_resolve(root) for root in self._allowed_roots(profile)))
        strict, window, _ = self._policy(profile)
        if not trusted and self._denied(resolved, fresh=fresh):
            # the system temp folder sits inside a denied data folder: only fresh files from it pass
            if self._denied(resolved, temp_ok=True, fresh=fresh) or not self._fresh(resolved, window):
                return None
        if strict and not trusted and not self._fresh(resolved, window):
            return None
        return resolved

    def _fresh(self, resolved, window):
        """True when the file changed within the recency window (never when the window is off)."""
        try:
            return window > 0 and self.clock() - resolved.stat().st_mtime <= window
        except OSError:
            return False

    # ---- tokens ---------------------------------------------------------------------------
    def mint(self, device, profile, raw):
        """A markdown link for a valid file, or None (the caller shows an unavailable note)."""
        resolved = self.validate(raw, profile)
        if resolved is None:
            return None
        try:
            with open(resolved, "rb") as stream:
                info = os.fstat(stream.fileno())
        except OSError:
            return None
        if info.st_size > MAX_FILE_BYTES:
            return None
        now = self.clock()
        bound = self.tokens.setdefault(device, OrderedDict())
        for token in [t for t, e in bound.items() if e["expires"] <= now]:
            del bound[token]
        key = (profile, str(resolved), info.st_size, info.st_mtime_ns, info.st_dev, info.st_ino)
        token = next((t for t, e in bound.items() if e["key"] == key), None)
        name = clean_name(resolved.name)
        if token is None:
            token = secrets.token_urlsafe(32)
            bound[token] = {"key": key, "profile": profile, "path": resolved, "size": info.st_size,
                            "mtime": info.st_mtime_ns, "identity": (info.st_dev, info.st_ino), "name": name, "expires": now + TOKEN_LIFETIME}
            while len(bound) > MAX_TOKENS_PER_DEVICE:
                bound.popitem(last=False)
            self.audit("file_offered", device, profile, name, info.st_size)
        return f"[{label(name, info.st_size)}]({LINK_SCHEME}{token})"

    def rewrite(self, device, profile, text):
        """Replace every MEDIA tag in complete text."""
        if "media:" not in text.lower():
            return text

        def replace(match):
            link = self.mint(device, profile, match.group("path"))
            if link is not None:
                return link
            name = clean_name(re.split(r"[\\/]", normalize(match.group("path")))[-1])
            return f"[file not available: {name}]"
        text = TAG.sub(replace, text)
        # A tag for a file type that is never delivered still must not show its path.
        return LOOSE_TAG.sub("[file not available]", text)

    @staticmethod
    def strip(text):
        """Tags in text the agent did not write become plain text: nothing is validated or minted."""
        if "media:" not in text.lower():
            return text
        return LOOSE_TAG.sub("[file]", TAG.sub("[file]", text))

    def rewrite_value(self, device, profile, value):
        """Mint links in every string of an assistant-written value."""
        return map_strings(value, lambda text: self.rewrite(device, profile, text))

    def strip_value(self, value):
        return map_strings(value, self.strip)

    def assistant_value(self, device, profile, value, mint_keys):
        """A dict whose ``mint_keys`` hold assistant text; every other string is stripped."""
        if not isinstance(value, dict):
            return self.strip_value(value)
        return {k: (self.rewrite_value(device, profile, v) if k in mint_keys else self.strip_value(v))
                for k, v in value.items()}

    def history_value(self, device, profile, value):
        """Session history: only an assistant message's ``content`` can mint links."""
        if isinstance(value, dict):
            return {k: (self.history_value(device, profile, v) if k == "messages" and isinstance(v, list)
                        else self.strip_value(v)) for k, v in value.items()}
        if isinstance(value, list):
            return [self.assistant_value(device, profile, m, ("content",))
                    if isinstance(m, dict) and m.get("role") == "assistant" else self.strip_value(m)
                    for m in value]
        return self.strip_value(value)

    def filter(self, device, profile, template):
        """The response rewriter for a proxied route, or None when the route cannot carry a tag."""
        if template == "/v1/runs/{id}/events":
            return EventRewriter(self, device, profile)
        if template == "/v1/runs/{id}":
            return JsonRewriter(self, device, profile, lambda v: self.assistant_value(device, profile, v, ("output",)))
        if template == "/api/sessions/{id}/messages":
            return JsonRewriter(self, device, profile, lambda v: self.history_value(device, profile, v))
        return None

    # ---- download -------------------------------------------------------------------------
    def _rate(self, device):
        now = self.clock()
        times = self.downloads.setdefault(device, deque())
        while times and times[0] <= now - 60:
            times.popleft()
        if len(times) >= DOWNLOADS_PER_MINUTE:
            return False
        times.append(now)
        return True

    def lookup(self, device, token):
        entry = self.tokens.get(device, {}).get(token)
        if entry is None:
            raise HTTPError(404, "file_not_found")
        if entry["expires"] <= self.clock():
            del self.tokens[device][token]
            raise HTTPError(410, "file_expired")
        return entry

    def open(self, device, token):
        """(entry, open binary file) after every check, or an HTTPError."""
        if not TOKEN.fullmatch(token):
            raise HTTPError(404, "file_not_found")
        if not self._rate(device):
            raise HTTPError(429, "file_rate_limited")
        entry = self.lookup(device, token)
        path = entry["path"]
        if self.validate(str(path), entry["profile"], fresh=True) != path:
            self.audit("file_denied", device, entry["profile"], entry["name"], entry["size"])
            raise HTTPError(410, "file_unavailable")
        try:
            stream = open(path, "rb")
        except OSError:
            raise HTTPError(410, "file_unavailable") from None
        try:
            info = os.fstat(stream.fileno())
            if (not stream.readable() or info.st_size != entry["size"] or info.st_mtime_ns != entry["mtime"]
                    or (info.st_dev, info.st_ino) != entry["identity"]):
                raise HTTPError(410, "file_changed")
        except BaseException:
            stream.close()
            raise
        return entry, stream

    async def download(self, link, device, token):
        """Stream the file; returns (status, entry-or-None). Raises HTTPError before any byte is sent."""
        entry, stream = self.open(device, token)
        ascii_name = entry["name"].encode("ascii", "replace").decode().replace("?", "_")
        disposition = (f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''"
                       + quote(entry["name"], safe=""))
        suffix = entry["path"].suffix.lower()
        active = suffix in ACTIVE_TYPES
        kind = "application/octet-stream" if active else MIME.get(suffix, "application/octet-stream")
        head = (f"HTTP/1.1 200 OK\r\nContent-Type: {kind}\r\n"
                f"Content-Length: {entry['size']}\r\nContent-Disposition: {disposition}\r\n"
                + ("Content-Security-Policy: sandbox\r\n" if active else "")
                + "X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n\r\n")
        sent = 0
        try:
            await link.write(head.encode("latin-1"))
            while sent < entry["size"]:
                data = await asyncio.to_thread(stream.read, min(CHUNK, entry["size"] - sent))
                if not data:
                    raise EOFError  # the file shrank mid-transfer: the length promise is broken
                await link.write(data)
                sent += len(data)
        except (OSError, EOFError):
            raise StreamAborted from None
        finally:
            stream.close()
        self.audit("file_sent", device, entry["profile"], entry["name"], entry["size"])
        return entry


class JsonRewriter:
    """Buffered: the whole JSON body is rewritten before the first byte goes out."""
    buffered = True

    def __init__(self, files, device, profile, transform=None):
        self.files, self.device, self.profile = files, device, profile
        self.transform = transform or (files.strip_value if files is not None else None)
        self.data = bytearray()

    def feed(self, data):
        self.data.extend(data)
        if len(self.data) > BODY_LIMIT:
            raise HTTPError(413, "history too large")
        return []

    def finish(self):
        raw = bytes(self.data)
        if b"media:" not in raw.lower():
            return [raw]
        try:
            value = json.loads(raw)
        except ValueError:
            return [raw]
        return [json.dumps(self.transform(value)).encode()]


class DeltaFilter:
    """Holds back a streamed tag until its line is complete, so no path fragment is sent."""

    def __init__(self, files, device, profile):
        self.files, self.device, self.profile = files, device, profile
        self.buffer, self.skip = "", False

    def feed(self, text):
        if self.skip:           # the rest of a tag that was cut at the hold limit
            end = text.find("\n")
            if end < 0:
                return ""
            text, self.skip = text[end:], False
        self.buffer += text
        out = ""
        while self.buffer:
            start = _TAG_START.search(self.buffer)
            if start is None:
                tail = _TAG_PREFIX_AT_END.search(self.buffer)
                out += self.buffer[:tail.start()]
                self.buffer = self.buffer[tail.start():]
                break
            out += self.buffer[:start.start()]
            self.buffer = self.buffer[start.start():]
            end = self.buffer.find("\n")
            if end < 0:
                if len(self.buffer) > HOLD_LIMIT:
                    out += self.files.rewrite(self.device, self.profile, self.buffer)
                    self.skip = LOOSE_TAG.search(TAG.sub("", self.buffer)) is not None
                    self.buffer = ""
                break
            out += self.files.rewrite(self.device, self.profile, self.buffer[:end])
            self.buffer = self.buffer[end:]
        return out

    def flush(self):
        out = self.files.rewrite(self.device, self.profile, self.buffer)
        self.buffer = ""
        return out


class EventRewriter:
    """Rewrites a server-sent event stream frame by frame; other frames pass byte for byte."""
    buffered = False

    def __init__(self, files, device, profile):
        self.files, self.device, self.profile = files, device, profile
        self.data = bytearray()
        self.delta = DeltaFilter(files, device, profile)

    def feed(self, data):
        self.data.extend(data)
        frames = []
        while True:
            end = self.data.find(b"\n\n")
            if end < 0:
                break
            frames.append(self.frame(bytes(self.data[:end + 2])))
            del self.data[:end + 2]
        if len(self.data) > BODY_LIMIT:
            raise HTTPError(502, "Upstream response too large")
        return [part for group in frames for part in group]

    def finish(self):
        rest = bytes(self.data)
        self.data.clear()
        out = [rest] if rest else []
        flushed = self.delta.flush()
        if flushed:
            out.insert(0, self.synthetic(flushed))
        return out

    def synthetic(self, text):
        value = {"event": "message.delta", "delta": text}
        return b"data: " + json.dumps(value).encode() + b"\n\n"

    def frame(self, raw):
        lowered = raw.lower()
        if (b"media:" not in lowered and b"message.delta" not in lowered and not self.delta.buffer):
            return [raw]
        lines = raw.decode("utf-8", errors="replace").split("\n")[:-2]
        data = [line for line in lines if line.startswith("data:")]
        if len(data) != 1:
            return [raw]
        try:
            value = json.loads(data[0][5:])
        except ValueError:
            return [raw]
        if not isinstance(value, dict):
            return [raw]
        prefix = []
        kind = str(value.get("event", ""))
        if kind == "message.delta" and isinstance(value.get("delta"), str):
            value["delta"] = self.delta.feed(value["delta"])
        else:
            flushed = self.delta.flush() if kind.startswith("run.") else ""
            if flushed:
                prefix.append(self.synthetic(flushed))
            minting = ("text",) if kind == "message.interim" else ("output",) if kind.startswith("run.") else ()
            value = self.files.assistant_value(self.device, self.profile, value, minting)
        lines[lines.index(data[0])] = "data: " + json.dumps(value)
        return prefix + [("\n".join(lines) + "\n\n").encode("utf-8")]
