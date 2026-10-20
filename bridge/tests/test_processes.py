import json
import os
import sys

import pytest

from rasul_bridge.desktop import process_times
from rasul_bridge.processes import MAX_COMMAND, MAX_ITEMS, running

from test_bridge import asynchronous, client, read_response, registered, send as send_request

ME = os.getpid()
MY_START = process_times(ME)[0]
DEAD_PID = 2 ** 22 + 12345


def entry(session="proc_a1", pid=ME, start=MY_START, command="npm run build", owner="sess-1", **more):
    return {"session_id": session, "pid": pid, "host_start_time": start, "command": command,
            "started_at": 1790000000.5, "owner_task_id": owner, "cwd": "/secret/place", "env": {"K": "v"}, **more}


def write(folder, rows):
    (folder / "processes.json").write_text(json.dumps(rows), encoding="utf-8")


def test_live_entry_is_listed_with_only_four_fields(tmp_path):
    write(tmp_path, [entry()])
    assert running(tmp_path) == [
        {"id": "proc_a1", "command": "npm run build", "started_at": 1790000000.5, "owner": "sess-1"}]


def test_stale_entries_are_dropped(tmp_path):
    write(tmp_path, [
        entry("proc_dead", pid=DEAD_PID),
        entry("proc_reused", start=MY_START + 5000),
        entry("proc_nostart", start=None),
        entry("proc_nopid", pid=None),
        entry("proc_live"),
    ])
    assert [row["id"] for row in running(tmp_path)] == ["proc_live"]


def test_one_tick_of_slack_exists_on_windows_only(tmp_path, monkeypatch):
    write(tmp_path, [entry("proc_tick")])
    shifted = lambda pid: (MY_START + 1,)
    monkeypatch.setattr(sys, "platform", "linux")
    assert running(tmp_path, times=shifted) == []
    monkeypatch.setattr(sys, "platform", "win32")
    assert [row["id"] for row in running(tmp_path, times=shifted)] == ["proc_tick"]


def test_command_is_cut_and_bad_rows_are_skipped(tmp_path):
    write(tmp_path, ["junk", 3, {"pid": ME}, entry("proc_long", command="x" * 5000, owner=None, started_at="soon")])
    [row] = running(tmp_path)
    assert row["id"] == "proc_long" and len(row["command"]) == MAX_COMMAND and row["owner"] is None


def test_list_is_capped(tmp_path):
    write(tmp_path, [entry("proc_%d" % n) for n in range(MAX_ITEMS + 10)])
    assert len(running(tmp_path)) == MAX_ITEMS


@pytest.mark.parametrize("text", ["", "{not json", "{}", "null", '"x"', pytest.param("[" * 200000 + "]" * 200000, id="deeply-nested")])
def test_malformed_file_is_empty(tmp_path, text):
    (tmp_path / "processes.json").write_text(text, encoding="utf-8")
    assert running(tmp_path) == []


def test_missing_file_is_empty(tmp_path):
    assert running(tmp_path) == []


@asynchronous
async def test_route_lists_live_processes_and_audits(tmp_path):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        write(bridge.config.hermes_home, [entry(), entry("proc_dead", pid=DEAD_PID)])
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/default/processes")
            status, value = await read_response(reader)
        assert status == 200
        assert value == [{"id": "proc_a1", "command": "npm run build", "started_at": 1790000000.5, "owner": "sess-1"}]
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert any(row.get("route") == "/v1/profiles/{id}/processes" and row["profile_id"] == "default"
                   and row["status"] == 200 for row in rows)
        assert "npm run build" not in bridge.audit.path.read_text()
        assert not upstream.requests


@asynchronous
async def test_route_missing_file_is_empty_and_named_profile_reads_its_own_folder(tmp_path):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        named = bridge.config.hermes_home / "profiles" / "sample"
        named.mkdir(parents=True)
        write(named, [entry("proc_named")])
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/default/processes")
            assert await read_response(reader) == (200, [])
            await send_request(link, "GET", "/v1/profiles/sample/processes")
            status, value = await read_response(reader)
            assert status == 200 and [row["id"] for row in value] == ["proc_named"]


@pytest.mark.parametrize("profile", ["missing", "Default", "%2e%2e"])
@asynchronous
async def test_route_unknown_profile_is_404(tmp_path, profile):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/" + profile + "/processes")
            assert (await read_response(reader))[0] == 404


@asynchronous
async def test_route_needs_a_paired_device_and_is_get_only(tmp_path):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, _):
        async with client(bridge, tmp_path) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/default/processes")
            assert (await read_response(reader))[0] == 403
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "DELETE", "/v1/profiles/default/processes")
            assert (await read_response(reader))[0] == 404
