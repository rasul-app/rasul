"""Log redaction: header values, bounded time, secret runs, home paths, account names, URLs and the small cases."""

import asyncio
import threading
import time

import pytest

from rasul_bridge import logs
from rasul_bridge.logs import clip, redact
from test_bridge import asynchronous
from test_logs import FakeLogs, logs as make_logs  # noqa: F401  (make_logs builds the fake desktop)

HOME = r"C:\Users\bob"


def piece(*parts):
    """Fake secrets are built from pieces so no scanner sees a whole one in this file."""
    return "".join(parts)


BASIC = piece("dXNlcj", "pwYXNz")
BASIC_LONG = piece("dXNlcjpwYXNzd2", "9yZA==")

HEADER_CASES = [
    ("headers={'Authorization': 'Basic " + BASIC + "'}", BASIC),
    ('{"Authorization": "Basic ' + BASIC_LONG + '"}', BASIC_LONG),
    ("headers={'Authorization': 'token " + piece("abcdef123", "secret") + "'}", piece("abcdef123", "secret")),
    ("headers={'Authorization': 'Digest username=\"u\", response=\"" + piece("0a1b", "2c3d") + "\"'}", piece("0a1b", "2c3d")),
    ("Authorization: Basic " + BASIC, BASIC),
    ("headers={'Proxy-Authorization': 'Basic " + BASIC + "'}", BASIC),
    ("Proxy-Authorization: Basic " + BASIC, BASIC),
    ("{'X-Api-Key': '" + piece("qwerty", "uiop12") + "'}", piece("qwerty", "uiop12")),
    ("X-Api-Key: " + piece("qwerty", "uiop12"), piece("qwerty", "uiop12")),
    ("{'Cookie': 'sessionid=" + piece("abcdef", "1234") + "; csrftoken=zzz'}", piece("abcdef", "1234")),
    ("Cookie: sessionid=" + piece("abcdef", "1234") + "; csrftoken=zzz", piece("abcdef", "1234")),
    ("Cookie: csrftoken=zzzzzz", "zzzzzz"),
    ("{'Set-Cookie': 'sid=" + piece("abcdef", "1234") + "; Path=/; HttpOnly'}", piece("abcdef", "1234")),
    ("Set-Cookie: sid=" + piece("abcdef", "1234") + "; Path=/; HttpOnly", piece("abcdef", "1234")),
    ("authorization: [hidden] Bearer " + piece("real", "secret999"), piece("real", "secret999")),
]


@pytest.mark.parametrize("line,secret", HEADER_CASES)
def test_logs_header_values_of_any_scheme_are_hidden(line, secret):
    result = redact(line, HOME)
    assert secret not in result and "[hidden]" in result


def test_logs_header_keeps_the_name_and_the_rest_of_a_dict():
    result = redact("headers={'Authorization': 'Basic " + BASIC + "', 'Accept': 'json'}", HOME)
    assert result == "headers={'Authorization': [hidden], 'Accept': 'json'}"


@pytest.mark.parametrize("line", [
    "the authorization step finished and a cookie banner was shown",
    "2026-10-18 22:40:11,123 INFO gateway.cookie: refreshed",
    "authorization failed for the request",
])
def test_logs_header_words_in_prose_stay(line):
    assert redact(line, HOME) == line


# ---- bounded time -------------------------------------------------------------------------------------------

def timed(text, count=200):
    start = time.perf_counter()
    for _ in range(count):
        redact(text, HOME)
    return time.perf_counter() - start


HUGE = {
    "letters": lambda: "a" * 100000,
    "dotted": lambda: "a." * 50000,
    "base64 image": lambda: "iVBORw0KGgo" * 9090,
    "digits": lambda: "1" * 100000,
    "question marks after a scheme": lambda: "http://" + "?" * 100000,
    "many quotes": lambda: 'key="' * 20000,
    "many at signs": lambda: "a@" * 50000,
    "many users prefixes": lambda: "C:" + chr(92) + "Users" + chr(92) + "a" * 100000,
}


