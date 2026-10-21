"""Read-only view of the session turn leases a profile's state.db holds.

Hermes writes one row per conversation into ``session_turn_leases`` while a turn runs
(load -> run -> flush): ``conversation_id`` is the compression-lineage ROOT session id and
``holder`` reads ``pid=<pid>...:turn=<id>:platform=<platform>``. A row counts while it has not
expired AND its holder pid is still alive; a holder with no parseable pid counts on expiry alone.

The file is opened read-only (sqlite ``mode=ro``, short timeout): it is never written to and
never created. A missing file, a missing table, a locked or corrupt database, or any read error
answers ``[]`` — never a traceback. The answer exposes only session ids and a coarse "where"; the
holder string and pid are never returned.
"""

import json
import re
import sqlite3
import time
from pathlib import Path

from .desktop import process_times

FILENAME = "state.db"
MAX_ROWS = 200
# Fetch wider than the answer cap so leases dropped by the liveness check do not starve live ones.
FETCH_LIMIT = MAX_ROWS * 5
MAX_DEPTH = 50
MAX_SESSION_IDS = 512
# A child page fetches the ids still allowed under the cap plus room for the fork/tool rows
# dropped after the fetch.
_CHILD_MARGIN = 64
TIMEOUT_S = 0.5

_SESSION_COLS = "id, parent_session_id, source, model_config, end_reason"
# A child row needs no end_reason of its own: it is re-read when it becomes a frontier node.
_CHILD_COLS = "id, parent_session_id, source, model_config"

# The same holder-pid pattern Hermes uses (``_compression_lock_holder_process_is_dead``).
_PID = re.compile(r"(?:^|:)pid=(\d+)(?::|$)")
_PLATFORM = re.compile(r":platform=([^\s:]+)\s*$")


def _fork_child(row):
    """True when the row is a branch/delegate/reset/tool child of its parent — not a compression
    continuation (same predicate as the installed agent's ``_is_explicit_fork_child_row`` with
    include_reset). Markers only count when they name the row's own parent: a continuation copies
    ``model_config`` verbatim, so a marker pointing elsewhere is inherited config, not a fork."""
    if row.get("source") == "tool":
        return True
    config = row.get("model_config")
    if isinstance(config, str):
        try:
            config = json.loads(config)
        except ValueError:
            return False
    if not isinstance(config, dict):
        return False
    markers = (config.get("_branched_from"), config.get("_delegate_from"), config.get("_reset_from"))
    parent = row.get("parent_session_id")
    return parent in markers if parent else any(m is not None for m in markers)


def _session_row(conn, session_id):
    row = conn.execute(f"SELECT {_SESSION_COLS} FROM sessions WHERE id = ?", (session_id,)).fetchone()
    return dict(zip(_SESSION_COLS.split(", "), row)) if row else None


def _root(conn, session_id):
    """The chain root of the lease key: compression parents walked up (the same walk the agent
    applies when it takes the lease), at most MAX_DEPTH steps. A deleted session keeps its own
    id as the key."""
    current = _session_row(conn, session_id)
    if not current:
        return session_id
    seen = {session_id}
    for _depth in range(MAX_DEPTH):
        parent_id = current.get("parent_session_id")
        if not parent_id or parent_id in seen or _fork_child(current):
            break
        parent = _session_row(conn, parent_id)
        if not parent or parent.get("end_reason") != "compression":
            break
        seen.add(parent_id)
        current = parent
    return str(current.get("id") or session_id)


def _lineage(conn, root):
    """``[root]`` plus its compression descendants: children of sessions that ended
    'compression', fork/tool children excluded, breadth-first, bounded by depth and count."""
    ids = [root]
    seen = {root}
    frontier = [root]
    for _depth in range(MAX_DEPTH):
        if not frontier or len(ids) >= MAX_SESSION_IDS:
            break
        children = []
        for node in frontier:
            row = _session_row(conn, node)
            if not row or row.get("end_reason") != "compression":
                continue
            found = conn.execute(
                f"SELECT {_CHILD_COLS} FROM sessions WHERE parent_session_id = ? LIMIT ?",
                (node, MAX_SESSION_IDS - len(ids) + _CHILD_MARGIN),
            ).fetchall()
            for raw in found:
                child = dict(zip(_CHILD_COLS.split(", "), raw))
                child_id = child.get("id")
                if not isinstance(child_id, str) or child_id in seen or _fork_child(child):
                    continue
                seen.add(child_id)
                ids.append(child_id)
                children.append(child_id)
                if len(ids) >= MAX_SESSION_IDS:
                    break
            if len(ids) >= MAX_SESSION_IDS:
                break
        frontier = children
    return ids


def _wal_without_writer(path):
    """True when the file header marks a WAL database — byte 18 is 2, the same byte the app's
    own tooling reads (1 = rollback journal, 2 = WAL) — and no connection holds it open (the
    ``-shm``/``-wal`` sidecars are gone). A read-only open of a closed WAL database recreates
    them, and with no writer up every lease row is dead, so the whole read is skipped."""
    try:
        with path.open("rb") as f:
            header = f.read(20)
    except OSError:
        return False
    if len(header) < 20 or header[18] != 2:
        return False
    return not (path.with_name(path.name + "-shm").is_file()
                and path.with_name(path.name + "-wal").is_file())


def _pid_alive(holder, times):
    """True while the holder's ``pid=<n>`` names a live process; an unparseable holder counts on
    expiry alone (``True``), matching the agent's "not provably dead" rule."""
    match = _PID.search(holder or "")
    if not match:
        return True
    try:
        times(int(match.group(1)))
        return True
    except (OSError, ValueError, IndexError, TypeError):
        return False


def active(directory, times=process_times):
    """Live turn leases of ``<directory>/state.db`` as ``[{"session_ids": [...], "where": ...}]``;
    ``where`` is ``"desktop"`` for the desktop app's turns (platform ``tui``), ``"app"`` otherwise.
    Any missing/unreadable/corrupt state answers ``[]``."""
    path = Path(directory) / FILENAME
    try:
        if path.is_symlink() or not path.is_file() or _wal_without_writer(path):
            return []
        conn = sqlite3.connect(path.absolute().as_uri() + "?mode=ro", uri=True, timeout=TIMEOUT_S)
    except (OSError, ValueError, sqlite3.Error):
        return []
    try:
        rows = conn.execute(
            "SELECT conversation_id, holder FROM session_turn_leases"
            " WHERE expires_at > ? ORDER BY acquired_at LIMIT ?",
            (time.time(), FETCH_LIMIT),
        ).fetchall()
        result = []
        for conversation_id, holder in rows:
            if len(result) == MAX_ROWS:
                break
            if not isinstance(conversation_id, str) or not conversation_id:
                continue
            if not _pid_alive(holder, times):
                continue
            platform = _PLATFORM.search(holder or "")
            result.append({
                "session_ids": _lineage(conn, _root(conn, conversation_id)),
                "where": "desktop" if platform and platform.group(1) == "tui" else "app",
            })
        return result
    except (OSError, ValueError, sqlite3.Error):
        return []
    finally:
        conn.close()
