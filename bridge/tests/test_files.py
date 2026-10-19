import asyncio
from contextlib import asynccontextmanager, suppress
import hashlib
import json
import os
import random
import re
import tempfile
import time
from pathlib import Path

import pytest

from rasul_bridge import files as files_module
from rasul_bridge.cli import initialize
from rasul_bridge.config import Config
from rasul_bridge.files import DeltaFilter, EventRewriter, Files, JsonRewriter, SECRET_NAME
from rasul_bridge.http import HTTPError, headers
from rasul_bridge.proxy import response_body
from rasul_bridge.server import Bridge
from rasul_bridge.storage import Store

from test_bridge import asynchronous, client, registered, send


@pytest.fixture(autouse=True)
def isolated_user(tmp_path, monkeypatch):
    """A fake user profile, so the real one never decides what a test may deliver."""
    fake = tmp_path / "user"
    for name in ("HOME", "USERPROFILE"):
        monkeypatch.setenv(name, str(fake))
    for prefix, folder in (("", "Roaming"), ("LOCAL", "Local")):
        monkeypatch.setenv(prefix + "APP" + "DATA", str(fake / ("App" + "Data") / folder))
    for name in ("HERMES_MEDIA_DELIVERY_STRICT", "HERMES_MEDIA_ALLOW_DIRS",
                 "HERMES_MEDIA_TRUST_RECENT_FILES", "HERMES_MEDIA_TRUST_RECENT_SECONDS"):
        monkeypatch.delenv(name, raising=False)


class Clock:
    def __init__(self):
        self.now = 1_000_000.0

    def __call__(self):
        return self.now


def frame(seq, event, **fields):
    value = {"event": event, "run_id": "one", **fields, "seq": seq}
    return f"id: {seq}\ndata: {json.dumps(value)}\n\n".encode()


