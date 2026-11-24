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


def extract_flow_mapping(line: str, value_start: int) -> tuple[str, int] | None:
    """Extract one flow mapping and its end offset, respecting nested collections and quotes."""
    index = value_start
    while index < len(line) and line[index].isspace():
        index += 1
    if index >= len(line) or line[index] != "{":
        return None

    contents_start = index + 1
    stack = ["}"]
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
        elif character in {"{", "["}:
            stack.append("}" if character == "{" else "]")
        elif character in {"}", "]"}:
            if not stack or stack.pop() != character:
                return None
            if not stack:
                return line[contents_start:index], index + 1
        index += 1
    return None


def flow_mapping_ranges(line: str) -> list[tuple[int, int, str]]:
    """Find flow mappings outside quoted scalars, including nested mappings."""
    ranges: list[tuple[int, int, str]] = []
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
        elif character == "{":
            extracted = extract_flow_mapping(line, index)
            if extracted is not None:
                contents, end = extracted
                ranges.append((index, end, contents))
        index += 1
    return ranges


def split_flow_mapping_entries(
    contents: str, *, allow_nested: bool = False
) -> list[str] | None:
    """Split a flow mapping at top-level commas while respecting quotes and nesting."""
    entries: list[str] = []
    start = 0
    stack: list[str] = []
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
        elif character in {"{", "["}:
            if not allow_nested:
                return None
            stack.append("}" if character == "{" else "]")
        elif character in {"}", "]"}:
            if not allow_nested or not stack or stack.pop() != character:
                return None
        elif character == "," and not stack:
            entry = contents[start:index].strip()
            if not entry:
                return None
            entries.append(entry)
            start = index + 1
        index += 1

    if quote is not None or stack:
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


