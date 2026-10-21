import json
import os
import sqlite3
import time

import pytest

from rasul_bridge.turns import MAX_ROWS, MAX_SESSION_IDS, active

from test_bridge import asynchronous, client, read_response, registered, send as send_request

ME = os.getpid()
DEAD_PID = 2 ** 22 + 12345

SESSION_COLUMNS = "id, parent_session_id, source, model_config, end_reason"


def session(sid, parent=None, source="tui", model_config=None, end_reason=None):
    return (sid, parent, source, json.dumps(model_config) if isinstance(model_config, dict) else model_config, end_reason)


def lease(conversation="root", pid=ME, platform="tui", expires_in=60.0, holder=None):
    if holder is None:
        holder = f"pid={pid}:turn=turn-1:platform={platform}"
    return {"conversation_id": conversation, "holder": holder,
            "acquired_at": time.time() - 10, "expires_at": time.time() + expires_in}


def write_db(folder, sessions=(), leases=()):
    path = folder / "state.db"
    conn = sqlite3.connect(str(path))
    try:
        conn.execute("CREATE TABLE session_turn_leases ("
                     "conversation_id TEXT PRIMARY KEY, holder TEXT NOT NULL,"
                     " acquired_at REAL NOT NULL, expires_at REAL NOT NULL)")
        conn.execute(f"CREATE TABLE sessions ({SESSION_COLUMNS})")
        for row in sessions:
            conn.execute(f"INSERT INTO sessions VALUES ({', '.join('?' * len(row))})", row)
        for row in leases:
            conn.execute("INSERT INTO session_turn_leases VALUES"
                         " (:conversation_id, :holder, :acquired_at, :expires_at)", row)
        conn.commit()
    finally:
        conn.close()
    return path


def test_live_lease_lists_root_and_compression_descendants(tmp_path):
    write_db(tmp_path,
             sessions=[session("root", end_reason="compression"),
                       session("mid", parent="root", end_reason="compression"),
                       session("tip", parent="mid")],
             leases=[lease("root")])
    assert active(tmp_path) == [{"session_ids": ["root", "mid", "tip"], "where": "desktop"}]


def test_expired_and_dead_pid_rows_are_dropped(tmp_path):
    write_db(tmp_path,
             leases=[lease("gone_expired", expires_in=-1),
                     lease("gone_dead", pid=DEAD_PID),
                     lease("gone_malformed_pid", holder="pid=abc:turn=t:platform=tui", expires_in=-1),
                     lease("live")])
    assert [row["session_ids"] for row in active(tmp_path)] == [["live"]]


def test_holder_without_parseable_pid_counts_on_expiry_alone(tmp_path):
    write_db(tmp_path,
             leases=[lease("npid", holder="turn=turn-1:platform=api_server"),
                     lease("npid_old", holder="turn=turn-0:platform=tui", expires_in=-1)])
    assert active(tmp_path) == [{"session_ids": ["npid"], "where": "app"}]


def test_platform_tui_is_desktop_anything_else_is_app(tmp_path):
    write_db(tmp_path,
             leases=[lease("a", platform="tui"),
                     lease("b", platform="api_server"),
                     lease("c", platform="telegram"),
                     lease("d", holder=f"pid={ME}")])
    assert [row["where"] for row in active(tmp_path)] == ["desktop", "app", "app", "app"]


def test_fork_and_tool_children_are_not_walked(tmp_path):
    write_db(tmp_path,
             sessions=[session("root", end_reason="compression"),
                       session("cont", parent="root"),
                       session("branch", parent="root", model_config={"_branched_from": "root"}),
                       session("delegated", parent="root", model_config={"_delegate_from": "root"}),
                       session("toolrun", parent="root", source="tool"),
                       session("inherited", parent="root", model_config={"_branched_from": "elsewhere"})],
             leases=[lease("root")])
    [row] = active(tmp_path)
    assert row["session_ids"] == ["root", "cont", "inherited"]


def test_fork_child_by_parent_end_reason_is_not_walked(tmp_path):
    # A child whose parent ended for a reason other than compression is a fork, not a segment.
    write_db(tmp_path,
             sessions=[session("root", end_reason="session_reset"),
                       session("reset_child", parent="root")],
             leases=[lease("root")])
    [row] = active(tmp_path)
    assert row["session_ids"] == ["root"]


def test_lease_key_mid_chain_normalizes_to_root(tmp_path):
    write_db(tmp_path,
             sessions=[session("root", end_reason="compression"),
                       session("mid", parent="root", end_reason="compression"),
                       session("tip", parent="mid")],
             leases=[lease("mid")])
    [row] = active(tmp_path)
    assert row["session_ids"] == ["root", "mid", "tip"]


def test_deleted_conversation_keeps_its_lease_key(tmp_path):
    write_db(tmp_path, leases=[lease("gone")])
    assert active(tmp_path) == [{"session_ids": ["gone"], "where": "desktop"}]


def test_missing_file_missing_table_and_garbage_are_empty(tmp_path):
    assert active(tmp_path) == []
    conn = sqlite3.connect(str(tmp_path / "state.db"))
    conn.execute("CREATE TABLE other (id TEXT)")
    conn.commit()
    conn.close()
    assert active(tmp_path) == []
    (tmp_path / "state.db").write_bytes(b"this is not sqlite at all" * 4)
    assert active(tmp_path) == []


def test_database_file_is_never_written(tmp_path):
    path = write_db(tmp_path, sessions=[session("root")], leases=[lease("root")])
    before = (path.stat().st_mtime_ns, path.read_bytes())
    assert active(tmp_path)
    assert (path.stat().st_mtime_ns, path.read_bytes()) == before
    assert sorted(p.name for p in tmp_path.iterdir()) == ["state.db"]