class Scripted:
    """A tiny upstream that answers each target with prepared bytes."""

    def __init__(self):
        self.events = []        # list of byte chunks for the events stream
        self.json = {}          # target suffix -> object

    async def handle(self, reader, writer):
        try:
            raw = (await reader.readuntil(b"\r\n\r\n")).split(b"\r\n")
            target = raw[0].decode().split(" ")[1]
            if target.endswith("/events"):
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                             b"Transfer-Encoding: chunked\r\n\r\n")
                for data in self.events:
                    writer.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
                    await writer.drain()
                writer.write(b"0\r\n\r\n")
            else:
                data = json.dumps(self.json.get(target, {"status": "ok"})).encode()
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                             + f"Content-Length: {len(data)}\r\n\r\n".encode() + data)
            await writer.drain()
        finally:
            writer.close()

    async def start(self):
        self.server = await asyncio.start_server(self.handle, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def close(self):
        self.server.close()
        await self.server.wait_closed()


@asynccontextmanager
async def running(tmp_path):
    upstream = Scripted()
    await upstream.start()
    store = Store(tmp_path / "data")
    initialize(store)
    config = Config(hermes_url=f"http://127.0.0.1:{upstream.port}", port=0, hermes_home=tmp_path / "upstream")
    config.hermes_home.mkdir()
    bridge = Bridge(store, config, random_source=random.Random(7))
    bridge.profiles.init_keys()
    await bridge.start()
    try:
        async with asyncio.timeout(120):
            yield bridge, upstream
    finally:
        await bridge.close()
        await upstream.close()


async def fetch(link, reader, target):
    await send(link, "GET", target)
    raw = (await reader.until(b"\r\n\r\n")).split(b"\r\n")
    head = headers(raw[1:])
    body = bytearray()
    async for data in response_body(reader, head):
        body.extend(data)
    return int(raw[0].split()[1]), head, bytes(body)


def make(tmp_path, clock=None):
    home, store = tmp_path / "upstream", tmp_path / "data"
    home.mkdir(exist_ok=True)
    store.mkdir(exist_ok=True)
    work = tmp_path / "work"
    work.mkdir(exist_ok=True)
    events = []
    return Files(home, store, lambda *a: events.append(a), clock or Clock()), work, events


def link_token(link):
    match = re.search(r"\]\(rasul-file://([A-Za-z0-9_-]{43})\)", link)
    assert match, link
    return match[1]


# ---- policy --------------------------------------------------------------------------------

@pytest.mark.parametrize("name,size", [("report.pdf", 2000), ("notes.txt", 10), ("bundle.zip", 5000),
                                       ("sheet.xlsx", 100), ("photo.png", 6 * 1024 * 1024),
                                       ("talk.mp3", 1), ("empty.csv", 0)])
def test_valid_files_become_links_without_the_path(tmp_path, name, size):
    files, work, events = make(tmp_path)
    path = work / name
    path.write_bytes(b"x" * size)
    out = files.rewrite("dev", "default", f"here you go\nMEDIA:{path}\nbye")
    assert str(work) not in out and name in out
    link_token(out)
    assert out.startswith("here you go\n[") and out.endswith("\nbye")
    assert events and events[0][0] == "file_offered" and events[0][3] == name


def test_quoted_and_emphasised_tags(tmp_path):
    files, work, _ = make(tmp_path)
    path = work / "my file.pdf"
    path.write_bytes(b"%PDF")
    for tag in (f'**MEDIA:{path}**', f'MEDIA:"{path}"', f"MEDIA:`{path}`", f"(MEDIA:{path})."):
        out = files.rewrite("dev", "default", tag)
        assert str(work) not in out and "rasul-file://" in out, tag


def test_denied_and_missing_paths_show_unavailable_note(tmp_path):
    files, work, events = make(tmp_path)
    env = work / ".env"
    env.write_text("KEY=1")
    secret = work / "server.pem"
    secret.write_text("x")
    (work / "folder.pdf").mkdir()
    big = work / "huge.zip"
    big.write_bytes(b"1")
    odd = work / "tool.exe"
    odd.write_bytes(b"1")
    inside_store = tmp_path / "data" / "profile-keys.json"
    inside_store.write_text("{}")
    agent_env = tmp_path / "upstream" / ".env"
    agent_env.write_text("API_SERVER_KEY=x")
    for tag in (f"MEDIA:{env}", f"MEDIA:{work}/server.pem", f"MEDIA:{work}/folder.pdf",
                f"MEDIA:{work}/missing.pdf", f"MEDIA:{inside_store}", f"MEDIA:{agent_env}"):
        out = files.rewrite("dev", "default", tag)
        assert "not available" in out and "rasul-file" not in out, tag
        assert str(work) not in out and str(tmp_path) not in out, tag
    assert files.validate(str(big)) is not None
    assert files.validate(str(odd)) is None
    assert not events


def test_agent_credential_stores_are_denied_in_profiles_too(tmp_path):
    files, _, _ = make(tmp_path)
    profile = tmp_path / "upstream" / "profiles" / "work"
    (profile / "sessions").mkdir(parents=True)
    (profile / "sessions" / "chat.json").write_text("{}")
    (profile / "state.db").write_bytes(b"x")
    assert files.validate(str(profile / "sessions" / "chat.json")) is None
    assert files.validate(str(profile / "state.db")) is None
    cache = tmp_path / "upstream" / "cache" / "documents"
    cache.mkdir(parents=True)
    (cache / "made.pdf").write_bytes(b"%PDF")
    assert files.validate(str(cache / "made.pdf")) is not None


def test_symlink_to_a_denied_file_is_refused(tmp_path):
    files, work, _ = make(tmp_path)
    target = work / ".env"
    target.write_text("KEY=1")
    link = work / "innocent.txt"
    try:
        os.symlink(target, link)
    except (OSError, NotImplementedError):
        pytest.skip("symbolic links unavailable")
    assert files.validate(str(link)) is None


def test_oversize_file_is_refused(tmp_path, monkeypatch):
    files, work, _ = make(tmp_path)
    path = work / "a.zip"
    path.write_bytes(b"x" * 100)
    monkeypatch.setattr(files_module, "MAX_FILE_BYTES", 99)
    assert files.mint("dev", "default", str(path)) is None
    monkeypatch.setattr(files_module, "MAX_FILE_BYTES", 100)
    assert files.mint("dev", "default", str(path)) is not None


def test_relative_and_tilde_free_paths_are_refused(tmp_path):
    files, _, _ = make(tmp_path)
    assert files.validate("report.pdf") is None
    assert files.validate("../report.pdf") is None
    assert files.validate("") is None


# ---- tokens --------------------------------------------------------------------------------

def test_token_is_bound_to_device_expires_and_detects_change(tmp_path):
    clock = Clock()
    files, work, _ = make(tmp_path, clock)
    path = work / "a.txt"
    path.write_bytes(b"one")
    token = link_token(files.mint("dev-a", "default", str(path)))
    with pytest.raises(HTTPError) as other:
        files.open("dev-b", token)
    assert other.value.status == 404
    with pytest.raises(HTTPError) as unknown:
        files.open("dev-a", "A" * 43)
    assert unknown.value.status == 404
    entry, stream = files.open("dev-a", token)
    stream.close()
    assert entry["name"] == "a.txt"
    path.write_bytes(b"two!")
    with pytest.raises(HTTPError) as changed:
        files.open("dev-a", token)
    assert (changed.value.status, changed.value.message) == (410, "file_changed")
    clock.now += 24 * 3600 + 1
    with pytest.raises(HTTPError) as expired:
        files.open("dev-a", token)
    assert (expired.value.status, expired.value.message) == (410, "file_expired")


def test_file_removed_after_offer_is_unavailable(tmp_path):
    files, work, _ = make(tmp_path)
    path = work / "a.txt"
    path.write_bytes(b"one")
    token = link_token(files.mint("dev", "default", str(path)))
    path.unlink()
    with pytest.raises(HTTPError) as gone:
        files.open("dev", token)
    assert gone.value.status == 410


def test_at_most_200_tokens_per_device_oldest_evicted_and_same_file_reused(tmp_path):
    files, work, _ = make(tmp_path)
    first = work / "f0.txt"
    first.write_text("0")
    first_token = link_token(files.mint("dev", "default", str(first)))
    assert link_token(files.mint("dev", "default", str(first))) == first_token
    for index in range(1, 205):
        path = work / f"f{index}.txt"
        path.write_text(str(index))
        files.mint("dev", "default", str(path))
    assert len(files.tokens["dev"]) == 200
    with pytest.raises(HTTPError) as evicted:
        files.open("dev", first_token)
    assert evicted.value.status == 404
    assert "dev2" not in files.tokens


def test_download_rate_limit(tmp_path):
    clock = Clock()
    files, work, _ = make(tmp_path, clock)
    path = work / "a.txt"
    path.write_text("x")
    token = link_token(files.mint("dev", "default", str(path)))
    for _ in range(30):
        files.open("dev", token)[1].close()
    with pytest.raises(HTTPError) as limited:
        files.open("dev", token)
    assert limited.value.status == 429
    clock.now += 61
    files.open("dev", token)[1].close()


# ---- streamed text ---------------------------------------------------------------------------

def test_tag_split_across_deltas_never_leaks_a_fragment(tmp_path):
    files, work, _ = make(tmp_path)
    path = work / "quarterly report.pdf"
    path.write_bytes(b"%PDF")
    text = f"Done. **MEDIA:{path}**\nEnjoy"
    sent = []
    for size in (1, 3, 7):
        sent.clear()
        stream = DeltaFilter(files, "dev", "default")
        for index in range(0, len(text), size):
            sent.append(stream.feed(text[index:index + size]))
            assert str(work) not in "".join(sent) and "MEDIA:" not in "".join(sent)
        sent.append(stream.flush())
        whole = "".join(sent)
        assert str(work) not in whole and "MEDIA:" not in whole
        assert whole.startswith("Done. [quarterly report.pdf (4 B)](rasul-file://") and whole.endswith("\nEnjoy")


def test_prose_without_a_tag_is_delayed_at_most_by_a_possible_prefix():
    stream = DeltaFilter(None, "dev", "default")
    assert stream.feed("hello ME") == "hello "
    stream.files = type("F", (), {"rewrite": staticmethod(lambda d, p, t: t)})()
    assert stream.feed("ssage") == "MEssage"
    assert stream.flush() == ""


def test_unfinished_tag_line_is_capped(tmp_path, monkeypatch):
    files, _, _ = make(tmp_path)
    monkeypatch.setattr(files_module, "HOLD_LIMIT", 20)
    stream = DeltaFilter(files, "dev", "default")
    out = stream.feed("MEDIA:/home/user/" + "a" * 40)
    assert out == "[file not available]" and stream.buffer == ""


# ---- end to end over the encrypted link --------------------------------------------------------

@asynchronous
async def test_run_events_history_and_download_over_the_link(tmp_path):
    work = tmp_path / "work"
    work.mkdir()
    pdf = work / "plan.pdf"
    pdf.write_bytes(b"%PDF-1.4 " + b"p" * 100)
    big = work / "dump.zip"
    big.write_bytes(os.urandom(1024 * 1024) * 20)       # 20 MB
    async with running(tmp_path) as (bridge, upstream):
        text = f"Here is the plan.\nMEDIA:{pdf}\nAnd data:\nMEDIA:{big}\nEnd"
        pieces = [text[i:i + 6] for i in range(0, len(text), 6)]
        upstream.events = ([frame(1, "run.started")]
                           + [frame(2 + i, "message.delta", delta=p) for i, p in enumerate(pieces)]
                           + [frame(100, "run.completed", output=text, usage={})])
        upstream.json["/v1/runs/one"] = {"status": "completed", "output": text}
        upstream.json["/api/sessions/s1/messages"] = {"messages": [{"role": "assistant", "content": text}]}
        identity, device = registered(bridge)
        other_identity, other_device = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            status, head, body = await fetch(link, reader, "/v1/profiles/default/hermes/v1/runs/one/events")
            assert status == 200 and b"text/event-stream" in head["content-type"].encode()
            wire = body.decode()
            assert str(work) not in wire and "MEDIA:" not in wire
            frames = [json.loads(line[5:]) for line in wire.splitlines() if line.startswith("data:")]
            streamed = "".join(f.get("delta", "") for f in frames if f.get("event") == "message.delta")
            assert "Here is the plan.\n[plan.pdf (" in streamed and "dump.zip (20.0 MB)" in streamed
            assert streamed.endswith("\nEnd")
            assert [f["seq"] for f in frames if "seq" in f] == sorted(f["seq"] for f in frames if "seq" in f)
            completed = next(f for f in frames if f["event"] == "run.completed")
            tokens = re.findall(r"rasul-file://([A-Za-z0-9_-]{43})", completed["output"])
            assert len(tokens) == 2

            status, _, body = await fetch(link, reader, "/v1/profiles/default/hermes/v1/runs/one")
            assert status == 200 and str(work) not in body.decode() and "rasul-file://" in body.decode()
            status, _, body = await fetch(link, reader, "/v1/profiles/default/hermes/api/sessions/s1/messages")
            assert status == 200 and str(work) not in body.decode() and "rasul-file://" in body.decode()
            upstream.json["/api/sessions/s2/messages"] = {"object": "list", "session_id": "s2", "data": [
                {"role": "assistant", "content": text}], "pagination": {}}
            status, _, body = await fetch(link, reader, "/v1/profiles/default/hermes/api/sessions/s2/messages")
            assert status == 200 and str(work) not in body.decode() and "rasul-file://" in body.decode()

            status, head, body = await fetch(link, reader, "/v1/files/" + tokens[0])
            assert status == 200 and body == pdf.read_bytes()
            assert head["content-type"] == "application/pdf" and head["content-length"] == str(len(body))
            assert 'filename="plan.pdf"' in head["content-disposition"]
            status, head, body = await fetch(link, reader, "/v1/files/" + tokens[1])
            assert status == 200 and hashlib.sha256(body).digest() == hashlib.sha256(big.read_bytes()).digest()
            assert head["content-type"] == "application/zip"

            status, _, body = await fetch(link, reader, "/v1/files/" + "B" * 43)
            assert (status, json.loads(body)) == (404, {"error": "file_not_found"})
            status, _, _ = await fetch(link, reader, "/v1/files/short")
            assert status == 404
        async with client(bridge, tmp_path, other_identity) as (link, reader, _):
            status, _, body = await fetch(link, reader, "/v1/files/" + tokens[0])
            assert (status, json.loads(body)) == (404, {"error": "file_not_found"})
        rows = [json.loads(x) for x in bridge.audit.path.read_text().splitlines()]
        sent = [r for r in rows if r.get("event") == "file_sent"]
        assert {r["filename"] for r in sent} == {"plan.pdf", "dump.zip"}
        assert all(r["device_id"] == device and "path" not in r and str(work) not in json.dumps(r) for r in sent)


@asynchronous
async def test_anonymous_device_cannot_download(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        async with client(bridge, tmp_path) as (link, reader, _):
            status, _, body = await fetch(link, reader, "/v1/files/" + "A" * 43)
            assert status == 403


@asynchronous
async def test_streams_without_tags_pass_through_byte_for_byte(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        raw = b"event: update\ndata: first\n\n"
        upstream.events = [raw, b": keepalive\n\n"]
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            status, _, body = await fetch(link, reader, "/v1/profiles/default/hermes/v1/runs/one/events")
            assert body == raw + b": keepalive\n\n"


# ---- security round: network paths, provenance, deny list, config, identity, limits ---------------

def boom(*args, **kwargs):
    raise AssertionError("filesystem touched")


@pytest.mark.parametrize("raw", [
    "//evil.example/s/a.pdf", "\\\\evil.example\\s\\a.pdf", "\\\\.\\C:\\x\\a.pdf",
    "\\\\?\\C:\\x\\a.pdf", "/\\evil/s/a.pdf", "\\/evil/s/a.pdf", "C:/x/a.pdf:evil",
    "C:\\x\\a.pdf:Zone.Identifier", "/x/y:z/a.pdf"])
def test_network_and_device_paths_never_touch_the_filesystem(tmp_path, monkeypatch, raw):
    files, _, events = make(tmp_path)
    with monkeypatch.context() as patched:
        for target in (os, "stat"), (os.path, "realpath"), (Path, "resolve"), (Path, "stat"), (Path, "is_file"):
            patched.setattr(*target, boom)
        assert files.validate(raw) is None
        assert files.mint("dev", "default", raw) is None
    assert not events


def test_tags_with_network_paths_become_unavailable_without_a_filesystem_call(tmp_path, monkeypatch):
    files, _, _ = make(tmp_path)
    with monkeypatch.context() as patched:
        for target in (os, "stat"), (os.path, "realpath"), (Path, "resolve"), (Path, "stat"):
            patched.setattr(*target, boom)
        for tag in ("MEDIA://evil.example/s/a.pdf", 'MEDIA:"\\\\evil.example\\s\\a.pdf"'):
            out = files.rewrite("dev", "default", tag)
            assert "not available" in out and "evil" not in out.replace("not available", ""), tag


def events_for(files, *items):
    rewriter = EventRewriter(files, "dev", "default")
    wire = b"".join(frame(index + 1, event, **fields) for index, (event, fields) in enumerate(items))
    return (b"".join(rewriter.feed(wire)) + b"".join(rewriter.finish())).decode()


def test_only_assistant_text_in_events_can_mint_links(tmp_path):
    files, work, events = make(tmp_path)
    pdf = work / "plan.pdf"
    pdf.write_bytes(b"%PDF")
    tag = f"MEDIA:{pdf}"
    files.validate = boom       # stripped text must not be validated at all
    quiet = events_for(files,
                       ("tool.completed", {"tool": "web", "preview": f"page says {tag}"}),
                       ("reasoning.available", {"text": f"I could {tag}"}),
                       ("subagent.progress", {"output_tail": f"tail {tag}"}),
                       ("tool.started", {"preview": tag}))
    assert "rasul-file" not in quiet and str(work) not in quiet and "MEDIA:" not in quiet
    assert quiet.count("[file]") == 4 and not events
    del files.validate
    loud = events_for(files,
                      ("message.interim", {"text": f"first\n{tag}\n"}),
                      ("run.completed", {"output": f"done\n{tag}", "usage": {}}))
    assert loud.count("rasul-file://") == 2 and str(work) not in loud


def test_run_and_history_json_mint_only_assistant_output(tmp_path):
    files, work, events = make(tmp_path)
    pdf = work / "plan.pdf"
    pdf.write_bytes(b"%PDF")
    tag = f"MEDIA:{pdf}"
    run = files.filter("dev", "default", "/v1/runs/{id}")
    run.feed(json.dumps({"status": "completed", "output": f"ok {tag}", "input": f"user wrote {tag}"}).encode())
    value = json.loads(b"".join(run.finish()))
    assert "rasul-file://" in value["output"] and value["input"] == "user wrote [file]"
    history = files.filter("dev", "default", "/api/sessions/{id}/messages")
    history.feed(json.dumps({"messages": [
        {"role": "user", "content": f"send {tag}"},
        {"role": "tool", "content": f"result {tag}"},
        {"role": "system", "content": f"rules {tag}"},
        {"role": "assistant", "content": f"here {tag}", "reasoning": f"think {tag}"},
        {"role": "assistant", "content": [{"type": "text", "text": f"again {tag}"}]}]}).encode())
    messages = json.loads(b"".join(history.finish()))["messages"]
    assert [("rasul-file://" in str(m["content"])) for m in messages] == [False, False, False, True, True]
    assert messages[0]["content"] == "send [file]" and messages[3]["reasoning"] == "think [file]"
    assert str(work) not in json.dumps(messages)


@pytest.mark.parametrize("key", ["data", "messages"])
def test_history_list_key_of_the_agent_mints_only_assistant_tags(tmp_path, key):
    files, work, _ = make(tmp_path)
    png = work / "shot.png"
    png.write_bytes(b"\x89PNG")
    tag = f"MEDIA:{png}"
    body = {"object": "list", "session_id": "s1", key: [
        {"role": "assistant", "content": tag}, {"role": "user", "content": f"look {tag}"}],
        "pagination": {"note": f"x {tag}"}}
    history = files.filter("dev", "default", "/api/sessions/{id}/messages")
    history.feed(json.dumps(body).encode())
    value = json.loads(b"".join(history.finish()))
    assert "rasul-file://" in value[key][0]["content"] and str(work) not in json.dumps(value)
    assert value[key][1]["content"] == "look [file]" and value["pagination"]["note"] == "x [file]"


def strict_files(tmp_path):
    clock = Clock()
    clock.now = time.time()
    files, work, _ = make(tmp_path, clock)
    write_config(tmp_path / "upstream", "gateway:\n  strict: true\n")
    path = work / "shot.png"
    path.write_bytes(b"\x89PNG-one")
    return files, clock, path


def test_a_file_offered_while_recent_is_offered_again_after_the_window(tmp_path):
    files, clock, path = strict_files(tmp_path)
    link = files.mint("dev", "default", str(path))
    assert link is not None
    clock.now += files_module.DEFAULT_RECENT_SECONDS + 60
    assert files.validate(str(path), "default") is None
    assert files.mint("dev", "default", str(path)) == link
    entry, stream = files.open("dev", link_token(link))
    stream.close()
    assert entry["name"] == "shot.png"


def test_a_stale_file_is_not_offered_again_when_anything_differs(tmp_path):
    files, clock, path = strict_files(tmp_path)
    assert files.mint("dev", "default", str(path))
    clock.now += files_module.DEFAULT_RECENT_SECONDS + 60
    assert files.mint("other", "default", str(path)) is None             # another device
    assert files.mint("dev", "another", str(path)) is None              # another profile
    other = path.with_name("other.png")
    other.write_bytes(b"\x89PNG-two")
    assert files.mint("dev", "default", str(other)) is None             # never offered
    path.write_bytes(b"\x89PNG-changed!")
    assert files.mint("dev", "default", str(path)) is None              # changed since the offer


def test_a_stale_file_is_not_offered_again_after_its_link_expired(tmp_path):
    files, clock, path = strict_files(tmp_path)
    assert files.mint("dev", "default", str(path))
    clock.now += files_module.TOKEN_LIFETIME + 1
    assert files.mint("dev", "default", str(path)) is None


def test_denies_match_the_agents_cache_credentials_and_kanban_boards(tmp_path):
    files, _, _ = make(tmp_path)
    home = tmp_path / "upstream"
    (home / "cache").mkdir()
    board = home / "kanban" / "boards" / "one"
    board.mkdir(parents=True)
    for path in (home / "cache" / "bws_cache.json", home / "cache" / "bws_cache.enc.json",
                 board / "kanban.db", board / "kanban.db-wal"):
        path.write_bytes(b"x")
        assert files._denied(path.resolve()), path


def write_config(home, text):
    home.mkdir(parents=True, exist_ok=True)
    (home / "config.yaml").write_text(text, encoding="utf-8")


def aged(path, seconds):
    stamp = time.time() - seconds
    os.utime(path, (stamp, stamp))


def test_strict_mode_comes_from_the_profile_config(tmp_path):
    files, work, _ = make(tmp_path, time.time)
    old, new = work / "old.pdf", work / "new.pdf"
    old.write_bytes(b"%PDF")
    new.write_bytes(b"%PDF")
    aged(old, 3600)
    home = tmp_path / "upstream"
    assert files.validate(str(old)) is not None
    write_config(home, "model: x\ngateway:\n  strict: true  # public bot\n")
    assert files.validate(str(old)) is None and files.validate(str(new)) is not None
    write_config(home, "gateway:\n  strict: true\n  trust_recent_files: false\n")
    assert files.validate(str(new)) is None
    write_config(home, "gateway:\n  strict: true\n  media_delivery_allow_dirs:\n    - " + str(work) + "\n")
    assert files.validate(str(old)) is not None
    write_config(home, "gateway:\n  strict: true\n  trust_recent_files_seconds: 5000\n")
    assert files.validate(str(old)) is not None
    write_config(home, "gateway:\n  strict: no\nother:\n  strict: true\n")
    assert files.validate(str(old)) is not None


def test_strict_config_of_a_named_profile_applies_to_that_profile_only(tmp_path):
    files, work, _ = make(tmp_path, time.time)
    old = work / "old.pdf"
    old.write_bytes(b"%PDF")
    aged(old, 3600)
    write_config(tmp_path / "upstream" / "profiles" / "work", "gateway:\n  strict: true\n")
    assert files.validate(str(old), "work") is None
    assert files.validate(str(old), "default") is not None
    assert files.mint("dev", "work", str(old)) is None and files.mint("dev", "default", str(old)) is not None


def test_bridge_data_folder_is_denied_even_under_an_allow_root(tmp_path, monkeypatch):
    files, _, _ = make(tmp_path)
    secret = tmp_path / "data" / "notes.json"
    secret.write_text("{}")
    monkeypatch.setenv("HERMES_MEDIA_ALLOW_DIRS", str(tmp_path))
    assert files.validate(str(secret)) is None
    monkeypatch.delenv("HERMES_MEDIA_ALLOW_DIRS")
    write_config(tmp_path / "upstream", "gateway:\n  media_delivery_allow_dirs: " + str(tmp_path) + "\n")
    assert files.validate(str(secret)) is None
    ok = tmp_path / "work" / "fine.txt"
    ok.write_text("x")
    assert files.validate(str(ok)) is not None


def test_replaced_file_with_same_size_and_time_is_changed(tmp_path):
    files, work, _ = make(tmp_path)
    path = work / "a.txt"
    path.write_bytes(b"one")
    token = link_token(files.mint("dev", "default", str(path)))
    stamp = path.stat().st_mtime_ns
    other = work / "b.txt"
    other.write_bytes(b"two")
    os.replace(other, path)
    os.utime(path, ns=(stamp, stamp))
    with pytest.raises(HTTPError) as changed:
        files.open("dev", token)
    assert (changed.value.status, changed.value.message) == (410, "file_changed")


def test_default_denies_for_credential_places_and_names(tmp_path):
    files, _, _ = make(tmp_path)
    user = tmp_path / "user"
    folders = [".ssh", ".aws", ".config/gh", ".config/gcloud", ".anything", ".docker", ".kube",
               "App" + "Data/Roaming/anything", "App" + "Data/Local/anything", "App" + "Data/Roaming/gcloud"]
    for folder in folders:
        target = user / folder / "notes.txt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("x")
        assert files.validate(str(target)) is None, folder
    work = tmp_path / "work"
    for name in ("My-Credentials-backup.txt", "aws_credential.json", "a.jks", "a.keystore", "a.pem", "a.key",
                 "a.p12", "a.pfx", "id_rsa_old.txt", "id_ed25519.txt", ".env.txt", ".env.json"):
        (work / name).write_text("x")
        assert files.validate(str(work / name)) is None, name
    for name in ("id_rsa", "id_ed25519.pub", ".env", ".env.production", "x.kdbx", "CREDENTIALS",
                 "pre-credentials-post.bin"):
        assert SECRET_NAME.match(name), name
    for folder in ("Documents", "Desktop", "Downloads", "projects/site"):
        target = user / folder / "report.pdf"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"%PDF")
        assert files.validate(str(target)) is not None, folder


def test_history_limit_is_64_mb_and_overflow_is_a_specific_error(monkeypatch):
    assert files_module.BODY_LIMIT == 64 * 1024 * 1024
    monkeypatch.setattr(files_module, "BODY_LIMIT", 10)
    reader = JsonRewriter(None, "dev", "default")
    with pytest.raises(HTTPError) as error:
        reader.feed(b"x" * 11)
    assert (error.value.status, error.value.message) == (413, "history too large")


def test_long_held_line_is_flushed_not_dropped(tmp_path, monkeypatch):
    files, work, _ = make(tmp_path)
    pdf = work / "plan.pdf"
    pdf.write_bytes(b"%PDF")
    monkeypatch.setattr(files_module, "HOLD_LIMIT", 60)
    stream = DeltaFilter(files, "dev", "default")
    out = stream.feed(f"see MEDIA:{pdf} and then some very long prose that never ends " + "z" * 80)
    assert out.startswith("see [plan.pdf (4 B)](rasul-file://") and out.endswith("z" * 80)
    assert str(work) not in out and stream.buffer == ""
    stream = DeltaFilter(files, "dev", "default")
    out = stream.feed("MEDIA:/home/user/" + "a" * 100)
    assert out == "[file not available]"
    assert stream.feed("tailofpath.pdf\nnext") == "\nnext"
    assert stream.feed("plain") == "plain"


class Sink:
    def __init__(self):
        self.data = bytearray()

    async def write(self, data):
        self.data.extend(data)


@pytest.mark.parametrize("name", ["page.html", "page.htm", "image.svg", "feed.xml"])
def test_active_content_is_served_as_an_inert_download(tmp_path, name):
    files, work, _ = make(tmp_path)
    path = work / name
    path.write_text("<script>1</script>")
    token = link_token(files.mint("dev", "default", str(path)))
    sink = Sink()
    asyncio.run(files.download(sink, "dev", token))
    head = bytes(sink.data).split(b"\r\n\r\n")[0].decode()
    assert "Content-Type: application/octet-stream" in head
    assert "Content-Security-Policy: sandbox" in head and "X-Content-Type-Options: nosniff" in head


# ---- strict reader, dot folders, odd tags, temp folder, device names ---------------

def policy_of(tmp_path, text, write=True):
    files, work, _ = make(tmp_path, time.time)
    if write:
        (tmp_path / "upstream" / "config.yaml").write_bytes(text if isinstance(text, bytes) else text.encode())
    old, new = work / "old.pdf", work / "new.pdf"
    old.write_bytes(b"%PDF")
    new.write_bytes(b"%PDF")
    aged(old, 3600)
    return files, old, new


@pytest.mark.parametrize("text", [
    "gateway:\n  strict: true\n", "gateway:\n  strict: yes\n", "gateway:\n  strict: on\n",
    "gateway:\n  strict: 1\n", 'gateway:\n  strict: "false"\n', "gateway:\n  strict: 'false'\n",
    'gateway:\n  strict: "0"\n', "gateway:\n  strict: maybe\n", "gateway:\n  strict: [false]\n",
    "gateway:\n  strict:\n    - a\n", "gateway:\n  strict:\n    deep: 1\n",
    "gateway: {strict: true}\n", "gateway: {strict: false}\n", '"gateway":\n  strict: false\n',
    "gateway:\n  strict: false\ngateway:\n  strict: false\n",
    b"\xef\xbb\xbfgateway:\n  strict: false\n", b"gateway:\n  strict: \xff\xfe\n",
    "{{{ not yaml\n", "gateway:\n\tstrict: false\n", "gateway:\n  strict: false\n bad\n",
    "gateway:\n  strict: false\n strict2\n", b"gateway:\n  strict: false\x00\n"])
def test_strict_config_fails_closed_when_on_or_not_understood(tmp_path, text):
    files, old, new = policy_of(tmp_path, text)
    assert files.validate(str(old)) is None
    assert files.validate(str(new)) is not None


@pytest.mark.parametrize("text", [
    "gateway:\n  strict: false\n", "gateway:\n  strict: False\n", "gateway:\n  strict: no\n",
    "gateway:\n  strict: off\n", "gateway:\n  strict: 0\n", "gateway:\n  strict: null\n",
    "gateway:\n  strict: ~\n", "gateway:\n  strict:\n", "gateway:\n  strict: false # note\n",
    "---\ngateway:\n  strict: false\n", "other: 1\n", "gateway:\nother:\n  strict: true\n", ""])
def test_strict_config_off_values_match_the_agent(tmp_path, text):
    files, old, _ = policy_of(tmp_path, text)
    assert files.validate(str(old)) is not None


def test_missing_config_uses_the_agent_default_and_unreadable_config_is_strict(tmp_path):
    files, old, new = policy_of(tmp_path, "", write=False)
    assert files.validate(str(old)) is not None
    (tmp_path / "upstream" / "config.yaml").mkdir()          # exists but cannot be read as a file
    assert files.validate(str(old)) is None and files.validate(str(new)) is not None


def test_trust_recent_files_uses_the_same_truthiness(tmp_path):
    for text, expected in (("trust_recent_files: 0", False), ("trust_recent_files: false", False),
                           ('trust_recent_files: "false"', True), ("trust_recent_files: yes", True),
                           ("trust_recent_files: null", False), ("other: 1", True)):
        files, _, new = policy_of(tmp_path, "gateway:\n  strict: true\n  " + text + "\n")
        assert (files.validate(str(new)) is not None) is expected, text


def test_dot_folders_of_the_home_are_denied_but_ordinary_folders_are_not(tmp_path):
    files, _, _ = make(tmp_path)
    user = tmp_path / "user"
    for folder in (".ssh", ".anything", ".hidden/deep"):
        target = user / folder / "x.pdf"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"%PDF")
        assert files.validate(str(target)) is None, folder
    for folder in ("Documents", "Desktop", "Downloads", "projects"):
        target = user / folder / "x.pdf"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"%PDF")
        assert files.validate(str(target)) is not None, folder
    plain = user / "Desktop" / "a.docx"
    plain.write_bytes(b"x")
    assert files.validate(str(plain)) is not None