@pytest.mark.parametrize("name", list(HUGE))
def test_logs_long_lines_finish_quickly(name):
    text = HUGE[name]()
    elapsed = timed(text)
    print(f"{name}: 200 lines of {len(text)} characters in {elapsed:.3f} s")
    assert elapsed < 2.0


def test_logs_the_raw_line_is_cut_before_redaction():
    assert len(clip("a" * 100000)) == logs.RAW_CAP
    assert clip("word " * 2000) == ("word " * 2000)[:logs.RAW_CAP]
    assert clip("abc " * 999 + "x" * 50) == ("abc " * 999).rstrip(" ")  # cut inside the last word: the half word is dropped
    assert len(redact("q" * 100000)) == logs.LINE_CAP


@asynchronous
async def test_logs_redaction_runs_off_the_event_loop(tmp_path, monkeypatch):
    seen = []
    original = logs.redact

    def spy(line, home=None):
        seen.append(threading.get_ident())
        return original(line, home)

    monkeypatch.setattr(logs, "redact", spy)
    context, _ = make_logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.lines = ["one", "two"]
        status, value = await call("GET", "/logs?lines=2")
        assert (status, value["lines"]) == (200, ["one", "two"])
    assert seen and threading.get_ident() not in seen
    assert asyncio.get_running_loop() is not None


@asynchronous
async def test_logs_two_hundred_huge_lines_through_the_route(tmp_path, monkeypatch):
    context, _ = make_logs(tmp_path, monkeypatch)
    async with context as (_, fake, call):
        fake.lines = ["a." * 2000] * 200  # 800 KB: the biggest answer the bridge accepts from Hermes
        start = time.perf_counter()
        status, value = await call("GET", "/logs?lines=200")
        elapsed = time.perf_counter() - start
        print(f"route, 200 lines of 4000 characters: {elapsed:.3f} s")
        assert status == 200 and len(value["lines"]) == 200 and elapsed < 2.0


# ---- secret runs and known prefixes ---------------------------------------------------------------------------

PREFIXED = [
    piece("hf_", "AbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGh"),
    piece("xoxb", "-1234567890-abcdefghijkl"),
    piece("xoxp", "-1234567890-abcdefghijkl"),
    piece("glpat", "-abcdefghij0123456789"),
    piece("AK", "IA", "IOSFODNN7EXAMPLE"),
    piece("AS", "IA", "IOSFODNN7EXAMPLE"),
    piece("sk_", "live_", "abcdefgh12345678"),
    piece("sk_", "test_", "abcdefgh12345678"),
    piece("rk_", "live_", "abcdefgh12345678"),
    piece("npm_", "abcdefghijklmnopqrstuvwxyz0123456789"),
    piece("pypi-", "AgEIcHlwaS5vcmcCJGFiY2RlZmdo"),
    piece("MTk4NjIyNDgzNDcx", "OTI1MjQ4", ".", "Cl2FMQ", ".", "ZnCjm1XVW7vRze4b7Cq4se7kKWs"),
]


@pytest.mark.parametrize("secret", PREFIXED)
def test_logs_known_prefixes_are_hidden(secret):
    assert redact("login failed " + secret + " end", HOME) == "login failed [hidden] end"


MIXED_RUNS = [
    "lowercase0digits1only2secret3value4abcdefghijk",
    "UPPERCASE0DIGITS1ONLY2SECRET3VALUE4ABCDEFGHIJK",
    piece("QWxhZGRpbjpvcGVuIHNl", "c2FtZTEyMzQ1Njc4"),
    piece("k3j9x0m2p7qz4w8r", "5t1y6u3i9o0a2s4d"),  # lower case letters and digits
    piece("xK3_9fLmQ-2vNpRt7Yb", "AzW8cDeFgHiJk0O1P"),  # base64url
    piece("ab+cd/ef", "gh12ij+kl/mn", "op34qr5s", "tuvw"),
]


