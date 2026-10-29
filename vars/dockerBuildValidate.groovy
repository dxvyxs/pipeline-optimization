import groovy.transform.Field

@Field final String STEP = 'dockerBuildValidate'
@Field final List SUPPORTED_PLATFORMS = ['linux/amd64', 'linux/arm64']
@Field final List KNOWN_OPTIONS = [
    'imageName', 'imageTag', 'dockerfile', 'context', 'platforms',
    'releaseTarget', 'testTarget', 'lint', 'runTests', 'build', 'validate', 'smokeTest',
    'hadolintImage', 'builder', 'cacheDir', 'buildArgs', 'labels', 'secrets',
    'forbiddenPaths', 'healthTimeoutSeconds', 'stopTimeoutSeconds', 'allowedExitCodes',
    'smokeTestEnv', 'smokeTestTmpfs', 'smokeTestMemory'
]
@Field final String MEMORY_PATTERN = /[1-9][0-9]*[kmg]/
@Field final String ABSOLUTE_PATH_PATTERN = /\/[A-Za-z0-9_.\/-]*/
@Field final String IMAGE_NAME_PATTERN = /[a-z0-9]+(?:[._-][a-z0-9]+)*(?:\/[a-z0-9]+(?:[._-][a-z0-9]+)*)*/
@Field final String TAG_PATTERN = /[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}/
@Field final String PATH_PATTERN = /[A-Za-z0-9_.\/-]+/
@Field final String NAME_PATTERN = /[A-Za-z0-9][A-Za-z0-9_.-]*/
@Field final String ENV_NAME_PATTERN = /[A-Za-z_][A-Za-z0-9_]*/
@Field final String LABEL_KEY_PATTERN = /[A-Za-z0-9][A-Za-z0-9._\/-]*/
@Field final String IMAGE_REF_PATTERN = /[A-Za-z0-9][A-Za-z0-9._\/:@-]*/
@Field final String SECRET_LIKE_PATTERN = /(?i).*(SECRET|TOKEN|PASSWORD|PASSWD|CREDENTIAL|API_?KEY|PRIVATE_?KEY).*/
@Field final String NON_ROOT_USER_PATTERN = /[1-9][0-9]*(?::[0-9]+)?/

Map call(Map config = [:]) {
    Map cfg = resolveConfig(config)
    Map result = [
        images     : [:],
        lint       : 'disabled',
        tests      : 'disabled',
        build      : 'disabled',
        validation : [:],
        smokeTest  : [:],
        nativeImage: null
    ]
    String builderOpt = cfg.builder ? " --builder ${shellQuote(cfg.builder)}" : ''

    if (cfg.lint) {
        runLint(cfg, builderOpt)
        result.lint = 'passed'
    }

    if (cfg.runTests && cfg.testTarget) {
        runTestTarget(cfg, builderOpt)
        result.tests = 'passed'
    }

    if (cfg.build) {
        for (String platform in cfg.platforms) {
            List tags = imageTags(cfg, platform)
            sh(label: "${STEP}: build ${cfg.releaseTarget} for ${platform}", script: buildCommand(cfg, builderOpt, platform, tags))
            result.images[platform] = tags[0]
        }
        result.build = 'passed'
    }

    if (cfg.validate) {
        String nativeArch = sh(label: "${STEP}: detect Docker engine architecture", returnStdout: true,
            script: "docker version --format '{{.Server.Arch}}'").trim()
        for (String platform in cfg.platforms) {
            String image = result.images[platform]
            boolean isNative = platform == "linux/${nativeArch}".toString()
            validateImage(cfg, platform, image, isNative, result)
            if (isNative && !result.nativeImage) {
                result.nativeImage = image
            }
        }
    }

    result.summary = summarize(cfg, result)
    echo(result.summary)
    return result
}