def test_agent_cache_under_a_dot_home_is_still_delivered(tmp_path):
    user = tmp_path / "user"
    cache = user / ".hermes" / "cache" / "documents"
    cache.mkdir(parents=True)
    (cache / "a.pdf").write_bytes(b"%PDF")
    files = Files(user / ".hermes", tmp_path / "data")
    assert files.validate(str(cache / "a.pdf")) is not None
    other = user / ".hermes" / "cache" / "bws_cache.json"
    other.write_bytes(b"{}")
    assert files.validate(str(other)) is None


@pytest.mark.parametrize("tag", [
    'MEDIA:"C:\\x\\a.pdf', "MEDIA:`/home/u/a.pdf", "MEDIA:'/home/u/a.pdf", "MEDIA:\\\\evil\\share\\a.pdf",
    'MEDIA:"', "MEDIA:\\x", "**MEDIA:`/etc/passwd"])
def test_unfinished_or_odd_tags_never_leak_raw_text(tmp_path, tag):
    files, _, _ = make(tmp_path)
    assert files.rewrite("dev", "default", "a " + tag + " tail\nnext") == "a [file not available]\nnext"
    stream = DeltaFilter(files, "dev", "default")
    out = stream.feed("before " + tag + "x" * 6000) + stream.feed(" more\nnext line") + stream.flush()
    assert out == "before [file not available]\nnext line"
    assert "evil" not in out and "passwd" not in out


