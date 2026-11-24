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
YAML_MAPPING_KEY = r"""(?:'(?:[^']|'')*'|"(?:[^"\\]|\\.)*"|[^:\s{}\[\],]+)"""
BLOCK_MAPPING_KEY = re.compile(
    rf"^(?P<indent> *)(?P<key>{YAML_MAPPING_KEY})\s*:\s*(?P<value>.*?)\s*$"
)
FLOW_MAPPING_KEY = re.compile(
    rf"""(?:^|[{{,])\s*(?P<key>{YAML_MAPPING_KEY})\s*:"""
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

def decode_yaml_mapping_key(raw_key: str) -> str | None:
    """Decode a quoted YAML key without resolving aliases or YAML node properties."""
    if raw_key.startswith(("'", '"')):
        try:
            decoded = ast.literal_eval(raw_key)
        except (SyntaxError, ValueError):
            return None
        return decoded if isinstance(decoded, str) else None
    return raw_key


def has_unsupported_mapping_key(line: str) -> bool:
    """Reject YAML key indirection and merge syntax in workflow documents."""
    return any(
        pattern.search(line)
        for pattern in (EXPLICIT_MAPPING_KEY, ALIASED_MAPPING_KEY, DECORATED_MAPPING_KEY)
    ) or re.search(r"(?:^\s*(?:-\s*)?|[{,])\s*<<\s*:", line) is not None


def flow_delimiters_balanced(value: str) -> bool:
    """Require flow collections to be complete on one line for fail-closed scanning."""
    stack: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(value):
        character = value[index]
        if quote == "'":
            if character == "'" and index + 1 < len(value) and value[index + 1] == "'":
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
        elif character in {"[", "{"}:
            stack.append("]" if character == "[" else "}")
        elif character in {"]", "}"}:
            if not stack or stack.pop() != character:
                return False
        index += 1
    return quote is None and not stack


def extract_flow_mapping_contents(line: str, value_start: int) -> str | None:
    """Extract one flat flow mapping, rejecting nested collections and multiline values."""
    index = value_start
    while index < len(line) and line[index].isspace():
        index += 1
    if index >= len(line) or line[index] != "{":
        return None

    contents_start = index + 1
    quote: str | None = None
    index += 1
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
        elif character in {"{", "[", "]"}:
            # Checkout inputs must be direct scalar entries. Nested collections can
            # conceal a key that is not actually an input to actions/checkout.
            return None
        elif character == "}":
            return line[contents_start:index]
        index += 1
    return None


def split_flow_mapping_entries(contents: str) -> list[str] | None:
    """Split a flat flow mapping at commas outside quoted scalar values."""
    entries: list[str] = []
    start = 0
    quote: str | None = None
    index = 0
    while index < len(contents):
        character = contents[index]
        if quote == "'":
            if character == "'" and index + 1 < len(contents) and contents[index + 1] == "'":
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
        elif character in {"{", "[", "]", "}"}:
            return None
        elif character == ",":
            entry = contents[start:index].strip()
            if not entry:
                return None
            entries.append(entry)
            start = index + 1
        index += 1

    if quote is not None:
        return None
    tail = contents[start:].strip()
    if tail:
        entries.append(tail)
    elif contents.strip() and not contents.rstrip().endswith(","):
        return None
    return entries


def flow_credentials_disabled(contents: str) -> bool:
    """Require one canonical, literal false entry in a flat flow-style input map."""
    if has_unsupported_mapping_key(contents):
        return False

    entries = split_flow_mapping_entries(contents)
    if entries is None:
        return False

    values: list[tuple[str, str]] = []
    for entry in entries:
        match = re.match(
            rf"^\s*(?P<key>{YAML_MAPPING_KEY})\s*:\s*(?P<value>.*?)\s*$",
            entry,
        )
        if match is None:
            return False
        raw_key = match.group("key")
        decoded_key = decode_yaml_mapping_key(raw_key)
        if decoded_key is None:
            return False
        value = match.group("value").strip()
        if value.startswith(("*", "&", "!", "{", "[")):
            return False
        if decoded_key == "persist-credentials":
            values.append((raw_key, value))
    return len(values) == 1 and values[0] == ("persist-credentials", "false")


def is_block_scalar_header(value: str) -> bool:
    """Recognize YAML literal/folded scalar headers so their text is not parsed as keys."""
    return re.fullmatch(r"[|>](?:(?:[+-][1-9]?)|(?:[1-9][+-]?)|[+-]|[1-9])?", value) is not None


def block_credentials_disabled(
    lines: list[str], with_index: int, with_indent: int, step_end: int
) -> bool:
    """Require one canonical false entry among direct scalar block-style inputs."""
    with_end = step_end
    for candidate_index in range(with_index + 1, step_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        if not candidate.strip():
            continue
        indentation = len(candidate) - len(candidate.lstrip(" "))
        if indentation <= with_indent:
            with_end = candidate_index
            break

    values: list[tuple[str, str]] = []
    input_indent: int | None = None
    block_scalar_indent: int | None = None
    for candidate_index in range(with_index + 1, with_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        if not candidate.strip():
            continue
        indentation = len(candidate) - len(candidate.lstrip(" "))

        if block_scalar_indent is not None:
            if indentation > block_scalar_indent:
                continue
            block_scalar_indent = None

        if input_indent is None:
            input_indent = indentation
        if indentation != input_indent:
            # Nested mappings/sequences and continuation lines are ambiguous inputs.
            return False
        if has_unsupported_mapping_key(candidate) or candidate.lstrip().startswith("- "):
            return False

        match = BLOCK_MAPPING_KEY.match(candidate)
        if match is None:
            return False
        raw_key = match.group("key")
        decoded_key = decode_yaml_mapping_key(raw_key)
        value = match.group("value").strip()
        if decoded_key == "<<":
            return False
        if decoded_key == "persist-credentials":
            values.append((raw_key, value))
        if value.startswith(("*", "&", "!", "{", "[")):
            return False
        if is_block_scalar_header(value):
            block_scalar_indent = input_indent

    return len(values) == 1 and values[0] == ("persist-credentials", "false")


def checkout_credentials_disabled(lines: list[str], uses_line_index: int) -> bool:
    """Require one unambiguous false setting before repository-controlled steps run."""
    uncommented = strip_yaml_comment(lines[uses_line_index])
    if any(delimiter in uncommented for delimiter in "[]{}"):
        if not flow_delimiters_balanced(uncommented) or has_unsupported_mapping_key(uncommented):
            return False
        with_matches = [
            match
            for match in FLOW_MAPPING_KEY.finditer(uncommented)
            if decode_yaml_mapping_key(match.group("key")) == "with"
        ]
        if len(with_matches) != 1 or with_matches[0].group("key") != "with":
            return False
        contents = extract_flow_mapping_contents(uncommented, with_matches[0].end())
        return contents is not None and flow_credentials_disabled(contents)

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

    with_entries: list[tuple[int, str, int, str]] = []
    for candidate_index in range(step_start, step_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        if not candidate.strip():
            continue
        if has_unsupported_mapping_key(candidate):
            return False

        block_match = BLOCK_MAPPING_KEY.match(candidate)
        if block_match is not None:
            raw_key = block_match.group("key")
            if decode_yaml_mapping_key(raw_key) == "with":
                candidate_indent = len(candidate) - len(candidate.lstrip(" "))
                if candidate_indent != current_indent:
                    return False
                value = block_match.group("value").strip()
                if value.startswith("{"):
                    for flow_match in FLOW_MAPPING_KEY.finditer(candidate):
                        if decode_yaml_mapping_key(flow_match.group("key")) == "with":
                            with_entries.append(
                                (candidate_index, flow_match.group("key"), flow_match.end(), "flow")
                            )
                else:
                    with_entries.append(
                        (
                            candidate_index,
                            raw_key,
                            block_match.start("value"),
                            "block",
                        )
                    )
                continue
            if not block_match.group("value").lstrip().startswith(("[", "{")):
                continue

        for flow_match in FLOW_MAPPING_KEY.finditer(candidate):
            if decode_yaml_mapping_key(flow_match.group("key")) == "with":
                with_entries.append(
                    (candidate_index, flow_match.group("key"), flow_match.end(), "flow")
                )

    if len(with_entries) != 1 or with_entries[0][1] != "with":
        return False

    with_index, _, value_start, mapping_style = with_entries[0]
    with_line = strip_yaml_comment(lines[with_index])
    if mapping_style == "flow":
        if not flow_delimiters_balanced(with_line):
            return False
        contents = extract_flow_mapping_contents(with_line, value_start)
        return contents is not None and flow_credentials_disabled(contents)

    with_match = BLOCK_MAPPING_KEY.match(with_line)
    if with_match is None or with_match.group("value").strip():
        return False
    with_indent = len(with_match.group("indent"))
    return block_credentials_disabled(lines, with_index, with_indent, step_end)


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
            if has_unsupported_mapping_key(uncommented):
                violations.append(
                    f"{workflow_file}:{line_number}: explicit, anchored, aliased, or tagged "
                    "YAML mapping keys are unsupported; YAML merge keys are also unsupported; use a plain or quoted 'uses' key"
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
