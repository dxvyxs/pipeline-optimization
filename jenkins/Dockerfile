ARG JENKINS_IMAGE=jenkins/jenkins:2.580.2-lts-jdk21@sha256:74401de6a6f92e1afe1b02d1c8e3d0c33f263bfb5275dd8813f9150355f2367b
ARG DOCKER_CLI_IMAGE=docker:29.5.2-cli@sha256:9ba8e32bfc35a2c7ae2feb1e3241b2778ae21dee80f4dcd31d04e1cfdea86ea2
ARG BUILDX_IMAGE=docker/buildx-bin:0.38.1@sha256:0b7d9e66e81dd1155639421b8342f9731f8bb67cb11b58663f938d9c6e9b6eef

FROM ${DOCKER_CLI_IMAGE} AS docker-cli

FROM ${BUILDX_IMAGE} AS buildx

FROM ${JENKINS_IMAGE}
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=buildx /buildx /usr/local/libexec/docker/cli-plugins/docker-buildx
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY casc.yaml /usr/share/jenkins/casc/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc/casc.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
USER jenkins