Map resolveConfig(Map config) {
    if (config == null) {
        error("${STEP}: a configuration map is required")
    }
    List unknown = []
    for (Object key in config.keySet()) {
        if (!KNOWN_OPTIONS.contains(key as String)) {
            unknown.add(key as String)
        }
    }
    if (unknown) {
        error("${STEP}: unknown option(s): ${unknown.join(', ')}")
    }

    Map cfg = [
        imageName           : config.imageName,
        imageTag            : config.containsKey('imageTag') ? config.imageTag : defaultTag(),
        dockerfile          : option(config, 'dockerfile', 'Dockerfile'),
        context             : option(config, 'context', '.'),
        platforms           : option(config, 'platforms', ['linux/amd64']),
        releaseTarget       : option(config, 'releaseTarget', 'runtime'),
        testTarget          : option(config, 'testTarget', 'test'),
        lint                : option(config, 'lint', true),
        runTests            : option(config, 'runTests', true),
        build               : option(config, 'build', true),
        validate            : option(config, 'validate', true),
        smokeTest           : option(config, 'smokeTest', true),
        hadolintImage       : option(config, 'hadolintImage', null),
        builder             : option(config, 'builder', null),
        cacheDir            : option(config, 'cacheDir', null),
        buildArgs           : option(config, 'buildArgs', [:]),
        labels              : option(config, 'labels', [:]),
        secrets             : option(config, 'secrets', []),
        forbiddenPaths      : option(config, 'forbiddenPaths', []),
        healthTimeoutSeconds: option(config, 'healthTimeoutSeconds', 30),
        stopTimeoutSeconds  : option(config, 'stopTimeoutSeconds', 10),
        allowedExitCodes    : option(config, 'allowedExitCodes', [0]),
        smokeTestEnv        : option(config, 'smokeTestEnv', [:]),
        smokeTestTmpfs      : option(config, 'smokeTestTmpfs', []),
        smokeTestMemory     : option(config, 'smokeTestMemory', '128m')
    ]

    requirePattern(cfg.imageName, IMAGE_NAME_PATTERN, 'imageName', 'a lowercase local repository name such as team/app, without a registry host')
    if (cfg.imageTag == null) {
        error("${STEP}: imageTag is required when env.GIT_COMMIT is not available")
    }
    requirePattern(cfg.imageTag, TAG_PATTERN, 'imageTag', 'a Docker tag of at most 101 characters')
    requirePattern(cfg.dockerfile, PATH_PATTERN, 'dockerfile', 'a relative or absolute path')
    requirePattern(cfg.context, PATH_PATTERN, 'context', 'a relative or absolute path')
    requirePattern(cfg.releaseTarget, NAME_PATTERN, 'releaseTarget', 'a Dockerfile stage name')
    if (cfg.testTarget != null && cfg.testTarget != '') {
        requirePattern(cfg.testTarget, NAME_PATTERN, 'testTarget', 'a Dockerfile stage name, or null to disable')
    }

    for (String flag in ['lint', 'runTests', 'build', 'validate', 'smokeTest']) {
        if (!(cfg[flag] instanceof Boolean)) {
            error("${STEP}: ${flag} must be true or false")
        }
    }
    if (cfg.validate && !cfg.build) {
        error("${STEP}: validate requires build; set validate: false when build is false")
    }

    validatePlatforms(cfg.platforms)

    if (cfg.hadolintImage != null) {
        requirePattern(cfg.hadolintImage, IMAGE_REF_PATTERN, 'hadolintImage', 'an image reference')
    }
    if (cfg.builder != null) {
        requirePattern(cfg.builder, NAME_PATTERN, 'builder', 'a Buildx builder name')
    }
    if (cfg.cacheDir != null) {
        requirePattern(cfg.cacheDir, PATH_PATTERN, 'cacheDir', 'a local directory path')
        if (cfg.builder == null) {
            error("${STEP}: cacheDir requires builder, because the default docker driver cannot export a local cache")
        }
    }

    validateBuildArgs(cfg.buildArgs)
    validateLabels(cfg.labels)
    validateSecrets(cfg.secrets)
    cfg.forbiddenPaths = normalizeForbiddenPaths(cfg.forbiddenPaths)

    requireIntegerBetween(cfg.healthTimeoutSeconds, 1, 600, 'healthTimeoutSeconds')
    requireIntegerBetween(cfg.stopTimeoutSeconds, 1, 120, 'stopTimeoutSeconds')
    validateExitCodes(cfg.allowedExitCodes)
    validateSmokeTestEnv(cfg.smokeTestEnv)
    validateTmpfs(cfg.smokeTestTmpfs)
    requirePattern(cfg.smokeTestMemory, MEMORY_PATTERN, 'smokeTestMemory', 'a Docker memory limit such as 128m or 1g')
    return cfg
}

