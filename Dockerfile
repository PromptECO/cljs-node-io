FROM ghcr.io/njordhov/docker-shadow-cljs:v0.3

MAINTAINER "terje@in-progress.com"

LABEL org.opencontainers.image.title="cljs-node-io"
LABEL org.opencontainers.image.description="ClojureScript IO Library for NodeJS "
LABEL org.opencontainers.image.authors=""
LABEL org.opencontainers.image.source="https://github.com/prompteco/clariform"
LABEL org.opencontainers.image.licenses=EPL-2.0

COPY . /app
WORKDIR /app
RUN clj -X:build

WORKDIR /home
# ENTRYPOINT []
