# syntax=docker/dockerfile:1
#
# #118: containerize the Ktor Hub. Multi-stage: Gradle build of :server (application-plugin
# distribution) -> slim JRE runtime with ffmpeg/ffprobe on PATH for HLS transcode + codec probing.
# The app resolves ffmpeg/ffprobe via viewrr.media.ffmpegPath/ffprobePath, which default to the
# bare "ffmpeg"/"ffprobe" names (application.yaml), so the apt-installed binaries are used with no
# extra env. All prod config (DB/Redis/JWT/CORS/PUBLIC_BASE_URL) is injected at runtime via env.

# --- Stage 1: build the server distribution ---
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy the whole repo (:server depends on :core; settings also configures the JVM-only :client).
# .dockerignore keeps build caches, node_modules, .git, etc. out of the context.
COPY . .

# installDist (Gradle application plugin, applied by the Ktor plugin) produces a runnable
# distribution under server/build/install/server (bin/ launcher + lib/*.jar). Tests run in CI.
RUN ./gradlew --no-daemon --stacktrace :server:installDist -x test

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-jammy AS runtime

# ffmpeg package ships both ffmpeg and ffprobe.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ /app/

# Ktor Netty EngineMain listens on 8080 (application.yaml ktor.deployment.port).
EXPOSE 8080

# The launcher honors JAVA_OPTS for JVM flags.
ENV JAVA_OPTS=""
ENTRYPOINT ["/app/bin/server"]