void requireIntegerBetween(def value, int min, int max, String name) {
    if (!(value instanceof Integer) || value < min || value > max) {
        error("${STEP}: ${name} must be an integer between ${min} and ${max}")
    }
}

void validateExitCodes(def codes) {
    if (!(codes instanceof List) || codes.isEmpty()) {
        error("${STEP}: allowedExitCodes must be a non-empty list of integers between 0 and 255")
    }
    for (Object code in codes) {
        requireIntegerBetween(code, 0, 255, 'allowedExitCodes entry')
    }
}

void validateSmokeTestEnv(def envVars) {
    if (!(envVars instanceof Map)) {
        error("${STEP}: smokeTestEnv must be a map")
    }
    for (Object key in envVars.keySet()) {
        requirePattern(key, ENV_NAME_PATTERN, 'smokeTestEnv key', 'an environment variable name')
        if (key.toString() ==~ SECRET_LIKE_PATTERN) {
            error("${STEP}: smokeTestEnv '${key}' looks like a credential; smoke tests must not receive credentials on the command line")
        }
        requireSingleLine(envVars[key], "smokeTestEnv.${key}")
    }
}

void validateTmpfs(def paths) {
    if (!(paths instanceof List)) {
        error("${STEP}: smokeTestTmpfs must be a list of absolute paths")
    }
    for (Object path in paths) {
        requirePattern(path, ABSOLUTE_PATH_PATTERN, 'smokeTestTmpfs entry', 'an absolute path such as /tmp')
    }
}

def option(Map config, String key, def defaultValue) {
    return config.containsKey(key) ? config[key] : defaultValue
}

String defaultTag() {
    String sha = env.GIT_COMMIT
    if (sha && sha.length() >= 12) {
        return sha.substring(0, 12)
    }
    return null
}

void requirePattern(def value, String pattern, String name, String expectation) {
    if (!(value instanceof CharSequence) || !(value.toString() ==~ pattern)) {
        error("${STEP}: ${name} must be ${expectation}; got '${value}'")
    }
}

void validatePlatforms(def platforms) {
    if (!(platforms instanceof List) || platforms.isEmpty()) {
        error("${STEP}: platforms must be a non-empty list chosen from ${SUPPORTED_PLATFORMS.join(', ')}")
    }
    List seen = []
    for (Object platform in platforms) {
        if (!SUPPORTED_PLATFORMS.contains(platform)) {
            error("${STEP}: unsupported platform '${platform}'; supported: ${SUPPORTED_PLATFORMS.join(', ')}")
        }
        if (seen.contains(platform)) {
            error("${STEP}: platform '${platform}' is listed more than once")
        }
        seen.add(platform)
    }
}

void validateBuildArgs(def buildArgs) {
    if (!(buildArgs instanceof Map)) {
        error("${STEP}: buildArgs must be a map")
    }
    for (Object key in buildArgs.keySet()) {
        requirePattern(key, ENV_NAME_PATTERN, 'buildArgs key', 'a valid build argument name')
        if (key.toString() ==~ SECRET_LIKE_PATTERN) {
            error("${STEP}: build argument '${key}' looks like a credential; pass it through secrets so it is not stored in image metadata")
        }
        requireSingleLine(buildArgs[key], "buildArgs.${key}")
    }
}

