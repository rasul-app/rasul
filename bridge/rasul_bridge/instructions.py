"""Bounded profile instructions with optimistic revisions and atomic replacement."""

from contextlib import contextmanager, ExitStack
import ctypes
import errno
import hashlib
import os
from pathlib import Path
import re
import stat
import sys
import uuid

from .http import HTTPError


# Conservative byte bound below the default 20,000-character context-file cap.
MAX_INSTRUCTIONS_BYTES = 20_000
FILENAME = "SOUL.md"


def _safe(info, directory=False):
    if (stat.S_ISLNK(info.st_mode)
            or getattr(info, "st_file_attributes", 0) & 0x400
            or (not directory and info.st_nlink > 1)
            or not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))):
        raise HTTPError(403, "instructions_unsafe_path")


def _kernel():
    from ctypes import wintypes as w
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, w.LPVOID, w.DWORD, w.DWORD, w.HANDLE]
    kernel.CreateFileW.restype = w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.GetFileInformationByHandleEx.argtypes = [w.HANDLE, ctypes.c_int, w.LPVOID, w.DWORD]
    return kernel


@contextmanager
def _directory(path):
    """Pin every parent; reject reparse points rather than following them."""
    path = Path(os.path.abspath(path))
    with ExitStack() as stack:
        if os.name == "nt":
            kernel = _kernel()
            for parent in reversed((path, *path.parents)):
                # No delete sharing: a pinned directory cannot be swapped out.
                handle = kernel.CreateFileW(str(parent), 0, 3, None, 3, 0x02200000, None)
                if handle == ctypes.c_void_p(-1).value:
                    raise ctypes.WinError(ctypes.get_last_error())
                stack.callback(kernel.CloseHandle, handle)
                attributes = (ctypes.c_ulong * 2)()
                if not kernel.GetFileInformationByHandleEx(handle, 9, attributes, ctypes.sizeof(attributes)):
                    raise ctypes.WinError(ctypes.get_last_error())
                if attributes[0] & 0x400 or not attributes[0] & 0x10:
                    raise HTTPError(403, "instructions_unsafe_path")
            yield path, None
        else:
            descriptor = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY)
            stack.callback(os.close, descriptor)
            for part in path.parts[1:]:
                try:
                    descriptor = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                         dir_fd=descriptor)
                except OSError:
                    raise HTTPError(403, "instructions_unsafe_path") from None
                stack.callback(os.close, descriptor)
            yield path, descriptor


def _read(directory, descriptor):
    path = directory / FILENAME
    try:
        if os.name == "nt":
            import msvcrt
            kernel = _kernel()
            handle = kernel.CreateFileW(str(path), 0x80000000, 1, None, 3, 0x00200000, None)
            if handle == ctypes.c_void_p(-1).value:
                error = ctypes.get_last_error()
                if error == 2:
                    return b"", None
                raise ctypes.WinError(error)
            try:
                from ctypes import wintypes as w
                class StandardInfo(ctypes.Structure):
                    _fields_ = [("allocation_size", ctypes.c_longlong), ("end_of_file", ctypes.c_longlong),
                                ("number_of_links", w.DWORD), ("delete_pending", w.BYTE),
                                ("directory", w.BYTE)]
                standard = StandardInfo()
                if not kernel.GetFileInformationByHandleEx(handle, 1, ctypes.byref(standard), ctypes.sizeof(standard)):
                    raise ctypes.WinError(ctypes.get_last_error())
                if standard.number_of_links > 1:
                    raise HTTPError(403, "instructions_unsafe_path")
                fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
            except BaseException:
                kernel.CloseHandle(handle)
                raise
        else:
            fd = os.open(FILENAME, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
    except FileNotFoundError:
        return b"", None
    except OSError as error:
        if path.is_symlink() or (path.exists() and getattr(path.lstat(), "st_file_attributes", 0) & 0x400):
            raise HTTPError(403, "instructions_unsafe_path") from None
        raise error
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        _safe(info)
        data = stream.read(MAX_INSTRUCTIONS_BYTES + 1)
    if len(data) > MAX_INSTRUCTIONS_BYTES:
        raise HTTPError(413, "instructions_too_large")
    try:
        data.decode("utf-8", errors="strict")
    except UnicodeError:
        raise HTTPError(400, "instructions_not_utf8") from None
    return data, info


def _revision(data):
    return hashlib.sha256(data).hexdigest()


def _copy_permissions(source, target):
    """Copy the existing Windows DACL before placing any text in the temp file."""
    from ctypes import wintypes as w
    security = ctypes.WinDLL("advapi32", use_last_error=True)
    security.GetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, w.LPVOID, w.DWORD, ctypes.POINTER(w.DWORD)]
    security.SetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, w.LPVOID]
    security.GetSecurityDescriptorControl.argtypes = [w.LPVOID, ctypes.POINTER(w.WORD), ctypes.POINTER(w.DWORD)]
    length = w.DWORD()
    security.GetFileSecurityW(str(source), 4, None, 0, ctypes.byref(length))
    if not length.value:
        raise ctypes.WinError(ctypes.get_last_error())
    descriptor = ctypes.create_string_buffer(length.value)
    if not security.GetFileSecurityW(str(source), 4, descriptor, length, ctypes.byref(length)):
        raise ctypes.WinError(ctypes.get_last_error())
    control, revision = w.WORD(), w.DWORD()
    if not security.GetSecurityDescriptorControl(descriptor, ctypes.byref(control), ctypes.byref(revision)):
        raise ctypes.WinError(ctypes.get_last_error())
    information = 4 | (0x80000000 if control.value & 0x1000 else 0x20000000)
    if not security.SetFileSecurityW(str(target), information, descriptor):
        raise ctypes.WinError(ctypes.get_last_error())


