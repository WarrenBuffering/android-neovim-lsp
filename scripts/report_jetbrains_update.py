#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = REPO_ROOT / "third_party" / "jetbrains" / "vendor-manifest.json"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def fetch_json(url: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "android-neovim-lsp-vendor-report",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def fetch_text(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "android-neovim-lsp-vendor-report"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8", errors="replace")


def summarize_notes(urls: list[str]) -> list[tuple[str, list[str]]]:
    sections: list[tuple[str, list[str]]] = []
    for url in urls:
        try:
            text = fetch_text(url)
        except Exception as exc:  # noqa: BLE001
            sections.append((url, [f"Failed to fetch notes: {exc}"]))
            continue
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        sections.append((url, lines[:20]))
    return sections


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize upstream JetBrains changes before bumping vendored refs.")
    parser.add_argument("--source", help="Source name from vendor-manifest.json. Defaults to the first source.")
    parser.add_argument("--new-ref", required=True, help="Target upstream git ref/commit to compare against.")
    parser.add_argument("--notes-url", action="append", default=[], help="Optional changelog or release-notes URL to fetch and summarize.")
    parser.add_argument("--report", help="Optional output path for markdown report. Defaults to stdout.")
    args = parser.parse_args()

    manifest = load_json(MANIFEST_PATH)
    sources = manifest["sources"]
    source = next((item for item in sources if item["name"] == args.source), sources[0] if sources else None)
    if source is None:
        print("No vendored sources configured.", file=sys.stderr)
        return 1

    old_ref = source["ref"]
    repo = source["repo"]
    compare_url = f"https://api.github.com/repos/{repo}/compare/{old_ref}...{args.new_ref}"
    compare = fetch_json(compare_url)

    vendored_files = set(source["files"])
    vendored_dirs = {path.rsplit("/", 1)[0] + "/" for path in vendored_files if "/" in path}

    exact_matches = []
    nearby_matches = []
    for entry in compare.get("files", []):
        filename = entry["filename"]
        if filename in vendored_files:
            exact_matches.append(entry)
        elif any(filename.startswith(prefix) for prefix in vendored_dirs):
            nearby_matches.append(entry)

    note_sections = summarize_notes(args.notes_url)
    compare_html = f"https://github.com/{repo}/compare/{old_ref}...{args.new_ref}"

    report = [
        f"# JetBrains Vendor Update Report: {source['name']}",
        "",
        f"- Repo: `{repo}`",
        f"- Current ref: `{old_ref}`",
        f"- Target ref: `{args.new_ref}`",
        f"- Compare: {compare_html}",
        f"- Upstream status: {compare.get('status', 'unknown')}",
        "",
        "## Directly Vendored File Changes",
        "",
    ]
    if exact_matches:
        for entry in exact_matches:
            report.append(f"- `{entry['status']}` `{entry['filename']}` (+{entry.get('additions', 0)} / -{entry.get('deletions', 0)})")
    else:
        report.append("- None of the currently vendored files changed directly.")

    report.extend(["", "## Nearby Upstream Changes", ""])
    if nearby_matches:
        for entry in nearby_matches[:50]:
            report.append(f"- `{entry['status']}` `{entry['filename']}` (+{entry.get('additions', 0)} / -{entry.get('deletions', 0)})")
    else:
        report.append("- No nearby changes in the vendored directories.")

    report.extend(["", "## Commits", ""])
    for commit in compare.get("commits", [])[:20]:
        sha = commit["sha"][:12]
        message = commit["commit"]["message"].splitlines()[0]
        url = commit["html_url"]
        report.append(f"- `{sha}` {message} ({url})")

    report.extend(["", "## Notes Summary", ""])
    if note_sections:
        for url, lines in note_sections:
            report.append(f"### {url}")
            report.extend([f"- {line}" for line in lines] or ["- No non-empty lines found."])
            report.append("")
    else:
        report.append("- No changelog URLs were supplied.")

    output = "\n".join(report).rstrip() + "\n"
    if args.report:
        Path(args.report).write_text(output, encoding="utf-8")
    else:
        print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
