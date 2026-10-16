"""Private atomic storage and cross-process transactions."""

import contextlib
import ctypes
import json
import os
from pathlib import Path
import tempfile


def data_dir():
    if os.name == "nt":
        return Path(os.environ["LOCAL" "APP" "DATA"]) / "rasul-bridge"
    return Path.home() / ".local" / "share" / "rasul-bridge"


def windows_api():
    from ctypes import wintypes as w
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    security = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel.GetCurrentProcess.restype = w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.LocalFree.argtypes = [w.LPVOID]
    kernel.LocalFree.restype = w.LPVOID
    security.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, ctypes.POINTER(w.HANDLE)]
    security.GetTokenInformation.argtypes = [w.HANDLE, ctypes.c_int, w.LPVOID, w.DWORD, ctypes.POINTER(w.DWORD)]
    security.ConvertSidToStringSidW.argtypes = [w.LPVOID, ctypes.POINTER(w.LPWSTR)]
    security.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [w.LPCWSTR, w.DWORD, ctypes.POINTER(w.LPVOID), w.LPVOID]
    security.SetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, w.LPVOID]
    return kernel, security


def current_sid():
    from ctypes import wintypes as w
    kernel, security = windows_api()
    handle = w.HANDLE()
    if not security.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(handle)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        length = w.DWORD()
        security.GetTokenInformation(handle, 1, None, 0, ctypes.byref(length))
        buf = ctypes.create_string_buffer(length.value)
        if not security.GetTokenInformation(handle, 1, buf, length, ctypes.byref(length)):
            raise ctypes.WinError(ctypes.get_last_error())
        sid = ctypes.cast(buf, ctypes.POINTER(ctypes.c_void_p))[0]
        text = w.LPWSTR()
        if not security.ConvertSidToStringSidW(sid, ctypes.byref(text)):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            return text.value
        finally:
            kernel.LocalFree(text)
    finally:
        kernel.CloseHandle(handle)


def private_dir(path):
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink():
        raise ValueError("Private directory must not be a symbolic link")
    if os.name == "nt":
        from ctypes import wintypes as w
        kernel, security = windows_api()
        descriptor = w.LPVOID()
        # Protected DACL: replace explicit grants as well as inherited grants.
        sddl = "D:P(A;OICI;FA;;;" + current_sid() + ")"
        if not security.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, ctypes.byref(descriptor), None):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if not security.SetFileSecurityW(str(path), 0x80000004, descriptor):
                raise ctypes.WinError(ctypes.get_last_error())
        finally:
            kernel.LocalFree(descriptor)
    else:
        path.chmod(0o700)
    return path


class Blob(ctypes.Structure):
    _fields_ = [("size", ctypes.c_ulong), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def protect(data, decrypt=False):
    if os.name != "nt":
        return data
    source = ctypes.create_string_buffer(data)
    inp = Blob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_ubyte)))
    out = Blob()
    from ctypes import wintypes as w
    library = ctypes.WinDLL("crypt32", use_last_error=True)
    fn = library.CryptUnprotectData if decrypt else library.CryptProtectData
    fn.argtypes = [ctypes.POINTER(Blob), w.LPVOID, w.LPVOID, w.LPVOID, w.LPVOID, w.DWORD, ctypes.POINTER(Blob)]
    fn.restype = w.BOOL
    if not fn(ctypes.byref(inp), None, None, None, None, 1, ctypes.byref(out)):
        raise OSError("Protected storage failed")
    try:
        return ctypes.string_at(out.data, out.size)
    finally:
        kernel, _ = windows_api()
        kernel.LocalFree(out.data)


def atomic_write(path, data):
    path = Path(path)
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            if os.name != "nt":
                os.fchmod(stream.fileno(), 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


class Store:
    def __init__(self, root):
        self.root = private_dir(root)

    @contextlib.contextmanager
    def lock(self):
        with open(self.root / "transaction.lock", "a+b") as stream:
            if os.name == "nt":
                import msvcrt
                stream.seek(0)
                stream.write(b"\0")
                stream.flush()
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_LOCK, 1)
            else:
                import fcntl
                os.fchmod(stream.fileno(), 0o600)
                fcntl.flock(stream, fcntl.LOCK_EX)
            try:
                yield
            finally:
                if os.name == "nt":
                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(stream, fcntl.LOCK_UN)

    def read(self, name, default=None):
        path = self.root / name
        if not path.exists():
            return default
        return json.loads(protect(path.read_bytes(), decrypt=True))

    def write(self, name, value):
        atomic_write(self.root / name, protect(json.dumps(value).encode()))

    @contextlib.contextmanager
    def transaction(self, name, default=None):
        with self.lock():
            value = self.read(name, {} if default is None else default)
            yield value
            self.write(name, value)
