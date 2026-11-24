#!/usr/bin/env python3
"""Require immutable references for GitHub Actions and workflow service images."""

from __future__ import annotations

import argparse
import ast
import re
import sys
from pathlib import Path


# Match block mappings and flow mappings. Escaped double-quoted keys are
# rejected below because YAML decodes them before GitHub interprets mapping keys.
# Reject YAML mapping-key forms whose meaning requires resolving YAML indirection.
# This line-oriented, standard-library verifier deliberately fails closed on them.
EXPLICIT_MAPPING_KEY = re.compile(
    r"""(?:^\s*(?:-\s*)?|\{\s*|,\s*)\?\s+"""
)
ALIASED_MAPPING_KEY = re.compile(
    r"""(?:^\s*(?:-\s*)?|\{\s*|,\s*)\*[^\s,:{}]+\s*:"""
)
# YAML permits an anchor and a tag on the same node, in either order.
# Treat one or more node properties before a mapping key as unsupported.
YAML_NODE_PROPERTY = r"(?:&[^\s]+|!![^\s]+|!(?:[^\s]+)?)"
DECORATED_MAPPING_KEY = re.compile(
    rf"""(?:^\s*(?:-\s*)?|\{{\s*|,\s*)(?:{YAML_NODE_PROPERTY}\s+)+(?:'[^']*'|"(?:[^"\\]|\\.)*"|[^\s:]+)\s*:"""
)

USES_KEY = re.compile(
    r"""^\s*(?:-\s*)?(?P<block_key>uses|'uses'|"(?:[^"\\]|\\.)*")\s*:\s*|[{,]\s*(?P<flow_key>uses|'uses'|"(?:[^"\\]|\\.)*")\s*:\s*"""
)
EXTERNAL_ACTION = re.compile(
    r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-fA-F]{40}$"
)
DOCKER_ACTION = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-fA-F]{64}$")
IMAGE_KEY = re.compile(
    r"""^\s*(?:-\s*)?(?P<block_key>image|'image'|"(?:[^"\\]|\\.)*")\s*:\s*|[{,]\s*(?P<flow_key>image|'image'|"(?:[^"\\]|\\.)*")\s*:\s*"""
)
IMAGE_REFERENCE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-fA-F]{64}$")
STEP_START = re.compile(r"^(?P<indent> *)-\s+")
WITH_MAPPING = re.compile(r"^(?P<indent> *)with\s*:\s*(?P<value>.*)$")
PERSIST_CREDENTIALS = re.compile(
    r"^(?P<indent> *)persist-credentials\s*:\s*(?P<value>.*?)\s*$"
)


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


def is_escaped_double_quoted_key(key: str, expected: str) -> bool:
    """Return whether an escaped YAML double-quoted key decodes to expected."""
    if not key.startswith('"') or "\\" not in key:
        return False
    try:
        decoded = ast.literal_eval(key)
    except (SyntaxError, ValueError):
        return False
    return isinstance(decoded, str) and decoded == expected

