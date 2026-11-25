"""Offline scientific dataset audit only. Never touches the application or user data."""
import argparse
import hashlib
import json
import pathlib
import tarfile
import urllib.request

URL = "https://scifact.s3-us-west-2.amazonaws.com/release/latest/data.tar.gz"
SHA256 = "11c621288d41ac144d29b13b0f8503b3820b7d6e8b1f6ff24dff335c196d76be"
MAX_ARCHIVE = 16 * 1024 * 1024
MAX_MEMBER = 32 * 1024 * 1024

def verified_bytes(path):
    if path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("archive too large")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SHA256:
        raise ValueError("archive checksum mismatch")
    return raw

def download(path):
    with urllib.request.urlopen(URL, timeout=30) as response:
        raw = response.read(MAX_ARCHIVE + 1)
    if len(raw) > MAX_ARCHIVE or hashlib.sha256(raw).hexdigest() != SHA256:
        raise ValueError("download size/checksum mismatch; no data written")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as out:
        out.write(raw)

def read_rows(archive, name):
    entries = [m for m in archive.getmembers() if pathlib.PurePosixPath(m.name).name == name]
    if len(entries) != 1 or not entries[0].isfile() or entries[0].size > MAX_MEMBER:
        raise ValueError("missing, duplicate or unsafe archive member: " + name)
    with archive.extractfile(entries[0]) as stream:
        return [json.loads(line) for line in stream if line.strip()]

def audit(path):
    import io
    with tarfile.open(fileobj=io.BytesIO(verified_bytes(path)), mode="r:gz") as archive:
        corpus = read_rows(archive, "corpus.jsonl")
        claims = read_rows(archive, "claims_dev.jsonl")
    ids = {str(d["doc_id"]) for d in corpus}
    if len(ids) != len(corpus):
        raise ValueError("duplicate document IDs")
    pairs = 0
    labelled = 0
    for claim in claims:
        relevant = set(claim.get("evidence", {}))
        if not relevant.issubset(ids):
            raise ValueError("missing evidence document")
        pairs += len(relevant)
        labelled += bool(relevant)
    return {"dataset":"SciFact","sha256":SHA256,"corpus_documents":len(corpus),
            "dev_claims":len(claims),"claims_with_evidence":labelled,
            "positive_retrieval_pairs":pairs,"note":"Counts only; no document or claim text exported"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=pathlib.Path)
    parser.add_argument("--download", action="store_true", help="Explicitly fetch the official public archive if absent")
    args = parser.parse_args()
    if args.download and not args.archive.exists():
        download(args.archive)
    print(json.dumps(audit(args.archive), indent=2))

if __name__ == "__main__":
    main()
