"""Recent Hermes log lines for the phone. Every line is redacted before it leaves the computer."""

import asyncio
import functools
import json
from pathlib import Path
import re
import string

from .channels import Refused
from .http import HTTPError

# Phone-visible names, mapped explicitly to the upstream log keys.
FILES = {"errors": "errors", "agent": "agent", "gateway": "gateway"}
LEVELS = ("ERROR", "WARNING", "INFO", "ALL")
DEFAULT_FILE, DEFAULT_LINES, MAX_LINES = "errors", 100, 200
RAW_CAP, LINE_CAP, BODY_CAP, MAX_PIECES = 4000, 1000, 256 * 1024, 64
HIDDEN = "[hidden]"

# Every rule below is linear on long runs: each one starts only after a boundary (lookbehind), so a
# run of letters, dots or digits is scanned once, never once per character.
CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f\U0000061c\U0000200e\U0000200f\U0000202a-\U0000202e\U00002066-\U00002069]")
# "key" counts only at a word start: the name start, after "_", "-" or ".", a CamelCase capital (apiKey), or glued to a common
# word in front of it (apikey, userkey). "monkey" and "turkey" are not keys. The password, token, secret and auth words match
# anywhere in a name (PGPASSWORD, dbpassword, githubtoken, usersecret, oauth), because no ordinary word ends in them.
GLUED = ("api", "access", "secret", "private", "auth", "bot", "refresh", "client", "master", "session", "pass", "ssh",
         "id", "csrf", "xsrf", "jwt", "app", "webhook", "fcm", "push", "device", "priv", "encryption", "signing", "consumer", "shared", "user", "license", "hmac")
BOUND = r"(?:(?<![A-Za-z0-9])|(?<=[_.-])|(?-i:(?<=[a-z0-9])(?=[A-Z]))|" + "|".join("(?<=%s)" % g for g in GLUED) + ")"
PASSWORDS = r"(?:password|passwd|pwd|passphrase|token|secret|auth)"
NAME_END = r"(?:" + PASSWORDS + r"|(?=key)" + BOUND + r"key)(?:[_-](?:id|base))*"
# A value is a masked placeholder in brackets, a tuple, a call, a quoted string (with a b/r/u/f prefix), or one bare word.
VALUE = (r"""(?:\[[^\s\[\]()]*\]|\[[^\[\]]*\]|\{(?:[^{}]|\{[^{}]*\})*\}|\([^()]*\)|[A-Za-z_][\w.]*\([^()]*\)"""
         r"""|(?:[rRbBuUfF]{1,2})?"[^"]*"|(?:[rRbBuUfF]{1,2})?'[^']*'|["']?[^\s,;&"'}\])]+)""")
# The name may follow a literal backslash-n/r/t (a header block inside a quoted string) and may be a tuple key: ('Cookie', 'x').
HEADER = re.compile(r"""(?:(?<=\\[nrt])|(?<![\w.-]))((?:proxy-)?authorization|x-api-key|set-cookie|cookie)"""
                    r"""(["']?\s*[:=]\s*|["']\s*,\s*)"""
                    r"""(?:[bB]?"[^"]*"|[bB]?'[^']*'|\[[^\s\[\]]*\][^)}\]]*|[^)}\]]+)""", re.I)