def checkout_credentials_disabled(
    lines: list[str], uses_line_index: int, expected_reference: str, uses_match_start: int
) -> bool:
    """Require one direct, unambiguous false setting on the checkout step."""
    uncommented = strip_yaml_comment(lines[uses_line_index])
    if any(delimiter in uncommented for delimiter in "[]{}"):
        if not flow_delimiters_balanced(uncommented) or has_unsupported_mapping_key(uncommented):
            return False
        enclosing_maps = [
            item for item in flow_mapping_ranges(uncommented)
            if item[0] < uses_match_start < item[1]
        ]
        if not enclosing_maps:
            return False
        _, _, step_contents = min(enclosing_maps, key=lambda item: item[1] - item[0])
        entries = split_flow_mapping_entries(step_contents, allow_nested=True)
        if entries is None:
            return False

        uses_entries: list[tuple[str, str]] = []
        with_entries: list[tuple[str, str]] = []
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
            if decoded_key == "uses":
                uses_entries.append((raw_key, value))
            elif decoded_key == "with":
                with_entries.append((raw_key, value))

        if (
            len(uses_entries) != 1
            or uses_entries[0][0] not in {"uses", "'uses'", '"uses"'}
            or extract_reference(uses_entries[0][1]) != expected_reference
            or len(with_entries) != 1
            or with_entries[0][0] != "with"
        ):
            return False
        with_mapping = extract_flow_mapping(with_entries[0][1], 0)
        if with_mapping is None or with_entries[0][1][with_mapping[1] :].strip():
            return False
        return flow_credentials_disabled(with_mapping[0])

    step_match = STEP_START.match(uncommented)
    current_indent = (
        step_match.end()
        if step_match is not None
        else len(uncommented) - len(uncommented.lstrip(" "))
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

    with_entries: list[tuple[int, str, str, str | None]] = []
    for candidate_index in range(step_start, step_end):
        candidate = strip_yaml_comment(lines[candidate_index])
        if not candidate.strip():
            continue
        if has_unsupported_mapping_key(candidate):
            return False

        block_match = BLOCK_MAPPING_KEY.match(candidate)
        if block_match is None:
            continue
        raw_key = block_match.group("key")
        if decode_yaml_mapping_key(raw_key) != "with":
            continue

        candidate_indent = len(candidate) - len(candidate.lstrip(" "))
        if candidate_indent != current_indent:
            return False
        value = block_match.group("value").strip()
        if value.startswith("{"):
            extracted = extract_flow_mapping(candidate, block_match.start("value"))
            if extracted is None or candidate[extracted[1] :].strip():
                return False
            with_entries.append((candidate_index, raw_key, "inline-flow", extracted[0]))
        elif value:
            return False
        else:
            with_entries.append((candidate_index, raw_key, "block", None))

    if len(with_entries) != 1 or with_entries[0][1] != "with":
        return False

    with_index, _, mapping_style, contents = with_entries[0]
    if mapping_style == "inline-flow":
        return contents is not None and flow_credentials_disabled(contents)

    with_line = strip_yaml_comment(lines[with_index])
    with_match = BLOCK_MAPPING_KEY.match(with_line)
    if with_match is None or with_match.group("value").strip():
        return False
    with_indent = len(with_match.group("indent"))
    return block_credentials_disabled(lines, with_index, with_indent, step_end)


def workflow_permissions_are_read_only(lines: list[str], workflow_file: Path) -> list[str]:
    """Require one canonical workflow permission block and reject job-level overrides."""
    violations: list[str] = []
    top_level_permissions: list[tuple[int, str, str]] = []
    in_jobs_block = False
    block_scalar_parent_indent: int | None = None

    for line_number, line in enumerate(lines, start=1):
        uncommented = strip_yaml_comment(line)
        if not uncommented.strip():
            continue

        indentation = len(uncommented) - len(uncommented.lstrip(" "))
        if block_scalar_parent_indent is not None:
            if indentation > block_scalar_parent_indent:
                continue
            block_scalar_parent_indent = None

        # Do not interpret script or other literal/folded scalar bodies as YAML.
        # A sequence item such as "- run: |" places the mapping key after "- ".
        scalar_header_text = uncommented[indentation:]
        if scalar_header_text.startswith("- "):
            scalar_header_text = scalar_header_text[2:]
        scalar_header = BLOCK_MAPPING_KEY.match(scalar_header_text)
        if scalar_header is not None and is_block_scalar_header(
            scalar_header.group("value").strip()
        ):
            block_scalar_parent_indent = indentation

        block_match = BLOCK_MAPPING_KEY.match(uncommented)
        raw_key: str | None = None
        decoded_key: str | None = None
        value = ""
        if block_match is not None:
            raw_key = block_match.group("key")
            decoded_key = decode_yaml_mapping_key(raw_key)
            value = block_match.group("value").strip()

            if indentation == 0:
                if decoded_key == "jobs":
                    if not value:
                        in_jobs_block = True
                    elif value.startswith("{"):
                        in_jobs_block = False
                    else:
                        in_jobs_block = False
                        violations.append(
                            f"{workflow_file}:{line_number}: jobs must use an inspectable block or flow mapping; "
                            "aliases and tagged job mappings are unsupported"
                        )
                else:
                    in_jobs_block = False

                if decoded_key == "permissions":
                    top_level_permissions.append((line_number - 1, raw_key, value))
            elif in_jobs_block:
                if decoded_key == "permissions":
                    violations.append(
                        f"{workflow_file}:{line_number}: job-level permissions overrides are unsupported "
                        "at every indentation; keep the single workflow-level contents: read grant"
                    )
                if value.startswith(("&", "*", "!")):
                    violations.append(
                        f"{workflow_file}:{line_number}: YAML anchors, aliases and tags on job values are unsupported"
                    )

        inspect_job_flow = in_jobs_block or (
            indentation == 0 and decoded_key == "jobs" and value.startswith("{")
        )
        if inspect_job_flow:
            for _, _, contents in flow_mapping_ranges(uncommented):
                entries = split_flow_mapping_entries(contents, allow_nested=True)
                if entries is None:
                    violations.append(
                        f"{workflow_file}:{line_number}: job flow mappings must be complete and inspectable"
                    )
                    continue
                for entry in entries:
                    entry_match = re.match(
                        rf"^\s*(?P<key>{YAML_MAPPING_KEY})\s*:\s*(?P<value>.*?)\s*$",
                        entry,
                    )
                    if entry_match is None:
                        continue
                    if decode_yaml_mapping_key(entry_match.group("key")) == "permissions":
                        violations.append(
                            f"{workflow_file}:{line_number}: flow-style job permissions overrides are unsupported; "
                            "keep the single workflow-level contents: read grant"
                        )
                    if entry_match.group("value").startswith(("&", "*", "!")):
                        violations.append(
                            f"{workflow_file}:{line_number}: YAML anchors, aliases and tags in job flow mappings are unsupported"
                        )

    if len(top_level_permissions) != 1:
        violations.append(
            f"{workflow_file}: workflow must declare exactly one top-level permissions block "
            "containing only contents: read"
        )
        return violations

    permissions_index, raw_key, value = top_level_permissions[0]
    if raw_key != "permissions" or value:
        violations.append(
            f"{workflow_file}:{permissions_index + 1}: permissions must use a plain top-level block mapping"
        )
        return violations

    entries: list[tuple[str, str, str]] = []
    for candidate_index in range(permissions_index + 1, len(lines)):
        candidate = strip_yaml_comment(lines[candidate_index])
        if not candidate.strip():
            continue
        indentation = len(candidate) - len(candidate.lstrip(" "))
        if indentation == 0:
            break
        if indentation != 2:
            violations.append(
                f"{workflow_file}:{candidate_index + 1}: workflow permissions must be a flat two-space mapping"
            )
            continue
        child_match = BLOCK_MAPPING_KEY.match(candidate)
        if child_match is None:
            violations.append(
                f"{workflow_file}:{candidate_index + 1}: workflow permissions contain an unsupported entry"
            )
            continue
        raw_child_key = child_match.group("key")
        decoded_child_key = decode_yaml_mapping_key(raw_child_key)
        child_value = child_match.group("value").strip()
        entries.append((raw_child_key, decoded_child_key or "", child_value))
        if (raw_child_key, decoded_child_key, child_value) != ("contents", "contents", "read"):
            violations.append(
                f"{workflow_file}:{candidate_index + 1}: only the literal contents: read workflow permission is allowed"
            )

    if len(entries) != 1:
        violations.append(
            f"{workflow_file}:{permissions_index + 1}: workflow permissions must contain exactly one contents: read entry"
        )
    return violations


def verify_workflows(workflow_directory: Path) -> list[str]:
    """Return violations for read-only token permissions and immutable workflow references."""
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

        violations.extend(workflow_permissions_are_read_only(lines, workflow_file))

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
                        lines, line_number - 1, reference, match.start()
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

    print("GitHub workflows use read-only token permissions and immutable action/image references.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
