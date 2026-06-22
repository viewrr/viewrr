plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc.grpc)
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
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

    // Phase 9: gRPC client to Python rec engine (kotlinx.rpc over grpc-netty)
    implementation(libs.kotlinx.rpc.grpc.client)
    implementation(libs.grpc.netty)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
