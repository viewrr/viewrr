plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.rpc.grpc)
    alias(libs.plugins.kotlin.serialization)
}


rpc {
    protoc()
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.opentelemetry.exporterOtlp)
            api(libs.opentelemetry.ktorInstrumentation)
            api(libs.opentelemetry.sdkAutoconfigure)
            api(libs.opentelemetry.semconv)
            implementation(libs.kotlinx.rpc.grpc.core)
            implementation(libs.kotlinx.rpc.protobufCore)
        }

    }
}