@pytest.mark.parametrize("run", MIXED_RUNS)
def test_logs_long_mixed_runs_are_hidden(run):
    assert redact("value " + run + " end", HOME) == "value [hidden] end"


READABLE = [
    "ZeroChunkAbsenceReclaimEnabledFlagManagerXY and zero_chunk_absence_reclaim_enabled_flag_manager",
    "zero_chunk_absence_reclaim_enabled_flag_manager_v2",
    "ZeroChunkAbsenceReclaimEnabledFlagManagerV2",
    "uuid 123e4567-e89b-12d3-a456-426614174000 end",
    "request-123e4567-e89b-12d3-a456-426614174000-done",
    "route /v1/profiles/default/desktop/channels and /api/messaging/telegram/onboarding/start",
    "path /var/lib/hermes/2026-10-18/agent_logs/python3/site-packages/v1beta1/service",
    "HERMES_GATEWAY_ALLOWED_USERS_LIST_FOR_TESTS_2",
    "AllLettersSecretValueThatIsLongEnoughToMatterNow",
    "11111111111111111111111111111111111111111111",
]


@pytest.mark.parametrize("line", READABLE)
def test_logs_identifiers_paths_and_uuids_stay_readable(line):
    result = redact(line, HOME)
    # a long digit run is a phone-like number, everything else must come out unchanged
    assert result == line or (line.isdigit() and result == "[hidden]")


# ---- home paths ---------------------------------------------------------------------------------------------

HOME_CASES = [
    (r"open C:\Users\bob\AppData\x.log", r"open ~\AppData\x.log"),
    (r"open C:\Users\carol\AppData\x.log", r"open ~\AppData\x.log"),
    ("open /c/Users/Zed Q/AppData", "open ~/AppData"),
    ("open /mnt/c/Users/Zed Q/x", "open ~/x"),
    ("open /cygdrive/c/Users/Zed Q/x", "open ~/x"),
    (r"open \\PCNAME\Users\Zed Q\x", r"open ~\x"),
    (r"open \\\\PCNAME\\Users\\Zed Q\\x", r"open ~\\x"),
    (r"open \\PCNAME\C$\Users\Zed Q\x", r"open ~\x"),
    ("open file:///C:/Users/Zed%20Q/x", "open file:///~/x"),
    (r"open C:\Users\ZEDQ~1\AppData", r"open ~\AppData"),
    (r"open \\?\C:\Users\Zed Q\x", r"open \\?\~\x"),
    (r"File \"C:\Users\Zed Q\AppData\Local\x.py\", line 3", r"File \"~\AppData\Local\x.py\", line 3"),
    ("open c:/users/ZED q/y and /Users/zed/z", "open ~/y and ~/z"),
    (r"open C:\\Users\\Zed Q\\z", r"open ~\\z"),
]


@pytest.mark.parametrize("line,expected", HOME_CASES)
def test_logs_home_paths_in_every_form_become_tilde(line, expected):
    assert redact(line, HOME) == expected


@pytest.mark.parametrize("line", ["see src/Users/models.py and UsersService.find", "docs/Users guide"])
def test_logs_users_word_without_a_drive_stays(line):
    assert redact(line, HOME) == line


def test_logs_real_home_with_a_space_is_hidden_without_the_generic_rule():
    assert redact(r"open D:\Profiles\Zed Q\x", r"D:\Profiles\Zed Q") == r"open ~\x"


# ---- the small cases ------------------------------------------------------------------------------------------

