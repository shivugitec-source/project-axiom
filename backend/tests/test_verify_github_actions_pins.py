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

    def write_workflow(self, name: str, content: str, *, include_default_permissions: bool = True) -> None:
        path = self.workflow_directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if include_default_permissions:
            content = "permissions:\n  contents: read\n\n" + content
        path.write_text(content, encoding="utf-8")

    def test_accepts_canonical_read_only_workflow_permissions(self) -> None:
        self.write_workflow("ci.yml", "jobs:\n  build:\n    runs-on: ubuntu-latest\n")
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_rejects_missing_workflow_permissions(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n  build:\n    runs-on: ubuntu-latest\n",
            include_default_permissions=False,
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("exactly one top-level permissions block" in error for error in errors))

    def test_rejects_write_all_workflow_permissions(self) -> None:
        self.write_workflow(
            "ci.yml",
            "permissions: write-all\njobs:\n  build:\n",
            include_default_permissions=False,
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("plain top-level block mapping" in error for error in errors))

    def test_rejects_additional_workflow_permission_scopes(self) -> None:
        self.write_workflow(
            "ci.yml",
            "permissions:\n  contents: read\n  id-token: write\njobs:\n  build:\n",
            include_default_permissions=False,
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("only the literal contents: read" in error for error in errors))

    def test_rejects_duplicate_workflow_permissions_blocks(self) -> None:
        self.write_workflow(
            "ci.yml",
            "permissions:\n  contents: read\npermissions:\n  contents: read\n",
            include_default_permissions=False,
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("exactly one top-level permissions block" in error for error in errors))

    def test_rejects_flow_style_workflow_permissions(self) -> None:
        self.write_workflow(
            "ci.yml",
            "permissions: { contents: read }\n",
            include_default_permissions=False,
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("plain top-level block mapping" in error for error in errors))

    def test_rejects_job_level_permissions_override(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n  build:\n    permissions:\n      contents: write\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("job-level permissions overrides" in error for error in errors))

    def test_rejects_flow_style_job_level_permissions_override(self) -> None:
        self.write_workflow("ci.yml", "jobs:\n  build: { permissions: write-all }\n")
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("flow-style job permissions overrides" in error for error in errors))

    def test_rejects_job_level_permissions_override_with_nonstandard_indentation(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n    build:\n        runs-on: ubuntu-latest\n"
            "        permissions:\n            contents: write\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("job-level permissions overrides" in error for error in errors))

    def test_rejects_flow_job_permissions_override_with_nonstandard_indentation(self) -> None:
        self.write_workflow("ci.yml", "jobs:\n    build: { permissions: write-all }\n")
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("flow-style job permissions overrides" in error for error in errors))

    def test_rejects_multiline_top_level_jobs_flow_mapping(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs: {\n"
            "  build: {\n"
            "    runs-on: ubuntu-latest\n"
            "    , permissions: write-all\n"
            "  }\n"
            "}\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("multi-line flow mappings in jobs are unsupported" in error for error in errors))

    def test_rejects_multiline_flow_mapping_inside_jobs_block(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n"
            "  build: {\n"
            "    runs-on: ubuntu-latest\n"
            "    , permissions: write-all\n"
            "  }\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("multi-line flow collections inside jobs are unsupported" in error for error in errors))

    def test_rejects_job_mapping_alias_that_could_hide_permissions(self) -> None:
        self.write_workflow(
            "ci.yml",
            "defaults: &job\n  runs-on: ubuntu-latest\n  permissions:\n    contents: write\n"
            "jobs:\n  build: *job\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("YAML anchors, aliases and tags" in error for error in errors))

    def test_rejects_escaped_workflow_permissions_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            '"\\u0070ermissions":\n  contents: read\n',
            include_default_permissions=False,
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("plain top-level block mapping" in error for error in errors))

    def test_ignores_permission_lookalikes_inside_run_scripts(self) -> None:
        self.write_workflow(
            "ci.yml",
            "jobs:\n  build:\n    steps:\n      - run: |\n"
            "          permissions: write-all\n"
            "          { permissions: write }\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_accepts_full_commit_sha_with_release_comment(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + " # v5\n    with:\n      persist-credentials: false\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))


    def test_rejects_checkout_when_credentials_are_persisted(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n"
            "  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_checkout_when_persist_credentials_is_missing(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      fetch-depth: 0\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_duplicate_checkout_persist_credentials_keys(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "      persist-credentials: true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])


    def test_rejects_quoted_duplicate_persist_credentials_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "      \"persist-credentials\": true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_escaped_duplicate_persist_credentials_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "      \"persist-\\u0063redentials\": true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_tagged_duplicate_persist_credentials_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "      !!str persist-credentials: true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        # Both the global YAML-key guard and checkout-specific credential guard
        # independently reject this ambiguous mapping; diagnostic count is not
        # part of the security contract.
        self.assertTrue(
            any(
                "explicit, anchored, aliased, or tagged YAML mapping keys are unsupported"
                in error
                for error in errors
            )
        )
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_aliased_duplicate_persist_credentials_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "defaults: &defaults persist-credentials\n"
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "      *defaults: true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        # Both the global YAML-key guard and checkout-specific credential guard
        # independently reject this ambiguous mapping; diagnostic count is not
        # part of the security contract.
        self.assertTrue(
            any(
                "explicit, anchored, aliased, or tagged YAML mapping keys are unsupported"
                in error
                for error in errors
            )
        )
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_quoted_duplicate_with_mapping(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "    \"with\":\n      persist-credentials: true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_flow_quoted_duplicate_persist_credentials_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { persist-credentials: false, "
            "\"persist-credentials\": true } }]\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_flow_quoted_duplicate_with_mapping(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { persist-credentials: false }, "
            "\"with\": { persist-credentials: true } }]\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_multiline_flow_checkout_mapping(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ",\n  with: { persist-credentials: false },\n"
            "  with: { persist-credentials: true } }]\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_explicit_duplicate_persist_credentials_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "      ? persist-credentials\n"
            "      : true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        # Both the global YAML-key guard and checkout-specific credential guard
        # independently reject this ambiguous mapping; diagnostic count is not
        # part of the security contract.
        self.assertTrue(
            any(
                "explicit, anchored, aliased, or tagged YAML mapping keys are unsupported"
                in error
                for error in errors
            )
        )
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_yaml_merge_key_in_checkout_inputs(self) -> None:
        self.write_workflow(
            "ci.yml",
            "defaults: &defaults { persist-credentials: true }\n"
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      <<: *defaults\n"
            "      persist-credentials: false\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(any("YAML merge keys are also unsupported" in error for error in errors))
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_merge_key_that_inherits_checkout_and_overrides_credentials(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n"
            "  - &checkout\n"
            "    uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      persist-credentials: false\n"
            "  - <<: *checkout\n"
            "    with:\n      persist-credentials: true\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("YAML merge keys are also unsupported", errors[0])

    def test_rejects_nested_persist_credentials_key_under_checkout_input(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      other:\n        persist-credentials: false\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_with_mapping_nested_under_env(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    env:\n      with:\n        persist-credentials: false\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_nested_flow_sequence_credential_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { other: [persist-credentials: false, ignored] } }]\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_ignores_credential_lookalike_inside_quoted_flow_scalar(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ', with: { other: "text, persist-credentials: false, still text" } }]\n',
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_accepts_block_scalar_text_that_looks_like_a_credential_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with:\n      note: |\n"
            "        persist-credentials: true\n"
            "      persist-credentials: false\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_accepts_quoted_flow_scalar_with_commas_and_direct_false_input(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { note: 'one, two', persist-credentials: false } }]\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_rejects_with_lookalike_inside_quoted_flow_step_scalar(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ', note: "text, with: { persist-credentials: false }" }]\n',
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_rejects_with_lookalike_inside_nested_env_flow_scalar(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + '\n    env: { note: "text, with: { persist-credentials: false }" }\n',
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertTrue(
            any("actions/checkout must set with.persist-credentials: false" in error for error in errors)
        )

    def test_accepts_block_step_with_inline_flow_inputs(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - uses: actions/checkout@"
            + "a" * 40
            + "\n    with: { persist-credentials: false, fetch-depth: 0 }\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_accepts_flow_checkout_with_credentials_disabled(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { persist-credentials: false, fetch-depth: 0 } }]\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_rejects_duplicate_flow_checkout_with_mappings(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { persist-credentials: false }, "
            "with: { persist-credentials: true } }]\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

    def test_rejects_duplicate_flow_checkout_with_mapping_that_omits_credentials(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps: [{ name: Checkout, uses: actions/checkout@"
            + "a" * 40
            + ", with: { persist-credentials: false }, "
            "with: { fetch-depth: 0 } }]\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("persist-credentials: false", errors[0])

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

    def test_rejects_escaped_double_quoted_uses_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            'steps:\n  - {"\\u0075ses": vendor/action@main}\n',
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("escaped double-quoted", errors[0])

    def test_rejects_explicit_yaml_uses_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - ? uses\n    : vendor/action@main\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("explicit, anchored, aliased, or tagged", errors[0])

    def test_rejects_aliased_yaml_uses_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "key: &uses_key uses\nsteps:\n  - {*uses_key: vendor/action@main}\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("explicit, anchored, aliased, or tagged", errors[0])

    def test_rejects_tagged_yaml_uses_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - {!!str uses: vendor/action@main}\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("explicit, anchored, aliased, or tagged", errors[0])

    def test_rejects_anchored_yaml_uses_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n  - {&uses_key uses: vendor/action@main}\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("explicit, anchored, aliased, or tagged", errors[0])

    def test_rejects_combined_anchor_and_tagged_yaml_uses_keys(self) -> None:
        self.write_workflow(
            "ci.yml",
            "steps:\n"
            "  - {!!str &first uses: vendor/action@main}\n"
            "  - {&second !!str uses: vendor/action@main}\n"
            "  - !!str &third uses: vendor/action@main\n"
            "  - &fourth !!str uses: vendor/action@main\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(4, len(errors))
        self.assertTrue(
            all("explicit, anchored, aliased, or tagged" in error for error in errors)
        )

    def test_rejects_non_specific_yaml_tag_on_uses_keys(self) -> None:
        self.write_workflow(
            "ci.yml",
            """steps:
  - ! uses: vendor/action@main
  - {! uses: vendor/action@main}
  - ! &first uses: vendor/action@main
  - &second ! uses: vendor/action@main
  - {! &third uses: vendor/action@main}
  - {&fourth ! uses: vendor/action@main}
""",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(6, len(errors))
        self.assertTrue(
            all("explicit, anchored, aliased, or tagged" in error for error in errors)
        )

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
            + " }, { uses: vendor/second@main }, { uses: vendor/third@develop }]",
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

    def test_accepts_digest_pinned_service_image(self) -> None:
        self.write_workflow(
            "ci.yml",
            "services:\n  postgres:\n    image: postgres:17.12-alpine@sha256:"
            + "a" * 64
            + "\n",
        )
        self.assertEqual([], VERIFIER.verify_workflows(self.workflow_directory))

    def test_rejects_mutable_service_image_tag(self) -> None:
        self.write_workflow(
            "ci.yml",
            "services:\n  postgres:\n    image: postgres:17-alpine\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("service/container image", errors[0])

    def test_rejects_short_service_image_digest(self) -> None:
        self.write_workflow(
            "ci.yml",
            "services:\n  postgres:\n    image: postgres:17-alpine@sha256:1234abcd\n",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("full sha256 digest", errors[0])

    def test_checks_every_flow_image_mapping_on_same_line(self) -> None:
        self.write_workflow(
            "ci.yml",
            "services: [{ image: postgres:17-alpine@sha256:"
            + "a" * 64
            + " }, { image: postgres:17-alpine }, { image: redis:latest }]",
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(2, len(errors))

    def test_rejects_escaped_double_quoted_image_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            'services:\n  postgres:\n    {"\\u0069mage": postgres:17-alpine}\n',
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("escaped double-quoted", errors[0])

    def test_rejects_hex_escaped_double_quoted_uses_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            'steps:\n  - {"\\x75ses": vendor/action@main}\n',
        )
        errors = VERIFIER.verify_workflows(self.workflow_directory)
        self.assertEqual(1, len(errors))
        self.assertIn("escaped double-quoted", errors[0])

    def test_ignores_escaped_unrelated_double_quoted_mapping_key(self) -> None:
        self.write_workflow(
            "ci.yml",
            'steps:\n  - {"\\u006eame": Checkout}\n',
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
