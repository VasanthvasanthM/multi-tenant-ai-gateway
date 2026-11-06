# =============================================================================
# Runtime image for any of the five services.
#
# Hand-written rather than built with Paketo buildpacks (`spring-boot:build-image`)
# for one concrete reason: the ONNX Runtime jar ships native libraries for five
# platforms and a buildpack has no way to drop the four we never use. See the
# strip stage below - it removes ~95% of that dependency.
#
# Build:
#   docker build -f deploy/docker/Dockerfile --build-arg SERVICE=gateway -t ai-gateway/gateway .
# =============================================================================

ARG SERVICE=gateway

# -----------------------------------------------------------------------------
# Stage 1: strip and layer the application jar.
#
# A JDK image is used here, not a JRE: `jar` and `jarmode` need the full toolchain.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:24-jdk-alpine AS prepare

ARG SERVICE
WORKDIR /build

# Alpine ships neither; the strip step below needs both.
RUN apk add --no-cache zip unzip

COPY ${SERVICE}/target/${SERVICE}-*.jar app.jar

# Strip foreign-platform native libraries. See the script for the reasoning and
# the numbers; it is a file rather than an inline RUN because a twenty-line shell
# one-liner reports its failures against a line number Docker has already mangled.
COPY deploy/docker/strip-native-libs.sh /usr/local/bin/strip-native-libs.sh
RUN chmod +x /usr/local/bin/strip-native-libs.sh &&     /usr/local/bin/strip-native-libs.sh /build/app.jar linux-x64

# Split the jar into layers that change at different rates. Dependencies are
# rebuilt rarely and application classes on every commit, so this turns a
# ~200 MB push per deploy into a few hundred kilobytes.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# -----------------------------------------------------------------------------
# Stage 2: runtime.
#
# JRE, not JDK: no compiler, no jar tool, no jcmd. Smaller and a narrower attack
# surface - a JDK in production hands an attacker a compiler.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:24-jre-alpine AS runtime

ARG SERVICE

# Non-root, with no shell and no home directory. If the process is compromised it
# cannot write to the filesystem, install anything, or spawn an interactive shell.
RUN addgroup -S -g 10001 app && \
    adduser -S -u 10001 -G app -H -s /sbin/nologin app

WORKDIR /app

# Ordered least- to most-frequently-changed, so a code-only change reuses every
# layer above application/.
COPY --from=prepare --chown=app:app /build/extracted/dependencies/ ./
COPY --from=prepare --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=prepare --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=prepare --chown=app:app /build/extracted/application/ ./

USER 10001:10001

ENV SERVICE_NAME=${SERVICE}

# JAVA_TOOL_OPTIONS rather than a hard-coded command line, so a deployment can
# append flags without rewriting the entrypoint.
#
#   MaxRAMPercentage=70   - the JVM reads the cgroup limit, not the host's RAM.
#                           Without it a 512Mi pod sizes its heap from the node's
#                           total memory and is OOMKilled on startup.
#   UseSerialGC           - correct below ~2 cores. G1's concurrent threads cost
#                           more in context switching than they save at this size.
#   user.timezone=UTC     - NOT cosmetic. Both Postgres drivers send the JVM zone
#                           to the server as a session parameter, and Java reports
#                           legacy ids such as "Asia/Calcutta" that slim images'
#                           tzdata does not know. The connection is rejected
#                           outright. This cost a debugging session to find.
#   ExitOnOutOfMemoryError- a JVM that has exhausted its heap cannot recover. Dying
#                           lets Kubernetes restart it; limping along serves errors
#                           while still passing its liveness probe.
ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=70 \
-XX:+UseSerialGC \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp \
-Duser.timezone=UTC \
-Dfile.encoding=UTF-8 \
-Djava.security.egd=file:/dev/urandom"

EXPOSE 8080

# No HEALTHCHECK instruction: Kubernetes owns liveness and readiness through the
# probes in the Helm chart. A Docker-level healthcheck would duplicate them and
# report to something nothing is watching.

# `java -jar`, NOT JarLauncher.
#
# Spring Boot 3.3 replaced the `layertools` jarmode with `tools`, and they produce
# different layouts. The old one wrote a BOOT-INF tree that had to be started
# through org.springframework.boot.loader.launch.JarLauncher. The new one writes a
# thin app.jar whose manifest Class-Path points at a sibling lib/ directory, which
# is started with a plain -jar.
#
# Using JarLauncher against the new layout fails with
# "Could not find or load main class org.springframework.boot.loader.launch.JarLauncher"
# - at container start, not at build time.
ENTRYPOINT ["java", "-jar", "app.jar"]