SMALL = [
    ("Cookie: sessionid=" + piece("abcdef", "1234"), "Cookie: [hidden]"),
    ("pwd=" + piece("hunter", "22"), "pwd=[hidden]"),
    ("passphrase=" + piece("correct", "horse"), "passphrase=[hidden]"),
    ("running hermes --token " + piece("abc123", "secret"), "running hermes --token [hidden]"),
    ("run --password " + piece("hunter", "2") + " now", "run --password [hidden] now"),
    ("run --api-key " + piece("sk-", "abcdefghijklmnop") + " now", "run --api-key [hidden] now"),
    ("aws_access_key_id=" + piece("AK", "IA", "IOSFODNN7EXAMPLE"), "aws_access_key_id=[hidden]"),
    ("AWS_SECRET_ACCESS_KEY_ID: " + piece("abc", "def"), "AWS_SECRET_ACCESS_KEY_ID: [hidden]"),
    ("client.token: " + piece("short", "secret1"), "client.token: [hidden]"),
    ("self._auth = ('user', '" + piece("pw", "123") + "')", "self._auth = [hidden]"),
    ("auth=('user', 'x') then", "auth=[hidden] then"),
    ("call 0944 123 456 now", "call [hidden] now"),
    ("call 0944-123-456 now", "call [hidden] now"),
    ("call +963 944-123-456 now", "call [hidden] now"),
    ("call +963 944 123 456 now", "call [hidden] now"),
    ("password=[REDACTED]", "password=[hidden]"),
    ("token=[hidden]", "token=[hidden]"),
    ("secret: [***]", "secret: [hidden]"),
    ("post https://hooks.slack.com/services/T00000000/B00000000/" + piece("XXXXXXXX", "XXXXXXXXXXXXXXXX"),
     "post https://hooks.slack.com/[hidden]"),
    ("a\x1b[31mb\tc" + chr(0x202E) + "d\x07e\x85f\n", "a [31mb c d e f "),
    (chr(0x2066) + "x" + chr(0x2069) + chr(0x202A) + chr(0x202D), " x   "),
]


@pytest.mark.parametrize("line,expected", SMALL)
def test_logs_small_cases(line, expected):
    assert redact(line, HOME) == expected


@pytest.mark.parametrize("line", [
    "run --token-file /x/y --tokens 5 --no-token --verbose",
    "2026-10-18 22:40:11,123 INFO gateway.auth: login ok",
    "2026-10-18 22:40:11,123 took 0.944 s at 192.168.1.1 version 1.2.3",
    "ids 0944 and 123 456 and 2026-10-18",
    "https://hooks.example.com/services/ok",
])
def test_logs_small_near_misses(line):
    assert redact(line, HOME) == line


# ---- bounded time, part two: every rule that could start anywhere inside a run ----------------------------------

BS = chr(92)
WINHOME = "C:" + BS + "Users" + BS + "Zed Q"
SHAPES = {
    "users then slashes": "users " + "/" * 3994,
    "slashes then users": "/" * 3994 + " users",
    "users then backslashes": "users " + BS * 3994,
    "slashes only": "/" * 4000,
    "backslashes only": BS * 4000,
    "home word after slashes": "/" * 3990 + "home",
    "home word after backslashes": BS * 3990 + "home",
    "wsl then slashes": BS * 2 + "wsl$" + "/" * 3990,
    "unc then dashes": BS * 2 + "?" + BS + "UNC" + BS + "h" * 3980,
    "name keyword digits": "0/a" * 1333,
    "jwt prefix dashes": ("-----eyJ") * 500,
    "drive dashes": ("C:" + "-" * 40) * 90,
    "bytes colons": ("b'" + "x:" * 40) * 49,
    "open parens": "key=( " * 660,
    "open brackets": "token=[" * 570,
    "open quotes": "password='x " * 330,
    "flags": "--token" * 570,
    "at signs": "a@a" * 1300,
    "headers": "cookie," * 570,
    "percent": "%" + "%2" * 1998,
    "scheme dots": "a." * 1990 + "://",
    "double slash at signs": "//a@" * 1000,
    "wsl dots": BS * 2 + "wsl.localhost" + BS + "a" + BS + "home" + BS + "a" * 3900,
}


