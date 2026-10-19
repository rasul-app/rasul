"""Log route: query rules, redaction classes with near misses, caps, and upstream failures."""

import json
from urllib.parse import parse_qs, urlsplit

import pytest

from rasul_bridge.logs import redact
from test_bridge import asynchronous
from test_desktop import FakeDesktop, desktop

HOME = r"C:\Users\bob"
PLANTED = "FAKEPLANTEDSECRETVALUE0123456789abcdef"


class FakeLogs(FakeDesktop):
    def __init__(self, directory):
        super().__init__(directory)
        self.lines = ["2026-10-18 22:40:11,123 ERROR gateway.run: adapter stopped"]
        self.token = "Ab1-" + self.token  # mixed case and a digit, so the test never depends on chance

    def result(self, method, path, value):
        if path == "/api/logs":
            return {"file": "x", "lines": self.lines}
        return super().result(method, path, value)

    def sent(self):
        return [parse_qs(urlsplit(r[1]).query) for r in self.requests if urlsplit(r[1]).path == "/api/logs"]


def logs(tmp_path, monkeypatch):
    home = tmp_path / "home" / "bob"
    home.mkdir(parents=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    return desktop(tmp_path, monkeypatch, FakeLogs), home


SECRET_CASES = [
    "Authorization: Bearer abc.def-123456",
    "sent header Bearer tok_ABC123xyz now",
    "key sk-abcdefghijklmnopqr end",
    "key xoxb-1234567890-abcdefghij end",
    "key ghp_abcdefghijklmnopqrstuvwx end",
    "key github_pat_11ABCDEFG0abcdefghijkl end",
    "key AIzaSyD-abcdefghijklmnopqrstuvwxyz0 end",
    "password=hunter2xyz",
    "api_key: 'my value 1'",
    '{"token": "abc12345"}',
    "TELEGRAM_BOT_TOKEN=abcdef",
    "secret: s3cr3t",
    "passwd=zz9",
    "apikey=AK12",
    "auth: zzzzz",
    "jwt " + "eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0In0" + ".sig_nature-xx end",
    "jwt aaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccc end",
    "hex 0123456789abcdef0123456789abcdef end",
    "b64 QWxhZGRpbjpvcGVuIHNlc2FtZTEyMzQ1Njc4 end",
    "url https://user:pw@example.com/x end",
    "url https://example.com/p?a=secretval&b=other end",
    "url https://example.com/p#access-fragment end",
    "mail bob@example.com end",
    "bot 123456789:AAEabcdefghijklmnopqrstuvwxyz012345 end",
    "phone 963944123456 end",
    "phone +963 944 123 456 end",
]
SECRET_PARTS = ["abc.def-123456", "tok_ABC123xyz", "abcdefghijklmnopqr", "1234567890-abcdefghij",
                "abcdefghijklmnopqrstuvwx", "11ABCDEFG0abcdefghijkl", "SyD-abcdefghijklmnopqrstuvwxyz0",
                "hunter2xyz", "my value 1", "abc12345", "abcdef", "s3cr3t", "zz9", "AK12", "zzzzz",
                "sig_nature-xx", "cccccccccccccccccccc", "0123456789abcdef0123456789abcdef",
                "QWxhZGRpbjpvcGVuIHNlc2FtZTEyMzQ1Njc4", "user:pw", "secretval", "access-fragment",
                "bob@example.com", "AAEabcdefghijklmnopqrstuvwxyz012345", "963944123456", "944 123 456"]
KEPT_PARTS = [("url https://example.com/p?a=secretval&b=other end", "https://example.com/p?a="),
              ("url https://user:pw@example.com/x end", "@example.com/x")]
NEAR_MISSES = [
    "2026-10-18 22:40:11,123 WARNING gateway.platforms.telegram: polling restarted",
    "2026-10-18 22:40:11,123 ERROR gateway.auth: login failed for the account",
    "hermes_cli.web_routers.status ready",
    "task-list sk-learn tokens=5 max_tokens: 100 keyboard: us the bearer of news",
    "hex 0123456789abcdef0123456789abcde end",
    "ZeroChunkAbsenceReclaimEnabledFlagManagerXY and zero_chunk_absence_reclaim_enabled_flag_manager",
    "uuid 123e4567-e89b-12d3-a456-426614174000 end",
    "pid 12345678 took 1.123456789 s",
    "see https://example.com/docs?page and https://example.com/a/b end",
    "route /v1/profiles/default/desktop/channels and /api/messaging/telegram/onboarding/start",
    r"other /srv/bobby/x and C:\Programs\bobby\AppData",
    "ghp_short sk-abc xoxb-1 github_pat_1 AIza123",
]


@pytest.mark.parametrize("line", SECRET_CASES)
def test_logs_redaction_hides_each_class(line):
    result = redact(line, HOME)
    assert "[hidden]" in result
    assert not any(part in result for part in SECRET_PARTS if part in line)


@pytest.mark.parametrize("line,kept", KEPT_PARTS)
def test_logs_redaction_keeps_the_surrounding_text(line, kept):
    assert kept in redact(line, HOME)


@pytest.mark.parametrize("line", NEAR_MISSES)
def test_logs_redaction_leaves_near_misses(line):
    assert redact(line, HOME) == line


@pytest.mark.parametrize("line,expected", [
    (r"open C:\Users\bob\AppData\x.log", r"open ~\AppData\x.log"),
    ("open c:/users/BOB/y", "open ~/y"),
    (r"open C:\\Users\\bob\\z", r"open ~\\z"),
    (r"open C:\Users\bob", "open ~"),
])
def test_logs_home_path_becomes_tilde(line, expected):
    assert redact(line, HOME) == expected


def test_logs_home_path_needs_a_boundary_before_it():
    assert redact("open /home/bob/x and /mnt/home/bob/x", "/home/bob") == "open ~/x and /mnt/home/bob/x"


def test_logs_timestamp_level_and_component_stay():
    line = "2026-10-18 22:40:11,123 ERROR [abc_123] gateway.run: token=abcdef failed"
    assert redact(line, HOME) == "2026-10-18 22:40:11,123 ERROR [abc_123] gateway.run: token=[hidden] failed"


def test_logs_line_cap_applies_after_redaction():
    assert len(redact("lorem " * 1000, HOME)) == 1000
    assert redact("x" * 995 + " sk-abcdefghijklmnopqrstuv", HOME) == "x" * 995 + " [hid"


@asynchronous
async def test_logs_defaults_and_projection(tmp_path, monkeypatch):
    context, home = logs(tmp_path, monkeypatch)
    async with context as (bridge, fake, call):
        fake.lines = [f"line {i}" for i in range(150)]
        status, value = await call("GET", "/logs")
        assert status == 200 and set(value) == {"file", "lines"}
        assert value["file"] == "errors" and value["lines"] == [f"line {i}" for i in range(50, 150)]
        assert fake.sent() == [{"file": ["errors"], "lines": ["100"], "level": ["ALL"], "profile": ["default"]}]


@pytest.mark.parametrize("name", ["errors", "agent", "gateway"])
@pytest.mark.parametrize("level", ["ERROR", "WARNING", "INFO", "ALL"])
@asynchronous
async def test_logs_file_and_level_map_to_upstream(tmp_path, monkeypatch, name, level):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.lines = ["a", "b", "c"]
        status, value = await call("GET", f"/logs?file={name}&lines=2&level={level}")
        assert (status, value) == (200, {"file": name, "lines": ["b", "c"]})
        assert fake.sent()[0] == {"file": [name], "lines": ["2"], "level": [level], "profile": ["default"]}


@pytest.mark.parametrize("query", [
    "file=gui", "file=desktop", "file=update", "file=Errors", "file=", "file=errors&file=agent",
    "lines=0", "lines=201", "lines=01", "lines=+5", "lines=%205", "lines=1e1", "lines=-1", "lines=5.0", "lines=9999",
    "level=error", "level=DEBUG", "level=CRITICAL", "level=", "search=password", "profile=other", "component=x",
    "lines=5&&level=ALL", "file=errors&", "lines", "&", "lines=5&lines=6", "lines=5;level=ALL",
])
@asynchronous
async def test_logs_query_is_strict(tmp_path, monkeypatch, query):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        assert await call("GET", "/logs?" + query) == (400, {"error": "desktop_invalid"})
        assert not fake.requests


@pytest.mark.parametrize("method,body", [("POST", None), ("PUT", {"a": 1}), ("DELETE", None)])
@asynchronous
async def test_logs_is_read_only(tmp_path, monkeypatch, method, body):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        assert (await call(method, "/logs", body))[0] == 404
        assert not fake.requests


@asynchronous
async def test_logs_get_refuses_a_body(tmp_path, monkeypatch):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        assert await call("GET", "/logs", {"file": "agent"}) == (400, {"error": "desktop_invalid"})
        assert not fake.requests


@asynchronous
async def test_logs_planted_secret_never_leaves(tmp_path, monkeypatch):
    context, home = logs(tmp_path, monkeypatch)
    async with context as (bridge, fake, call):
        fake.lines = [f"2026-10-18 22:40:11,123 ERROR gateway.run: {fake.token} password={PLANTED}",
                      f"opened {home}\\AppData\\hermes\\logs and {home}/x with key {PLANTED}",
                      f"Authorization: Bearer {PLANTED} and user bob@example.com"]
        status, value = await call("GET", "/logs?lines=3")
        assert status == 200 and len(value["lines"]) == 3
        encoded = json.dumps(value)
        for forbidden in (PLANTED, fake.token, str(home), "bob@example.com"):
            assert forbidden not in encoded
        assert value["lines"][0].startswith("2026-10-18 22:40:11,123 ERROR gateway.run: [hidden]")
        assert fake.token not in bridge.audit.path.read_text()


@asynchronous
async def test_logs_each_line_is_capped(tmp_path, monkeypatch):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.lines = ["lorem " * 1000, "short"]
        status, value = await call("GET", "/logs?lines=2")
        assert status == 200 and [len(line) for line in value["lines"]] == [1000, 5]


@asynchronous
async def test_logs_body_cap_drops_oldest_first(tmp_path, monkeypatch):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.lines = [f"n{i:03d} " + "\u00e9" * 400 for i in range(200)]
        status, value = await call("GET", "/logs?lines=200")
        assert status == 200
        assert len(json.dumps(value, separators=(",", ":"))) <= 256 * 1024
        assert 0 < len(value["lines"]) < 200
        assert value["lines"][-1].startswith("n199 ")
        first = int(value["lines"][0][1:4])
        assert [int(line[1:4]) for line in value["lines"]] == list(range(first, 200))


@asynchronous
async def test_logs_empty_file(tmp_path, monkeypatch):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.lines = []
        assert await call("GET", "/logs?file=agent") == (200, {"file": "agent", "lines": []})


@asynchronous
async def test_logs_rate_limit(tmp_path, monkeypatch):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        for _ in range(10):
            assert (await call("GET", "/logs"))[0] == 200
        before = len(fake.requests)
        assert await call("GET", "/logs") == (429, {"error": "desktop_rate_limited"})
        assert len(fake.requests) == before
        assert (await call("GET", "/skills"))[0] == 200


@asynchronous
async def test_logs_upstream_404_stays_404(tmp_path, monkeypatch):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.status, fake.payload = 404, {"detail": fake.token}
        status, value = await call("GET", "/logs")
        assert (status, value) == (404, {"error": "logs_not_found"})
        assert fake.token not in json.dumps(value)


@pytest.mark.parametrize("status", [400, 401, 403, 500, 502, 503])
@asynchronous
async def test_logs_upstream_failure_is_502(tmp_path, monkeypatch, status):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.status, fake.payload = status, {"detail": fake.token}
        assert await call("GET", "/logs") == (502, {"error": "desktop_error"})


@pytest.mark.parametrize("payload", [{"file": "x"}, {"lines": "text"}, {"lines": [1]}, {"lines": [None]},
                                     [], "text", {"lines": [{"a": 1}]}])
@asynchronous
async def test_logs_malformed_upstream_is_502(tmp_path, monkeypatch, payload):
    context, _ = logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.payload = payload
        assert await call("GET", "/logs") == (502, {"error": "desktop_error"})
