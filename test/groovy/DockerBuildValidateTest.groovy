import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Test

import java.util.regex.Pattern

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNull
import static org.junit.Assert.assertTrue
import static org.junit.Assert.fail

class DockerBuildValidateTest extends BasePipelineTest {

    static class PipelineAbort extends RuntimeException {
        PipelineAbort(String message) { super(message) }
    }

    static class ShellFailure extends RuntimeException {
        final int exitCode
        ShellFailure(String script, int exitCode) {
            super("script returned exit code ${exitCode}: ${script}")
            this.exitCode = exitCode
        }
    }

    List<Map> shCalls
    List<Map> shRules
    List<Map> sleepCalls
    List<String> echoes
    def subject

    @Before
    void setUp() {
        super.setUp()
        shCalls = []
        shRules = []
        sleepCalls = []
        echoes = []
        helper.registerAllowedMethod('sh', [Map], { Map args -> fakeSh(args) })
        helper.registerAllowedMethod('error', [String], { String message ->
            updateBuildStatus('FAILURE')
            throw new PipelineAbort(message)
        })
        helper.registerAllowedMethod('sleep', [Map], { Map args -> sleepCalls << args })
        helper.registerAllowedMethod('echo', [String], { String message -> echoes << message })
        binding.setVariable('env', [GIT_COMMIT: '0123456789abcdef0123456789abcdef01234567', BUILD_TAG: 'jenkins-demo-7'])

        respond(~/docker version --format .*/, 'amd64\n')
        respond(~/docker image inspect --format '\{\{\.Config\.User\}\}' .*/, '65532:65532\n')
        respond(~/docker create .*/, 'cid-123\n')
        respond(~/docker export .*/, 'server\n')
        respond(~/docker inspect --format '\{\{\.State\.Status\}\}.*/, 'running|healthy\n')
        respond(~/docker inspect --format '\{\{\.State\.ExitCode\}\}' .*/, '0\n')

        subject = loadScript('vars/dockerBuildValidate.groovy')
    }

    void respond(Pattern pattern, String stdout, int exitCode = 0) {
        shRules << [pattern: pattern, stdout: stdout, exit: exitCode]
    }

    void failWhen(Pattern pattern, int exitCode = 1) {
        respond(pattern, '', exitCode)
    }

    def fakeSh(Map args) {
        shCalls << args
        String script = args.script
        Map rule = shRules.reverse().find { script ==~ it.pattern }
        int exitCode = rule ? rule.exit : 0
        String stdout = rule ? rule.stdout : ''
        if (args.returnStatus) {
            return exitCode
        }
        if (exitCode != 0) {
            throw new ShellFailure(script, exitCode)
        }
        return args.returnStdout ? stdout : null
    }

    List<String> scripts() {
        shCalls.collect { it.script as String }
    }

    List<String> scriptsMatching(String fragment) {
        scripts().findAll { it.contains(fragment) }
    }

    List<String> releaseBuilds() {
        scripts().findAll { it.startsWith('docker buildx build') && it.contains('--load') }
    }

    int indexOfFirst(String fragment) {
        scripts().findIndexOf { it.contains(fragment) }
    }

    Map baseConfig(Map overrides = [:]) {
        Map config = [imageName: 'newtalkopt/starter-app', imageTag: 'abc123', dockerfile: 'app/Dockerfile', context: 'app']
        config.putAll(overrides)
        return config
    }

    PipelineAbort expectAbort(Closure action) {
        try {
            action()
        } catch (PipelineAbort expected) {
            assertEquals('FAILURE', binding.getVariable('currentBuild').result)
            return expected
        }
        fail('expected the step to call error()')
        return null
    }

    ShellFailure expectShellFailure(Closure action) {
        try {
            action()
        } catch (ShellFailure expected) {
            return expected
        }
        fail('expected a failing sh command to propagate')
        return null
    }

    @Test
    void buildsEachPlatformWithItsOwnLocalTagAndNeverPushes() {
        Map result = subject.call(baseConfig(platforms: ['linux/amd64', 'linux/arm64'], buildArgs: [VERSION: 'abc123'],
            labels: ['org.opencontainers.image.revision': 'abc123']))

        List<String> builds = releaseBuilds()
        assertEquals(2, builds.size())
        assertEquals("docker buildx build --progress=plain --platform 'linux/amd64' --target 'runtime' --file 'app/Dockerfile' " +
            "--tag 'newtalkopt/starter-app:abc123-amd64' --build-arg 'VERSION=abc123' " +
            "--label 'org.opencontainers.image.revision=abc123' --load 'app'", builds[0])
        assertTrue(builds[1].contains("--platform 'linux/arm64'"))
        assertTrue(builds[1].contains("--tag 'newtalkopt/starter-app:abc123-arm64'"))
        assertFalse(builds.any { it.contains("--tag 'newtalkopt/starter-app:abc123' ") })
        assertTrue(scripts().every { !it.contains('--push') && !it.contains('docker push') && !it.contains('type=registry') })
        assertEquals(['linux/amd64': 'newtalkopt/starter-app:abc123-amd64', 'linux/arm64': 'newtalkopt/starter-app:abc123-arm64'], result.images)
        assertEquals('passed', result.build)
    }

    @Test
    void singlePlatformBuildAlsoReceivesThePlainTag() {
        subject.call(baseConfig())

        List<String> builds = releaseBuilds()
        assertEquals(1, builds.size())
        assertTrue(builds[0].contains("--tag 'newtalkopt/starter-app:abc123-amd64' --tag 'newtalkopt/starter-app:abc123'"))
    }

    @Test
    void imageTagDefaultsToTheShortGitCommit() {
        Map config = baseConfig()
        config.remove('imageTag')

        Map result = subject.call(config)

        assertEquals('newtalkopt/starter-app:0123456789ab-amd64', result.images['linux/amd64'])
    }

    @Test
    void missingImageTagWithoutGitCommitFails() {
        binding.setVariable('env', [:])
        Map config = baseConfig()
        config.remove('imageTag')

        PipelineAbort abort = expectAbort { subject.call(config) }

        assertTrue(abort.message.contains('imageTag is required'))
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void missingImageNameFailsBeforeAnyCommandRuns() {
        PipelineAbort abort = expectAbort { subject.call([imageTag: 'abc123']) }

        assertTrue(abort.message.contains('imageName must be'))
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void imageNamesWithRegistryHostsOrShellCharactersAreRejected() {
        for (String bad in ['localhost:5000/app', 'Team/App', 'app;rm -rf /', 'app name']) {
            shCalls.clear()
            PipelineAbort abort = expectAbort { subject.call(baseConfig(imageName: bad)) }
            assertTrue(abort.message.contains('imageName'))
            assertTrue("no command may run for ${bad}", shCalls.isEmpty())
        }
    }

    @Test
    void unknownOptionsAreRejectedSoTyposDoNotSilentlyDisableChecks() {
        PipelineAbort abort = expectAbort { subject.call(baseConfig(valdiate: false)) }

        assertTrue(abort.message.contains('unknown option(s): valdiate'))
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void unsupportedOrDuplicatePlatformsAreRejected() {
        PipelineAbort unsupported = expectAbort { subject.call(baseConfig(platforms: ['linux/s390x'])) }
        assertTrue(unsupported.message.contains("unsupported platform 'linux/s390x'"))

        PipelineAbort duplicate = expectAbort { subject.call(baseConfig(platforms: ['linux/amd64', 'linux/amd64'])) }
        assertTrue(duplicate.message.contains('more than once'))

        PipelineAbort empty = expectAbort { subject.call(baseConfig(platforms: [])) }
        assertTrue(empty.message.contains('non-empty list'))
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void nonBooleanFlagsAreRejected() {
        PipelineAbort abort = expectAbort { subject.call(baseConfig(lint: 'false')) }

        assertTrue(abort.message.contains('lint must be true or false'))
    }

    @Test
    void credentialLikeBuildArgsAreRejected() {
        for (String key in ['GITHUB_TOKEN', 'db_password', 'NPM_API_KEY']) {
            PipelineAbort abort = expectAbort { subject.call(baseConfig(buildArgs: [(key): 'value'])) }
            assertTrue(abort.message.contains("'${key}' looks like a credential"))
        }
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void secretsArePassedByEnvironmentReferenceOnly() {
        subject.call(baseConfig(secrets: [[id: 'netrc', env: 'NETRC_CONTENT']]))

        List<String> withSecret = scriptsMatching("--secret 'id=netrc,env=NETRC_CONTENT'")
        assertEquals('test target and release build both receive the secret mount', 2, withSecret.size())
        assertTrue(scripts().every { !it.contains('--build-arg') })
    }

    @Test
    void malformedSecretDefinitionsAreRejected() {
        PipelineAbort abort = expectAbort { subject.call(baseConfig(secrets: [[id: 'netrc', value: 'literal']])) }

        assertTrue(abort.message.contains('exactly the keys id and env'))
    }

    @Test
    void buildArgValuesAreShellQuoted() {
        subject.call(baseConfig(buildArgs: [VERSION: "1.0'; touch /tmp/pwned; echo '"]))

        assertTrue(releaseBuilds()[0].contains("--build-arg 'VERSION=1.0'\"'\"'; touch /tmp/pwned; echo '\"'\"''"))
    }

    @Test
    void localCacheRequiresAnExplicitBuilder() {
        PipelineAbort abort = expectAbort { subject.call(baseConfig(cacheDir: '/var/cache/buildkit')) }
        assertTrue(abort.message.contains('cacheDir requires builder'))

        shCalls.clear()
        subject.call(baseConfig(cacheDir: '/var/cache/buildkit', builder: 'local-builder'))
        String build = releaseBuilds()[0]
        assertTrue(build.startsWith("docker buildx build --builder 'local-builder' "))
        assertTrue(build.contains("--cache-from 'type=local,src=/var/cache/buildkit'"))
        assertTrue(build.contains("--cache-to 'type=local,mode=max,dest=/var/cache/buildkit'"))
        assertFalse(scriptsMatching("--target 'test'")[0].contains('--cache-to'))
    }

    @Test
    void lintRunsBuildxCheckAndHadolintOnlyWhenConfigured() {
        Map result = subject.call(baseConfig())
        assertEquals(1, scriptsMatching('--check').size())
        assertEquals(0, scriptsMatching('hadolint').size())
        assertEquals('passed', result.lint)

        shCalls.clear()
        subject.call(baseConfig(hadolintImage: 'hadolint/hadolint:v2.15.1'))
        assertEquals(["docker run --rm -i --entrypoint /bin/hadolint 'hadolint/hadolint:v2.15.1' --failure-threshold warning - < 'app/Dockerfile'"],
            scriptsMatching('hadolint'))

        shCalls.clear()
        Map disabled = subject.call(baseConfig(lint: false, hadolintImage: 'hadolint/hadolint:v2.15.1'))
        assertEquals(0, scriptsMatching('--check').size())
        assertEquals(0, scriptsMatching('hadolint').size())
        assertEquals('disabled', disabled.lint)
    }

    @Test
    void testTargetRunsOnceBeforeTheReleaseBuild() {
        Map result = subject.call(baseConfig(platforms: ['linux/amd64', 'linux/arm64']))

        List<String> testRuns = scriptsMatching("--target 'test'")
        assertEquals(1, testRuns.size())
        assertTrue(testRuns[0].endsWith("--output type=cacheonly 'app'"))
        assertFalse(testRuns[0].contains('--load'))
        assertTrue(indexOfFirst('--check') < indexOfFirst("--target 'test'"))
        assertTrue(indexOfFirst("--target 'test'") < indexOfFirst("--target 'runtime'"))
        assertEquals('passed', result.tests)
    }

    @Test
    void testTargetIsSkippedWhenDisabledOrUnset() {
        Map disabled = subject.call(baseConfig(runTests: false))
        assertEquals(0, scriptsMatching("--target 'test'").size())
        assertEquals('disabled', disabled.tests)

        shCalls.clear()
        Map noTarget = subject.call(baseConfig(testTarget: null))
        assertEquals(0, scriptsMatching("--target 'test'").size())
        assertEquals(1, scriptsMatching("--target 'runtime'").size())
        assertEquals('disabled', noTarget.tests)
    }

    @Test
    void buildFalseSkipsBuildAndRequiresValidationToBeDisabled() {
        PipelineAbort abort = expectAbort { subject.call(baseConfig(build: false)) }
        assertTrue(abort.message.contains('validate requires build'))
        assertTrue(shCalls.isEmpty())

        Map result = subject.call(baseConfig(build: false, validate: false))
        assertEquals(0, releaseBuilds().size())
        assertEquals(0, scriptsMatching('docker run').size())
        assertEquals(1, scriptsMatching("--target 'test'").size())
        assertEquals('disabled', result.build)
        assertEquals([:], result.images)
    }

    @Test
    void validateFalseBuildsButRunsNoImageChecks() {
        Map result = subject.call(baseConfig(validate: false, forbiddenPaths: ['bin/sh']))

        assertEquals(1, releaseBuilds().size())
        assertEquals(0, scriptsMatching('docker image inspect').size())
        assertEquals(0, scriptsMatching('docker create').size())
        assertEquals(0, scriptsMatching('docker run').size())
        assertEquals([:], result.validation)
        assertNull(result.nativeImage)
    }

    @Test
    void lintFailureStopsBeforeTestsAndBuild() {
        failWhen(~/docker buildx build --check .*/)

        ShellFailure failure = expectShellFailure { subject.call(baseConfig()) }

        assertEquals(1, failure.exitCode)
        assertEquals(0, scriptsMatching('--target').size())
    }

    @Test
    void hadolintFailureStopsBeforeTestsAndBuild() {
        failWhen(~/docker run --rm -i .*hadolint.*/)

        expectShellFailure { subject.call(baseConfig(hadolintImage: 'hadolint/hadolint:v2.15.1')) }

        assertEquals(0, scriptsMatching('--target').size())
    }

    @Test
    void failingTestTargetStopsBeforeTheReleaseBuild() {
        failWhen(~/.*--target 'test'.*/)

        expectShellFailure { subject.call(baseConfig()) }

        assertEquals(0, releaseBuilds().size())
        assertEquals(0, scriptsMatching('docker run').size())
    }

    @Test
    void failingReleaseBuildIsNotReportedAsSuccessAndSkipsValidation() {
        failWhen(~/.*--platform 'linux\/arm64'.*/)
        Map result = null

        expectShellFailure { result = subject.call(baseConfig(platforms: ['linux/amd64', 'linux/arm64'])) }

        assertNull(result)
        assertEquals(2, releaseBuilds().size())
        assertEquals(0, scriptsMatching('docker image inspect').size())
        assertFalse(echoes.any { it.contains('summary') })
    }

    @Test
    void imagesWithoutANumericNonRootUserFailValidation() {
        for (String user in ['', 'root', '0', '0:0', 'app']) {
            shCalls.clear()
            respond(~/docker image inspect --format '\{\{\.Config\.User\}\}' .*/, "${user}\n")

            PipelineAbort abort = expectAbort { subject.call(baseConfig()) }

            assertTrue(abort.message.contains('numeric non-root USER'))
            assertEquals("no container may start for user '${user}'", 0, scriptsMatching('docker run').size())
        }
    }

    @Test
    void forbiddenPathsInTheRuntimeImageFailValidationAndCleanUp() {
        respond(~/docker export .*/, 'server\nusr/\nusr/local/\nusr/local/go/\nusr/local/go/bin/go\nbin/sh\n')

        PipelineAbort abort = expectAbort { subject.call(baseConfig(forbiddenPaths: ['/usr/local/go/', 'bin/sh'])) }

        assertTrue(abort.message.contains('usr/local/go, usr/local/go/bin/go, bin/sh'))
        assertFalse(abort.message.contains('usr/local,'))
        assertEquals(["docker rm -f 'cid-123' >/dev/null 2>&1 || echo 'WARNING: could not remove inspection container' >&2"],
            scriptsMatching("docker rm -f 'cid-123'"))
        assertEquals(0, scriptsMatching('docker run').size())
    }

    @Test
    void cleanRuntimeImagePassesForbiddenPathCheck() {
        Map result = subject.call(baseConfig(forbiddenPaths: ['usr/local/go', 'bin/sh']))

        assertEquals(["docker create --platform 'linux/amd64' --entrypoint /dbv-inspection-only 'newtalkopt/starter-app:abc123-amd64'"],
            scriptsMatching('docker create'))
        assertEquals('passed', result.validation['linux/amd64'])
    }

    @Test
    void smokeTestRunsHardenedContainerAndVerifiesGracefulShutdown() {
        Map result = subject.call(baseConfig())

        String name = "'dbv-jenkins-demo-7-linux-amd64'"
        assertEquals(["docker run --detach --name ${name} --read-only --cap-drop ALL --security-opt no-new-privileges " +
            "--pids-limit 64 --memory 128m --cpus 1 'newtalkopt/starter-app:abc123-amd64'".toString()], scriptsMatching('docker run --detach'))
        assertTrue(indexOfFirst('docker run --detach') < indexOfFirst("docker stop --time 10 ${name}"))
        assertTrue(indexOfFirst("docker stop --time 10 ${name}") < indexOfFirst('{{.State.ExitCode}}'))
        assertTrue(scripts().last().startsWith("docker rm -f ${name}"))
        assertEquals('passed', result.smokeTest['linux/amd64'])
        assertEquals('newtalkopt/starter-app:abc123-amd64', result.nativeImage)
        assertEquals('SUCCESS', binding.getVariable('currentBuild').result)
    }

    @Test
    void smokeTestPollsUntilHealthy() {
        List<String> states = ['running|starting', 'running|starting', 'running|healthy']
        helper.registerAllowedMethod('sh', [Map], { Map args ->
            if (args.script.contains('{{.State.Status}}')) {
                shCalls << args
                return states.remove(0)
            }
            fakeSh(args)
        })

        subject.call(baseConfig())

        assertEquals(3, scriptsMatching('{{.State.Status}}').size())
        assertEquals(2, sleepCalls.size())
        assertEquals([time: 1, unit: 'SECONDS'], sleepCalls[0])
    }

    @Test
    void unhealthyContainerFailsAndCollectsLogsBeforeCleanup() {
        respond(~/docker inspect --format '\{\{\.State\.Status\}\}.*/, 'running|unhealthy\n')

        PipelineAbort abort = expectAbort { subject.call(baseConfig()) }

        assertTrue(abort.message.contains('reported unhealthy'))
        assertEquals(0, scriptsMatching('docker stop').size())
        assertTrue(indexOfFirst('docker logs') < scripts().findLastIndexOf { it.startsWith('docker rm -f') })
    }

    @Test
    void containerThatNeverBecomesHealthyTimesOut() {
        respond(~/docker inspect --format '\{\{\.State\.Status\}\}.*/, 'running|starting\n')

        PipelineAbort abort = expectAbort { subject.call(baseConfig(healthTimeoutSeconds: 3)) }

        assertTrue(abort.message.contains('did not become healthy within 3 seconds'))
        assertEquals(3, scriptsMatching('{{.State.Status}}').size())
        assertEquals(3, sleepCalls.size())
        assertTrue(scripts().last().startsWith('docker rm -f'))
    }

    @Test
    void containerThatExitsOrHasNoHealthcheckFails() {
        respond(~/docker inspect --format '\{\{\.State\.Status\}\}.*/, 'exited|unhealthy\n')
        PipelineAbort exited = expectAbort { subject.call(baseConfig()) }
        assertTrue(exited.message.contains('stopped before becoming healthy (state exited)'))

        respond(~/docker inspect --format '\{\{\.State\.Status\}\}.*/, 'running|none\n')
        PipelineAbort none = expectAbort { subject.call(baseConfig()) }
        assertTrue(none.message.contains('has no HEALTHCHECK'))
    }

    @Test
    void nonZeroExitAfterSigtermFailsValidation() {
        respond(~/docker inspect --format '\{\{\.State\.ExitCode\}\}' .*/, '2\n')

        PipelineAbort abort = expectAbort { subject.call(baseConfig()) }

        assertTrue(abort.message.contains('did not shut down cleanly on SIGTERM; exit code 2'))
        assertEquals(1, scriptsMatching('docker logs').size())
    }

    @Test
    void allowedExitCodesAndStopTimeoutAreConfigurable() {
        respond(~/docker inspect --format '\{\{\.State\.ExitCode\}\}' .*/, '143\n')

        Map result = subject.call(baseConfig(allowedExitCodes: [0, 143], stopTimeoutSeconds: 25))

        assertEquals(1, scriptsMatching("docker stop --time 25 'dbv-jenkins-demo-7-linux-amd64'").size())
        assertEquals('passed', result.smokeTest['linux/amd64'])
    }

    @Test
    void smokeTestEnvAndTmpfsAreAddedWithoutRelaxingHardening() {
        subject.call(baseConfig(smokeTestEnv: [APP_MODE: 'smoke test'], smokeTestTmpfs: ['/tmp'], smokeTestMemory: '256m'))

        String run = scriptsMatching('docker run --detach')[0]
        assertTrue(run.contains("--tmpfs '/tmp:rw,noexec,nosuid,size=64m'"))
        assertTrue(run.contains("--env 'APP_MODE=smoke test'"))
        assertTrue(run.contains('--memory 256m'))
        for (String flag in ['--read-only', '--cap-drop ALL', '--security-opt no-new-privileges', '--pids-limit 64']) {
            assertTrue("missing ${flag}", run.contains(flag))
        }
    }

    @Test
    void invalidSmokeTestOptionsAreRejected() {
        List<List> cases = [
            [[smokeTestEnv: [API_TOKEN: 'x']], 'looks like a credential'],
            [[smokeTestTmpfs: ['tmp']], 'smokeTestTmpfs entry must be'],
            [[smokeTestMemory: '128MB'], 'smokeTestMemory must be'],
            [[allowedExitCodes: []], 'allowedExitCodes must be'],
            [[allowedExitCodes: ['0']], 'allowedExitCodes entry must be'],
            [[stopTimeoutSeconds: 0], 'stopTimeoutSeconds must be'],
            [[healthTimeoutSeconds: 601], 'healthTimeoutSeconds must be']
        ]
        for (List testCase in cases) {
            Map overrides = testCase[0] as Map
            String expected = testCase[1] as String
            PipelineAbort abort = expectAbort { subject.call(baseConfig(overrides)) }
            assertTrue("${overrides} -> ${abort.message}".toString(), abort.message.contains(expected))
        }
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void failingStopCommandStillCollectsLogsAndRemovesTheContainer() {
        failWhen(~/docker stop .*/)

        expectShellFailure { subject.call(baseConfig()) }

        assertEquals(1, scriptsMatching('docker logs').size())
        assertTrue(scripts().last().startsWith("docker rm -f 'dbv-jenkins-demo-7-linux-amd64'"))
    }

    @Test
    void failingFilesystemListingStillRemovesTheInspectionContainer() {
        failWhen(~/docker export .*/)

        expectShellFailure { subject.call(baseConfig(forbiddenPaths: ['bin/sh'])) }

        assertEquals(1, scriptsMatching("docker rm -f 'cid-123'").size())
        assertEquals(0, scriptsMatching('docker run').size())
    }

    @Test
    void foreignPlatformsAreValidatedStaticallyButNotExecuted() {
        Map result = subject.call(baseConfig(platforms: ['linux/amd64', 'linux/arm64'], forbiddenPaths: ['bin/sh']))

        assertEquals(2, scriptsMatching('docker image inspect').size())
        assertEquals(2, scriptsMatching('docker create').size())
        List<String> runs = scriptsMatching('docker run --detach')
        assertEquals(1, runs.size())
        assertTrue(runs[0].endsWith("'newtalkopt/starter-app:abc123-amd64'"))
        assertEquals('passed', result.validation['linux/arm64'])
        assertTrue(result.smokeTest['linux/arm64'].startsWith('skipped'))
        assertEquals('newtalkopt/starter-app:abc123-amd64', result.nativeImage)
    }

    @Test
    void smokeTestCanBeDisabledWhileStaticValidationStillRuns() {
        Map result = subject.call(baseConfig(smokeTest: false))

        assertEquals(1, scriptsMatching('docker image inspect').size())
        assertEquals(0, scriptsMatching('docker run').size())
        assertEquals('disabled', result.smokeTest['linux/amd64'])
    }

    @Test
    void summaryIsEchoedAndReturned() {
        Map result = subject.call(baseConfig(platforms: ['linux/amd64', 'linux/arm64']))

        assertEquals(1, echoes.size())
        assertEquals(result.summary, echoes[0])
        assertTrue(result.summary.contains('linux/amd64: image=newtalkopt/starter-app:abc123-amd64 validation=passed smokeTest=passed'))
        assertTrue(result.summary.contains('linux/arm64: image=newtalkopt/starter-app:abc123-arm64 validation=passed smokeTest=skipped'))
    }
}
