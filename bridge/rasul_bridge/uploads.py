"""Files from the phone to the agent.

The phone sends a file in chunks, the bridge checks its size and SHA-256 and moves it into the
profile's Hermes document cache. A run then names the file by id and the bridge adds the same
note Hermes's own messaging gateway adds for a document. The bridge never opens, previews,
unpacks or runs an uploaded file, and it never answers an upload with the path on this computer.
"""

import errno
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import time
import unicodedata

from .files import MIME, RESERVED_DEVICE
from .http import HTTPError
from .storage import private_dir

MAX_FILE = 50 * 1024 * 1024
CHUNK = 786432                 # three quarters of a MiB: a chunk plus headers fits MAX_BODY
MAX_OPEN = 4
MAX_FILES_PER_RUN = 4
UPLOAD_LIFETIME = 60 * 60
FILE_LIFETIME = 24 * 60 * 60
DISK_RESERVE = 1024 ** 3
MAX_NAME = 120
ID = re.compile(r"[0-9a-f]{32}\Z")
SHA = re.compile(r"[0-9a-f]{64}\Z")
# Path separators, characters Windows refuses (a colon would open an alternate data stream) and
# bidirectional overrides that make a name read backwards.
DROPPED = set("/\\<>:\"|?*") | {chr(c) for c in (*range(0x202a, 0x202f), *range(0x2066, 0x206a))}
# Characters a shell treats as syntax: the name appears in the note the agent reads, so they become "_".
SHELL = set("&%^$`;")
DISK_FULL_WINDOWS = (39, 112)   # ERROR_HANDLE_DISK_FULL, ERROR_DISK_FULL


def clean_name(raw):
    """Unicode kept, separators and control characters dropped, safe on Windows, at most MAX_NAME."""
    name = "".join("_" if c in SHELL else c for c in unicodedata.normalize("NFC", raw)
                   if c not in DROPPED and unicodedata.category(c) not in ("Cc", "Cs"))
    name = name.strip(". ")
    if not name:
        return "file"
    if RESERVED_DEVICE.fullmatch(name.split(".", 1)[0].rstrip(" ")):
        name = "_" + name
    if len(name) > MAX_NAME:
        stem, dot, extension = name.rpartition(".")
        if not dot or len(extension) > 16:
            stem, extension = name, ""
        else:
            extension = "." + extension
        name = stem[:MAX_NAME - len(extension)].rstrip(". ") + extension
    return name or "file"


def prefix():
    return secrets.token_hex(4)


def failure(error):
    """A full disk is reported as full, any other write problem as a plain upload error."""
    if error.errno == errno.ENOSPC or getattr(error, "winerror", None) in DISK_FULL_WINDOWS:
        return HTTPError(507, "disk_full")
    return HTTPError(500, "upload_failed")


def document_note(name, path, text):
    """Hermes gateway/run.py _build_document_context_note(..., content_inlined=False)."""
    if text:
        return (f"[The user sent a text document: '{name}'. It is saved at: {path}. "
                f"Its content is not inlined here. Read the cached file yourself before answering "
                f"when the user's request involves its contents.]")
    return (f"[The user sent a document: '{name}'. It is saved at: {path}. "
            f"Its text is not inlined here (it's a binary format such as PDF or DOCX). "
            f"To read it, extract the document's text yourself — for example with the "
            f"terminal tool or the ocr-and-documents skill — before answering, instead "
            f"of asking the user to paste the contents.]")


