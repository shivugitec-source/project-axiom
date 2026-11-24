# ADR 0008 — Enforce read-only GitHub Actions workflow token permissions

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

Backend CI already declares workflow-level permissions: contents: read, and checkout credentials are not persisted into later build steps. However, the workflow verifier enforced immutable action references, service-image digests and checkout credential isolation without asserting the token permission boundary. A later workflow edit could add write-all, id-token: write, or a job-level permission override while all existing verifier tests continued to pass.

The workflow token is available to actions and steps for the duration of the job. Its permissions therefore remain part of the supply-chain trust boundary even when checkout does not persist the token in Git configuration.

## Decision

1. Require every GitHub Actions workflow under .github/workflows to declare exactly one canonical top-level block:

       permissions:
         contents: read

2. Reject missing or duplicate workflow permission blocks, scalar read-all/write-all forms, flow-style or quoted/escaped permission mappings, additional scopes, and job-level permission overrides.
3. Enforce the rule in the existing standard-library workflow verifier and regression suite. Treat ambiguous representations as unsupported rather than attempting to resolve YAML aliases, tags or merge semantics.
4. Keep the workflow token read-only. Any future need for additional authority must be a separately reviewed architecture/security decision, not an incidental workflow edit.

## Consequences

- A workflow change cannot silently broaden GITHUB_TOKEN beyond repository contents read access.
- New workflows must declare the same explicit least-privilege permission policy.
- The guard is CI-only. It does not change application runtime, database schema, migrations, database roles, RLS policies, production credentials or deployment infrastructure.