# "Authentication: Basic ..." carries a credential like Authorization; "Authentication: failed" is prose and stays. Only a run of
# 6+ characters that has a capital, a digit or one of + / = after the scheme word is hidden, so "token expired" stays readable.
AUTHENTICATION = re.compile(r"""(?<![\w.-])(authentication["']?\s*[:=]\s*["']?(?:basic|digest|negotiate|ntlm|bearer|token)\s+)(?=[^\s)}\]"']*(?-i:[A-Z0-9+/=]))[^\s)}\]"']{6,}""", re.I)
# "name = value" accepts dotted names; "name: value" does too, except a logger such as "gateway.auth:" keeps its message.
EQUALS = re.compile(r"(?<![\w.-])([A-Za-z0-9_.-]*" + NAME_END + r"[\"']?\s*=\s*)" + VALUE, re.I)
COLON = re.compile(r"(?<![\w.-])([A-Za-z0-9_.-]*" + NAME_END + r")([\"']?\s*:\s*)" + VALUE, re.I)
FLAG = re.compile(r"(?<![\w.-])(--[A-Za-z0-9_-]*(?:" + PASSWORDS + r"|(?=key)" + BOUND + r"key)\s+)(?!-)" + VALUE, re.I)
URL = re.compile(r"(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://[^\s\"'<>]+", re.I)
SCHEME_USER = re.compile(r"^([a-z][a-z0-9+.-]*://)[^/?#]*@", re.I)
SCHEMELESS = re.compile(r"(?<![\w:/.\\-])(//)[^/?#\s\"'<>]*@")
SESSION_PARAM = re.compile(r"(;jsessionid=)[^;?#&\s]*", re.I)
QUERY_VALUE = re.compile(r"([?&][^=&#?\s]*=)[^&#\s]*")
WEBHOOK = re.compile(r"(?i)(//(?:hooks\.slack\.com|discord(?:app)?\.com)/)(?:services|api/webhooks)/[^?#\s]*")


def colon(match):
    name = match.group(1)
    if "." in name and name.lower().endswith("auth"):
        return match.group(0)
    return name + match.group(2) + HIDDEN


GUARD = {}  # a rule runs only when the lower-cased line contains one of its needles (a cheap check before a regex scan)


def rule(regex, replacement, *needles):
    pattern = regex if isinstance(regex, re.Pattern) else re.compile(regex)
    GUARD[pattern] = needles
    return pattern, replacement


PATTERNS = [
    rule(r"(?<!\d)\d{6,}:[A-Za-z0-9_-]{30,}", HIDDEN, ":"),
    rule(HEADER, r"\1\2" + HIDDEN, "authorization", "x-api-key", "cookie"),
    rule(AUTHENTICATION, r"\1" + HIDDEN, "authentication"),
    rule(r"(?i)(\bbearer\s+)(?!\[hidden\])[A-Za-z0-9._~+/=-]{6,}", r"\1" + HIDDEN, "bearer"),
    rule(r"\bsk-[A-Za-z0-9_-]{10,}", HIDDEN, "sk-"),
    rule(r"\bxox[A-Za-z]-[A-Za-z0-9-]{8,}", HIDDEN, "xox"),
    rule(r"\bgh[pousr]_[A-Za-z0-9]{16,}", HIDDEN, "ghp_", "gho_", "ghu_", "ghs_", "ghr_"),
    rule(r"\bgithub_pat_[A-Za-z0-9_]{16,}", HIDDEN, "github_pat_"),
    rule(r"\bAIza[A-Za-z0-9_-]{20,}", HIDDEN, "aiza"),
    rule(r"\bhf_[A-Za-z0-9]{16,}", HIDDEN, "hf_"),
    rule(r"\bglpat-[A-Za-z0-9_-]{16,}", HIDDEN, "glpat-"),
    rule(r"(?<![A-Za-z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Za-z0-9])", HIDDEN, "akia", "asia"),
    rule(r"\b[sr]k_(?:live|test)_[A-Za-z0-9]{8,}", HIDDEN, "k_live_", "k_test_"),
    rule(r"\bnpm_[A-Za-z0-9]{30,}", HIDDEN, "npm_"),
    rule(r"\bpypi-[A-Za-z0-9_-]{16,}", HIDDEN, "pypi-"),
    rule(r"(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}(?:\.[A-Za-z0-9_-]*)?", HIDDEN, "eyj"),
    rule(r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])", HIDDEN, "."),
    rule(r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{24}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,}(?![A-Za-z0-9_-])", HIDDEN, "."),
    rule(FLAG, r"\1" + HIDDEN, "--"),
    rule(EQUALS, r"\1" + HIDDEN, "="),
    rule(COLON, colon, ":"),
    rule(r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", HIDDEN, "@"),
]
HEX = re.compile(r"(?<![A-Za-z0-9])[0-9A-Fa-f]{32,}(?![A-Za-z0-9])")
BASE64 = re.compile(r"(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{32,}={0,2}(?![A-Za-z0-9+/_=-])")
PHONE = re.compile(r"(?<![\w.])(?<![A-Za-z0-9]-)(?:\+\d[\d ()-]{7,}\d|0\d{2,3}[ -]\d{3,4}[ -]\d{3,4}|\+?\d{9,})(?!\w|\.\d)")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)
# A readable piece: letters, at most one digit group inside, then up to four trailing digits ("v1beta1", "python3", "2026").
PIECE = re.compile(r"[A-Za-z]*(?:[0-9]{1,4}[A-Za-z]+)?[0-9]{0,4}|[0-9]{1,8}")

