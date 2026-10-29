# newtalkopt: `dockerBuildValidate` Shared Library and local DevSecOps demo

> **Status: 0.1.0 release candidate. Implemented, not yet runtime-verified.**
> When this was committed, Docker Desktop was returning HTTP 500 because drive C: had no free space.
> As a result, neither the unit tests nor any Docker build, scan or Jenkins run has been executed.
> See [Verification status](#verification-status).

This repository contains two separate things.

1. **A reusable Jenkins Shared Library step, `dockerBuildValidate`.** It lints, tests, builds and
   validates a Docker image with the local Docker engine. It never pushes, so no registry is needed.
   Its JenkinsPipelineUnit tests are included. It is meant to move into the team's `operations`
   repository.
2. **A standalone demonstration** that uses the step: a **starter Go HTTP service** (not a
   production application), a pipeline with secret and vulnerability scans, and a local Jenkins on
   Docker Desktop. All of it runs from one **Build Now**.

The library does not reference the demo. Other projects call the step with their own Dockerfile.

## Repository layout

```text
Shared Library (reusable)
  vars/dockerBuildValidate.groovy          the step
  vars/dockerBuildValidate.txt             help text shown in Jenkins
  test/groovy/DockerBuildValidateTest.groovy
  build.gradle, settings.gradle            Gradle build for the library tests only
  ci/library-test.Dockerfile(.dockerignore) runs the tests in gradle:8.14.3-jdk21
  docs/shared-library.md                   API, image contract, guarantees, limitations
  docs/operations-integration.md           boundaries, registration, versioning, migration checklist
  CHANGELOG.md

Examples (templates for other projects)
  examples/consumer/Jenkinsfile            consumer pipeline using @Library('name@version')
  examples/jenkins/shared-library.casc.yaml JCasC library registration with placeholders

Standalone demonstration (project-specific)
  Jenkinsfile                              demo pipeline, loads the library from this repository
  app/                                     starter Go service and its multi-stage Dockerfile
  jenkins/, compose.yaml, .env.example     local Jenkins controller for Docker Desktop
  docs/design.md                           design decisions and research-only topics
```

## Using the step from another project

Register the library in Jenkins as described in
[docs/operations-integration.md](docs/operations-integration.md#4-registering-the-library-in-jenkins),
then pin a version:

```groovy
@Library('docker-build-validate@v0.1.0') _

Map result = dockerBuildValidate(
    imageName: 'team/service',
    imageTag: env.IMAGE_TAG,
    dockerfile: 'Dockerfile',
    context: '.',
    platforms: ['linux/amd64']
)
```

See [examples/consumer/Jenkinsfile](examples/consumer/Jenkinsfile) for a complete pipeline, and
[docs/shared-library.md](docs/shared-library.md) for every option, the image contract, the return
value, error behaviour and cleanup guarantees. The tag `v0.1.0` does not exist yet. It will be
created in `operations` after the acceptance checklist passes.

## Prerequisites (demo and tests)

- Docker Desktop with the Linux engine running and **enough free disk space** (the images used
  here total several GB).
- Git.
- Network access to Docker Hub, Maven Central, `repo.jenkins-ci.org`, the Jenkins update center, and
  the Trivy database mirrors. Images are pulled; nothing is pushed.

Go, Gradle, Maven and a JDK do not need to be installed. They run in containers.

## Run the Shared Library unit tests

```sh
docker buildx build --progress=plain -f ci/library-test.Dockerfile .
```

The default `verify` stage fails when any test fails, so the command's exit code is the test result.
To export the reports as the pipeline does:

```sh
docker buildx build -f ci/library-test.Dockerfile --target report --output type=local,dest=build/library-test .
```

This writes `build/library-test/exit-code` (`0` means passed), plus `test-results/*.xml` and
`html/index.html`. If JDK 21 and Gradle 8.14 are installed, `gradle test` also works. The tests need
no Jenkins, no app image build, and no registry.

## Build and check the starter app without Jenkins

```sh
docker buildx build --target test --output type=cacheonly app
docker buildx build --check app
docker buildx build --platform linux/amd64 --target runtime --build-arg VERSION=local -t newtalkopt/starter-app:local-amd64 --load app
docker buildx build --platform linux/arm64 --target runtime --build-arg VERSION=local -t newtalkopt/starter-app:local-arm64 --load app
docker run --rm -d --name starter --read-only --cap-drop ALL --security-opt no-new-privileges -p 127.0.0.1:8081:8080 newtalkopt/starter-app:local-amd64
curl http://127.0.0.1:8081/healthz
docker stop starter
```

The `runtime` target is `scratch` with one static binary, running as UID `65532`. It has no shell,
no Go toolchain and no tests. Each architecture is loaded under its own tag, and no multi-platform
manifest list is created locally.

## Start the local Jenkins

1. Copy `.env.example` to `.env` and set `JENKINS_ADMIN_PASSWORD`. `.env` is git-ignored.
2. Commit your changes. Jenkins clones the committed `main` branch of the repository, which is
   mounted read-only at `/repo`.
3. Run `docker compose up -d --build`.
4. Open `http://localhost:8090` (or `JENKINS_PORT`) and sign in as `admin` (or `JENKINS_ADMIN_ID`).

What this configures:

- `jenkins/Dockerfile` extends `jenkins/jenkins:2.580.2-lts-jdk21` (pinned by digest). It adds the
  static Docker CLI 29.5.2 and Buildx 0.38.1, and installs the plugins in `jenkins/plugins.txt`.
- `jenkins/casc.yaml` skips the setup wizard, creates the admin user from the environment, and
  seeds the pipeline job `newtalkopt`. That job uses the Git URL `file:///repo`, branch `*/main`, and
  `Jenkinsfile`.
- The Docker socket is mounted, and the container joins group `DOCKER_SOCKET_GID` (default `0`,
  because Docker Desktop exposes the socket as `root:root 0660`).
- `-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` and the `GIT_CONFIG_*` safe-directory
  variables are set. **Both exist only for the local `file://` demo.**

### How the demo pipeline loads the library

```groovy
library identifier: 'newtalkopt-pipeline-lib@main', retriever: legacySCM(scm)
```

This loads `vars/` from the same repository and SCM configuration as the Jenkinsfile, with no
global library configuration. Dynamically loaded libraries run in the Groovy sandbox. The library
checkout and the workspace checkout are separate. If a commit lands between them, the two can
differ for that one build. Other projects use `@Library('<name>@<version>')` instead, as shown above.

## Pipeline stages

| # | Stage | What runs | Fails the build when |
| --- | --- | --- | --- |
| 1 | Checkout | `checkout scm`, `git rev-parse HEAD`, Docker and Buildx checks | checkout fails or the engine is unreachable |
| 2 | Shared Library Unit Tests | `ci/library-test.Dockerfile`, then `junit` | the Gradle exit code is not 0, or there is no JUnit XML |
| 3 | Source Checks / Package Source | `tar` of the workspace, including `.git` | tar fails |
| 3 | Source Checks / Secret Scan (Gitleaks) | `gitleaks git` over full history, `--redact` | a leak is found or the scan errors |
| 3 | Source Checks / Config Scan (Trivy) | `trivy config` on the repository | a HIGH or CRITICAL misconfiguration is found, or the scan errors |
| 4 | Docker Build and Validate | `dockerBuildValidate(...)`: lint, hadolint, `test` target, per-arch `runtime` builds, validation, smoke test | any check fails |
| 5 | Image Scan (Trivy) | `trivy image` on the native image, plus a CycloneDX SBOM | a HIGH or CRITICAL vulnerability is found, or the scan errors |

Every scanner stage records the scanner's exit status, prints its report, and then exits with that
status. A scanner error, such as a failed database download, therefore fails the stage instead of
passing it. Declarative Pipeline skips all later stages after a failure, so a failing unit test
stops the pipeline before any release build.

### Where results appear

- Console log: one labelled `sh` step per command.
- **Tests** tab: the library's JUnit results.
- Artifacts:
  - `build/library-test/**`
  - `build/reports/gitleaks.json`
  - `build/reports/trivy-config.txt`
  - `build/reports/docker-validation.txt`
  - `build/reports/trivy-image.txt`
  - `build/reports/sbom.cdx.json`
- Local images: `docker images newtalkopt/starter-app` lists the `<sha12>-amd64` and `<sha12>-arm64`
  tags.

### Demonstrating expected failures

| Change (commit it, run, then revert) | Expected failing stage |
| --- | --- |
| In `vars/dockerBuildValidate.groovy`, change `--cap-drop ALL` to `--cap-drop NET_RAW` | Shared Library Unit Tests |
| Break `gofmt` formatting or a Go test in `app/` | Docker Build and Validate (`test` target). No release image is built. |
| Remove `USER 65532:65532` from `app/Dockerfile` | Config Scan (Trivy DS002). The step's non-root check also fails if that scan is bypassed. |
| Remove `HEALTHCHECK` from `app/Dockerfile` | Docker Build and Validate (smoke test: no `HEALTHCHECK`) |
| Commit a real-looking credential | Secret Scan (Gitleaks) |

These outcomes are expected from the code. They have not been demonstrated yet; see below.

## Verification status

| Check | Status |
| --- | --- |
| No comment lines in source or config files | Passed (search for lines starting with `#`, `//` or `/*`) |
| Credential-pattern search in the working tree | Passed (only `${JENKINS_ADMIN_PASSWORD}` references, no values) |
| `git diff --check` on the commit | Passed |
| Library unit tests | **Not run.** One earlier attempt on JDK 17 failed at dependency resolution (JenkinsPipelineUnit 1.31 needs Java 21), which led to the switch to JDK 21. The JDK 21 run was blocked by the Docker HTTP 500. |
| Go `gofmt`, `go vet`, `go test` | **Not run** (Docker unavailable, no host Go) |
| `buildx --check`, hadolint, image builds, smoke test | **Not run** |
| Gitleaks, Trivy config, Trivy image | **Not run** |
| Jenkins image build, Compose, pipeline run | **Not run** |
| Image digests | Resolved with `docker buildx imagetools inspect <tag>` on 29 Oct 2026, before the disk filled. The Jenkins digest was resolved from `lts-jdk21`, which then reported `JENKINS_VERSION=2.580.2`. |

Complete the [acceptance checklist](docs/operations-integration.md#acceptance-checklist) before
treating the step as ready for reuse.

## Known limitations

- The built-in Jenkins node runs the builds. That is acceptable for a single-developer local setup;
  use agents elsewhere.
- Mounting the Docker socket gives Jenkins root-equivalent control of the Docker Desktop VM. Jenkins
  is bound to `127.0.0.1` only.
- Plugins in `jenkins/plugins.txt` are not version-pinned. `jenkins-plugin-cli` resolves the latest
  versions compatible with the pinned controller.
- arm64 images are validated statically only, because running them needs emulation. Trivy scans the
  native image.
- The Jenkins Declarative linter (`/pipeline-model-converter/validate`) checks syntax only. It needs
  an authenticated controller, so it is not wired into a pre-commit hook.
- The step supports only `linux/amd64` and `linux/arm64`.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `request returned 500 Internal Server Error ... _ping` | The Docker Desktop engine is unhealthy. A full system drive is one known cause. Free space, then restart Docker Desktop. |
| `permission denied ... docker.sock` in Jenkins | Find the socket's GID with `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock busybox:1.37 ls -ln /var/run/docker.sock`, set `DOCKER_SOCKET_GID`, and recreate the container. |
| `ERROR: use docker --context=default buildx` on the Windows host | On the host, the docker-driver builder is `desktop-linux`. Omit `--builder`. Inside Jenkins the builder is `default`. |
| `only compatible with JVM runtime version 21` | Use the pinned `gradle:8.14.3-jdk21` image. |
| `Cache export is not supported for the docker driver` | Use `cacheDir` only together with a `docker-container` `builder`. |
| The job ignores recent changes | Jenkins builds committed `main` only. |
| Port 8090 is already in use | Set `JENKINS_PORT` in `.env`. |

## Moving the library into `operations`

See [docs/operations-integration.md](docs/operations-integration.md). It covers which files move,
plugin and agent requirements, security, Jenkins registration, pinned consumption, versioning, what
to inspect once access is granted, how to avoid overwriting existing work, and the acceptance
checklist. Nothing in this repository depends on `operations`.
