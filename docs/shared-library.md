# `dockerBuildValidate` Shared Library step

| | |
| --- | --- |
| Source | [`vars/dockerBuildValidate.groovy`](../vars/dockerBuildValidate.groovy) |
| Help text in Jenkins | [`vars/dockerBuildValidate.txt`](../vars/dockerBuildValidate.txt) |
| Tests | [`test/groovy/DockerBuildValidateTest.groovy`](../test/groovy/DockerBuildValidateTest.groovy) |
| Library version | `0.1.0` (see [CHANGELOG](../CHANGELOG.md)) |
| Status | Implemented and unit-tested in code. **Not yet runtime-verified**: see [Verification status](#verification-status). |

The step lints, tests, builds and validates a Docker image with the **local** Docker engine through
Buildx. It never pushes an image, never uses a registry cache, and never reads credential values.
Base images and the optional hadolint image are pulled as usual.

## Requirements for the calling pipeline

- An agent with a `sh` step, the Docker CLI, Buildx 0.15 or newer (for `--check` and
  `--output type=cacheonly`), `tar`, and `tail`, plus access to a Linux Docker engine.
- Jenkins plugins: Pipeline (`workflow-aggregator`) and Pipeline: Groovy Libraries
  (`pipeline-groovy-lib`). No other plugin is needed by the step.
- The step runs in the Groovy sandbox like any library that is not trusted. It uses only standard
  Groovy collection and string methods. If the controller requests a script approval, approve it in
  *Manage Jenkins → In-process Script Approval* rather than marking the library trusted.

## Image contract

These requirements apply only to the phases that are enabled. Each one can be adapted with an
option or a switch.

| Phase | Requires from the Dockerfile / image | To relax |
| --- | --- | --- |
| Test target | A stage named `testTarget` (default `test`) whose `RUN` fails when tests fail | `testTarget: null` or `runTests: false` |
| Release build | A stage named `releaseTarget` (default `runtime`) | Set `releaseTarget` |
| Non-root check | `USER` is a numeric non-zero UID, optionally with a GID (`65532`, `10001:10001`, `1000:0`) | Not configurable. A named user (`app`) or root fails, because a name cannot be verified as non-root without running the image. |
| Forbidden paths | Listed paths must not exist in the exported filesystem | Leave `forbiddenPaths` empty (the default) |
| Smoke test | A `HEALTHCHECK`, runs with a read-only root filesystem and no capabilities, exits with an allowed code after SIGTERM | `smokeTestTmpfs`, `smokeTestEnv`, `smokeTestMemory`, `allowedExitCodes`, `stopTimeoutSeconds`, or `smokeTest: false` |

## Execution order

1. **Configuration validation.** Any unknown option, malformed value or invalid combination calls
   `error()` before a single shell command runs.
2. **Lint** (`lint`): `docker buildx build --check`, then `hadolint --failure-threshold warning` in a
   container if `hadolintImage` is set.
3. **Test target** (`runTests` and `testTarget`): one `docker buildx build --target <testTarget>
   --output type=cacheonly`. No image is produced. This runs once, on the engine's native platform.
4. **Release build** (`build`): one `docker buildx build --platform <p> --target <releaseTarget>
   --load` per platform, tagged `<imageName>:<imageTag>-<arch>`. With exactly one platform, the image
   is also tagged `<imageName>:<imageTag>`.
5. **Validation** (`validate`), for each platform in order:
   1. `docker image inspect` checks the non-root user.
   2. If `forbiddenPaths` is not empty: `docker create --platform <p> --entrypoint
      /dbv-inspection-only`, then `docker export | tar -tf -`, then the inspection container is removed.
      The image is never started for this check, so it also works for foreign architectures and for
      images without an entrypoint.
   3. If `smokeTest` is on and the platform matches `docker version --format '{{.Server.Arch}}'`: run
      the smoke test. Non-native platforms are reported as `skipped: platform is not native to the
      Docker engine`. They are never reported as passed.
6. A summary is echoed and the result map is returned.

### Smoke test

The container is started like this:

```text
docker run --detach --name dbv-<BUILD_TAG>-<os>-<arch> --read-only --cap-drop ALL
  --security-opt no-new-privileges --pids-limit 64 --memory <smokeTestMemory> --cpus 1
  [--tmpfs <path>:rw,noexec,nosuid,size=64m ...] [--env KEY=VALUE ...] <image>
```

No ports are published, so the test does not depend on how the agent reaches the Docker host. The
step then:

1. Polls `docker inspect` once per second, up to `healthTimeoutSeconds` times. It succeeds on
   `healthy`, and fails immediately on `unhealthy`, on a missing `HEALTHCHECK` (`none`), or if the
   container is no longer `running`.
2. Runs `docker stop --time <stopTimeoutSeconds>`, which sends the image's stop signal (SIGTERM by
   default) and SIGKILL after the timeout.