@pytest.mark.parametrize("home", ["/home/zed", WINHOME, BS * 2 + "server" + BS + "home" + BS + "zed"])
def test_logs_every_worst_case_line_finishes_quickly(home):
    total = 0.0
    for name, text in SHAPES.items():
        start = time.perf_counter()
        logs.shape("errors", [text] * 200, home)
        elapsed = time.perf_counter() - start
        total = max(total, elapsed)
        print(f"{home!r:30} {name:28} 200 lines: {elapsed:.3f} s")
        assert elapsed < 2.0, name


def test_logs_event_loop_keeps_ticking_during_a_worst_case_request():
    async def main():
        text = "users " + "/" * 3994
        task = asyncio.create_task(asyncio.to_thread(logs.shape, "errors", [text] * 200, WINHOME))
        gaps, last = [], time.perf_counter()
        while not task.done():
            await asyncio.sleep(0.005)
            now = time.perf_counter()
            gaps.append(now - last)
            last = now
        await task
        return max(gaps), len(gaps)

    gap, ticks = asyncio.run(main())
    print(f"event loop: max gap {gap:.3f} s over {ticks} ticks")
    assert gap < 0.25


# ---- header names after a literal backslash-n, tuple keys, byte-string values ---------------------------------

TUPLE = piece("YWRtaW46U2Vj", "cmV0cHcx")
HEADER_FORMS = [
    '{"raw": "GET / HTTP/1.1' + BS + "r" + BS + "nAuthorization: Basic " + TUPLE + BS + "r" + BS + 'n"}',
    'msg="hdrs:' + BS + "nCookie: sid=" + piece("abc", "123def") + BS + 'n"',
    "[('Authorization', 'Basic " + TUPLE + "')]",
    "[(b'authorization', b'Basic " + TUPLE + "')]",
    "scope headers=[(b'authorization', b'Basic " + TUPLE + "'), (b'host', b'x')]",
]


@pytest.mark.parametrize("line", HEADER_FORMS)
def test_logs_header_in_escaped_text_and_tuples_is_hidden(line):
    result = redact(line, HOME)
    assert TUPLE not in result and piece("abc", "123def") not in result and "[hidden]" in result


def test_logs_header_tuple_keeps_the_next_pair():
    assert redact("scope headers=[(b'authorization', b'Basic " + TUPLE + "'), (b'host', b'x')]", HOME) \
        == "scope headers=[(b'authorization', [hidden]), (b'host', b'x')]"


@pytest.mark.parametrize("line", ["gateway.auth: ok", "author: zed", "cookies enabled, count=3", "the authorization header is absent"])
def test_logs_header_near_misses_stay(line):
    assert redact(line, HOME) == line


# ---- value prefixes and calls -----------------------------------------------------------------------------------

PW = piece("Secret", "pw1")
VALUE_FORMS = [
    ("{'password': b'" + PW + "'}", "{'password': [hidden]}"),
    ("password=u'" + PW + "'", "password=[hidden]"),
    ("{'passwd': rb'" + PW + "'}", "{'passwd': [hidden]}"),
    ('password=f"' + PW + '"', "password=[hidden]"),
    ("auth=HTTPBasicAuth('admin', '" + PW + "')", "auth=[hidden]"),
    ("auth=requests.auth.HTTPBasicAuth('admin', '" + PW + "') ok", "auth=[hidden] ok"),
]


@pytest.mark.parametrize("line,expected", VALUE_FORMS)
def test_logs_value_prefixes_and_calls_are_hidden(line, expected):
    assert redact(line, HOME) == expected


# ---- a cut inside a run -------------------------------------------------------------------------------------------

def test_logs_cut_inside_a_compact_run_drops_the_whole_run():
    tail = piece("01234567", "89abcdef") * 3
    line = '{"img":"' + "q," * 1979 + 'z"k":"' + tail + '"}'
    assert line.index(tail) == 3972 and " " not in line
    result = redact(line, HOME)
    for size in range(8, 49):
        for start in range(0, 49 - size):
            assert tail[start:start + size] not in result


def test_logs_cut_run_that_is_the_whole_line_is_kept_and_processed():
    assert len(clip("q" * 100000)) == logs.RAW_CAP


