import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc.grpc)
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("com.google.cloud.tools.jib") version "3.4.4"
}

rpc {
    protoc()
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.metrics.micrometer)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.partialContent)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.sessions)
    implementation(ktorLibs.server.sse)
    implementation(ktorLibs.server.websockets)
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.flaxoos.ktor.server.rateLimiting)
    implementation(libs.h2database.h2)
    implementation(libs.h2database.r2dbc)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)
    implementation(libs.logback.classic)
    implementation(libs.micrometer.registryPrometheus)
    implementation(libs.postgresql)
    implementation(project(":core"))
    implementation(libs.double.receive)

    // API docs: Ktor-native Swagger UI served from openapi/documentation.yaml
    implementation("io.ktor:ktor-server-swagger:3.5.0")

    // Phase 2: Flyway over JDBC for migrations + R2DBC pool
    implementation("org.flywaydb:flyway-core:11.0.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.0.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
    implementation("org.jetbrains.exposed:exposed-java-time:1.3.0")

    // Phase 3: Lettuce (Redis) + Argon2 (password hashing)
    implementation("io.lettuce:lettuce-core:6.5.1.RELEASE")
    implementation("de.mkammerer:argon2-jvm:2.12")

    // Phase 20 (#113): JWKS-backed RS256 verification for Keycloak OIDC (dual-mode; see Security.kt).
    // Already on the classpath transitively via ktor-server-auth-jwt — pinned explicitly as the
    // one new direct dep this issue justifies. ponytail: legacy HS256 stays the default.
    implementation("com.auth0:jwks-rsa:0.24.0")

    // Phase 9: gRPC client to Python rec engine (kotlinx.rpc over grpc-netty)
    implementation(libs.kotlinx.rpc.grpc.client)
    implementation(libs.grpc.netty)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

// --- Container image via Jib (daemon-less, Gradle-cached) ---
// Jib's base JRE has no ffmpeg; the app shells out to ffmpeg/ffprobe, so we layer
// static linux binaries into the image via extraDirectories and point the app at them.
val jibBinDir = layout.buildDirectory.dir("jib-extra/usr/local/bin")

val stageFfmpeg by tasks.registering {
    description = "Fetch static linux ffmpeg/ffprobe for the Jib image layer"
    val outDir = jibBinDir
    outputs.dir(outDir)
    outputs.cacheIf { true }
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val ffmpeg = dir.resolve("ffmpeg")
        val ffprobe = dir.resolve("ffprobe")
        if (ffmpeg.exists() && ffprobe.exists()) return@doLast
        val url =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz"
        val tarball = layout.buildDirectory.file("ffmpeg-dl.tar.xz").get().asFile
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        tarball.writeBytes(bytes)
        val work = layout.buildDirectory.dir("ffmpeg-extract").get().asFile
        work.deleteRecursively(); work.mkdirs()
        val proc = ProcessBuilder("tar", "-xf", tarball.absolutePath, "-C", work.absolutePath)
            .redirectErrorStream(true).start()
        check(proc.waitFor() == 0) { "tar extraction of ffmpeg failed" }
        for (name in listOf("ffmpeg", "ffprobe")) {
            val src = work.walkTopDown().first { it.name == name && it.parentFile?.name == "bin" }
            src.copyTo(dir.resolve(name), overwrite = true).setExecutable(true)
        }
    }
}

jib {
    from {
        image = "eclipse-temurin:21-jre-jammy"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }
    to {
        image = "ghcr.io/viewrr/viewrr"
    }
    container {
        mainClass = "io.ktor.server.netty.EngineMain"
        ports = listOf("8080")
        environment = mapOf(
            "FFMPEG_PATH" to "/usr/local/bin/ffmpeg",
            "FFPROBE_PATH" to "/usr/local/bin/ffprobe",
        )
    }
    extraDirectories {
        paths {
            path {
                setFrom(layout.buildDirectory.dir("jib-extra"))
                into = "/"
            }
        }
        permissions = mapOf(
            "/usr/local/bin/ffmpeg" to "755",
            "/usr/local/bin/ffprobe" to "755",
        )
    }
}

tasks.matching { it.name in listOf("jib", "jibDockerBuild", "jibBuildTar") }.configureEach {
    dependsOn(stageFfmpeg)
}