3. Requires `.State.ExitCode` to be in `allowedExitCodes` (default `[0]`). An application killed
   after the timeout exits with `137` and fails unless you allow that code.

**What the smoke test proves:** the image starts under these restrictions, its own health check
passes, and it exits with an allowed code after the stop signal. **What it does not prove:** that any
specific endpoint returns correct data, or that the application is functionally correct. Those belong
in the test target or in later integration tests.

## Options

| Option | Type | Default | Accepted values and notes |
| --- | --- | --- | --- |
| `imageName` | String | **required** | Lowercase repository path: `[a-z0-9]` segments separated by `.`, `_`, `-`, joined by `/`. A registry host or port (`localhost:5000/app`) is rejected to keep images local. |
| `imageTag` | String | first 12 chars of `env.GIT_COMMIT` | `[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}`. Required if `GIT_COMMIT` is unset. |
| `dockerfile` | String | `Dockerfile` | Path characters `[A-Za-z0-9_./-]`, relative to the workspace. |
| `context` | String | `.` | Same character set. |
| `platforms` | List | `['linux/amd64']` | Non-empty and unique, from `linux/amd64` and `linux/arm64`. |
| `releaseTarget` | String | `runtime` | Stage name `[A-Za-z0-9][A-Za-z0-9_.-]*`. |
| `testTarget` | String or null | `test` | Stage name. `null` or `''` disables the phase. |
| `lint`, `runTests`, `build`, `validate`, `smokeTest` | Boolean | `true` | Must be real booleans. The string `'false'` is rejected. `validate: true` requires `build: true`. |
| `hadolintImage` | String or null | `null` | Image reference. The image must provide `/bin/hadolint`, as the official images do. |
| `builder` | String or null | `null` (current builder) | Buildx builder name. |
| `cacheDir` | String or null | `null` | Local cache directory, used with `--cache-from type=local` and `--cache-to type=local,mode=max`. Requires `builder` naming a `docker-container` builder. |
| `buildArgs` | Map | `[:]` | Keys must be valid identifiers. Values must be single-line. Credential-like names are rejected (`*SECRET*`, `*TOKEN*`, `*PASSWORD*`, `*PASSWD*`, `*CREDENTIAL*`, `*API_KEY*`/`*APIKEY*`, `*PRIVATE_KEY*`). Passed to the test and release builds. |
| `labels` | Map | `[:]` | Keys `[A-Za-z0-9][A-Za-z0-9._/-]*`, single-line values. Release builds only. |
| `secrets` | List of Map | `[]` | Each entry is exactly `[id: '<id>', env: '<ENV_VAR>']` and becomes `--secret id=<id>,env=<ENV_VAR>` on the test and release builds. Bind the variable with `withCredentials`. Only its name appears in the command. |
| `forbiddenPaths` | List | `[]` | Paths inside the image. Leading `/` and `./` and trailing `/` are ignored. A path matches itself and everything below it, so `usr/local/go` matches `usr/local/go/bin/go` but not `usr/local/gopher`. |
| `healthTimeoutSeconds` | Integer | `30` | 1–600 one-second polls. |
| `stopTimeoutSeconds` | Integer | `10` | 1–120 seconds, passed to `docker stop --time`. |
| `allowedExitCodes` | List of Integer | `[0]` | Non-empty, each 0–255. Use `[0, 143]` for runtimes that exit 143 on SIGTERM. |
| `smokeTestEnv` | Map | `[:]` | Non-secret `--env` values for the smoke test. Credential-like names are rejected because the values would appear in the command line and `docker inspect`. |
| `smokeTestTmpfs` | List | `[]` | Absolute paths mounted as `tmpfs` (`rw,noexec,nosuid,size=64m`) for images that need writable scratch space under `--read-only`. |
| `smokeTestMemory` | String | `128m` | `<n>k`, `<n>m` or `<n>g`. |

All values are validated against these patterns and single-quoted before they are placed in a shell
command. That makes shell metacharacters in a value inert, and rejects them outright in names and paths.

## Return value