# ---- account names ----------------------------------------------------------------------------------------------

ACCOUNT_CASES = [
    ("user PCNAME" + BS + "bob logged in", "user ~ logged in"),
    ("user PCNAME" + BS + BS + "bob logged in", "user ~ logged in"),
    ("open " + BS + BS + "?" + BS + "UNC" + BS + "HOST" + BS + "Users" + BS + "Zed" + BS + "x", "open ~" + BS + "x"),
    ("open " + BS + BS + "wsl$" + BS + "Ubuntu" + BS + "home" + BS + "zed" + BS + "x", "open ~" + BS + "x"),
    ("open " + BS + BS + "wsl.localhost" + BS + "Ubuntu" + BS + "home" + BS + "zed" + BS + "x", "open ~" + BS + "x"),
    ("open /home/zed/x", "open ~/x"),
    ("open C:" + BS + "Users" + BS + "a b c d e f" + BS + "x", "open ~" + BS + "x"),
    ("open C:" + BS + "Users" + BS + "a b c d e f and more", "open ~ b c d e f and more"),
]


@pytest.mark.parametrize("line,expected", ACCOUNT_CASES)
def test_logs_account_names_in_every_form_are_hidden(line, expected):
    assert redact(line, HOME) == expected


def test_logs_host_user_form_uses_the_real_home_name():
    assert redact("PCNAME" + BS + "Zed Q logged in", WINHOME) == "~ logged in"
    assert redact("PCNAME" + BS + "Zed Qx ok", WINHOME) == "PCNAME" + BS + "Zed Qx ok"
    assert redact("open C:" + BS + "Users" + BS + "Zed Q and more", WINHOME) == "open ~ and more"


# ---- placeholders ---------------------------------------------------------------------------------------------

@pytest.mark.parametrize("line,expected", [
    ("password=[REDACTEDTOOLONGX]", "password=[hidden]"),
    ("password=[REDACTED] user=bob", "password=[hidden] user=bob"),
    ("Authorization: [REDACTEDTOOLONGXX]", "Authorization: [hidden]"),
])
def test_logs_placeholders_of_any_length_leave_no_stray_bracket(line, expected):
    assert redact(line, HOME) == expected


# ---- URLs -------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("line,expected", [
    ("ftp://user:pa@ss@host/x", "ftp://[hidden]@host/x"),
    ("url=//user:" + piece("pa", "ss") + "@host/x", "url=//[hidden]@host/x"),
    ("get https://example.com/x;jsessionid=" + piece("ABC", "123DEF") + "?a=1", "get https://example.com/x;jsessionid=[hidden]?a=[hidden]"),
    ("see //server/share/file and https://example.com/a@b/c", "see //server/share/file and https://example.com/a@b/c"),
])
def test_logs_url_credentials_scheme_relative_and_session_parameter(line, expected):
    assert redact(line, HOME) == expected


# ---- names that only end in key, token or secret -----------------------------------------------------------------

@pytest.mark.parametrize("line", ["monkey: banana", "turkey=1", "hockey=2 and donkey: 3", "--monkey abc", "keyring=on", "tokenizer: tiktoken"])
def test_logs_names_that_merely_end_in_key_stay_readable(line):
    assert redact(line, HOME) == line


@pytest.mark.parametrize("line,expected", [
    ("api_key=abc", "api_key=[hidden]"),
    ("apiKey=abc", "apiKey=[hidden]"),
    ("apikey=abc", "apikey=[hidden]"),
    ("secret_key_base: abc", "secret_key_base: [hidden]"),
    ("ACCESS_TOKEN=abc", "ACCESS_TOKEN=[hidden]"),
    ("x-goog-api-key: abc", "x-goog-api-key: [hidden]"),
    ("--api-key abc", "--api-key [hidden]"),
])
def test_logs_key_names_at_a_word_boundary_are_hidden(line, expected):
    assert redact(line, HOME) == expected