def _copy_access_acl(directory, descriptor, target):
    if sys.platform != "linux" or not (hasattr(os, "getxattr") and hasattr(os, "setxattr")):
        return
    source = os.open(FILENAME, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
    try:
        _safe(os.fstat(source))
        try:
            acl = os.getxattr(source, "system.posix_acl_access")
        except OSError as error:
            if error.errno not in (errno.ENODATA, errno.ENOTSUP):
                raise
            try:
                os.removexattr(target, "system.posix_acl_access")
            except OSError as error:
                if error.errno not in (errno.ENODATA, errno.ENOTSUP):
                    raise
            return
        os.setxattr(target, "system.posix_acl_access", acl)
    finally:
        os.close(source)


def _replace(directory, descriptor, data, previous, info):
    name = ".instructions-" + uuid.uuid4().hex
    path = directory / name
    fd = os.open(path if descriptor is None else name, os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                 0o600, dir_fd=descriptor)
    try:
        with os.fdopen(fd, "wb") as stream:
            if info is not None:
                if os.name == "nt":
                    _copy_permissions(directory / FILENAME, path)
                else:
                    os.fchown(stream.fileno(), info.st_uid, info.st_gid)
                    _copy_access_acl(directory, descriptor, stream.fileno())
                    os.fchmod(stream.fileno(), stat.S_IMODE(info.st_mode))
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        # Recheck after writing the temporary file, immediately before commit.
        current, current_info = _read(directory, descriptor)
        if current != previous or (info is None) != (current_info is None):
            raise HTTPError(409, "instructions_conflict")
        if os.name == "nt" and info is not None:
            os.replace(path, directory / FILENAME)
        elif os.name == "nt":
            # rename refuses an independently created destination on Windows.
            try:
                os.rename(path, directory / FILENAME)
            except FileExistsError:
                raise HTTPError(409, "instructions_conflict") from None
        else:
            os.replace(name, FILENAME, src_dir_fd=descriptor, dst_dir_fd=descriptor)
            try:
                os.fsync(descriptor)
            except OSError:
                # Replacement committed; durability failure cannot undo the save.
                pass
    finally:
        try:
            os.unlink(path if descriptor is None else name, dir_fd=descriptor)
        except FileNotFoundError:
            pass


class Instructions:
    def __init__(self, profiles):
        self.profiles = profiles

    def call(self, profile, body=None):
        directory = self.profiles.directories().get(profile)
        if directory is None:
            raise HTTPError(404, "instructions_profile_missing")
        data = None
        if body is not None:
            if (not isinstance(body, dict) or set(body) != {"text", "revision"}
                    or not isinstance(body["text"], str) or not isinstance(body["revision"], str)
                    or not re.fullmatch(r"[0-9a-f]{64}", body["revision"])):
                raise HTTPError(400, "instructions_invalid")
            try:
                data = body["text"].encode("utf-8", errors="strict")
            except UnicodeError:
                raise HTTPError(400, "instructions_invalid") from None
            if len(data) > MAX_INSTRUCTIONS_BYTES:
                raise HTTPError(413, "instructions_too_large")
        try:
            with self.profiles.store.lock(), _directory(directory) as (home, descriptor):
                previous, info = _read(home, descriptor)
                if data is None:
                    return {"text": previous.decode("utf-8"), "revision": _revision(previous)}
                if _revision(previous) != body["revision"]:
                    raise HTTPError(409, "instructions_conflict")
                _replace(home, descriptor, data, previous, info)
                return {"revision": _revision(data)}
        except OSError:
            raise HTTPError(503, "instructions_unavailable") from None