def test_temp_folder_files_are_delivered_only_when_fresh_and_not_secret(tmp_path, monkeypatch):
    files, _, _ = make(tmp_path, time.time)
    temp = tmp_path / "user" / ("App" + "Data") / "Local" / "Temp"
    temp.mkdir(parents=True)
    monkeypatch.setattr(tempfile, "gettempdir", lambda: str(temp))
    fresh, old, secret = temp / "fresh.pdf", temp / "old.pdf", temp / "token.pem"
    for path in (fresh, old, secret):
        path.write_bytes(b"x")
    aged(old, 3600)
    assert files.validate(str(fresh)) is not None
    assert files.validate(str(old)) is None and files.validate(str(secret)) is None
    sibling = temp.parent / "other" / "fresh.pdf"
    sibling.parent.mkdir()
    sibling.write_bytes(b"x")
    assert files.validate(str(sibling)) is None
    write_config(tmp_path / "upstream", "gateway:\n  trust_recent_files: false\n")
    assert files.validate(str(fresh)) is None
    write_config(tmp_path / "upstream", "gateway:\n  trust_recent_files_seconds: 5000\n")
    assert files.validate(str(old)) is not None


def scratch_setup(tmp_path, monkeypatch):
    """A Hermes home inside the fake per-user data folder, with the system temp folder somewhere else."""
    local = tmp_path / "user" / ("App" + "Data") / "Local"
    home = local / "hermes"
    (home / "profiles" / "admin").mkdir(parents=True)
    system_temp = tmp_path / "systemtemp"
    system_temp.mkdir()
    monkeypatch.setattr(tempfile, "gettempdir", lambda: str(system_temp))
    (tmp_path / "data").mkdir()
    return Files(home, tmp_path / "data", None, time.time), home, local