# ---- password words anywhere in a name; more glued front words ---------------------------------------------------

SECRET = piece("hunt", "er2x")


@pytest.mark.parametrize("name", [
    "PGPASSWORD=", "export PGPASSWORD=", "dbpassword=", "rootpassword: ", "mysqlpwd=", "adminpasswd=", "userpassphrase=",
    "--dbpassword ", "--adminpwd ",
    "idtoken=", "csrftoken=", "xsrftoken=", "fcmtoken=", "jwtsecret=", "appsecret=", "webhooksecret=", "privkey=", "encryptionkey=",
    "signingkey=", "consumersecret=", "sharedsecret=", "devicetoken=", "pushtoken=", "sessionkey=", "refreshtoken=", "clientsecret=",
])
def test_logs_password_words_anywhere_and_glued_front_words_are_hidden(name):
    assert redact(name + SECRET, HOME) == name + "[hidden]"


@pytest.mark.parametrize("line", ["monkey=1", "turkey: 2", "hockey=3", "gateway.auth: ok", "author: bob", "passwordless=on"])
def test_logs_ordinary_words_still_readable_after_the_wider_name_rule(line):
    assert redact(line, HOME) == line


# ---- JSON lists and nested objects, Authentication header, file URLs ------------------------------------------------

@pytest.mark.parametrize("line,expected", [
    ('{"password": ["' + SECRET + ' b"]}', '{"password": [hidden]}'),
    ('{"password": ["a", "' + SECRET + '"], "user": "bob"}', '{"password": [hidden], "user": "bob"}'),
    ('{"token": {"value": "' + SECRET + '"}}', '{"token": [hidden]}'),
    ('{"token": {"a": {"v": "' + SECRET + '"}}, "x": 1}', '{"token": [hidden], "x": 1}'),
    ("Authentication: Basic " + BASIC, "Authentication: Basic [hidden]"),
    ("file:///C%3A/Users/Bob/x", "file:///~/x"),
    ("file:///C:/Users/Bob/x", "file:///~/x"),
    ("file:///C%3a/Users/Bob%20Q/x", "file:///~/x"),
])
def test_logs_json_lists_nested_objects_authentication_and_file_urls(line, expected):
    assert redact(line, HOME) == expected


def test_logs_authentication_prose_stays():
    assert redact("Authentication: failed for bob", HOME) == "Authentication: failed for bob"


# ---- token, secret and auth words anywhere in a name; Authentication hides only the credential --------------------

@pytest.mark.parametrize("name", [
    "oauth=", "basicauth=", "httpauth: ", "proxyauth=", "xauth=", "--oauth ", "githubtoken=", "slacktoken=", "usertoken=",
    "hftoken=", "discordtoken=", "usersecret=", "dbsecret=", "userkey=", "licensekey=", "hmackey=",
])
def test_logs_token_secret_auth_words_anywhere_in_a_name_are_hidden(name):
    assert redact(name + SECRET, HOME) == name + "[hidden]"


@pytest.mark.parametrize("line", [
    "monkey=1", "hockey: 2", "author: bob", "gateway.auth: ok", "refresh_token_count: 3", "KeyError: 'x'", "passwordless login",
])
def test_logs_ordinary_words_stay_after_the_token_secret_auth_rule(line):
    assert redact(line, HOME) == line


@pytest.mark.parametrize("line", [
    "Authentication: token expired",
    "Authentication: basic auth disabled (from 10.0.0.1)",
    "Authentication: token expired at 12:00",
])
def test_logs_authentication_prose_after_a_scheme_word_stays(line):
    assert redact(line, HOME) == line


def test_logs_authentication_hides_only_the_credential_run():
    assert redact("Authentication: Basic " + BASIC + " tail words", HOME) == "Authentication: Basic [hidden] tail words"
    assert redact("(Authentication: Bearer " + piece("abc123", "def456") + ") ok", HOME) == "(Authentication: Bearer [hidden]) ok"
