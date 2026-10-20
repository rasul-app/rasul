"""Read-only list of the background processes a profile's agent still has running.

Hermes keeps a checkpoint of its running background processes in the profile folder. The file can be stale after
a crash, so an entry counts only while its process id is alive AND that process started when the entry says it did
(a recycled process id belongs to someone else). Nothing here ever signals or stops a process.
"""

import json
from pathlib import Path

from .desktop import process_times, start_matches

FILENAME = "processes.json"
MAX_BYTES = 1024 * 1024
MAX_ITEMS = 50
MAX_COMMAND = 200


def _alive(pid, started, times):
    """True while `pid` runs and its start fingerprint equals the recorded one (the same tolerance the desktop helper uses: 1 tick on Windows only)."""
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return False
    if not isinstance(started, int) or isinstance(started, bool):
        return False
    try:
        return start_matches(started, times(pid)[0])
    except (OSError, ValueError, IndexError, TypeError):
        return False


def running(directory, times=process_times):
    """The live entries of `directory/processes.json` as [{id, command, started_at, owner}]; a missing or broken file is []."""
    path = Path(directory) / FILENAME
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
            return []
        rows = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, RecursionError):
        return []
    if not isinstance(rows, list):
        return []
    result = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("session_id"), str):
            continue
        if not _alive(row.get("pid"), row.get("host_start_time"), times):
            continue
        command, owner, begun = row.get("command"), row.get("owner_task_id"), row.get("started_at")
        result.append({
            "id": row["session_id"][:64],
            "command": command[:MAX_COMMAND] if isinstance(command, str) else "",
            "started_at": begun if isinstance(begun, (int, float)) and not isinstance(begun, bool) else None,
            "owner": owner[:128] if isinstance(owner, str) else None,
        })
        if len(result) == MAX_ITEMS:
            break
    return result