def scratch_file(folder, name="shot.png"):
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / name
    path.write_bytes(b"x")
    return path


def test_fresh_files_in_the_agent_scratch_folder_are_delivered(tmp_path, monkeypatch):
    files, home, _ = scratch_setup(tmp_path, monkeypatch)
    named = scratch_file(home / "profiles" / "admin" / "cache" / "scratch")
    default = scratch_file(home / "cache" / "scratch")
    assert files.validate(str(named), "admin") is not None
    assert files.validate(str(default)) is not None
    assert files.mint("dev", "admin", str(named)) is not None


def test_scratch_files_follow_the_recency_window_and_the_other_rules(tmp_path, monkeypatch):
    files, home, local = scratch_setup(tmp_path, monkeypatch)
    scratch = home / "profiles" / "admin" / "cache" / "scratch"
    old, secret = scratch_file(scratch, "old.png"), scratch_file(scratch, "token.pem")
    aged(old, 3600)
    assert files.validate(str(old), "admin") is None and files.validate(str(secret), "admin") is None
    # only scratch is lifted: the agent's other cache folders and other data folders stay refused
    for other in (home / "profiles" / "admin" / "cache" / "terminal", home / "profiles" / "admin" / "browser-profile",
                  local / "other"):
        assert files.validate(str(scratch_file(other)), "admin") is None, other
    # no recency trust, no scratch files
    fresh = scratch_file(scratch, "fresh.png")
    assert files.validate(str(fresh), "admin") is not None
    write_config(home / "profiles" / "admin", "gateway:\n  trust_recent_files: false\n")
    assert files.validate(str(fresh), "admin") is None