# Another account's home folder: drive, WSL, Git Bash, UNC, file: URL (percent-encoded) and 8.3 short names. The forms that start
# with a slash may not follow another slash, so a long run of slashes is scanned once, not once per slash.
_WORD = r"""(?:[^\\/:*?"<>|\s%]|%[0-9A-Fa-f]{2})+"""
USERS = re.compile(
    r"(?<![\w.~-])(?:[A-Za-z](?::|%3[Aa])[\\/]+|(?<![\\/])(?:[\\/]+(?:mnt|cygdrive)[\\/]+[A-Za-z][\\/]+|[\\/]+[A-Za-z][\\/]+"
    r"|[\\/]{2,4}[?.][\\/]+UNC[\\/]+[^\\/:\s]+[\\/]+(?:[A-Za-z]\$[\\/]+)?"
    r"|(?:\\{1,4}|/{2})[^\\/:\s]+[\\/]+(?:[A-Za-z]\$[\\/]+)?|[\\/]+))"
    r"Users[\\/]+(?:" + _WORD + r"(?: " + _WORD + r")*(?=[\\/])|" + _WORD + ")", re.I)
# /home/<name>, also inside a WSL share: \\wsl$\<distro>\home\<name>
HOMES = re.compile(r"(?<![\w.~$\\/-])(?:[\\/]{1,4}wsl(?:\$|\.localhost)[\\/]+[^\\/:\s]+)?[\\/]+home[\\/]+[^\\/:*?\"<>|\s]+", re.I)


@functools.lru_cache(maxsize=8)
def home_pattern(home):
    parts = [p for p in re.split(r"[\\/]+", str(home)) if p]
    if not parts:
        return None
    lead = r"[\\/]+" if str(home)[:1] in "\\/" else ""
    before = r"(?<![\w.~\\/-])" if lead else r"(?<![\w.~-])"
    return re.compile(before + lead + r"[\\/]+".join(re.escape(p) for p in parts) + r"(?![\w-]|\.\w)", re.I)


@functools.lru_cache(maxsize=8)
def account_pattern(home):
    """The account name after a host or domain: HOST\\name."""
    parts = [p for p in re.split(r"[\\/]+", str(home)) if p]
    if len(parts) < 2:
        return None
    return re.compile(r"(?<![\w.~$\\/-])[\w.-]+\\{1,2}" + re.escape(parts[-1]) + r"(?![\w-]|\.\w)", re.I)


def calm(piece):
    letters = re.sub(r"[^A-Za-z]", "", piece)
    upper = sum(c.isupper() for c in letters)
    return upper == len(letters) or upper * 3 <= len(letters)


def secret_run(match):
    """A long base64-like run is a secret when it has a digit and a letter, unless it reads as a path, name or UUID."""
    text = match.group(0)
    if not (re.search(r"\d", text) and re.search(r"[A-Za-z]", text)):
        return text
    pieces = [p for p in re.split(r"[/_+=-]+", UUID.sub("-", text)) if p]
    if len(pieces) <= MAX_PIECES and all(PIECE.fullmatch(p) and calm(p) for p in pieces):  # a run of hundreds of pieces is not a name
        return text
    return HIDDEN


def url(match):
    text = SCHEME_USER.sub(lambda m: m.group(1) + HIDDEN + "@", match.group(0))  # up to the LAST "@" before the path
    text = SESSION_PARAM.sub(lambda m: m.group(1) + HIDDEN, text)
    text = WEBHOOK.sub(lambda m: m.group(1) + HIDDEN, text)
    head, mark, rest = text.partition("?")
    if mark:
        rest = QUERY_VALUE.sub(lambda m: m.group(1) + HIDDEN, mark + rest)
        text = head + rest
    head, mark, rest = text.partition("#")
    return head + ("#" + HIDDEN if mark and rest else mark + rest)