void validateLabels(def labels) {
    if (!(labels instanceof Map)) {
        error("${STEP}: labels must be a map")
    }
    for (Object key in labels.keySet()) {
        requirePattern(key, LABEL_KEY_PATTERN, 'labels key', 'a valid label key')
        requireSingleLine(labels[key], "labels.${key}")
    }
}

void requireSingleLine(def value, String name) {
    if (value == null || value.toString().contains('\n') || value.toString().contains('\r')) {
        error("${STEP}: ${name} must be a non-null single-line value")
    }
}

void validateSecrets(def secrets) {
    if (!(secrets instanceof List)) {
        error("${STEP}: secrets must be a list of [id: ..., env: ...] maps")
    }
    for (Object secret in secrets) {
        if (!(secret instanceof Map) || secret.keySet() != (['id', 'env'] as Set)) {
            error("${STEP}: each secret must be a map with exactly the keys id and env")
        }
        requirePattern(secret.id, NAME_PATTERN, 'secret id', 'a BuildKit secret id')
        requirePattern(secret.env, ENV_NAME_PATTERN, 'secret env', 'an environment variable name')
    }
}

List normalizeForbiddenPaths(def paths) {
    if (!(paths instanceof List)) {
        error("${STEP}: forbiddenPaths must be a list of paths")
    }
    List normalized = []
    for (Object path in paths) {
        requirePattern(path, PATH_PATTERN, 'forbiddenPaths entry', 'a path inside the image')
        String clean = stripSlashes(path.toString())
        if (!clean) {
            error("${STEP}: forbiddenPaths entries must not be the image root")
        }
        normalized.add(clean)
    }
    return normalized
}

String stripSlashes(String value) {
    String clean = value
    while (clean.startsWith('./')) {
        clean = clean.substring(2)
    }
    while (clean.startsWith('/')) {
        clean = clean.substring(1)
    }
    while (clean.endsWith('/')) {
        clean = clean.substring(0, clean.length() - 1)
    }
    return clean
}

String shellQuote(def value) {
    return "'" + value.toString().replace("'", "'\"'\"'") + "'"
}

List imageTags(Map cfg, String platform) {
    String arch = platform.substring(platform.indexOf('/') + 1)
    List tags = ["${cfg.imageName}:${cfg.imageTag}-${arch}".toString()]
    if (cfg.platforms.size() == 1) {
        tags.add("${cfg.imageName}:${cfg.imageTag}".toString())
    }
    return tags
}

List commonBuildOptions(Map cfg) {
    List parts = []
    for (Object key in cfg.buildArgs.keySet()) {
        parts.add("--build-arg ${shellQuote(key.toString() + '=' + cfg.buildArgs[key].toString())}")
    }
    for (Object secret in cfg.secrets) {
        parts.add("--secret ${shellQuote('id=' + secret.id + ',env=' + secret.env)}")
    }
    if (cfg.cacheDir) {
        parts.add("--cache-from ${shellQuote('type=local,src=' + cfg.cacheDir)}")
    }
    return parts
}

void runLint(Map cfg, String builderOpt) {
    sh(label: "${STEP}: Dockerfile checks (buildx --check)",
        script: "docker buildx build${builderOpt} --check --file ${shellQuote(cfg.dockerfile)} ${shellQuote(cfg.context)}")
    if (cfg.hadolintImage) {
        sh(label: "${STEP}: Dockerfile lint (hadolint)",
            script: "docker run --rm -i --entrypoint /bin/hadolint ${shellQuote(cfg.hadolintImage)} --failure-threshold warning - < ${shellQuote(cfg.dockerfile)}")
    }
}

