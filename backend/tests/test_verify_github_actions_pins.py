"""Regression tests for immutable GitHub Actions workflow references."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "backend" / "verify-github-actions-pins.py"
SPEC = importlib.util.spec_from_file_location("verify_github_actions_pins", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class VerifyGitHubActionsPinsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.workflow_directory = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_workflow(self, name: str, content: str) -> None:
        path = self.workflow_directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def test_accepts_full_commit_sha_with_release_comment(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + " # v5\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_rejects_mutable_tags_and_branches(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@v5\n"
            "  - uses: vendor/action@main\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("full 40-character commit SHA" in error for error in errors))

    def test_rejects_short_commit_identifiers(self) -> None:
        self.write_workflow("ci.yml", "steps:\n  - uses: vendor/action@1234abcd\n")
        self.assertEqual(1, len(VERIFIER.verify_workflows(self.workflow_directory)))

    def test_accepts_local_reusable_workflows(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n  reuse:\n    uses: ./.github/workflows/reusable.yml\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_rejects_local_path_traversal(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n  outside:\n    uses: ./../outside/action\n"
            "  nested:\n    uses: ./actions/../../outside/action\n",
        )
        self.assertEqual(2, len(VERIFIER.verify_workflows(self.workflow_directory)))

    def test_rejects_flow_style_mutable_action_references(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - { name: Checkout, uses: vendor/action@main }\n",
        )
        self.assertEqual(1, len(VERIFIER.verify_workflows(self.workflow_directory)))

    def test_accepts_quoted_flow_style_pinned_action_references(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - { name: Checkout, 'uses': 'vendor/action@"
            + "a" * 40
            + "' }\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_checks_every_flow_mapping_on_same_line(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ uses: vendor/first@"
            + "a" * 40
            + " }, { uses: vendor/second@main }, { uses: vendor/third@develop }]\\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(2, len(errors))

    def test_requires_container_action_digest(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: docker://ghcr.io/example/action:latest\n",
        )
        self.assertEqual(1, len(VERIFIER.verify_workflows(self.workflow_directory)))

    def test_accepts_container_action_digest(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: docker://ghcr.io/example/action@sha256:"
            + "b" * 64
            + "\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_scans_nested_workflow_files(self) -> None:
        self.write_workflow("nested/ci.yaml", "steps:\n  - uses: vendor/action@v5\n")
        self.assertEqual(1, len(VERIFIER.verify_workflows(self.workflow_directory)))

    def test_missing_workflow_directory_fails_closed(self) -> None:
        missing = self.workflow_directory / "missing"
        errors = VERIFIER.verify_workflows(missing)
        self.assertEqual(1, len(errors))
        self.assertIn("does not exist", errors[0])


if __name__ == "__main__":
    unittest.main()