def test_wal_database_without_a_writer_is_not_opened(tmp_path):
    # The installed app keeps state.db in WAL mode; a closed WAL database has no -shm sidecar.
    # Opening it read-only would recreate -shm/-wal next to the file, and with no writer up no
    # lease can be live, so the answer is [] without touching the database.
    path = write_db(tmp_path, leases=[lease("root")])
    conn = sqlite3.connect(str(path))
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.commit()
    finally:
        conn.close()
    assert sorted(p.name for p in tmp_path.iterdir()) == ["state.db"]
    assert active(tmp_path) == []
    assert sorted(p.name for p in tmp_path.iterdir()) == ["state.db"]


def test_wal_database_with_a_writer_is_read(tmp_path):
    # While a connection holds the WAL database open the sidecars exist and the read works.
    write_db(tmp_path, leases=[lease("root")])
    conn = sqlite3.connect(str(tmp_path / "state.db"))
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("INSERT INTO sessions VALUES (?, ?, ?, ?, ?)",
                     session("root", end_reason="compression"))
        conn.commit()
        assert (tmp_path / "state.db-shm").is_file()
        assert active(tmp_path) == [{"session_ids": ["root"], "where": "desktop"}]
    finally:
        conn.close()


class _CountingCursor:
    fetched = 0

    def __init__(self, cursor):
        self._cursor = cursor

    def fetchall(self):
        rows = self._cursor.fetchall()
        _CountingCursor.fetched += len(rows)
        return rows

    def fetchone(self):
        row = self._cursor.fetchone()
        if row is not None:
            _CountingCursor.fetched += 1
        return row


class _CountingConnection:
    """Wraps a real connection so every execute() cursor counts the rows it hands out."""

    def __init__(self, conn):
        self._conn = conn

    def execute(self, *args, **kwargs):
        return _CountingCursor(self._conn.execute(*args, **kwargs))

    def close(self):
        self._conn.close()


def _count_rows(monkeypatch):
    real_connect = sqlite3.connect
    _CountingCursor.fetched = 0
    monkeypatch.setattr(sqlite3, "connect",
                        lambda *args, **kw: _CountingConnection(real_connect(*args, **kw)))
    return _CountingCursor


def test_child_rows_fetched_are_bounded(monkeypatch, tmp_path):
    kids = [session("kid_%05d" % n, parent="root") for n in range(5_000)]
    write_db(tmp_path, sessions=[session("root", end_reason="compression")] + kids,
             leases=[lease("root")])
    counter = _count_rows(monkeypatch)
    [row] = active(tmp_path)
    assert len(row["session_ids"]) == MAX_SESSION_IDS
    assert counter.fetched <= 2 * MAX_SESSION_IDS


def test_deep_chain_walk_is_capped(monkeypatch, tmp_path):
    chain = [session("s%05d" % n, parent=("s%05d" % (n - 1) if n else None),
                     end_reason="compression") for n in range(10_000)]
    write_db(tmp_path, sessions=chain, leases=[lease("s%05d" % 9_999)])
    counter = _count_rows(monkeypatch)
    [row] = active(tmp_path)
    assert row["where"] == "desktop"
    assert len(row["session_ids"]) <= MAX_SESSION_IDS
    assert counter.fetched <= 2 * MAX_SESSION_IDS


def test_list_is_capped(tmp_path):
    write_db(tmp_path, leases=[lease("conv_%04d" % n) for n in range(MAX_ROWS + 10)])
    assert len(active(tmp_path)) == MAX_ROWS


@asynchronous
async def test_route_lists_live_turns_without_holder_details(tmp_path):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, upstream):
        identity, _ = registered(bridge)
        write_db(bridge.config.hermes_home,
                 sessions=[session("root", end_reason="compression"), session("tip", parent="root")],
                 leases=[lease("root"), lease("stale", pid=DEAD_PID)])
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/default/turns")
            status, value = await read_response(reader)
        assert status == 200
        assert value == [{"session_ids": ["root", "tip"], "where": "desktop"}]
        rows = [json.loads(line) for line in bridge.audit.path.read_text().splitlines()]
        assert any(row.get("route") == "/v1/profiles/{id}/turns" and row["profile_id"] == "default"
                   and row["status"] == 200 for row in rows)
        assert str(ME) not in bridge.audit.path.read_text()
        assert not upstream.requests


@asynchronous
async def test_route_missing_db_is_empty_and_named_profile_reads_its_own(tmp_path):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        named = bridge.config.hermes_home / "profiles" / "sample"
        named.mkdir(parents=True)
        write_db(named, leases=[lease("named_root")])
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/default/turns")
            assert await read_response(reader) == (200, [])
            await send_request(link, "GET", "/v1/profiles/sample/turns")
            status, value = await read_response(reader)
            assert status == 200 and [row["session_ids"] for row in value] == [["named_root"]]


@pytest.mark.parametrize("profile", ["missing", "Default", "%2e%2e"])
@asynchronous
async def test_route_unknown_profile_is_404(tmp_path, profile):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/" + profile + "/turns")
            assert (await read_response(reader))[0] == 404


@asynchronous
async def test_route_needs_a_paired_device_and_is_get_only(tmp_path):
    from test_bridge import running as running_bridge
    async with running_bridge(tmp_path) as (bridge, _):
        async with client(bridge, tmp_path) as (link, reader, _):
            await send_request(link, "GET", "/v1/profiles/default/turns")
            assert (await read_response(reader))[0] == 403
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send_request(link, "DELETE", "/v1/profiles/default/turns")
            assert (await read_response(reader))[0] == 404