void runTestTarget(Map cfg, String builderOpt) {
    List parts = ["docker buildx build${builderOpt}", '--progress=plain',
                  "--target ${shellQuote(cfg.testTarget)}", "--file ${shellQuote(cfg.dockerfile)}"]
    parts.addAll(commonBuildOptions(cfg))
    parts.add('--output type=cacheonly')
    parts.add(shellQuote(cfg.context))
    sh(label: "${STEP}: run Dockerfile test target ${cfg.testTarget}", script: parts.join(' '))
}

String buildCommand(Map cfg, String builderOpt, String platform, List tags) {
    List parts = ["docker buildx build${builderOpt}", '--progress=plain',
                  "--platform ${shellQuote(platform)}", "--target ${shellQuote(cfg.releaseTarget)}",
                  "--file ${shellQuote(cfg.dockerfile)}"]
    for (String tag in tags) {
        parts.add("--tag ${shellQuote(tag)}")
    }
    parts.addAll(commonBuildOptions(cfg))
    for (Object key in cfg.labels.keySet()) {
        parts.add("--label ${shellQuote(key.toString() + '=' + cfg.labels[key].toString())}")
    }
    if (cfg.cacheDir) {
        parts.add("--cache-to ${shellQuote('type=local,mode=max,dest=' + cfg.cacheDir)}")
    }
    parts.add('--load')
    parts.add(shellQuote(cfg.context))
    return parts.join(' ')
}

void validateImage(Map cfg, String platform, String image, boolean isNative, Map result) {
    String user = sh(label: "${STEP}: inspect runtime user of ${image}", returnStdout: true,
        script: "docker image inspect --format '{{.Config.User}}' ${shellQuote(image)}").trim()
    if (!(user ==~ NON_ROOT_USER_PATTERN)) {
        error("${STEP}: ${image} must declare a numeric non-root USER; found '${user ?: '<unset>'}'")
    }

    if (cfg.forbiddenPaths) {
        checkForbiddenPaths(cfg, platform, image)
    }

    if (!cfg.smokeTest) {
        result.smokeTest[platform] = 'disabled'
    } else if (isNative) {
        runSmokeTest(cfg, platform, image)
        result.smokeTest[platform] = 'passed'
    } else {
        result.smokeTest[platform] = 'skipped: platform is not native to the Docker engine'
    }
    result.validation[platform] = 'passed'
}

void checkForbiddenPaths(Map cfg, String platform, String image) {
    String cid = sh(label: "${STEP}: create inspection container for ${image}", returnStdout: true,
        script: "docker create --platform ${shellQuote(platform)} --entrypoint /dbv-inspection-only ${shellQuote(image)}").trim()
    try {
        String listing = sh(label: "${STEP}: list filesystem of ${image}", returnStdout: true,
            script: "docker export ${shellQuote(cid)} | tar -tf -")
        List found = []
        for (String line in listing.split('\n')) {
            String entry = stripSlashes(line.trim())
            for (String forbidden in cfg.forbiddenPaths) {
                if (entry && (entry == forbidden || entry.startsWith(forbidden + '/')) && !found.contains(entry)) {
                    found.add(entry)
                }
            }
        }
        if (found) {
            List shown = found.size() > 10 ? found.subList(0, 10) : found
            error("${STEP}: ${image} contains forbidden paths: ${shown.join(', ')}")
        }
    } finally {
        sh(label: "${STEP}: remove inspection container",
            script: "docker rm -f ${shellQuote(cid)} >/dev/null 2>&1 || echo 'WARNING: could not remove inspection container' >&2")
    }
}

String containerName(String platform) {
    String buildTag = env.BUILD_TAG ?: 'local'
    return "dbv-${buildTag}-${platform.replace('/', '-')}".replaceAll(/[^A-Za-z0-9_.-]/, '-')
}

