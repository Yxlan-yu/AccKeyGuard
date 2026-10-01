#!/usr/bin/env python3
"""Deterministic Magisk/KernelSU module packager.

Always produces forward-slash entry names and correct permission bits,
regardless of the host OS. On Windows a naive Compress-Archive / "Send to
compressed folder" writes entries such as ``webroot\\index.html``, which every
Android unzipper turns into a *flat file literally named* ``webroot\\index.html``.
KernelSU then cannot find a ``webroot/`` directory and silently omits the WebUI
button, and ``service.sh`` cannot exec ``$MODDIR/data/accd.sh``.

Usage:  python pack.py [output.zip]
"""

import os
import stat
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

# Files shipped in the installable zip, in fixed order.
FILES = [
    "module.prop",
    "customize.sh",
    "service.sh",
    "README.md",
    "data/accd.sh",
    "webroot/index.html",
    "webroot/style.css",
    "webroot/app.js",
    "webroot/kernelsu.js",
    "webroot/icon.svg",
]

# Empty directories that must exist in the archive.
DIRS = ["webroot", "data"]

EXECUTABLE = {"customize.sh", "service.sh", "data/accd.sh"}


def read_version():
    version, code = "v0.0.0", 0
    with open(os.path.join(HERE, "module.prop"), encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("version="):
                version = line.split("=", 1)[1].strip()
            elif line.startswith("versionCode="):
                code = int(line.split("=", 1)[1].strip())
    return version, code


def check(name):
    """Sanity-check one file before it enters the archive."""
    path = os.path.join(HERE, name)
    if not os.path.isfile(path):
        raise SystemExit("missing required file: %s" % name)
    if "\\" in name:
        raise SystemExit("entry name must use forward slashes: %s" % name)
    with open(path, "rb") as fh:
        blob = fh.read()
    if blob[:3] == b"\xef\xbb\xbf":
        raise SystemExit("%s has a UTF-8 BOM" % name)
    if b"\r\n" in blob:
        raise SystemExit("%s has CRLF line endings" % name)
    return blob


def build(out_path):
    version, _code = read_version()

    for name in FILES:
        check(name)
    for d in DIRS:
        if not os.path.isdir(os.path.join(HERE, d)):
            raise SystemExit("missing required directory: %s" % d)

    entries = []
    for d in DIRS:
        entries.append((d + "/", b""))
    for name in FILES:
        entries.append((name, check(name)))

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, blob in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            if name.endswith("/"):
                info.external_attr = (stat.S_IFDIR | 0o755) << 16 | 0x10
            else:
                mode = 0o755 if name in EXECUTABLE else 0o644
                info.external_attr = (stat.S_IFREG | mode) << 16
            info.create_system = 3  # Unix, so the mode bits are honoured
            zf.writestr(info, blob)

    verify(out_path)
    print("packed %s  (%d entries, %d bytes)"
          % (out_path, len(entries), os.path.getsize(out_path)))
    print("version %s" % version)
    return out_path


def raw_entry_names(path):
    """Read central-directory names as raw bytes.

    zipfile.ZipFile normalises '\\\\' to '/' when reading, which hides exactly
    the defect we are guarding against. Parsing the central directory by hand
    is the only trustworthy check.
    """
    raw = open(path, "rb").read()
    end = raw.rfind(b"PK\x05\x06")
    if end < 0:
        raise SystemExit("not a zip file")
    cd_size, cd_off = struct.unpack_from("<II", raw, end + 12)
    names, pos = [], cd_off
    while pos < cd_off + cd_size:
        if raw[pos:pos + 4] != b"PK\x01\x02":
            raise SystemExit("corrupt central directory at %d" % pos)
        nlen = struct.unpack_from("<H", raw, pos + 28)[0]
        elen = struct.unpack_from("<H", raw, pos + 30)[0]
        clen = struct.unpack_from("<H", raw, pos + 32)[0]
        names.append(raw[pos + 46:pos + 46 + nlen])
        pos += 46 + nlen + elen + clen
    return names


def verify(path):
    names = raw_entry_names(path)
    bad = [n for n in names if b"\\" in n]
    if bad:
        raise SystemExit("backslash entries present: %r" % bad)
    for required in (b"webroot/", b"webroot/index.html", b"module.prop",
                     b"service.sh", b"data/accd.sh"):
        if required not in names:
            raise SystemExit("archive missing %r" % required)
    zf = zipfile.ZipFile(path)
    for info in zf.infolist():
        if info.filename.endswith(".sh"):
            mode = (info.external_attr >> 16) & 0o777
            if mode != 0o755:
                raise SystemExit("%s has mode %o, want 755"
                                 % (info.filename, mode))
    print("verified: %d entries, all forward-slash, exec bits set"
          % len(names))


if __name__ == "__main__":
    version, code = read_version()
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(HERE), "AccKeyGuard-%s.zip" % version)
    build(out)