def test_a_link_out_of_scratch_to_a_credential_file_is_refused(tmp_path, monkeypatch):
    files, home, _ = scratch_setup(tmp_path, monkeypatch)
    link = home / "profiles" / "admin" / "cache" / "scratch" / "innocent.txt"
    link.parent.mkdir(parents=True)
    secret = home / "profiles" / "admin" / ".env"
    secret.write_text("KEY=1")
    try:
        os.symlink(secret, link)
    except (OSError, NotImplementedError):
        pytest.skip("symbolic links unavailable")
    assert files.validate(str(link), "admin") is None


def test_data_folders_fall_back_to_the_home_when_the_env_names_are_unset(tmp_path, monkeypatch):
    for name in ("APP" + "DATA", "LOCAL" + "APP" + "DATA"):
        monkeypatch.delenv(name, raising=False)
    files, _, _ = make(tmp_path)
    for sub in ("Roaming", "Local"):
        target = tmp_path / "user" / ("App" + "Data") / sub / "thing" / "a.pdf"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"%PDF")
        assert files.validate(str(target)) is None, sub


@pytest.mark.parametrize("raw", ["C:\\x\\COM1.pdf", "C:\\x\\con.txt", "C:/x/NUL.csv", "C:\\x\\Lpt9.zip",
                                 "C:\\x\\aux.tar.gz", "C:\\x\\PRN .pdf", "C:\\x\\COM0.pdf", "/x/prn/a.pdf"])
def test_reserved_device_names_are_refused_before_any_filesystem_call(tmp_path, monkeypatch, raw):
    files, _, events = make(tmp_path)
    with monkeypatch.context() as patched:
        for target in (os, "stat"), (os.path, "realpath"), (Path, "resolve"), (Path, "stat"), (Path, "is_file"):
            patched.setattr(*target, boom)
        assert files.validate(raw) is None and files.mint("dev", "default", raw) is None
    assert not events


