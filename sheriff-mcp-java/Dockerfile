# Runs the agent (sheriff-mcp.jar --agent) inside a container. It does NOT run Docker itself:
# SheriffDockerAnalyzer talks to the *host's* daemon through a mounted
# socket, exactly as `docker` does for anyone at a terminal. Sheriff stays a
# sibling container, never vendored into this image.
#
# Two stages, because a Java deliverable should ship a jar rather than a
# source tree: the builder resolves dependencies and produces the shaded jar,
# and the runtime image carries only that jar plus what the agent needs to
# talk to the outside world.

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Dependencies first, as their own layer: the pom changes far less often than
# the code, so a normal edit-build cycle skips re-downloading the Anthropic
# SDK and Jackson entirely.
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# The rule catalog, when one sits beside this file. The bracket in each name
# makes the pattern a glob, so a catalog that is not there is no error, and
# the Dockerfile is named with them so that the instruction always has a
# match: a COPY whose globs all match nothing passes under BuildKit but fails
# under Docker's classic builder ("no source files were specified"), which is
# what a Docker without buildx runs. It is gathered here, in the builder, so
# that the image below gets the catalog and nothing else.
COPY Dockerfile rules_catalog.jso[n] SHERIFF_RULES.m[d] /extras/
RUN rm /extras/Dockerfile

FROM eclipse-temurin:21-jre

# docker-cli, not docker.io: this image only ever talks to the host's daemon
# through the mounted socket, so it needs the client binary, not an engine.
# git is not optional either -- the loop's whole safety net is a branch, a
# commit per pass, and asking git which files actually changed.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        docker-cli \
        curl \
        ca-certificates \
        maven \
    && rm -rf /var/lib/apt/lists/*

# Maven in the *runtime* image is not for building this agent -- that already
# happened in the builder. It is for the verification gate: once Sheriff
# reports zero errors the loop runs the *target* project's own tests, and the
# projects Sheriff analyzes are mostly Java ones (it also reads TypeScript,
# Vue, Perl and Python, whose tests need their own tools). Without Maven here,
# the gate's default command fails with "command not found", the loop
# correctly reads that as a failed test run, spends its one repair pass on a
# problem no fix can solve, and reports repair_failed. Mount the host's ~/.m2
# at run time so it reuses the cache instead of re-downloading the world.

# Node, only to install the Claude Code CLI, which is published on npm and is
# the default backend. It needs a session somebody logged into, which a fresh
# container does not have, so the backends that work in here unattended are
# the API ones -- anthropic_api, openai_api and local -- where a key or a URL
# is all there is to supply.
#
# Codex's CLI is not installed. That is a choice about weight: codex_cli works
# (it was validated outside a container), but like Claude Code it needs a login
# a fresh container does not have, so the image would carry it for nothing by
# default. Add it with `npm install -g @openai/codex` if you mount a login.
#
# One thing to know before setting AI_BACKEND=local in a container: localhost
# is the container's own, not the host's, so a model served on the host is not
# at http://localhost:11434 from in here -- it fails with ConnectException.
# Measured against Ollama on the host:
#
#   --network host                          works, and needs nothing else
#   --add-host=host.docker.internal:...     does NOT, on its own
#
# The second fails because Ollama binds 127.0.0.1 only, so the bridge gateway
# address reaches nothing; it works once the model server listens on every
# interface (OLLAMA_HOST=0.0.0.0), with
# OPENAI_BASE_URL=http://host.docker.internal:11434/v1. Prefer --network host
# unless you have a reason not to.
# Node 22, the long-term release: 20 reached its end of life in April 2026.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g @anthropic-ai/claude-code

WORKDIR /agent
COPY --from=builder /build/target/sheriff-mcp.jar ./sheriff-mcp.jar
# The catalog the builder gathered, if any. It is not in version control --
# it is extracted from Sheriff's own image with --extract-rules, so a clone
# that has not run that yet still builds this image, and extracts the catalog
# on first use instead. That is the only way the published image is built
# (.github/workflows/image.yml checks it): the catalog is Kaizten's content,
# so an image built here with one beside it is for this machine only.
COPY --from=builder /extras/ ./

# The agent's own directory is normally derived from where the jar sits, which
# is right everywhere except here: the catalog is copied next to it, so say so
# explicitly rather than relying on a derivation that a container layout could
# surprise.
ENV SHERIFF_AGENT_DIR=/agent

# No CMD with arguments: the mode is the point of every invocation
# (--check-only for a gate, --rules for a question, no arguments for the
# loop), so make the caller name it.
ENTRYPOINT ["java", "-jar", "/agent/sheriff-mcp.jar", "--agent"]
