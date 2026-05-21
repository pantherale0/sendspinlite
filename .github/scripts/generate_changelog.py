#!/usr/bin/env python3

"""Generate release changelog markdown from git commits."""

from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

FEAT_PREFIX = re.compile(r"^feat(\([^)]+\))?!?:\s*", re.IGNORECASE)
FIX_PREFIX = re.compile(r"^fix(\([^)]+\))?!?:\s*", re.IGNORECASE)
PERF_PREFIX = re.compile(r"^perf(\([^)]+\))?!?:", re.IGNORECASE)
REFACTOR_PREFIX = re.compile(r"^refactor(\([^)]+\))?!?:", re.IGNORECASE)


def run_git(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        check=check,
    )


def get_previous_tag(current_tag: str) -> str:
    try:
        result = run_git(["describe", "--tags", "--abbrev=0", f"{current_tag}^"])
    except subprocess.CalledProcessError:
        return ""
    return result.stdout.strip()


def get_commit_subjects(commit_range: str) -> list[str]:
    result = run_git(["log", commit_range, "--pretty=format:%s"])
    subjects = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not subjects:
        raise RuntimeError(f"No commits found for range '{commit_range}'.")
    return subjects


def strip_prefix(subject: str, prefix: re.Pattern[str]) -> str:
    return prefix.sub("", subject, count=1).strip()


def build_changelog(version: str, tag: str, sha: str, subjects: list[str]) -> str:
    features: list[str] = []
    fixes: list[str] = []
    other_changes: list[str] = []

    for subject in subjects:
        if FEAT_PREFIX.match(subject):
            features.append(strip_prefix(subject, FEAT_PREFIX))
            continue
        if FIX_PREFIX.match(subject):
            fixes.append(strip_prefix(subject, FIX_PREFIX))
            continue
        if PERF_PREFIX.match(subject) or REFACTOR_PREFIX.match(subject):
            continue
        other_changes.append(subject)

    lines = [
        f"## Version {version}",
        "",
        f"**Release Date:** {dt.datetime.now(dt.timezone.utc):%Y-%m-%d}",
        "",
    ]

    if features:
        lines.extend(["### ✨ Features", ""])
        lines.extend(f"- {item}" for item in features)
        lines.append("")

    if fixes:
        lines.extend(["### 🐛 Bug Fixes", ""])
        lines.extend(f"- {item}" for item in fixes)
        lines.append("")

    if other_changes:
        lines.extend(["### 📝 Changes", ""])
        lines.extend(f"- {item}" for item in other_changes)
        lines.append("")

    lines.extend(
        [
            "### 📋 Build Info",
            "",
            f"- **Commit:** {sha}",
            f"- **Tag:** {tag}",
            "- **Build:** Signed Release",
        ]
    )

    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="Current tag (for example, v1.2)")
    parser.add_argument(
        "--version", required=True, help="Version name without tag prefix (for example, 1.2)"
    )
    parser.add_argument("--sha", required=True, help="Git commit SHA to show in build info")
    parser.add_argument(
        "--output",
        default="CHANGELOG.md",
        help="Output markdown file path (default: CHANGELOG.md)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    try:
        previous_tag = get_previous_tag(args.tag)
        commit_range = "HEAD" if not previous_tag else f"{previous_tag}..HEAD"
        subjects = get_commit_subjects(commit_range)
        changelog = build_changelog(args.version, args.tag, args.sha, subjects)
        Path(args.output).write_text(changelog, encoding="utf-8")
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print(f"Failed to generate changelog: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