class Uploads:
    def __init__(self, store_root, home_of, clock=time.time, disk_free=None):
        """``home_of(profile)`` is the profile's Hermes home, or None when the profile is unknown."""
        self.directory = private_dir(Path(store_root) / "uploads")
        self.home_of, self.clock = home_of, clock
        self.disk_free = disk_free or (lambda path: shutil.disk_usage(path).free)
        self.open = {}      # upload id -> record
        self.files = {}     # file id -> record
        self.stale = set()  # names of partial files that could not be removed yet
        # Unfinished data from before a restart has no record and can never be completed.
        for leftover in self.directory.iterdir():
            self.remove(leftover.name)

    # ---- housekeeping ------------------------------------------------------------------
    def remove(self, key):
        """Delete a partial file; when the system refuses (another process holds it), try again at the next sweep."""
        try:
            (self.directory / key).unlink(missing_ok=True)
            self.stale.discard(key)
        except OSError:
            self.stale.add(key)

    def sweep(self):
        for key in list(self.stale):
            self.remove(key)
        now = self.clock()
        for key in [k for k, v in self.open.items() if v["created"] + UPLOAD_LIFETIME <= now]:
            self.discard(key)
        for key in [k for k, v in self.files.items() if v["created"] + FILE_LIFETIME <= now]:
            del self.files[key]

    def discard(self, key):
        self.open.pop(key, None)
        self.remove(key)

    def find(self, device, key):
        self.sweep()
        record = self.open.get(key)
        if record is None or record["device"] != device:
            raise HTTPError(404, "upload_not_found")
        return record

    # ---- routes ------------------------------------------------------------------------
    def start(self, device, profile, body):
        self.sweep()
        try:
            value = json.loads(body.decode("utf-8", errors="strict"))
        except (UnicodeError, ValueError):
            raise HTTPError(400, "invalid_upload") from None
        if (not isinstance(value, dict) or set(value) - {"name", "size", "type"}
                or not isinstance(value.get("name"), str) or len(value["name"]) > 1024
                or type(value.get("size")) is not int or value["size"] < 1
                or not isinstance(value.get("type", ""), str) or len(value.get("type", "")) > 255):
            raise HTTPError(400, "invalid_upload")
        size = value["size"]
        if size > MAX_FILE:
            raise HTTPError(413, "file_too_large")
        home = self.home_of(profile)
        if home is None:
            raise HTTPError(404, "Profile not found")
        if sum(1 for v in self.open.values() if v["device"] == device) >= MAX_OPEN:
            raise HTTPError(429, "too_many_uploads")
        try:
            if min(self.disk_free(self.directory), self.disk_free(home)) < size + DISK_RESERVE:
                raise HTTPError(507, "disk_full")
        except OSError:
            raise HTTPError(507, "disk_full") from None
        name = clean_name(value["name"])
        kind = value.get("type", "").split(";")[0].strip().lower()
        text = kind.startswith("text/") or MIME.get(Path(name).suffix.lower(), "").startswith("text/")
        key = secrets.token_hex(16)
        try:
            (self.directory / key).write_bytes(b"")
        except OSError as error:
            self.remove(key)
            raise failure(error) from None
        self.open[key] = {"device": device, "profile": profile, "name": name, "size": size, "text": text,
                          "created": self.clock(), "next": 0, "received": 0, "hash": hashlib.sha256(),
                          "last": None}
        return {"upload": key, "chunk_size": CHUNK}

    def chunk(self, device, key, index, data):
        record = self.find(device, key)
        if index == record["next"] - 1 and hashlib.sha256(data).digest() == record["last"]:
            return                                  # the phone repeated the last chunk
        if index != record["next"]:
            raise HTTPError(409, "chunk_out_of_order")
        expected = min(CHUNK, record["size"] - record["received"])
        if expected <= 0 or len(data) != expected:
            raise HTTPError(400, "invalid_chunk")
        try:
            # ponytail: a synchronous write of at most 768 KiB on the event loop; threads if it ever hurts
            with open(self.directory / key, "ab") as stream:
                stream.write(data)
        except OSError as error:
            self.discard(key)
            raise failure(error) from None
        record["hash"].update(data)
        record["last"] = hashlib.sha256(data).digest()
        record["received"] += len(data)
        record["next"] += 1

    def complete(self, device, key, body):
        record = self.find(device, key)
        try:
            value = json.loads(body.decode("utf-8", errors="strict"))
            digest = value["sha256"]
            if set(value) != {"sha256"} or not isinstance(digest, str) or not SHA.match(digest):
                raise ValueError
        except (UnicodeError, ValueError, TypeError, KeyError):
            raise HTTPError(400, "invalid_upload") from None
        if record["received"] != record["size"]:
            raise HTTPError(409, "upload_incomplete")
        if record["hash"].hexdigest() != digest:
            self.discard(key)
            raise HTTPError(400, "checksum_mismatch")
        home = self.home_of(record["profile"])
        if home is None:
            self.discard(key)
            raise HTTPError(404, "upload_not_found")
        documents = home / "cache" / "documents"
        part = self.directory / key
        try:
            # No parents=True: a profile folder deleted in the meantime must not come back.
            for folder in (home / "cache", documents):
                folder.mkdir(exist_ok=True)
            temporary = documents / (".rasul_" + secrets.token_hex(8))
            try:
                shutil.move(part, temporary)
                for _ in range(8):
                    final = documents / ("rasul_" + prefix() + "_" + record["name"])
                    try:
                        # Never overwrite: a Windows rename and a hard link both refuse an existing target.
                        (os.rename if os.name == "nt" else os.link)(temporary, final)
                        break
                    except FileExistsError:
                        continue
                else:
                    raise FileExistsError(errno.EEXIST, "no free file name")
            finally:
                temporary.unlink(missing_ok=True)
            info = final.stat()
        except OSError as error:
            self.discard(key)
            if isinstance(error, FileNotFoundError) and not home.is_dir():
                raise HTTPError(404, "upload_not_found") from None
            raise failure(error) from None
        self.open.pop(key, None)
        file_id = secrets.token_hex(16)
        self.files[file_id] = {"device": device, "profile": record["profile"], "path": final,
                               "name": record["name"], "size": record["size"], "text": record["text"],
                               "mtime": info.st_mtime_ns, "created": self.clock()}
        return {"file": file_id, "name": record["name"], "size": record["size"]}

    def delete(self, device, key):
        record = self.open.get(key)
        if record is not None and record["device"] == device:
            self.discard(key)

    # ---- runs --------------------------------------------------------------------------
    def note_for(self, device, profile, file_id):
        record = self.files.get(file_id) if isinstance(file_id, str) else None
        if (record is None or record["device"] != device or record["profile"] != profile
                or record["created"] + FILE_LIFETIME <= self.clock()):
            raise HTTPError(400, "file_unavailable")
        try:
            info = record["path"].stat()
        except OSError:
            raise HTTPError(400, "file_unavailable") from None
        if info.st_size != record["size"] or info.st_mtime_ns != record["mtime"]:
            raise HTTPError(400, "file_unavailable")
        return document_note(record["name"], record["path"], record["text"])

    def attach(self, device, profile, body):
        """The run body without ``rasul_files`` and with one document note per file before the user's
        text; the same bytes when the field is absent."""
        self.sweep()
        try:
            value = json.loads(body.decode("utf-8", errors="strict"))
        except (UnicodeError, ValueError):
            return body
        if not isinstance(value, dict) or "rasul_files" not in value:
            return body
        ids = value.pop("rasul_files")
        if not isinstance(ids, list) or not 1 <= len(ids) <= MAX_FILES_PER_RUN:
            raise HTTPError(400, "file_unavailable")
        notes = "\n\n".join(self.note_for(device, profile, i) for i in ids)
        content = value.get("input")
        if isinstance(content, list) and content and isinstance(content[-1], dict):
            content = content[-1]                   # Hermes reads the last message's content
            if isinstance(content.get("content"), str):
                content["content"] = notes + "\n\n" + content["content"] if content["content"] else notes
            elif isinstance(content.get("content"), list):
                content["content"].insert(0, {"type": "text", "text": notes})
            else:
                raise HTTPError(400, "invalid_run_input")
        elif isinstance(content, str):
            value["input"] = notes + "\n\n" + content if content else notes
        else:
            raise HTTPError(400, "invalid_run_input")
        try:
            return json.dumps(value, ensure_ascii=False).encode("utf-8")
        except UnicodeEncodeError:
            raise HTTPError(400, "invalid_run_input") from None
