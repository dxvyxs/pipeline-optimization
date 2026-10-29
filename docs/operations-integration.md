# Integrating `dockerBuildValidate` into the `operations` repository

This guide is written **before** access to `operations` exists. It does not assume that repository's
layout, naming rules, branch policy, access model, or release process. Each place where those
conventions decide the outcome is marked **Confirm in operations**.

## 1. File boundaries

### Shared Library (move or copy these)

| Path | Purpose | Required |
| --- | --- | --- |
| `vars/dockerBuildValidate.groovy` | The step | Yes |
| `vars/dockerBuildValidate.txt` | Help text shown in *Pipeline Syntax → Global Variables Reference* | Recommended |
| `docs/shared-library.md` | API, contract, guarantees and limitations | Recommended |
| `CHANGELOG.md` (the `dockerBuildValidate` entries) | Version history | Recommended |

The step does not use `src/` or `resources/`, so there is nothing to copy from those directories.

### Shared Library tests (move together with the step)

| Path | Purpose |
| --- | --- |
| `test/groovy/DockerBuildValidateTest.groovy` | JenkinsPipelineUnit suite |
| `build.gradle`, `settings.gradle` | Gradle 8.14 build: Groovy 2.4.21, JenkinsPipelineUnit 1.31, JUnit 4.13.2, repositories Maven Central and `repo.jenkins-ci.org/releases` |
| `ci/library-test.Dockerfile`, `ci/library-test.Dockerfile.dockerignore` | Runs the suite in `gradle:8.14.3-jdk21` and exports JUnit XML and the exit code |

The test file loads the step from `vars/dockerBuildValidate.groovy`, relative to the Gradle project
directory. If `operations` puts the library somewhere else, change that one `loadScript(...)` path
and the `vars` entries in `build.gradle` and the `.dockerignore`.

### Standalone demonstration only (do not move)

| Path | Why it stays |
| --- | --- |
| `app/` | Starter Go service, its Dockerfile and tests |
| `Jenkinsfile` (root) | This repository's demo pipeline: scanner versions, thresholds, `forbiddenPaths` for the starter app, and `legacySCM(scm)` loading |
| `jenkins/`, `compose.yaml`, `.env.example` | Local Docker Desktop Jenkins controller with a `file:///repo` job |
| `examples/` | Templates. Copy their content into consumer repositories or Jenkins configuration as needed. |

The step has no reference to any of these files.

## 2. Dependencies and requirements

| Area | Requirement |
| --- | --- |
| Jenkins core | An LTS line that supports `pipeline-groovy-lib`. The local demo uses 2.580.2. |
| Plugins used by the step | `workflow-aggregator` (Pipeline), `pipeline-groovy-lib` (included in the aggregator), `git` for SCM retrieval |
| Plugins used only by the demo Jenkinsfile | `junit`, `timestamper`, `configuration-as-code`, `job-dsl`, `pipeline-stage-view` |
| Agent | Linux shell, Docker CLI, Buildx 0.15 or newer, `tar`, `tail`, access to a Docker engine |
| Network | Pulls of base images and, optionally, of the hadolint image. No registry login. No push. |
| Tests | Docker only (runs Gradle in a container), or a local JDK 21 with Gradle 8.14 |

## 3. Security considerations

- **SCM credentials for library retrieval** should be a read-only credential (deploy key or token
  with read scope) used only for `operations`. Store it as a Jenkins credential; never put it in the
  library or a Jenkinsfile.
- **Prefer a sandboxed scope.** The step needs no privileged Groovy. A trusted global library is
  acceptable only if write access to the library repository is restricted and reviewed, because
  trusted code runs unsandboxed on the controller.
- **Docker access is root-equivalent.** Any agent that runs this step can control its Docker engine.
  Run it on agents dedicated to builds, not on a shared controller.
- **No registry credentials.** The step has no login or push path, and rejects registry-qualified
  image names.
- **Secrets** go through `secrets: [[id:, env:]]` and `withCredentials`. Credential-like `buildArgs`
  and `smokeTestEnv` names are rejected.
- **Allowing version overrides** lets a Jenkinsfile select any branch, tag or commit of the library.
  Decide with the `operations` owners whether `allowVersionOverride` stays `true` (useful for testing
  release candidates) or is set to `false` (consumers get only the default version). **Confirm in operations.**

