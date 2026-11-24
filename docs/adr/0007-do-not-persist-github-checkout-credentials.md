# ADR 0007 — Do not persist GitHub checkout credentials into build steps

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

The Backend CI workflow grants its `GITHUB_TOKEN` only `contents: read`. However, `actions/checkout` defaults `persist-credentials` to true and stores the token in the local repository Git configuration so later Git commands can authenticate.

Subsequent workflow steps execute repository-controlled Python verification, Maven packaging and tests. Those processes need the checked-out source and Git history, but they do not need the checkout credential. Leaving it in `.git/config` unnecessarily exposes the token to every later process in the job.

## Decision

1. Set `persist-credentials: false` on every pinned `actions/checkout` step.
2. Keep the workflow's explicit `contents: read` permission and full-history checkout.
3. Extend the standard-library workflow verifier to require exactly one literal `persist-credentials: false` value in each checkout step's `with` mapping.
4. Reject missing, enabled, duplicate or unsupported/ambiguous settings. Test block and flow-style checkout mappings.
5. Do not add credentials to later build, migration or test steps. The repository is public, so the existing fallback fetch of the main branch can remain anonymous when the push event has no usable base SHA.

## Consequences

- The checkout token is used for the checkout operation but is not left in local Git configuration for repository-controlled build and test processes.
- Full Git history and existing migration-immutability comparisons remain available.
- The rule is enforced by Backend CI regression tests.
- This is a workflow security hardening change only; it does not change production credentials, application behavior, database permissions, migrations or infrastructure.
