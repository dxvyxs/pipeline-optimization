# Design notes

## Goals and constraints

- One developer, one Git repository, one Jenkins pipeline, one **Build Now**.
- Docker Desktop only. No registry: no `docker push`, no Buildx `--push`, no `type=registry` cache.
- Tests for the pipeline logic run before the expensive build, and any failure fails the build.

## Components and responsibilities

| Component | Responsibility | Reusable or project-specific |
| --- | --- | --- |
| `vars/dockerBuildValidate.groovy` | Generic local build + validation of a Dockerfile with `test` and `runtime` targets | **Reusable** |
| `test/groovy/DockerBuildValidateTest.groovy`, `build.gradle`, `ci/library-test.Dockerfile` | Unit-test harness for the step | **Reusable** (moves with the step) |
| `Jenkinsfile` | Orchestration for this repository: stage order, image name, scanner versions, thresholds, forbidden paths | Project-specific |
| `app/` | Starter Go service and its Dockerfile | Project-specific |
| `jenkins/`, `compose.yaml` | Local Jenkins controller wired to Docker Desktop | Local environment. Reusable as a pattern. |

The step knows nothing about this repository. It takes image name, Dockerfile, context, targets,
platforms and policy (forbidden paths, health timeout) as inputs, and returns a result map. It has no
hard-coded paths, image names or tool versions. The hadolint image, for example, is supplied by the caller.

## Dockerfile targets

`app/Dockerfile` has these stages:

1. `deps`: `golang` on `$BUILDPLATFORM`. It copies `go.mod`/`go.sum` and runs `go mod download` and
   `go mod verify` with a module cache mount and an optional `netrc` secret mount
   (`required=false`). `GOFLAGS=-mod=readonly` stops the build from rewriting module metadata, and
   `GOTOOLCHAIN=local` stops it from downloading a different Go toolchain.
2. `source`: copies only `cmd/` and `internal/`.
3. `test`: runs `gofmt -l`, `go vet ./...` and `go test ./...`. It is only built for tests
   (`--output type=cacheonly`) and is never shipped.
4. `build`: declares `TARGETOS`/`TARGETARCH` *after* the dependency layers, so switching architecture
   does not invalidate the module download. It cross-compiles with `CGO_ENABLED=0`, `-trimpath`, and
   `-ldflags "-s -w -X main.version=..."`.
5. `runtime`: `scratch` with one binary at `/server`, `--chown=65532:65532 --chmod=0555`,
   `USER 65532:65532`, an exec-form `ENTRYPOINT`, a `HEALTHCHECK` that runs `/server -healthcheck`,
   and `STOPSIGNAL SIGTERM`.

`scratch` fits this starter app because it is a static binary that makes no outbound TLS calls,
needs no timezone data, and writes no files. An app that needs CA certificates, tzdata, or
dynamically linked libraries (for example a `CGO_ENABLED=1` Oracle driver) needs a different base,
such as distroless `base`/`cc`, or explicitly copied certificates. The forbidden-path list in the
Jenkinsfile would then need to be reviewed.

The app has no third-party modules, so there is no `go.sum`. The Dockerfile copies `go.sum*`, so a
`go.sum` is picked up automatically once a dependency is added. Because of `-mod=readonly`, a missing
or stale `go.sum` then fails the build instead of being regenerated.

## BuildKit decisions

- **Cache.** The default `docker` driver keeps its BuildKit cache inside Docker Desktop between
  builds, and the cache mounts (`/go/pkg/mod`, `/root/.cache/go-build`, Gradle caches) persist there
  too. The step supports a local exported cache (`cacheDir`, `mode=max`), but only with an explicit
  `docker-container` builder, because the `docker` driver cannot export caches. Registry and inline
  caches are not used.
- **Secrets.** Credentials are passed only as BuildKit secret mounts (`--secret id=...,env=...`).
  The step rejects build-arg names that look like credentials.
- **Multi-architecture.** Each platform is built separately with `--load` and its own tag
  (`-amd64`, `-arm64`). A local image store does not hold a pushed multi-platform manifest, so none
  is claimed. The `runtime` stage has no `RUN`, so building arm64 on an amd64 engine needs no
  emulation. Only the native image is executed.

## Failure semantics

- The step throws on the first failing phase, so nothing after it runs.
- The Jenkinsfile reads the exported Gradle exit code and calls `error()` when it is non-zero. JUnit
  XML is published in `post { always }`, with `allowEmptyResults: false`.
- Scanner stages capture the scanner's exit status, print the report, and exit with that status. A
  missing database or network failure is a failed stage, not a pass.

## Unit-test strategy

See [shared-library.md](shared-library.md#unit-tests). In short, the tests replace only `sh`,
`error`, `sleep` and `echo`. They assert the commands the step builds, their order and count, the
returned map, and the build result. They do not re-check values the fakes themselves return.

The tests run in `gradle:8.14.3-jdk21`, because JenkinsPipelineUnit 1.31 is published for Java 21
and newer.

## Researched but not implemented

The following were researched but are **not** part of this repository:

- **Signing and attestation.** Cosign/Sigstore keyless signing (Fulcio, Rekor), TUF root setup,
  SLSA provenance, and Buildx `--attest`/`--sbom` attestations. Digest-based signing and attestation
  storage are tied to a registry or a transparency log, which is out of scope for a registry-free
  local workflow. The pipeline produces a CycloneDX SBOM file with Trivy instead.
- **OpenSSF Scorecard.** It needs a hosted repository (GitHub/GitLab) and a token. Not applicable
  until the repository has a hosted remote.
- **Snyk, Grype, Syft, OSV-Scanner.** Trivy covers image, configuration and SBOM scanning here. Snyk
  needs an account token.
- **Pylint.** There is no Python code in this repository.
- **DefectDojo import.** No finding exports are part of this repository, and no finding data has been
  invented. The Trivy and Gitleaks JSON/text reports are archived and could be imported later.
- **Pre-commit Jenkins linter.** It needs an authenticated, reachable controller. It is documented
  in the README as a manual check.

## Relationship with the future `operations` repository

The reusable boundary, Jenkins registration, versioning policy, migration steps and acceptance
checklist are in [operations-integration.md](operations-integration.md). Nothing in this repository
depends on `operations`, and no structure or API has been assumed for it.