## 4. Registering the library in Jenkins

Jenkins offers these scopes:

- **Global trusted libraries.** These run outside the Groovy sandbox, and any committer to the
  library repository can then run arbitrary code on the controller. The `globalLibraries` key in
  JCasC configures this scope.
- **Global untrusted libraries** (in recent `pipeline-groovy-lib` versions) and **folder-level
  libraries**. These run in the sandbox. The step needs nothing more than that, so this is the
  least-privilege choice.

**Confirm in operations** which scope the team uses. If it is a sandboxed scope, configure it once
in the UI, then copy the generated YAML from *Manage Jenkins → Configuration as Code → View
Configuration* instead of guessing the key name.

| Field | Value |
| --- | --- |
| Name | `docker-build-validate`, or the name required by operations conventions |
| Default version | A release tag, e.g. `v0.1.0`. Avoid a moving branch for production consumers. |
| Allow default version to be overridden | Per the team policy above |
| Include `@Library` changes in job recent changes | Off |
| Retrieval method | Modern SCM → Git |
| Project repository | `<LIBRARY_REPOSITORY_URL>` |
| Credentials | `<READ_ONLY_CHECKOUT_CREDENTIAL_ID>` |
| Library path | Only if the library is not at the repository root. Set it to the subdirectory that contains `vars/`, if the installed `pipeline-groovy-lib` version offers *Library Path*. **Confirm in operations.** |

[`examples/jenkins/shared-library.casc.yaml`](../examples/jenkins/shared-library.casc.yaml) shows
the global (trusted) registration in Configuration as Code, because that key is the stable and
documented one. Replace both placeholders; no real URL or credential is assumed. Only use this file
if the team accepts a trusted library and protects the library repository accordingly (review
required, limited write access). Otherwise use the sandboxed scope described above.

## 5. Consuming a pinned version

[`examples/consumer/Jenkinsfile`](../examples/consumer/Jenkinsfile) shows a separate project:

```groovy
@Library('docker-build-validate@v0.1.0') _
```

- Pin to a **tag**, such as `@v0.1.0`, or to a full commit SHA. Do not pin production pipelines to
  a branch.
