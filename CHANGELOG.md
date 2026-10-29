# Changelog

All notable changes to the `dockerBuildValidate` Shared Library step. Versions follow the policy in
[docs/operations-integration.md](docs/operations-integration.md#6-versioning-and-compatibility).

## 0.1.0 — release candidate (not tagged)

Status: implemented and covered by unit tests in code. **Not runtime-verified.** Docker Desktop was
unavailable (HTTP 500, drive C: full) when this version was committed, so neither the unit-test suite
nor any Docker-dependent check has been executed yet. Do not tag until the
[acceptance checklist](docs/operations-integration.md#acceptance-checklist) passes.

### Added

- `dockerBuildValidate` step: configuration validation, `buildx --check`, optional hadolint,
  Dockerfile test target (`--output type=cacheonly`), per-platform `--load` builds with
  `<name>:<tag>-<arch>` tags, numeric non-root user check, forbidden-path check through
  `docker export`, hardened smoke test with `HEALTHCHECK` polling and a SIGTERM exit-code check, and
  guaranteed container cleanup.
- Options: `imageName`, `imageTag`, `dockerfile`, `context`, `platforms`, `releaseTarget`,
  `testTarget`, `lint`, `runTests`, `build`, `validate`, `smokeTest`, `hadolintImage`, `builder`,
  `cacheDir`, `buildArgs`, `labels`, `secrets`, `forbiddenPaths`, `healthTimeoutSeconds`,
  `stopTimeoutSeconds`, `allowedExitCodes`, `smokeTestEnv`, `smokeTestTmpfs`, `smokeTestMemory`.
- JenkinsPipelineUnit 1.31 test suite run through `ci/library-test.Dockerfile`.

### Breaking

- None (first version).
