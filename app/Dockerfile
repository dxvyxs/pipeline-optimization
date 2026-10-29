ARG GO_IMAGE=golang:1.27.3-alpine3.24@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb

FROM --platform=$BUILDPLATFORM ${GO_IMAGE} AS deps
WORKDIR /src
ENV CGO_ENABLED=0 \
    GOFLAGS=-mod=readonly \
    GOTOOLCHAIN=local
COPY go.mod go.sum* ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=secret,id=netrc,target=/root/.netrc,required=false \
    go mod download && go mod verify

FROM deps AS source
COPY cmd ./cmd
COPY internal ./internal

FROM source AS test
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    unformatted="$(gofmt -l .)" && \
    if [ -n "$unformatted" ]; then echo "gofmt required for: $unformatted"; exit 1; fi && \
    go vet ./... && \
    go test -count=1 ./...

FROM source AS build
ARG TARGETOS
ARG TARGETARCH
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    GOOS="$TARGETOS" GOARCH="$TARGETARCH" \
    go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/server ./cmd/server

FROM scratch AS runtime
ARG VERSION=dev
LABEL org.opencontainers.image.title="newtalkopt starter-app" \
      org.opencontainers.image.description="Starter Go HTTP service used to exercise the local DevSecOps pipeline" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="NOASSERTION"
COPY --from=build --chown=65532:65532 --chmod=0555 /out/server /server
USER 65532:65532
ENV LISTEN_ADDR=:8080
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=3 CMD ["/server", "-healthcheck"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["/server"]