- Omitting `@...` uses the configured default version.
- The consumer needs only a Dockerfile with a `test` and a `runtime` stage that meet the
  [image contract](shared-library.md#image-contract), or options that describe its own stages.
- The example does not reference `file:///repo`, Docker Desktop paths, or this repository's starter app.

To load the library dynamically instead, for example to try a release candidate:

```groovy
library(identifier: 'docker-build-validate@<commit-sha-or-tag>',
        retriever: modernSCM([$class: 'GitSCMSource',
                              remote: '<LIBRARY_REPOSITORY_URL>',
                              credentialsId: '<READ_ONLY_CHECKOUT_CREDENTIAL_ID>']))
```

## 6. Versioning and compatibility

Use semantic versioning tags, `vMAJOR.MINOR.PATCH`, unless `operations` already defines a scheme.
**Confirm in operations.**

| Change | Version bump | Examples |
| --- | --- | --- |
| Breaking | MAJOR | Removing or renaming an option or a result key; changing a default so that previously passing builds fail (for example tightening `forbiddenPaths` defaults or the smoke-test flags); dropping a platform; changing the tag format `<name>:<tag>-<arch>` |
| Compatible feature | MINOR | New optional option with a default that keeps current behaviour; new result key; new supported platform |
| Fix | PATCH | Bug fixes that do not change any documented behaviour |

Until `v1.0.0`, MINOR releases may include breaking changes. Any such change is listed under
**Breaking** in `CHANGELOG.md`. Consumers should:

- pin a tag;
- read the changelog before moving to a new tag;
- upgrade by changing only the `@Library` line, then run their pipeline once.

## 7. Cutting the first reusable release

1. Complete the [acceptance checklist](#acceptance-checklist) in this standalone repository.
2. Record the actual results in `CHANGELOG.md` under 0.1.0.
3. In `operations`, after the files have been integrated and its own CI is green, create the tag the
   team's process requires, for example `v0.1.0`. Tag it in `operations`, not here, so consumers
   reference a single source of truth. **Confirm in operations.**
4. Register or update the library's default version in Jenkins.
5. Point one consumer pipeline at the tag and run it.

## 8. After access to `operations` is granted: inspect before changing

1. **Layout.** Is there already a `vars/` at the root, or a library in a subdirectory? Are there
   other Jenkins libraries?
2. **Name collisions.** Does a `vars/dockerBuildValidate.groovy` exist? Does any step or global
   variable already use the name `dockerBuildValidate`? If so, rename this step (and update the
   tests and docs) rather than overwriting.
3. **Existing build or test tooling.** Does `operations` already have a Gradle build, a different
   JenkinsPipelineUnit version, or another test framework? Merge into it instead of adding a second
   Gradle project, and re-run this suite with its versions.
4. **Conventions.** Naming of steps and options, documentation location, changelog format, code style.
5. **Branching and release process.** Protected branches, review requirements, tag naming, who
   publishes releases.
6. **Jenkins registration.** Whether the library is already registered, under which name, which
   scope (global or folder), trusted or untrusted, default version.
7. **CI for `operations`.** How its own pipeline runs library tests. Add `ci/library-test.Dockerfile`
   or its Gradle task there.
8. **Security policy.** Pinned base images and digests, allowed scanners and thresholds, credential
   handling.

## 9. Integrating without overwriting

- Work on a feature branch and open a merge or pull request. Never push directly to a protected
  branch.
- Add the files at the agreed paths with `git add <path>` for each new file. Do not copy a whole
  directory over an existing one.
- If `build.gradle` or another shared file already exists, merge the dependency lines and the
  `test/groovy` source directory into it by hand.
- Do not change existing steps, Jenkins configuration or other teams' pipelines as part of this
  change.
- Keep the step name and option names unless the review asks for changes. If they change, release
  the result as a new version and update `docs/shared-library.md`.

## 10. Migration steps for this repository

Once `operations` has a released tag:

1. Replace the first line of the root `Jenkinsfile` with
   `@Library('<operations-library-name>@<tag>') _`.
2. Remove `vars/`, `test/`, `build.gradle`, `settings.gradle` and `ci/library-test.*` from this
   repository, together with the **Shared Library Unit Tests** stage, because `operations` now tests
   the step.
3. In `jenkins/casc.yaml`, register the global library with the `operations` URL and a read-only
   credential, or configure that in the target Jenkins instead.
4. Run the pipeline and confirm that the stage results match those from before the migration.

## Acceptance checklist

Complete this before tagging the first release. Record the results in `CHANGELOG.md`.

- [ ] Docker Desktop is healthy: `docker version` shows a Server section, and
      `docker run --rm busybox:1.37 echo ok` prints `ok`.
- [ ] `docker buildx build --progress=plain -f ci/library-test.Dockerfile .` passes, and the JUnit XML
      lists every test as passed.
- [ ] `docker buildx build --target test --output type=cacheonly app` passes (gofmt, vet, tests).
- [ ] `docker buildx build --check app` reports no warnings, and hadolint passes on `app/Dockerfile`.
- [ ] The `runtime` target builds for `linux/amd64` and `linux/arm64` with `--load` and separate tags.
      `docker image inspect` shows the expected `Architecture` and `User 65532:65532`.
- [ ] Each digest in `app/Dockerfile`, `ci/library-test.Dockerfile`, `jenkins/Dockerfile` and the
      `Jenkinsfile` resolves to its tag:
      `docker buildx imagetools inspect <tag>` shows the same digest.
- [ ] `docker compose up -d --build` starts Jenkins, the plugins install, and the `newtalkopt` job exists.
- [ ] **Build Now** completes every stage, the Tests tab shows the library tests, and the artifacts
      include the Gitleaks, Trivy, validation and SBOM reports.
- [ ] A deliberately failing library test stops the pipeline at **Shared Library Unit Tests**, and
      later stages show as skipped.
- [ ] A deliberately failing Go test stops the pipeline in **Docker Build and Validate** before any
      release image is built.
- [ ] No stray containers named `dbv-*` remain after a successful and a failed run (`docker ps -a`).
- [ ] `git diff --check` is clean, and Gitleaks reports no leaks.
- [ ] A consumer pipeline in a separate repository, using the `examples/consumer/Jenkinsfile` pattern,
      loads the library by name and pinned version and passes.