```groovy
[
  images     : ['linux/amd64': 'team/app:abc123-amd64', 'linux/arm64': 'team/app:abc123-arm64'],
  lint       : 'passed' | 'disabled',
  tests      : 'passed' | 'disabled',
  build      : 'passed' | 'disabled',
  validation : ['linux/amd64': 'passed', 'linux/arm64': 'passed'],
  smokeTest  : ['linux/amd64': 'passed' | 'disabled',
                'linux/arm64': 'skipped: platform is not native to the Docker engine'],
  nativeImage: 'team/app:abc123-amd64',
  summary    : '<multi-line text, also echoed>'
]
```

`nativeImage` is `null` when validation is disabled or no requested platform is native.

## Error behaviour

- Configuration errors and validation failures call `error('dockerBuildValidate: <reason>')`, which
  marks the build `FAILURE` and aborts the step.
- A failing shell command (lint, hadolint, test target, build, `docker create`, `docker export`,
  `docker run`, `docker stop`) throws the usual Jenkins `script returned exit code N` error.
- The step stops at the first failure. No later phase runs and no result map is returned, so a
  caller cannot mistake a partial run for success.

## Cleanup guarantees

- **Inspection container** (forbidden paths): removed in a `finally` block after a successful or
  failed listing. A failing removal only prints a warning, so it never masks the original error.
- **Smoke-test container**: a stale container with the same name is removed first. On any failure,
  including an aborted build, the last 50 log lines are printed. The container is then removed in a
  `finally` block, using `|| true`, so cleanup cannot mask the original error.
- **Not cleaned up**: the images built by the step stay in the local image store on purpose, so later
  stages such as scanners can use them. Prune them on whatever schedule suits the agent.
- If the agent process itself dies (for example, the controller is killed), the `finally` blocks do
  not run. The next build with the same `BUILD_TAG` removes the stale smoke-test container.

## Limitations

- Only `linux/amd64` and `linux/arm64` are supported. A single multi-platform manifest list is not
  created, because `--load` into a local image store keeps one image per tag. Each architecture is
  therefore loaded under its own tag.
- Non-native platforms are checked statically only.
- `cacheDir` needs a `docker-container` builder. The default `docker` driver keeps its own cache.
- The health status comes from the image's own `HEALTHCHECK`. Images without one need `smokeTest: false`.
- Container names are derived from `BUILD_TAG`, so two concurrent calls in the same build need
  different platforms, or the second call must wait for the first.

## Unit tests

The suite uses JenkinsPipelineUnit 1.31 and JUnit 4.13.2 on Gradle 8.14.3 with JDK 21.
JenkinsPipelineUnit 1.31 is published for Java 21 and newer, and a JDK 17 run fails during
dependency resolution. The tests load the real step file and replace only the Jenkins boundary:

- `sh` is a recording fake. It returns scripted stdout and, like Jenkins, throws when a rule gives a
  non-zero exit code without `returnStatus`.
- `error` sets `FAILURE` **and throws**. The stock JenkinsPipelineUnit mock only sets the status and
  carries on, which would hide control-flow bugs.
- `sleep` and `echo` are recorded.

The assertions check exact command strings, call counts and order, the returned map, cleanup calls,
and the build result. The suite covers:

- **Defaults and parameter handling:** default tag, missing or invalid names, tags and platforms,
  unknown options, non-boolean flags, credential-like build args and smoke-test env, malformed
  secrets, shell quoting, cache and builder rules, numeric ranges.
- **Conditional execution:** the lint, hadolint, test target, build, validation and smoke-test
  switches; `build: false` combined with `validate: true`; native versus foreign platforms.
- **Ordering:** lint → test target → release builds → validation; run → health → stop → exit code.
- **Failures:** every phase's command failure, root or named users, forbidden paths, `unhealthy`,
  `exited`, no `HEALTHCHECK`, timeout, disallowed exit code, a failing `docker stop` and a failing
  `docker export`. Each case also checks that the containers are removed.

Run the tests in Docker (no local JDK or Gradle needed):

```sh
docker buildx build --progress=plain -f ci/library-test.Dockerfile .
```

The command exits non-zero if any test fails. Add `--target report --output
type=local,dest=build/library-test` to export the JUnit XML, the HTML report and `exit-code`.

## Verification status

At the time of the first commit, Docker Desktop was returning `500 Internal Server Error` because
drive C: had no free space. **None of the following have been executed yet:** compiling and running
this suite, `buildx --check`, `--output type=cacheonly` with the `docker` driver, the per-platform
`--load` builds, `docker create --entrypoint` with the containerd image store, and the smoke test.
Treat 0.1.0 as a release candidate until the acceptance checklist in
[operations-integration.md](operations-integration.md#acceptance-checklist) has been completed.
