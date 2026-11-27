import json
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from technical_archive import Archive, import_report, validate, MAX_BYTES

class TechnicalArchiveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.archive = Archive(self.root / "store")
        self.report = {"schema": 1, "window_days": 30, "counts": {"buffering": 8}}

    def tearDown(self):
        self.tmp.cleanup()

    def test_formats_and_deduplication(self):
        j = self.root / "report.json"
        j.write_text(json.dumps(self.report), encoding="utf-8")
        c = self.root / "report.csv"
        c.write_text("kind,count\nbuffering,8\n", encoding="utf-8")
        s = self.root / "report.sqlite"
        with closing(sqlite3.connect(s)) as db, db:
            db.execute("CREATE TABLE technical_counts(kind TEXT,count INTEGER)")
            db.execute("INSERT INTO technical_counts VALUES (?,?)", ("buffering", 8))
        first, created = self.archive.add(import_report(j))
        self.assertTrue(created)
        for p in [c, s]:
            self.assertEqual(self.archive.add(import_report(p)), (first, False))
        self.assertEqual(self.archive.list(), [first])
        self.assertEqual(self.archive.get(first)["counts"]["buffering"], 8)
        self.archive.delete(first)
        self.assertEqual(self.archive.list(), [])

    def test_reject_unknown_fields_and_categories(self):
        for report in [
            {**self.report, "query": "private"},
            {**self.report, "counts": {"unknown": 1}},
            {**self.report, "counts": {"buffering": True}},
            {**self.report, "counts": {"buffering": -1}},
            {**self.report, "counts": {"buffering": 10000, "first_frame": 1}},
        ]:
            with self.assertRaises(ValueError):
                validate(report)

    def test_reject_duplicate_json_and_csv(self):
        j = self.root / "bad.json"
        j.write_text('{"schema":1,"schema":1,"window_days":30,"counts":{"buffering":8}}')
        c = self.root / "bad.csv"
        c.write_text("kind,count\nbuffering,1\nbuffering,2\n")
        for p in [j, c]:
            with self.assertRaises(ValueError):
                import_report(p)

    def test_reject_large_input(self):
        p = self.root / "big.json"
        p.write_bytes(b" " * (MAX_BYTES + 1))
        with self.assertRaises(ValueError):
            import_report(p)

    def test_reject_unexpected_sqlite_schema(self):
        p = self.root / "wrong.sqlite"
        with closing(sqlite3.connect(p)) as db, db:
            db.execute("CREATE TABLE other(kind TEXT,count INTEGER)")
            db.execute("CREATE VIEW technical_counts AS SELECT * FROM other")
        with self.assertRaises(ValueError):
            import_report(p)

    def test_checksum_and_path_validation(self):
        ident, _ = self.archive.add(self.report)
        with self.assertRaises(ValueError):
            self.archive.get("../report")
        (self.archive.root / (ident + ".json")).write_text("{}")
        with self.assertRaises(ValueError):
            self.archive.get(ident)

if __name__ == "__main__":
    unittest.main()
