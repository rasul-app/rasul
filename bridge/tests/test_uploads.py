import asyncio
import errno
import hashlib
import json
import os
import re
import shutil
import time
from pathlib import Path

import pytest

from rasul_bridge import uploads as uploads_module
from rasul_bridge.http import HTTPError, headers
from rasul_bridge.proxy import MEDIA_NOTE, response_body
from rasul_bridge.uploads import CHUNK, MAX_FILE, Uploads, clean_name, document_note

from test_bridge import asynchronous, client, registered, running, send


async def call(link, reader, method, target, value=None):
    await send(link, method, target, value)
    raw = (await reader.until(b"\r\n\r\n")).split(b"\r\n")
    head = headers(raw[1:])
    data = bytearray()
    async for part in response_body(reader, head):
        data.extend(part)
    return int(raw[0].split()[1]), (json.loads(data) if data else None)


def sha(data):
    return hashlib.sha256(data).hexdigest()


async def begin(link, reader, name, size, profile="default", kind=""):
    return await call(link, reader, "POST", f"/v1/profiles/{profile}/uploads",
                      {"name": name, "size": size, "type": kind})


async def upload(link, reader, name, data, profile="default", kind=""):
    status, started = await begin(link, reader, name, len(data), profile, kind)
    assert status == 201 and started["chunk_size"] == CHUNK
    for index in range(0, len(data), CHUNK):
        status, _ = await call(link, reader, "PUT", f"/v1/uploads/{started['upload']}/chunks/{index // CHUNK}",
                               data[index:index + CHUNK])
        assert status == 204
    return await call(link, reader, "POST", f"/v1/uploads/{started['upload']}/complete", {"sha256": sha(data)})


def documents(tmp_path, profile="default"):
    home = tmp_path / "upstream" if profile == "default" else tmp_path / "upstream" / "profiles" / profile
    return home / "cache" / "documents"


def runs(upstream):
    return [r for r in upstream.requests if r[1] == "/v1/runs" or r[1].endswith("/v1/runs")]


async def start_run(link, reader, body, profile="default"):
    return await call(link, reader, "POST", f"/v1/profiles/{profile}/hermes/v1/runs", body)


def add_profile(bridge, tmp_path, name="work"):
    directory = tmp_path / "upstream" / "profiles" / name
    directory.mkdir(parents=True)
    bridge.profiles.init_key(name, directory)


class Clock:
    now = 1_000_000.0

    def __call__(self):
        return self.now


@asynchronous
async def test_chunks_become_a_file_in_the_document_cache(tmp_path):
    data = os.urandom(CHUNK * 2 + 17)
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            status, done = await upload(link, reader, "تقرير نهائي.pdf", data)
        assert status == 200 and set(done) == {"file", "name", "size"}
        assert re.fullmatch(r"[0-9a-f]{32}", done["file"]) and done["name"] == "تقرير نهائي.pdf"
        assert done["size"] == len(data) and str(tmp_path) not in json.dumps(done)
        (saved,) = documents(tmp_path).iterdir()
        assert re.fullmatch(r"rasul_[0-9a-f]{8}_تقرير نهائي\.pdf", saved.name)
        assert saved.read_bytes() == data
        assert not any((bridge.store.root / "uploads").iterdir())
        for path in bridge.store.root.rglob("*"):
            if path.is_file():
                assert "تقرير".encode() not in path.read_bytes(), path


@asynchronous
async def test_repeating_the_last_chunk_is_accepted(tmp_path):
    data = os.urandom(CHUNK + 5)
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, started = await begin(link, reader, "a.bin", len(data))
            target = f"/v1/uploads/{started['upload']}/chunks/"
            assert (await call(link, reader, "PUT", target + "0", data[:CHUNK]))[0] == 204
            assert (await call(link, reader, "PUT", target + "0", data[:CHUNK]))[0] == 204
            assert (await call(link, reader, "PUT", target + "0", b"x" * CHUNK))[0] == 409
            assert (await call(link, reader, "PUT", target + "1", data[CHUNK:]))[0] == 204
            assert (await call(link, reader, "PUT", target + "1", data[CHUNK:]))[0] == 204
            status, done = await call(link, reader, "POST", f"/v1/uploads/{started['upload']}/complete",
                                      {"sha256": sha(data)})
        assert status == 200
        (saved,) = documents(tmp_path).iterdir()
        assert saved.read_bytes() == data


