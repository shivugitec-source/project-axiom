#!/usr/bin/env python3
"""Require immutable references for external actions used by GitHub workflows."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


# Match block mappings and flow mappings. Escaped double-quoted keys are
# rejected below because YAML decodes them before GitHub interprets mapping keys.
USES_KEY = re.compile(
    r"""^\s*(?:-\s*)?(?P<block_key>uses|'uses'|"(?:[^"\\\\]|\\\\.)*")\s*:\s*|[{,]\s*(?P<flow_key>uses|'uses'|"(?:[^"\\\\]|\\\\.)*")\s*:\s*"""
)
EXTERNAL_ACTION = re.compile(
    r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-fA-F]{40}$"
)
DOCKER_ACTION = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-fA-F]{64}$")


def strip_yaml_comment(line: str) -> str:
    """Remove a YAML comment without truncating quoted scalar content."""
    quote: str | None = None
    index = 0
    while index < len(line):
        character = line[index]
        if quote == "'":
            if character == "'" and index + 1 < len(line) and line[index + 1] == "'":
                index += 2
                continue
            if character == "'":
                quote = None
        elif quote == '"':
            if character == "\\":
                index += 2
                continue
            if character == '"':
                quote = None
        elif character in {"'", '"'}:
            quote = character
        elif character == "#" and (index == 0 or line[index - 1].isspace()):
            return line[:index]
        index += 1
    return line


def extract_reference(raw_value: str) -> str:
    """Extract a scalar action reference from block or flow-style YAML."""
    value = raw_value.strip()
    if not value:
        return ""

    if value[0] in {"'", '"'}:
        quote = value[0]
        characters: list[str] = []
        index = 1
        while index < len(value):
            character = value[index]
            if quote == "'" and character == "'" and index + 1 < len(value) and value[index + 1] == "'":
                characters.append("'")
                index += 2
                continue
            if quote == '"' and character == "\\" and index + 1 < len(value):
                characters.append(value[index + 1])
                index += 2
                continue
            if character == quote:
                trailing = value[index + 1 :].strip()
                if trailing and not trailing.startswith((",", "}")):
                    return value
                return "".join(characters)
            characters.append(character)
            index += 1
        return value

    # A flow-mapping value ends at its comma or closing brace. A block scalar
    # has no such delimiter, so the same split safely leaves its full value.
    return re.split(r"[,}]", value, maxsplit=1)[0].strip()


def is_safe_local_reference(reference: str) -> bool:
    """Allow only repository-relative local actions/workflows without traversal."""
    if not reference.startswith("./") or "@" in reference:
        return False
    segments = reference[2:].split("/")
    return bool(segments) and all(segment not in {"", ".", ".."} for segment in segments)


def verify_workflows(workflow_directory: Path) -> list[str]:
    """Return violations for mutable or unsupported workflow action references."""
    if not workflow_directory.is_dir():
        return [f"workflow directory does not exist: {workflow_directory}"]

    workflow_files = sorted(
        path
        for pattern in ("*.yml", "*.yaml")
        for path in workflow_directory.rglob(pattern)
        if path.is_file()
    )
    if not workflow_files:
        return [f"no GitHub workflow YAML files found in {workflow_directory}"]

    violations: list[str] = []
    for workflow_file in workflow_files:
        try:
            lines = workflow_file.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError) as exception:
            violations.append(f"cannot read workflow {workflow_file}: {exception}")
            continue

        for line_number, line in enumerate(lines, start=1):
            uncommented = strip_yaml_comment(line)
            for match in USES_KEY.finditer(uncommented):
                key = match.group("block_key") or match.group("flow_key")
                if key.startswith('"'):
                    if "\\" in key:
                        violations.append(
                            f"{workflow_file}:{line_number}: escaped double-quoted YAML mapping "
                            "keys are unsupported because they can disguise the 'uses' key"
                        )
                        continue
                    if key != '"uses"':
                        continue
                elif key not in {"uses", "'uses'"}:
                    continue

                reference = extract_reference(uncommented[match.end() :])
                if is_safe_local_reference(reference):
                    continue
                if EXTERNAL_ACTION.fullmatch(reference) or DOCKER_ACTION.fullmatch(reference):
                    continue

                violations.append(
                    f"{workflow_file}:{line_number}: external action must use a full "
                    f"40-character commit SHA (or a container digest): {reference!r}"
                )

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Require immutable GitHub Actions and container-action references."
    )
    parser.add_argument(
        "--workflow-directory",
        type=Path,
        default=Path(__file__).resolve().parents[1] / ".github" / "workflows",
        help="Directory containing GitHub workflow YAML files",
    )
    args = parser.parse_args()

    violations = verify_workflows(args.workflow_directory)
    if violations:
        for violation in violations:
            print(f"ERROR: {violation}", file=sys.stderr)
        return 1

    print("GitHub workflow action references use immutable revisions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
