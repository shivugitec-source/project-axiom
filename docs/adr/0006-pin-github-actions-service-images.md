# ADR 0006 — Pin GitHub Actions service images to immutable digests

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

The GitHub Actions workflow pins external actions to immutable commit SHAs, but its PostgreSQL service used the mutable postgres:17-alpine tag. A workflow commit therefore did not uniquely identify the database service image used by that run. A tag can be retargeted or receive a new patch image without a repository change.

The service image is part of the CI execution environment and should be reviewed with the workflow that consumes it. PostgreSQL 17 remains the required major version.

## Decision

1. Require every external service/container image declared by a GitHub Actions workflow to include a full sha256 digest.
2. Continue to retain a human-readable version tag before the digest so the intended software version is visible during review.
3. Extend the standard-library workflow verifier to inspect block- and flow-style image: mappings and fail closed on mutable tags or abbreviated digests.
4. Pin the PostgreSQL CI service to the multi-platform OCI index for Docker Official Image postgres:17.12-alpine:
   postgres:17.12-alpine@sha256:f9cf3e1f0066f0eb0c48707d864f0ac5941e9ff454a072077d97a4d278d07d73.
5. Update image versions only after reviewing the upstream official image metadata and changing the readable tag and digest together.

## Consequences

- The PostgreSQL image used by a given workflow revision is content-addressed and reproducible across supported runner architectures.
- PostgreSQL remains on major version 17; the selected tag identifies patch version 17.12.
- CI regression tests reject mutable service tags and incomplete digests.
- This is a CI supply-chain control only. It does not deploy infrastructure or change application runtime, database schema, migrations, privileges, RLS, credentials or production orchestration.

## Source

- Docker Library official metadata for postgres:17.12-alpine: https://github.com/docker-library/repo-info/blob/master/repos/postgres/remote/17.12-alpine.md