@asynchronous
async def test_run_gets_one_note_per_file_before_the_text_and_loses_the_field(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, text = await upload(link, reader, "notes.txt", b"hello", kind="text/plain")
            _, binary = await upload(link, reader, "plan.pdf", b"%PDF-1.4 data", kind="application/pdf")
            before = len(runs(upstream))
            status, _ = await start_run(link, reader, {"input": "summarise", "model": "m",
                                                       "rasul_files": [text["file"], binary["file"]]})
            assert status == 200
            sent = json.loads(runs(upstream)[before][3])
            saved = {p.name.split("_", 2)[2]: p for p in documents(tmp_path).iterdir()}
            first, second = saved["notes.txt"], saved["plan.pdf"]
            assert sent["input"] == (document_note("notes.txt", first, True) + "\n\n"
                                     + document_note("plan.pdf", second, False) + "\n\nsummarise")
            assert sent["input"].startswith(
                "[The user sent a text document: 'notes.txt'. It is saved at: " + str(first)
                + ". Its content is not inlined here. Read the cached file yourself before answering "
                "when the user's request involves its contents.]\n\n[The user sent a document: 'plan.pdf'. "
                "It is saved at: " + str(second) + ". Its text is not inlined here (it's a binary format such "
                "as PDF or DOCX). To read it, extract the document's text yourself — for example with the "
                "terminal tool or the ocr-and-documents skill — before answering, instead of asking the "
                "user to paste the contents.]\n\nsummarise")
            assert "rasul_files" not in sent and sent["model"] == "m"
            assert sent["instructions"] == MEDIA_NOTE        # the media note still applies
            # A message list: the notes go first in the last message, as text or as a leading part.
            for content in ("look", [{"type": "text", "text": "look"}]):
                await start_run(link, reader, {"input": [{"role": "user", "content": content}],
                                               "rasul_files": [text["file"]]})
                message = json.loads(runs(upstream)[-1][3])["input"][0]["content"]
                note = document_note("notes.txt", first, True)
                assert message == (note + "\n\nlook" if isinstance(content, str)
                                   else [{"type": "text", "text": note}] + content)


@asynchronous
async def test_run_without_files_and_bad_shapes(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, done = await upload(link, reader, "a.txt", b"x")
            before = len(runs(upstream))
            for body in ({"rasul_files": [done["file"]]}, {"input": 5, "rasul_files": [done["file"]]},
                         {"input": [{"role": "user"}], "rasul_files": [done["file"]]}):
                status, value = await start_run(link, reader, body)
                assert (status, value) == (400, {"error": "invalid_run_input"})
            assert len(runs(upstream)) == before


@asynchronous
async def test_other_device_and_other_profile_cannot_use_an_upload_or_file(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        add_profile(bridge, tmp_path)
        identity, _ = registered(bridge)
        stranger, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, started = await begin(link, reader, "a.bin", 3)
            _, done = await upload(link, reader, "b.bin", b"abc")
            async with client(bridge, tmp_path, stranger) as (other, other_reader, _):
                key = started["upload"]
                assert (await call(other, other_reader, "PUT", f"/v1/uploads/{key}/chunks/0", b"abc")) == (
                    404, {"error": "upload_not_found"})
                assert (await call(other, other_reader, "POST", f"/v1/uploads/{key}/complete",
                                   {"sha256": sha(b"abc")}))[1] == {"error": "upload_not_found"}
                assert (await call(other, other_reader, "DELETE", f"/v1/uploads/{key}"))[0] == 204
                before = len(runs(upstream))
                assert await start_run(other, other_reader, {"input": "x", "rasul_files": [done["file"]]}) == (
                    400, {"error": "file_unavailable"})
            # the stranger's delete did not remove the owner's upload
            assert (await call(link, reader, "PUT", f"/v1/uploads/{key}/chunks/0", b"abc"))[0] == 204
            assert await start_run(link, reader, {"input": "x", "rasul_files": [done["file"]]},
                                   profile="work") == (400, {"error": "file_unavailable"})
            assert len(runs(upstream)) == before
            assert (await start_run(link, reader, {"input": "x", "rasul_files": [done["file"]]}))[0] == 200


@asynchronous
async def test_unfinished_uploads_expire_after_an_hour_and_file_ids_after_a_day(tmp_path):
    clock = Clock()
    async with running(tmp_path) as (bridge, upstream):
        bridge.uploads.clock = clock
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, started = await begin(link, reader, "a.bin", 3)
            _, done = await upload(link, reader, "b.bin", b"abc")
            part = bridge.store.root / "uploads" / started["upload"]
            assert part.exists()
            clock.now += 3599
            assert (await start_run(link, reader, {"input": "x", "rasul_files": [done["file"]]}))[0] == 200
            clock.now += 2
            await asyncio.sleep(0.5)                       # the background sweep removes it by itself
            assert not part.exists()
            assert (await call(link, reader, "PUT", f"/v1/uploads/{started['upload']}/chunks/0", b"abc")) == (
                404, {"error": "upload_not_found"})
            assert not part.exists()
            clock.now += 86400 - 3601 + 1
            before = len(runs(upstream))
            assert await start_run(link, reader, {"input": "x", "rasul_files": [done["file"]]}) == (
                400, {"error": "file_unavailable"})
            assert len(runs(upstream)) == before


@asynchronous
async def test_file_that_changed_or_vanished_is_refused(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            ids = [(await upload(link, reader, f"f{i}.bin", b"abcd"))[1]["file"] for i in range(3)]
            before = len(runs(upstream))
            by_name = {p.name.split("_", 2)[2]: p for p in documents(tmp_path).iterdir()}
            by_name["f0.bin"].write_bytes(b"abcde")                     # size changed
            stat = by_name["f1.bin"].stat()
            os.utime(by_name["f1.bin"], ns=(stat.st_atime_ns, stat.st_mtime_ns + 5_000_000_000))  # touched
            by_name["f2.bin"].unlink()                                  # gone
            for file_id in ids:
                assert await start_run(link, reader, {"input": "x", "rasul_files": [file_id]}) == (
                    400, {"error": "file_unavailable"})
            assert len(runs(upstream)) == before


@asynchronous
async def test_bad_chunks_are_refused(tmp_path):
    data = os.urandom(CHUNK + 10)
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, started = await begin(link, reader, "a.bin", len(data))
            target = f"/v1/uploads/{started['upload']}/chunks/"
            for bad in (data[:CHUNK - 1], data[:10], b"", data[:CHUNK] + b"x"):
                assert (await call(link, reader, "PUT", target + "0", bad)) == (400, {"error": "invalid_chunk"})
            assert (await call(link, reader, "PUT", target + "1", data[CHUNK:])) == (
                409, {"error": "chunk_out_of_order"})
            assert (await call(link, reader, "PUT", target + "0", data[:CHUNK]))[0] == 204
            assert (await call(link, reader, "PUT", target + "2", data[CHUNK:]))[0] == 409
            assert (await call(link, reader, "PUT", target + "1", data[CHUNK:CHUNK + 9])) == (
                400, {"error": "invalid_chunk"})
            assert (await call(link, reader, "POST", f"/v1/uploads/{started['upload']}/complete",
                               {"sha256": sha(data)})) == (409, {"error": "upload_incomplete"})
            assert (await call(link, reader, "PUT", target + "1", data[CHUNK:]))[0] == 204
            assert (await call(link, reader, "PUT", target + "2", b"x"))[0] == 400    # past the size
            assert (await call(link, reader, "POST", f"/v1/uploads/{started['upload']}/complete",
                               {"sha256": sha(data)}))[0] == 200


@asynchronous
async def test_wrong_checksum_deletes_the_data(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, started = await begin(link, reader, "a.bin", 3)
            key = started["upload"]
            await call(link, reader, "PUT", f"/v1/uploads/{key}/chunks/0", b"abc")
            assert (await call(link, reader, "POST", f"/v1/uploads/{key}/complete",
                               {"sha256": sha(b"abd")})) == (400, {"error": "checksum_mismatch"})
            assert not any((bridge.store.root / "uploads").iterdir()) and not documents(tmp_path).exists()
            assert (await call(link, reader, "POST", f"/v1/uploads/{key}/complete",
                               {"sha256": sha(b"abc")}))[0] == 404
            for bad in ({}, {"sha256": "ABC"}, {"sha256": sha(b"a").upper()}, {"sha256": sha(b"a"), "x": 1}):
                _, started = await begin(link, reader, "a.bin", 3)
                assert (await call(link, reader, "POST", f"/v1/uploads/{started['upload']}/complete", bad)) == (
                    400, {"error": "invalid_upload"})


@pytest.mark.parametrize("value,status,code", [
    ({"name": "a", "size": MAX_FILE + 1, "type": ""}, 413, "file_too_large"),
    ({"name": "a", "size": 0, "type": ""}, 400, "invalid_upload"),
    ({"name": "a", "size": -5, "type": ""}, 400, "invalid_upload"),
    ({"name": "a", "size": True, "type": ""}, 400, "invalid_upload"),
    ({"name": "a", "size": "5", "type": ""}, 400, "invalid_upload"),
    ({"name": 5, "size": 5, "type": ""}, 400, "invalid_upload"),
    ({"size": 5, "type": ""}, 400, "invalid_upload"),
    ({"name": "a" * 2000, "size": 5}, 400, "invalid_upload"),
    ({"name": "a", "size": 5, "type": 5}, 400, "invalid_upload"),
    ({"name": "a", "size": 5, "extra": 1}, 400, "invalid_upload"),
    ([1], 400, "invalid_upload"),
])
@asynchronous
async def test_bad_start_requests(tmp_path, value, status, code):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            assert await call(link, reader, "POST", "/v1/profiles/default/uploads", value) == (
                status, {"error": code})
    assert not (tmp_path / "upstream" / "cache").exists()


@asynchronous
async def test_the_largest_size_is_accepted_and_a_fifth_open_upload_is_not(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        other, _ = registered(bridge)
        bridge.uploads.disk_free = lambda path: 10 ** 12
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            keys = []
            for _ in range(4):
                status, started = await begin(link, reader, "a.bin", MAX_FILE)
                assert status == 201
                keys.append(started["upload"])
            assert await begin(link, reader, "a.bin", 1) == (429, {"error": "too_many_uploads"})
            async with client(bridge, tmp_path, other) as (second, second_reader, _):
                assert (await begin(second, second_reader, "a.bin", 1))[0] == 201     # per device
            assert (await call(link, reader, "DELETE", f"/v1/uploads/{keys[0]}"))[0] == 204
            assert (await begin(link, reader, "a.bin", 1))[0] == 201


@asynchronous
async def test_not_enough_disk_space(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            bridge.uploads.disk_free = lambda path: 1000 + 1024 ** 3 - 1
            assert await begin(link, reader, "a.bin", 1000) == (507, {"error": "disk_full"})
            bridge.uploads.disk_free = lambda path: 1000 + 1024 ** 3
            assert (await begin(link, reader, "a.bin", 1000))[0] == 201
            assert len(bridge.uploads.open) == 1


@pytest.mark.parametrize("raw,cleaned", [
    ("../x", "x"), ("..\\..\\evil.exe", "evil.exe"), ("/etc/passwd", "etcpasswd"), ("CON.txt", "_CON.txt"),
    ("nul", "_nul"), ("com1.tar.gz", "_com1.tar.gz"), ("a\x00b\x1f\x7f.txt", "ab.txt"), ("", "file"),
    ("  ..  ", "file"), ("report. ", "report"), ("a:b.txt", "ab.txt"), ("x.txt:stream", "x.txtstream"),
    ("‮gpj.exe", "gpj.exe"), ("ملف  مهم.docx", "ملف  مهم.docx"), ("con", "_con"), ("console.txt", "console.txt"),
])
def test_names_are_cleaned(raw, cleaned):
    assert clean_name(raw) == cleaned


def test_long_names_keep_the_extension():
    name = clean_name("a" * 300 + ".pdf")
    assert len(name) == 120 and name.endswith("aaa.pdf")
    assert len(clean_name("b" * 300)) == 120
    assert len(clean_name("ب" * 300 + "." + "x" * 40)) == 120


@asynchronous
async def test_a_dirty_name_stays_inside_the_document_cache(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            status, done = await upload(link, reader, "../../x", b"abc")
        assert status == 200 and done["name"] == "x"
        (saved,) = documents(tmp_path).iterdir()
        assert saved.parent == documents(tmp_path) and saved.name.endswith("_x")
        assert not (tmp_path / "x").exists() and not (tmp_path / "upstream" / "x").exists()


@asynchronous
async def test_more_than_four_ids_or_none_or_unknown_are_refused(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            ids = [(await upload(link, reader, f"f{i}.txt", b"a"))[1]["file"] for i in range(5)]
            before = len(runs(upstream))
            for files in (ids, [], "x", None, [5], ["0" * 32], [ids[0], "0" * 32]):
                assert await start_run(link, reader, {"input": "x", "rasul_files": files}) == (
                    400, {"error": "file_unavailable"})
            assert len(runs(upstream)) == before
            assert (await start_run(link, reader, {"input": "x", "rasul_files": ids[:4]}))[0] == 200


@asynchronous
async def test_device_and_profile_are_required(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        for method, target in (("POST", "/v1/profiles/default/uploads"),
                               ("PUT", "/v1/uploads/" + "0" * 32 + "/chunks/0"),
                               ("POST", "/v1/uploads/" + "0" * 32 + "/complete"),
                               ("DELETE", "/v1/uploads/" + "0" * 32)):
            async with client(bridge, tmp_path) as (link, reader, _):      # no device certificate
                assert (await call(link, reader, method, target, {"name": "a", "size": 1}))[0] == 403
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            assert await begin(link, reader, "a.bin", 3, profile="nobody") == (404, {"error": "Profile not found"})
            assert (await call(link, reader, "PUT", "/v1/uploads/" + "0" * 32 + "/chunks/0", b"a"))[0] == 404
            assert (await call(link, reader, "PUT", "/v1/uploads/" + "0" * 32 + "/chunks/01", b"a"))[0] == 404
            assert not bridge.uploads.open


@asynchronous
async def test_delete_removes_the_partial_data(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            _, started = await begin(link, reader, "a.bin", CHUNK + 1)
            key = started["upload"]
            await call(link, reader, "PUT", f"/v1/uploads/{key}/chunks/0", b"x" * CHUNK)
            part = bridge.store.root / "uploads" / key
            assert part.stat().st_size == CHUNK
            assert (await call(link, reader, "DELETE", f"/v1/uploads/{key}"))[0] == 204
            assert not part.exists() and not bridge.uploads.open
            assert (await call(link, reader, "DELETE", f"/v1/uploads/{key}"))[0] == 204
            assert (await call(link, reader, "PUT", f"/v1/uploads/{key}/chunks/1", b"x"))[0] == 404


def test_unfinished_data_from_before_a_restart_is_removed(tmp_path):
    (tmp_path / "uploads").mkdir()
    (tmp_path / "uploads" / ("a" * 32)).write_bytes(b"left over")
    Uploads(tmp_path, lambda profile: tmp_path)
    assert not any((tmp_path / "uploads").iterdir())


@asynchronous
async def test_audit_records_the_route_not_the_file(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await upload(link, reader, "secret-name.pdf", b"abc")
        text = b"".join(p.read_bytes() for p in bridge.store.root.glob("audit*"))
        assert b"/v1/uploads/{id}/complete" in text and b"/v1/profiles/{id}/uploads" in text
        assert b"secret-name" not in text


# ---- profile changes, busy files, name cleaning, disk errors -----------------------------
def direct(tmp_path, home=None, clock=None):
    home = home if home is not None else tmp_path / "home"
    home.mkdir(parents=True, exist_ok=True)
    kwargs = {"clock": clock} if clock else {}
    return Uploads(tmp_path / "store", lambda profile: home if profile == "p" else None,
                   disk_free=lambda path: 10 ** 12, **kwargs), home


def begin_direct(uploads, size=3, name="a.bin"):
    return uploads.start("d", "p", json.dumps({"name": name, "size": size}).encode())["upload"]


def finish_direct(uploads, key, data=b"abc"):
    return uploads.complete("d", key, json.dumps({"sha256": sha(data)}).encode())


@asynchronous
async def test_profiles_are_looked_up_on_every_call(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            add_profile(bridge, tmp_path)                        # created after the bridge started
            status, _ = await upload(link, reader, "a.txt", b"abc", profile="work")
            assert status == 200 and len(list(documents(tmp_path, "work").iterdir())) == 1
            _, started = await begin(link, reader, "b.txt", 3, profile="work")
            key = started["upload"]
            await call(link, reader, "PUT", f"/v1/uploads/{key}/chunks/0", b"abc")
            work = tmp_path / "upstream" / "profiles" / "work"
            shutil.rmtree(work)                                  # deleted after the upload started
            assert await call(link, reader, "POST", f"/v1/uploads/{key}/complete",
                              {"sha256": sha(b"abc")}) == (404, {"error": "upload_not_found"})
            assert not work.exists()
            assert await begin(link, reader, "c.txt", 3, profile="work") == (404, {"error": "Profile not found"})


def test_a_profile_folder_that_vanishes_is_not_recreated(tmp_path):
    uploads, home = direct(tmp_path)
    key = begin_direct(uploads)
    uploads.chunk("d", key, 0, b"abc")
    shutil.rmtree(home)                      # gone after the lookup answered
    with pytest.raises(HTTPError) as error:
        finish_direct(uploads, key)
    assert (error.value.status, error.value.message) == (404, "upload_not_found")
    assert not home.exists() and not uploads.open


def test_a_file_that_cannot_be_removed_is_left_for_the_next_sweep(tmp_path, monkeypatch):
    stuck = tmp_path / "store" / "uploads"
    stuck.mkdir(parents=True)
    (stuck / "leftover").write_bytes(b"x")
    refused = set()
    real = Path.unlink

    def unlink(self, missing_ok=False):
        if self.parent == stuck and self.name not in refused:
            refused.add(self.name)
            raise PermissionError(13, "in use")
        return real(self, missing_ok=missing_ok)

    monkeypatch.setattr(Path, "unlink", unlink)
    clock = Clock()
    uploads, _ = direct(tmp_path, clock=clock)           # start survives the refusal
    assert (stuck / "leftover").exists()
    key = begin_direct(uploads)
    clock.now += 3601
    uploads.sweep()                                      # neither the old leftover nor the expired upload raises
    assert not uploads.open
    uploads.sweep()
    assert not any(stuck.iterdir())


def test_names_lose_lone_surrogates_and_shell_characters():
    assert clean_name("a\ud800b.txt") == "ab.txt"
    clean_name("a\udfffb.txt").encode("utf-8")
    assert clean_name("a&b%c^d$e`f;g (1) ملف.txt") == "a_b_c_d_e_f_g (1) ملف.txt"


@asynchronous
async def test_an_existing_file_is_never_overwritten(tmp_path, monkeypatch):
    prefixes = iter(["aaaaaaaa", "bbbbbbbb"])
    monkeypatch.setattr("rasul_bridge.uploads.prefix", lambda: next(prefixes))
    async with running(tmp_path) as (bridge, upstream):
        folder = documents(tmp_path)
        folder.mkdir(parents=True)
        (folder / "rasul_aaaaaaaa_x.txt").write_bytes(b"old")
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            status, _ = await upload(link, reader, "x.txt", b"new")
        assert status == 200
        assert (folder / "rasul_aaaaaaaa_x.txt").read_bytes() == b"old"
        assert (folder / "rasul_bbbbbbbb_x.txt").read_bytes() == b"new"


class WindowsFull(OSError):
    winerror = 112


@pytest.mark.parametrize("error,status,code", [
    (OSError(errno.ENOSPC, "full"), 507, "disk_full"),
    (WindowsFull(0, "full"), 507, "disk_full"),
    (OSError(errno.EIO, "broken"), 500, "upload_failed"),
    (PermissionError(13, "denied"), 500, "upload_failed"),
])
def test_only_a_full_disk_is_reported_as_full(tmp_path, monkeypatch, error, status, code):
    uploads, _ = direct(tmp_path)

    def refuse(*args, **kwargs):
        raise error

    key = begin_direct(uploads)
    monkeypatch.setattr(uploads_module, "open", refuse, raising=False)
    with pytest.raises(HTTPError) as caught:
        uploads.chunk("d", key, 0, b"abc")
    assert (caught.value.status, caught.value.message) == (status, code) and not uploads.open
    monkeypatch.undo()
    key = begin_direct(uploads)
    uploads.chunk("d", key, 0, b"abc")
    monkeypatch.setattr(uploads_module.shutil, "move", refuse)
    with pytest.raises(HTTPError) as caught:
        finish_direct(uploads, key)
    assert (caught.value.status, caught.value.message) == (status, code) and not uploads.open
    monkeypatch.undo()
    monkeypatch.setattr(Path, "write_bytes", refuse)
    with pytest.raises(HTTPError) as caught:
        begin_direct(uploads)
    assert (caught.value.status, caught.value.message) == (status, code)


def test_a_failed_first_write_does_not_hold_an_open_slot(tmp_path, monkeypatch):
    uploads, _ = direct(tmp_path)
    real = Path.write_bytes
    monkeypatch.setattr(Path, "write_bytes", lambda self, data: (_ for _ in ()).throw(OSError(errno.EIO, "x")))
    for _ in range(6):
        with pytest.raises(HTTPError):
            begin_direct(uploads)
    assert not uploads.open
    monkeypatch.setattr(Path, "write_bytes", real)
    assert [begin_direct(uploads) for _ in range(4)]


# ---- slow links ------------------------------------------------------------------------------

class Trickle:
    """A link that hands out ``parts`` one by one, waiting ``gap`` seconds before each; ``stall`` stops it for good."""

    def __init__(self, parts, gap=0.0, stall=False):
        self.parts, self.gap, self.stall = list(parts), gap, stall

    async def read(self, size):
        if not self.parts:
            if self.stall:
                await asyncio.sleep(3600)
            return b""
        await asyncio.sleep(self.gap)
        part = self.parts.pop(0)
        if len(part) > size:
            self.parts.insert(0, part[size:])
            part = part[:size]
        return part


HEAD = b"PUT /v1/uploads/x/chunks/0 HTTP/1.1\r\nHost: h\r\nContent-Length: 12\r\n\r\n"
SMALL = dict(head_timeout=0.3, body_idle=0.3, body_timeout=5)


@asynchronous
async def test_a_body_that_trickles_past_the_head_limit_is_accepted():
    from rasul_bridge.http import Reader, request
    reader = Reader(Trickle([HEAD] + [b"abc"] * 4, gap=0.12))
    method, _, _, body = await request(reader, **SMALL)     # ~0.6 s in total, twice the head limit
    assert (method, body) == ("PUT", b"abcabcabcabc")


@asynchronous
async def test_a_body_that_stops_is_dropped_after_the_idle_time():
    from rasul_bridge.http import Reader, request
    reader = Reader(Trickle([HEAD, b"abc"], stall=True))
    start = time.monotonic()
    with pytest.raises(TimeoutError):
        await asyncio.wait_for(request(reader, **SMALL), 3)
    assert time.monotonic() - start < 1.5       # the idle time ended it, not the outer wait


@asynchronous
async def test_a_body_is_dropped_after_the_total_limit_even_if_it_keeps_trickling():
    from rasul_bridge.http import Reader, request
    reader = Reader(Trickle([HEAD] + [b"a"] * 12, gap=0.1))
    with pytest.raises(TimeoutError):
        await asyncio.wait_for(request(reader, **{**SMALL, "body_timeout": 0.5}), 3)


@asynchronous
async def test_headers_that_never_finish_still_time_out_at_the_head_limit():
    from rasul_bridge.http import Reader, request
    reader = Reader(Trickle([b"PUT /v1 HTTP/1.1\r\n", b"Host: h\r\n"], gap=0.2, stall=True))
    start = time.monotonic()
    with pytest.raises(TimeoutError):
        await asyncio.wait_for(request(reader, **{**SMALL, "body_idle": 60, "body_timeout": 60}), 3)
    assert time.monotonic() - start < 1.5       # the head limit ended it, not the outer wait
