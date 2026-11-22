#!/usr/bin/env python3
"""Reject edits, deletions and renames of already committed Flyway migrations."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


MIGRATION_DIRECTORY = "backend/axiom-parent/axiom-migrator/src/main/resources/db/migration"
COMMIT_SHA = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$", re.IGNORECASE)


def verify_migration_history(repository: Path, base_ref: str, head_ref: str) -> list[str]:
    """Return violations when an existing migration resource changes between two commits."""
    for label, revision in (("base", base_ref), ("head", head_ref)):
        if not COMMIT_SHA.fullmatch(revision):
            return [f"{label} revision must be a full Git commit SHA"]

    result = subprocess.run(
        [
            "git",
            "diff",
            "--name-status",
            "-z",
            "--find-renames",
            base_ref,
            head_ref,
            "--",
            MIGRATION_DIRECTORY,
        ],
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or "git diff returned a non-zero exit status"
        return [f"cannot compare migration history: {detail}"]

    fields = result.stdout.split("\0")
    violations: list[str] = []
    index = 0
    while index < len(fields) - 1:
        status = fields[index]
        index += 1

        if status.startswith(("R", "C")):
            if index + 1 >= len(fields):
                return ["git returned an incomplete rename/copy record"]
            old_path, new_path = fields[index], fields[index + 1]
            index += 2
            violations.append(
                f"existing migration resource is immutable; rename/copy detected: "
                f"{old_path} -> {new_path}"
            )
            continue

        if index >= len(fields):
            return ["git returned an incomplete migration change record"]
        path = fields[index]
        index += 1

        if status != "A":
            if status in {"M", "D", "T", "U", "X", "B"}:
                violations.append(
                    f"existing migration resource is immutable (Git status {status}): {path}"
                )
            else:
                violations.append(
                    f"unrecognized Git change status {status!r} for migration resource {path}; "
                    "failing closed"
                )

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Fail if a Flyway migration resource already present in the base commit "
            "is modified, deleted or renamed."
        )
    )
    parser.add_argument("base_commit", help="Full base commit SHA")
    parser.add_argument("head_commit", help="Full head commit SHA")
    args = parser.parse_args()

    repository = Path(__file__).resolve().parents[1]
    violations = verify_migration_history(repository, args.base_commit, args.head_commit)
    if violations:
        for violation in violations:
            print(f"ERROR: {violation}", file=sys.stderr)
        return 1

    print("Flyway migration history is immutable; changes are additive.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