def checkout_credentials_disabled(lines: list[str], uses_line_index: int) -> bool:
    """Require actions/checkout to remove its token from later step environments."""
    uncommented = strip_yaml_comment(lines[uses_line_index])
    inline_with_matches = list(
        re.finditer(
            r"(?:^|,)\s*with\s*:\s*\{(?P<contents>[^{}]*)\}", uncommented
        )
    )
    if inline_with_matches:
        if len(inline_with_matches) != 1:
            return False
        inline_with = inline_with_matches[0]
        values = re.findall(
            r"""(?:^|,)\s*['"]?persist-credentials['"]?\s*:\s*([^,}]+)""",
            inline_with.group("contents"),
        )
        return len(values) == 1 and values[0].strip() == "false"

    current_indent = len(lines[uses_line_index]) - len(
        lines[uses_line_index].lstrip(" ")
    )
    step_start = None
    step_indent = None
    for candidate_index in range(uses_line_index, -1, -1):
        candidate = strip_yaml_comment(lines[candidate_index])
        match = STEP_START.match(candidate)
        if match:
            candidate_indent = len(match.group("indent"))
            if candidate_index == uses_line_index or candidate_indent < current_indent:
                step_start = candidate_index
                step_indent = candidate_indent
                break
    if step_start is None or step_indent is None:
        return False

    step_end = len(lines)
    for candidate_index in range(step_start + 1, len(lines)):
        candidate = strip_yaml_comment(lines[candidate_index])
        match = STEP_START.match(candidate)
        if match and len(match.group("indent")) <= step_indent:
            step_end = candidate_index
            break

    with_blocks: list[tuple[int, int, str]] = []
    for candidate_index in range(step_start + 1, step_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        match = WITH_MAPPING.match(candidate)
        if match and len(match.group("indent")) > step_indent:
            with_blocks.append(
                (candidate_index, len(match.group("indent")), match.group("value"))
            )
    if len(with_blocks) != 1:
        return False

    with_index, with_indent, inline_value = with_blocks[0]
    if inline_value.strip():
        if not inline_value.lstrip().startswith("{"):
            return False
        values = re.findall(
            r"""(?:^|,)\s*['"]?persist-credentials['"]?\s*:\s*([^,}]+)""",
            inline_value.lstrip()[1:],
        )
        return len(values) == 1 and values[0].strip() == "false"

    with_end = step_end
    for candidate_index in range(with_index + 1, step_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        if not candidate.strip():
            continue
        indentation = len(candidate) - len(candidate.lstrip(" "))
        if indentation <= with_indent:
            with_end = candidate_index
            break

    values: list[str] = []
    for candidate_index in range(with_index + 1, with_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        match = PERSIST_CREDENTIALS.match(candidate)
        if match and len(match.group("indent")) > with_indent:
            values.append(match.group("value").strip())
    return len(values) == 1 and values[0] == "false"


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
            if (
                EXPLICIT_MAPPING_KEY.search(uncommented)
                or ALIASED_MAPPING_KEY.search(uncommented)
                or DECORATED_MAPPING_KEY.search(uncommented)
            ):
                violations.append(
                    f"{workflow_file}:{line_number}: explicit, anchored, aliased, or tagged "
                    "YAML mapping keys are unsupported; use a plain or quoted 'uses' key"
                )
                continue

            for match in USES_KEY.finditer(uncommented):
                key = match.group("block_key") or match.group("flow_key")
                if key.startswith('"') and "\\" in key:
                    if is_escaped_double_quoted_key(key, "uses"):
                        violations.append(
                            f"{workflow_file}:{line_number}: escaped double-quoted YAML mapping "
                            "keys are unsupported because they can disguise the 'uses' key"
                        )
                    continue
                if key not in {"uses", "'uses'", '"uses"'}:
                    continue

                reference = extract_reference(uncommented[match.end() :])
                if is_safe_local_reference(reference):
                    continue
                if EXTERNAL_ACTION.fullmatch(reference) or DOCKER_ACTION.fullmatch(reference):
                    if reference.startswith("actions/checkout@") and not checkout_credentials_disabled(
                        lines, line_number - 1
                    ):
                        violations.append(
                            f"{workflow_file}:{line_number}: actions/checkout must set "
                            "with.persist-credentials: false so later steps cannot read the checkout token"
                        )
                    continue

                violations.append(
                    f"{workflow_file}:{line_number}: external action must use a full "
                    f"40-character commit SHA (or a container digest): {reference!r}"
                )

            for match in IMAGE_KEY.finditer(uncommented):
                key = match.group("block_key") or match.group("flow_key")
                if key.startswith('"') and "\\" in key:
                    if is_escaped_double_quoted_key(key, "image"):
                        violations.append(
                            f"{workflow_file}:{line_number}: escaped double-quoted YAML mapping "
                            "keys are unsupported because they can disguise an 'image' key"
                        )
                    continue
                if key not in {"image", "'image'", '"image"'}:
                    continue

                reference = extract_reference(uncommented[match.end() :])
                if IMAGE_REFERENCE.fullmatch(reference):
                    continue

                violations.append(
                    f"{workflow_file}:{line_number}: workflow service/container image "
                    f"must use a full sha256 digest: {reference!r}"
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

    print("GitHub workflow actions and service images use immutable revisions/digests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
