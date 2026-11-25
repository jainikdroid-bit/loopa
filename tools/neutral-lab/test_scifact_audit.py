import io
import json
import pathlib
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import scifact_audit as audit

class AuditTest(unittest.TestCase):
    def test_wrong_checksum(self):
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d)/"archive.tar.gz"
            p.write_bytes(b"not a verified dataset")
            with self.assertRaises(ValueError):
                audit.verified_bytes(p)

    def test_synthetic_archive_schema(self):
        data=io.BytesIO()
        with tarfile.open(fileobj=data, mode="w:gz") as t:
            for name, rows in [("corpus.jsonl",[{"doc_id":1}]),
                               ("claims_dev.jsonl",[{"evidence":{"1":[{"label":"SUPPORT"}]}}])]:
                raw=b"".join(json.dumps(r).encode()+b"\n" for r in rows)
                info=tarfile.TarInfo("data/"+name)
                info.size=len(raw)
                t.addfile(info,io.BytesIO(raw))
        with patch.object(audit,"verified_bytes",return_value=data.getvalue()):
            result=audit.audit(pathlib.Path("unused"))
        self.assertEqual(result["corpus_documents"],1)
        self.assertEqual(result["positive_retrieval_pairs"],1)

if __name__=="__main__":
    unittest.main()