def test_names_that_only_resemble_device_names_are_fine(tmp_path):
    files, work, _ = make(tmp_path)
    for name in ("console.pdf", "com10.pdf", "auxiliary.txt", "nullable.csv"):
        (work / name).write_bytes(b"x")
        assert files.validate(str(work / name)) is not None, name


# ---- links into dot folders, network drives, strict spellings, names ----------------

def make_dir_link(link, target):
    """A directory symlink, or a junction on Windows; skips the test when neither is allowed."""
    try:
        os.symlink(target, link, target_is_directory=True)
        return
    except (OSError, NotImplementedError):
        pass
    try:
        import _winapi
        _winapi.CreateJunction(str(target), str(link))
    except (ImportError, OSError):
        pytest.skip("cannot create a directory link here")


def drop_dir_link(link):
    try:
        os.rmdir(link)
    except OSError:
        pass


def test_dot_folder_reached_through_a_link_is_denied(tmp_path):
    files, _, _ = make(tmp_path)
    user, outside = tmp_path / "user", tmp_path / "elsewhere"
    user.mkdir(exist_ok=True)
    outside.mkdir()
    (outside / "x.pdf").write_bytes(b"%PDF")
    (user / "Documents").mkdir()
    (user / "Documents" / "ok.pdf").write_bytes(b"%PDF")
    link = user / ".config"
    make_dir_link(link, outside)
    try:
        assert files.validate(str(link / "x.pdf")) is None
        assert files.validate(str(outside / "x.pdf")) is None
        assert files.validate(str(user / "Documents" / "ok.pdf")) is not None
    finally:
        drop_dir_link(link)


def test_home_that_cannot_be_resolved_denies_everything_under_it(tmp_path, monkeypatch):
    files, _, _ = make(tmp_path)
    user = tmp_path / "user"
    (user / "Documents").mkdir(parents=True)
    target = user / "Documents" / "ok.pdf"
    target.write_bytes(b"%PDF")
    real = files_module._resolve

    def failing(path, strict=False):
        return None if path == user else real(path, strict)
    monkeypatch.setattr(files_module, "_resolve", failing)
    assert files.validate(str(target)) is None


@pytest.fixture
def remote_drive(monkeypatch):
    monkeypatch.setattr(files_module, "_DRIVE_CACHE", {})
    monkeypatch.setattr(files_module, "_drive_type", lambda letter: 4 if letter.upper() == "Z" else 3)


def test_mapped_network_drive_is_refused_before_any_filesystem_call(tmp_path, monkeypatch, remote_drive):
    files, _, events = make(tmp_path)
    with monkeypatch.context() as patched:
        for target in (os, "stat"), (os.path, "realpath"), (Path, "resolve"), (Path, "stat"), (Path, "is_file"):
            patched.setattr(*target, boom)
        for raw in ("Z:\\docs\\a.pdf", "z:/docs/a.pdf"):
            assert files.validate(raw) is None and files.mint("dev", "default", raw) is None
    assert not events


def test_drive_type_is_asked_once_per_letter(monkeypatch):
    calls = []
    monkeypatch.setattr(files_module, "_DRIVE_CACHE", {})
    monkeypatch.setattr(files_module, "_drive_type", lambda letter: calls.append(letter) or 3)
    for _ in range(3):
        assert files_module.remote_drive("Q:\\a.pdf") is False
    assert calls == ["Q"]


def test_path_that_resolves_to_a_network_share_is_refused(tmp_path, monkeypatch):
    files, _, _ = make(tmp_path)
    monkeypatch.setattr(files_module, "_resolve", lambda path, strict=False: Path("\\\\host\\s\\a.pdf"))
    monkeypatch.setattr(Path, "is_file", boom)
    assert files.validate(str(tmp_path / "work" / "a.pdf")) is None


def test_path_that_resolves_onto_a_remote_drive_is_refused(tmp_path, monkeypatch, remote_drive):
    files, _, _ = make(tmp_path)
    monkeypatch.setattr(files_module, "_resolve", lambda path, strict=False: Path("Z:\\a.pdf"))
    monkeypatch.setattr(Path, "is_file", boom)
    assert files.validate(str(tmp_path / "work" / "a.pdf")) is None


@pytest.mark.parametrize("value", [
    "false", "False", "FALSE", "no", "No", "NO", "off", "Off", "OFF", "0", "null", "Null", "NULL", "~", "",
    "FaLsE", "fALSE", "nO", "oFf", "NuLL", "nULL", "00", "-0", "+0", "0x0", "0b0", "0.0", ".0", "0e0", "0_0",
    "true", "yes", "on", "1", "maybe", "[false]", "0o0", "0:00"])
def test_strict_flag_truthiness_matches_the_yaml_parser(value):
    yaml = pytest.importorskip("yaml")
    expected = bool(yaml.safe_load(f"strict: {value}")["strict"])
    assert files_module._on(value) is expected, value


@pytest.mark.parametrize("text", [
    "gateway:\n  strict: FaLsE\n", "gateway:\n  strict: false\n    more words\n",
    "gateway:\n  strict: false\n      - x\n", "gateway:\n  base: &b\n    strict: true\n  <<: *b\n",
    "gateway:\n  <<: {strict: true}\n"])
def test_strict_config_odd_forms_are_strict(tmp_path, text):
    files, old, new = policy_of(tmp_path, text)
    assert files.validate(str(old)) is None
    assert files.validate(str(new)) is not None


@pytest.mark.parametrize("name", ["my_oauth.json", "OAuth-session.JSON", "x.token.json", "Access_TOKEN.json",
                                  "refresh-token-2.json"])
def test_oauth_and_token_json_files_are_denied(tmp_path, name):
    files, work, _ = make(tmp_path)
    (work / name).write_bytes(b"{}")
    assert files.validate(str(work / name)) is None
    (work / "tokens.pdf").write_bytes(b"%PDF")
    (work / "notes.json").write_bytes(b"{}")
    assert files.validate(str(work / "tokens.pdf")) is not None
    assert files.validate(str(work / "notes.json")) is not None


@pytest.mark.parametrize("raw", ["C:\\x\\COM\u00b9.pdf", "C:\\x\\com\u00b2.txt", "C:/x/LPT\u00b3.csv",
                                 "C:\\x\\LPT\u00b9.zip", "C:\\x\\COM\u00b3.tar.gz", "C:\\x\\LPT\u00b2.pdf"])
def test_superscript_device_names_are_refused_before_any_filesystem_call(tmp_path, monkeypatch, raw):
    files, _, _ = make(tmp_path)
    with monkeypatch.context() as patched:
        for target in (os, "stat"), (os.path, "realpath"), (Path, "resolve"), (Path, "stat"), (Path, "is_file"):
            patched.setattr(*target, boom)
        assert files.validate(raw) is None


# ---- environment without a home, float spelling, cached lookups ------------------------------

