"""Import/export coarse technical diagnostics only; no media, preference or search metadata."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile

KINDS = frozenset({
    "first_frame", "buffering", "playback_error", "player_background",
    "preview_start", "preview_error", "preview_release", "prefetch_cancel",
})
MAX_BYTES = 256 * 1024
MAX_COUNT = 10000
ID = re.compile(r"[0-9a-f]{64}\Z")

def validate(report):
    if type(report) is not dict or set(report) != {"schema", "window_days", "counts"}:
        raise ValueError("unexpected report fields")
    if type(report["schema"]) is not int or report["schema"] != 1:
        raise ValueError("unsupported schema")
    if type(report["window_days"]) is not int or report["window_days"] != 30:
        raise ValueError("unsupported time window")
    counts = report["counts"]
    if type(counts) is not dict or not set(counts).issubset(KINDS) or not counts:
        raise ValueError("unexpected count category")
    if any(type(n) is not int or n < 0 or n > MAX_COUNT for n in counts.values()):
        raise ValueError("invalid count")
    if sum(counts.values()) > MAX_COUNT:
        raise ValueError("total exceeds journal capacity")
    return {"schema": 1, "window_days": 30,
            "counts": {k: counts.get(k, 0) for k in sorted(KINDS)}}

def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result

def file_bytes(path):
    if not path.is_file() or path.is_symlink():
        raise ValueError("input must be a regular file")
    with path.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("input too large")
    return raw

def count_rows(rows):
    counts = {}
    for kind, value in rows:
        if kind not in KINDS or kind in counts:
            raise ValueError("unexpected or repeated category")
        if isinstance(value, str):
            if not re.fullmatch(r"[0-9]{1,5}", value):
                raise ValueError("invalid count")
            value = int(value)
        counts[kind] = value
    return validate({"schema": 1, "window_days": 30, "counts": counts})

def import_report(path):
    path = Path(path)
    raw = file_bytes(path)
    ext = path.suffix.lower()
    if ext == ".json":
        return validate(json.loads(raw.decode("utf-8-sig"), object_pairs_hook=pairs))
    if ext == ".csv":
        rows = csv.reader(io.StringIO(raw.decode("utf-8-sig")), strict=True)
        if next(rows, None) != ["kind", "count"]:
            raise ValueError("expected kind,count CSV header")
        return count_rows(rows)
    if ext in (".sqlite", ".db"):
        # Work from the bounded bytes, not from a mutable original or external WAL.
        with tempfile.TemporaryDirectory() as temp:
            copy = Path(temp) / "input.sqlite"
            copy.write_bytes(raw)
            db = sqlite3.connect(copy.as_uri() + "?mode=ro&immutable=1", uri=True)
            try:
                db.execute("PRAGMA trusted_schema=OFF")
                db.execute("PRAGMA query_only=ON")
                schema = db.execute("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall()
                if len(schema) != 1 or schema[0][:2] != ("table", "technical_counts"):
                    raise ValueError("unexpected database schema")
                if not re.match(r"^\s*CREATE\s+TABLE\b", schema[0][2] or "", re.I):
                    raise ValueError("not an ordinary table")
                cols = db.execute("PRAGMA table_info(technical_counts)").fetchall()
                if [r[1] for r in cols] != ["kind", "count"]:
                    raise ValueError("unexpected database columns")
                db.set_progress_handler(lambda: 1, 100000)
                return count_rows(db.execute("SELECT kind,count FROM technical_counts LIMIT 9"))
            finally:
                db.close()
    raise ValueError("supported formats: JSON, CSV, SQLite")

def encoded(report):
    return json.dumps(validate(report), sort_keys=True, separators=(",", ":")).encode("utf-8")

class Archive:
    """Content-addressed aggregate snapshots. Single-user, local, no networking."""
    def __init__(self, directory):
        self.root = Path(directory)
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.root.is_symlink() or not self.root.is_dir():
            raise ValueError("invalid archive directory")
        if os.name == "posix" and self.root.stat().st_mode & 0o077:
            raise ValueError("archive directory must be private (chmod 700)")

    def _path(self, ident):
        if not ID.fullmatch(ident):
            raise ValueError("invalid report ID")
        path = self.root / (ident + ".json")
        if path.is_symlink():
            raise ValueError("unexpected symlink")
        return path

    def add(self, report):
        data = encoded(report)
        ident = hashlib.sha256(data).hexdigest()
        path = self._path(ident)
        if path.exists():
            self.get(ident)
            return ident, False
        if len(list(self.root.glob("*.json"))) >= 1000:
            raise ValueError("archive full; delete unused reports")
        # Same-directory temporary file, then an atomic create-only hard link.
        fd, temp = tempfile.mkstemp(prefix=".stage-", dir=self.root)
        try:
            with os.fdopen(fd, "wb") as out:
                out.write(data)
                out.flush()
                os.fsync(out.fileno())
            try:
                os.link(temp, path)
            except FileExistsError:
                self.get(ident)
                return ident, False
        finally:
            Path(temp).unlink(missing_ok=True)
        return ident, True

    def get(self, ident):
        raw = file_bytes(self._path(ident))
        if hashlib.sha256(raw).hexdigest() != ident:
            raise ValueError("report checksum mismatch")
        return validate(json.loads(raw, object_pairs_hook=pairs))

    def list(self):
        return sorted(p.stem for p in self.root.glob("*.json")
                      if ID.fullmatch(p.stem) and p.is_file() and not p.is_symlink())

    def delete(self, ident):
        self._path(ident).unlink()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", required=True, type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    imp = sub.add_parser("import")
    imp.add_argument("file", type=Path)
    sub.add_parser("list")
    show = sub.add_parser("show")
    show.add_argument("id")
    delete = sub.add_parser("delete")
    delete.add_argument("id")
    args = parser.parse_args()
    try:
        archive = Archive(args.store)
        if args.command == "import":
            ident, created = archive.add(import_report(args.file))
            result = {"id": ident, "created": created}
        elif args.command == "list":
            result = {"reports": archive.list()}
        elif args.command == "show":
            result = archive.get(args.id)
        else:
            archive.delete(args.id)
            result = {"deleted": args.id}
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, sqlite3.Error, csv.Error, UnicodeError, RecursionError) as exc:
        # Do not echo arbitrary input strings or file contents on failure.
        parser.exit(2, "Import/archive operation rejected: " + type(exc).__name__ + "\n")

if __name__ == "__main__":
    main()