RUN = frozenset(string.ascii_letters + string.digits + "+/=_.~%-")


def clip(line):
    """At most RAW_CAP characters, control and bidirectional characters turned into spaces. A word cut in half is dropped; with
    no space to cut at, the whole run of secret-like characters the cut lands in is dropped (unless it is the whole line)."""
    line = CONTROL.sub(" ", line[:RAW_CAP + 1])
    if len(line) > RAW_CAP:
        cut = line[:RAW_CAP]
        if line[RAW_CAP] != " " and cut[-1] != " ":
            if " " in cut:
                cut = cut.rpartition(" ")[0]
            elif line[RAW_CAP] in RUN and cut[-1] in RUN:
                end = len(cut)
                while end and cut[end - 1] in RUN:
                    end -= 1
                cut = cut[:end] or cut
        line = cut
    return line


def redact(line, home=None):
    """One log line with secrets and personal paths replaced; timestamps, levels and names stay."""
    line = clip(line)
    if home is not None:
        if pattern := home_pattern(home):
            line = pattern.sub("~", line)
        if pattern := account_pattern(home):
            line = pattern.sub("~", line)
    low = line.lower()
    if "users" in low:
        line = USERS.sub("~", line)
    if "home" in low:
        line = HOMES.sub("~", line)
    if "//" in line:
        line = SCHEMELESS.sub(r"\1" + HIDDEN + "@", line)
    if "://" in line:
        line = URL.sub(url, line)
    low = None
    for pattern, replacement in PATTERNS:
        if low is None:
            low = line.lower()
        if not any(needle in low for needle in GUARD[pattern]):
            continue
        line, count = pattern.subn(replacement, line)
        if count:
            low = None
    line = BASE64.sub(secret_run, HEX.sub(HIDDEN, line))
    line = PHONE.sub(HIDDEN, line)
    return line[:LINE_CAP]


def parse(raw):
    """Strict query: only file, lines and level, each at most once, plain ASCII values."""
    query = {}
    for pair in raw.split("&") if raw else ():
        match = re.fullmatch(r"(file|lines|level)=([A-Za-z0-9]{1,16})", pair)
        if match is None or match[1] in query:
            raise HTTPError(400, "desktop_invalid")
        query[match[1]] = match[2]
    if query.get("file", DEFAULT_FILE) not in FILES or query.get("level", "ALL") not in LEVELS:
        raise HTTPError(400, "desktop_invalid")
    if "lines" in query and (not re.fullmatch(r"[1-9][0-9]{0,2}", query["lines"]) or int(query["lines"]) > MAX_LINES):
        raise HTTPError(400, "desktop_invalid")
    return {"file": query.get("file", DEFAULT_FILE), "lines": int(query.get("lines", DEFAULT_LINES)),
            "level": query.get("level", "ALL")}


def encode(file, lines):
    return json.dumps({"file": file, "lines": lines}, separators=(",", ":"), allow_nan=False).encode()


def shape(file, raw_lines, home):
    """The slow part (redaction and the size cap); runs in a worker thread so the event loop stays free."""
    lines = [redact(line, home) for line in raw_lines]
    while lines and len(encode(file, lines)) > BODY_CAP:
        lines.pop(0)  # drop the oldest first
    return lines


async def handle(query, request, home=None):
    """Returns (status, body)."""
    home = Path.home() if home is None else home
    try:
        raw = await request("GET", "/api/logs", soft=(404,), file=FILES[query["file"]],
                            lines=query["lines"], level=query["level"])
    except Refused as refused:
        return refused.status, {"error": "logs_not_found"}
    if not isinstance(raw, dict) or not isinstance(raw.get("lines"), list) \
            or any(not isinstance(line, str) for line in raw["lines"]):
        raise ValueError
    lines = await asyncio.to_thread(shape, query["file"], raw["lines"][-query["lines"]:], home)
    return 200, {"file": query["file"], "lines": lines}