def test_no_home_variables_still_refuses_agent_credential_files(tmp_path, monkeypatch):
    for name in ("USERPROFILE", "HOMEPATH", "HOMEDRIVE", "HOME"):
        monkeypatch.delenv(name, raising=False)
    files, work, _ = make(tmp_path)
    (files.home / "auth.json").write_text("{}")
    (files.home / "config.yaml").write_text("model: x\n")
    (work / "ok.pdf").write_bytes(b"%PDF")
    assert files.validate(str(files.home / "auth.json")) is None
    assert files.validate(str(files.home / "config.yaml")) is None
    assert files.validate(str(work / "ok.pdf")) is not None


def test_relative_home_counts_as_unresolved(tmp_path, monkeypatch):
    files, work, _ = make(tmp_path)
    (files.home / "auth.json").write_text("{}")
    (work / "ok.pdf").write_bytes(b"%PDF")
    monkeypatch.setattr(os.path, "expanduser", lambda p: "relhome" if p == "~" else p)
    assert files.validate(str(files.home / "auth.json")) is None
    assert files.validate(str(work / "ok.pdf")) is not None


@pytest.mark.parametrize("value", ["._", "._0", ".__"])
def test_underscore_only_fraction_is_not_a_number(value):
    assert files_module._on(value) is True


def test_drive_type_answer_expires_after_a_minute(monkeypatch):
    now, calls = [1000.0], []
    monkeypatch.setattr(files_module, "_DRIVE_CACHE", {})
    monkeypatch.setattr(files_module.time, "monotonic", lambda: now[0])
    answers = iter([3, 4])
    monkeypatch.setattr(files_module, "_drive_type", lambda letter: calls.append(letter) or next(answers))
    assert files_module.remote_drive("Q:\a.pdf") is False
    now[0] += 59
    assert files_module.remote_drive("Q:\a.pdf") is False and calls == ["Q"]
    now[0] += 2
    assert files_module.remote_drive("Q:\a.pdf") is True and calls == ["Q", "Q"]


def test_home_dot_entries_are_resolved_once_a_minute(tmp_path, monkeypatch):
    now = [5000.0]
    monkeypatch.setattr(files_module.time, "monotonic", lambda: now[0])
    files, work, _ = make(tmp_path)
    user = tmp_path / "user"
    (user / ".mytool").mkdir(parents=True)
    (work / "ok.pdf").write_bytes(b"%PDF")
    seen = []
    real = files_module._resolve
    monkeypatch.setattr(files_module, "_resolve",
                        lambda p, strict=False: seen.append(p.name) or real(p, strict))
    assert files.validate(str(work / "ok.pdf")) is not None
    first = seen.count(".mytool")
    assert first >= 1
    assert files.validate(str(work / "ok.pdf")) is not None
    assert seen.count(".mytool") == first
    now[0] += 61
    assert files.validate(str(work / "ok.pdf")) is not None
    assert seen.count(".mytool") > first


def test_dot_entry_cache_ignores_the_wall_clock(tmp_path, monkeypatch):
    clock = Clock()
    now = [5000.0]
    monkeypatch.setattr(files_module.time, "monotonic", lambda: now[0])
    files, work, _ = make(tmp_path, clock)
    (tmp_path / "user" / ".mytool").mkdir(parents=True)
    (work / "ok.pdf").write_bytes(b"%PDF")
    seen = []
    real = files_module._resolve
    monkeypatch.setattr(files_module, "_resolve",
                        lambda p, strict=False: seen.append(p.name) or real(p, strict))
    assert files.validate(str(work / "ok.pdf")) is not None
    first = seen.count(".mytool")
    clock.now += 3600
    assert files.validate(str(work / "ok.pdf")) is not None
    assert seen.count(".mytool") == first


def test_failed_home_listing_is_not_cached_and_denies_under_the_home(tmp_path, monkeypatch):
    files, work, _ = make(tmp_path)
    user = tmp_path / "user"
    (user / "Documents").mkdir(parents=True)
    (user / "Documents" / "ok.pdf").write_bytes(b"%PDF")
    (work / "ok.pdf").write_bytes(b"%PDF")
    real = Path.iterdir
    broken = [True]

    def listing(self):
        if broken[0] and self == user:
            raise OSError("no listing")
        return real(self)
    monkeypatch.setattr(Path, "iterdir", listing)
    assert files.validate(str(user / "Documents" / "ok.pdf")) is None
    assert files.validate(str(work / "ok.pdf")) is not None
    broken[0] = False
    assert files.validate(str(user / "Documents" / "ok.pdf")) is not None


def test_dot_folder_made_after_the_cache_was_filled_is_refused_at_once(tmp_path):
    files, work, _ = make(tmp_path)
    user, outside = tmp_path / "user", tmp_path / "elsewhere"
    user.mkdir(exist_ok=True)
    outside.mkdir()
    (outside / "a.pdf").write_bytes(b"%PDF")
    (work / "ok.pdf").write_bytes(b"%PDF")
    assert files.validate(str(work / "ok.pdf")) is not None
    (user / ".realdot").mkdir(parents=True)
    (user / ".realdot" / "r.pdf").write_bytes(b"%PDF")
    assert files.validate(str(user / ".realdot" / "r.pdf")) is None
    link = user / ".newdot"
    make_dir_link(link, outside)
    try:
        assert files.validate(str(link / "a.pdf")) is None
    finally:
        drop_dir_link(link)


def test_serve_check_lists_the_home_afresh(tmp_path):
    files, work, _ = make(tmp_path)
    user, outside = tmp_path / "user", tmp_path / "elsewhere"
    user.mkdir(exist_ok=True)
    outside.mkdir()
    (outside / "a.pdf").write_bytes(b"%PDF")
    out = files.rewrite("dev", "default", f"MEDIA:{(outside / 'a.pdf').as_posix()}")
    token = re.search(r"rasul-file://([A-Za-z0-9_-]{43})", out).group(1)
    link = user / ".cfg"
    make_dir_link(link, outside)
    try:
        with pytest.raises(HTTPError) as error:
            files.open("dev", token)
        assert error.value.status == 410
    finally:
        drop_dir_link(link)


@pytest.mark.skipif(os.name != "nt", reason="drive-less home paths exist on Windows only")
def test_drive_less_home_still_applies_the_home_rules(tmp_path, monkeypatch):
    user = tmp_path / "user"
    drive, rest = os.path.splitdrive(str(user))
    if os.path.abspath(rest) != str(user):
        pytest.skip("temporary folder is not on the current drive")
    for name in ("USERPROFILE", "HOMEDRIVE", "HOME"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("HOMEPATH", rest)
    files, _, _ = make(tmp_path)
    for folder in (".config", ".x"):
        target = user / folder / "x.pdf"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"%PDF")
        assert files.validate(str(target)) is None, folder
    (user / "Documents").mkdir()
    (user / "Documents" / "ok.pdf").write_bytes(b"%PDF")
    assert files.validate(str(user / "Documents" / "ok.pdf")) is not None
