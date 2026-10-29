library identifier: 'newtalkopt-pipeline-lib@main', retriever: legacySCM(scm)

pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 60, unit: 'MINUTES')
        skipDefaultCheckout(true)
    }

    parameters {
        booleanParam(name: 'BUILD_ARM64', defaultValue: true,
            description: 'Also cross-compile a linux/arm64 image under its own local tag and validate it statically')
    }

    environment {
        IMAGE_NAME = 'newtalkopt/starter-app'
        REPORTS = 'build/reports'
        BUILDX_BUILDER = 'default'
        HADOLINT_IMAGE = 'hadolint/hadolint:v2.15.1@sha256:32dac94127fd60b7b7e3fbfc65e1383b9b5e25c9bfd7b8536de7a539fe68a12d'
        GITLEAKS_IMAGE = 'zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
        TRIVY_IMAGE = 'aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c'
        TRIVY_CACHE_VOLUME = 'newtalkopt-trivy-cache'
        TRIVY_SEVERITY = 'HIGH,CRITICAL'
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                script {
                    env.GIT_COMMIT_FULL = sh(label: 'Resolve revision', returnStdout: true, script: 'git rev-parse HEAD').trim()
                    env.IMAGE_TAG = env.GIT_COMMIT_FULL.substring(0, 12)
                }
                sh label: 'Prepare report directory', script: 'mkdir -p "$REPORTS"'
                sh label: 'Docker engine and Buildx', script: '''
                    docker version --format 'Docker engine {{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}'
                    docker buildx version
                    docker buildx inspect "$BUILDX_BUILDER"
                '''
            }
        }

        stage('Shared Library Unit Tests') {
            steps {
                sh label: 'JenkinsPipelineUnit tests (Gradle in Docker)', script: '''
                    docker buildx build --builder "$BUILDX_BUILDER" --progress=plain \
                        --file ci/library-test.Dockerfile --target report \
                        --output type=local,dest=build/library-test .
                '''
                script {
                    String exitCode = readFile('build/library-test/exit-code').trim()
                    if (exitCode != '0') {
                        error("Shared Library unit tests failed (Gradle exit code ${exitCode}); the release build will not run")
                    }
                }
            }
            post {
                always {
                    junit testResults: 'build/library-test/test-results/*.xml', allowEmptyResults: false
                }
            }
        }

        stage('Source Checks') {
            stages {
                stage('Package Source') {
                    steps {
                        sh label: 'Archive workspace for scanners', script: 'tar -cf build/source.tar --exclude=./build .'
                    }
                }

                stage('Secret Scan (Gitleaks)') {
                    steps {
                        sh label: 'gitleaks git history scan', script: '''
                            status=0
                            docker run --rm -i \
                                -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
                                --entrypoint sh "$GITLEAKS_IMAGE" -c '
                                    mkdir -p /tmp/scan && tar -xf - -C /tmp/scan &&
                                    gitleaks git --redact --no-banner --exit-code 1 \
                                        --report-format json --report-path /tmp/gitleaks.json /tmp/scan >&2
                                    rc=$?
                                    cat /tmp/gitleaks.json 2>/dev/null
                                    exit $rc
                                ' < build/source.tar > "$REPORTS/gitleaks.json" || status=$?
                            exit $status
                        '''
                    }
                }

                stage('Config Scan (Trivy)') {
                    steps {
                        sh label: 'trivy config scan', script: '''
                            status=0
                            docker run --rm -i \
                                -v "$TRIVY_CACHE_VOLUME":/root/.cache/trivy \
                                -e TRIVY_SEVERITY \
                                --entrypoint sh "$TRIVY_IMAGE" -c '
                                    mkdir -p /tmp/src && tar -xf - -C /tmp/src &&
                                    trivy config --quiet --exit-code 1 --format table --skip-dirs /tmp/src/.git /tmp/src
                                ' < build/source.tar > "$REPORTS/trivy-config.txt" || status=$?
                            cat "$REPORTS/trivy-config.txt"
                            exit $status
                        '''
                    }
                }
            }
        }

        stage('Docker Build and Validate') {
            steps {
                script {
                    List platforms = params.BUILD_ARM64 ? ['linux/amd64', 'linux/arm64'] : ['linux/amd64']
                    Map result = dockerBuildValidate(
                        imageName: env.IMAGE_NAME,
                        imageTag: env.IMAGE_TAG,
                        dockerfile: 'app/Dockerfile',
                        context: 'app',
                        platforms: platforms,
                        builder: env.BUILDX_BUILDER,
                        hadolintImage: env.HADOLINT_IMAGE,
                        buildArgs: [VERSION: env.IMAGE_TAG],
                        labels: [
                            'org.opencontainers.image.revision': env.GIT_COMMIT_FULL,
                            'org.opencontainers.image.ref.name': "${env.IMAGE_NAME}:${env.IMAGE_TAG}".toString()
                        ],
                        forbiddenPaths: ['bin/sh', 'usr/local/go', 'src', 'root'],
                        healthTimeoutSeconds: 60
                    )
                    writeFile file: "${env.REPORTS}/docker-validation.txt", text: result.summary + '\n'
                    env.SCAN_IMAGE = result.nativeImage ?: result.images[platforms[0]]
                }
            }
        }

        stage('Image Scan (Trivy)') {
            steps {
                sh label: 'trivy image scan and SBOM', script: '''
                    status=0
                    docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -v "$TRIVY_CACHE_VOLUME":/root/.cache/trivy \
                        -e TRIVY_SEVERITY \
                        "$TRIVY_IMAGE" image --quiet --exit-code 1 --format table "$SCAN_IMAGE" \
                        > "$REPORTS/trivy-image.txt" || status=$?
                    cat "$REPORTS/trivy-image.txt"
                    docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -v "$TRIVY_CACHE_VOLUME":/root/.cache/trivy \
                        "$TRIVY_IMAGE" image --quiet --format cyclonedx "$SCAN_IMAGE" \
                        > "$REPORTS/sbom.cdx.json"
                    exit $status
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'build/reports/**, build/library-test/**', allowEmptyArchive: true
        }
        success {
            echo "Local images are tagged ${env.IMAGE_NAME}:${env.IMAGE_TAG}-<arch>; nothing was pushed to a registry."
        }
    }
}
