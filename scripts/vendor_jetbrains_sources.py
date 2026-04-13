#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
THIRD_PARTY_ROOT = REPO_ROOT / "third_party" / "jetbrains"
MANIFEST_PATH = THIRD_PARTY_ROOT / "vendor-manifest.json"
METADATA_PATH = THIRD_PARTY_ROOT / "VENDORED_SOURCES.json"
UPSTREAM_ROOT = THIRD_PARTY_ROOT / "upstream"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def raw_url(repo: str, ref: str, path: str) -> str:
    return f"https://raw.githubusercontent.com/{repo}/{ref}/{path}"


def destination_path(source_name: str, source_path: str) -> Path:
    return UPSTREAM_ROOT / source_name / source_path


def fetch_text(url: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "android-neovim-lsp-vendor-sync",
            "Accept": "text/plain",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def sync() -> int:
    manifest = load_json(MANIFEST_PATH)
    metadata = {
        "generated_at_utc": dt.datetime.now(dt.UTC).isoformat(),
        "manifest_path": str(MANIFEST_PATH.relative_to(REPO_ROOT)),
        "sources": [],
    }

    for source in manifest["sources"]:
        source_entry = {
            "name": source["name"],
            "repo": source["repo"],
            "ref": source["ref"],
            "license": source["license"],
            "files": [],
        }
        for source_path in source["files"]:
            url = raw_url(source["repo"], source["ref"], source_path)
            try:
                content = fetch_text(url)
            except urllib.error.HTTPError as exc:
                raise SystemExit(f"Failed to fetch {url}: HTTP {exc.code}") from exc
            destination = destination_path(source["name"], source_path)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
            source_entry["files"].append(
                {
                    "path": source_path,
                    "destination": str(destination.relative_to(REPO_ROOT)),
                    "source_url": url,
                    "sha256": sha256_bytes(content),
                    "bytes": len(content),
                }
            )
        metadata["sources"].append(source_entry)

    METADATA_PATH.write_text(json.dumps(metadata, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    return 0


def check() -> int:
    manifest = load_json(MANIFEST_PATH)
    metadata = load_json(METADATA_PATH)
    metadata_sources = {source["name"]: source for source in metadata["sources"]}

    for source in manifest["sources"]:
        metadata_source = metadata_sources.get(source["name"])
        if metadata_source is None:
            print(f"Missing metadata for source {source['name']}", file=sys.stderr)
            return 1
        if metadata_source["repo"] != source["repo"] or metadata_source["ref"] != source["ref"]:
            print(f"Metadata mismatch for source {source['name']}", file=sys.stderr)
            return 1
        metadata_files = {entry["path"]: entry for entry in metadata_source["files"]}
        for source_path in source["files"]:
            entry = metadata_files.get(source_path)
            if entry is None:
                print(f"Missing metadata entry for {source['name']}:{source_path}", file=sys.stderr)
                return 1
            destination = REPO_ROOT / entry["destination"]
            if not destination.is_file():
                print(f"Missing vendored file {destination}", file=sys.stderr)
                return 1
            digest = sha256_bytes(destination.read_bytes())
            if digest != entry["sha256"]:
                print(f"SHA mismatch for {destination}", file=sys.stderr)
                return 1

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync exact-source snapshots from public JetBrains repositories.")
    parser.add_argument("--check", action="store_true", help="Validate vendored files and metadata without fetching.")
    args = parser.parse_args()
    return check() if args.check else sync()


if __name__ == "__main__":
    raise SystemExit(main())
