# S1 e2e mock — deploy as DEPLOY_DIR on edge-host.
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/e2e-mock .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates wget \
    && adduser -D -H -u 65532 nonroot
USER nonroot
WORKDIR /app
COPY --from=build /out/e2e-mock /app/e2e-mock
ENV LISTEN_ADDR=:8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/__health >/dev/null || exit 1
ENTRYPOINT ["/app/e2e-mock"]
