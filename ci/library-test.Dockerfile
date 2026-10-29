ARG GRADLE_IMAGE=gradle:8.14.3-jdk21@sha256:21bd311ed01360c189b8870c6b6e988199ff10f72d445d02fb39d3cff9da91d7

FROM ${GRADLE_IMAGE} AS run
WORKDIR /workspace
RUN chown gradle:gradle /workspace
USER gradle
COPY --chown=gradle:gradle settings.gradle build.gradle ./
COPY --chown=gradle:gradle vars ./vars
COPY --chown=gradle:gradle test ./test
RUN --mount=type=cache,target=/home/gradle/.gradle/caches,uid=1000,gid=1000 \
    gradle --no-daemon --console=plain test; \
    echo "$?" > /workspace/exit-code; \
    mkdir -p /workspace/build/test-results/test /workspace/build/reports/tests/test

FROM scratch AS report
COPY --from=run /workspace/exit-code /exit-code
COPY --from=run /workspace/build/test-results/test /test-results
COPY --from=run /workspace/build/reports/tests/test /html

FROM run AS verify
USER gradle
RUN test "$(cat /workspace/exit-code)" = "0"