void runSmokeTest(Map cfg, String platform, String image) {
    String name = containerName(platform)
    String quotedName = shellQuote(name)
    sh(label: "${STEP}: remove stale smoke-test container", script: "docker rm -f ${quotedName} >/dev/null 2>&1 || true")
    try {
        sh(label: "${STEP}: start hardened smoke-test container for ${image}", script: smokeRunCommand(cfg, quotedName, image))
        waitForHealthy(cfg, name, image)
        sh(label: "${STEP}: stop smoke-test container with SIGTERM", script: "docker stop --time ${cfg.stopTimeoutSeconds} ${quotedName}")
        String exitCode = sh(label: "${STEP}: read smoke-test exit code", returnStdout: true,
            script: "docker inspect --format '{{.State.ExitCode}}' ${quotedName}").trim()
        List allowed = []
        for (Object code in cfg.allowedExitCodes) {
            allowed.add(code.toString())
        }
        if (!allowed.contains(exitCode)) {
            error("${STEP}: ${image} did not shut down cleanly on SIGTERM; exit code ${exitCode}, allowed ${allowed.join(', ')}")
        }
    } catch (Exception failure) {
        sh(label: "${STEP}: smoke-test container logs", script: "docker logs ${quotedName} 2>&1 | tail -n 50 || true")
        throw failure
    } finally {
        sh(label: "${STEP}: remove smoke-test container", script: "docker rm -f ${quotedName} >/dev/null 2>&1 || true")
    }
}

String smokeRunCommand(Map cfg, String quotedName, String image) {
    List parts = ['docker run --detach', "--name ${quotedName}", '--read-only', '--cap-drop ALL',
                  '--security-opt no-new-privileges', '--pids-limit 64', "--memory ${cfg.smokeTestMemory}", '--cpus 1']
    for (Object path in cfg.smokeTestTmpfs) {
        parts.add("--tmpfs ${shellQuote(path.toString() + ':rw,noexec,nosuid,size=64m')}")
    }
    for (Object key in cfg.smokeTestEnv.keySet()) {
        parts.add("--env ${shellQuote(key.toString() + '=' + cfg.smokeTestEnv[key].toString())}")
    }
    parts.add(shellQuote(image))
    return parts.join(' ')
}

void waitForHealthy(Map cfg, String name, String image) {
    String format = '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
    for (int attempt = 1; attempt <= cfg.healthTimeoutSeconds; attempt++) {
        String state = sh(label: "${STEP}: poll container health", returnStdout: true,
            script: "docker inspect --format '${format}' ${shellQuote(name)}").trim()
        List parts = state.tokenize('|')
        String status = parts.size() > 0 ? parts[0] : ''
        String health = parts.size() > 1 ? parts[1] : ''
        if (health == 'none') {
            error("${STEP}: ${image} has no HEALTHCHECK; the smoke test requires one")
        }
        if (status != 'running') {
            error("${STEP}: ${image} stopped before becoming healthy (state ${status})")
        }
        if (health == 'healthy') {
            return
        }
        if (health == 'unhealthy') {
            error("${STEP}: ${image} reported unhealthy")
        }
        sleep(time: 1, unit: 'SECONDS')
    }
    error("${STEP}: ${image} did not become healthy within ${cfg.healthTimeoutSeconds} seconds")
}

String summarize(Map cfg, Map result) {
    List lines = ["${STEP} summary for ${cfg.imageName}:${cfg.imageTag}".toString(),
                  "lint: ${result.lint}".toString(),
                  "tests: ${result.tests}".toString(),
                  "build: ${result.build}".toString()]
    for (String platform in cfg.platforms) {
        String image = result.images[platform] ?: 'not built'
        String validation = result.validation[platform] ?: (cfg.validate ? 'not run' : 'disabled')
        String smoke = result.smokeTest[platform] ?: (cfg.validate ? 'not run' : 'disabled')
        lines.add("${platform}: image=${image} validation=${validation} smokeTest=${smoke}".toString())
    }
    return lines.join('\n')
}
