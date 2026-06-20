plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.rpc.grpc)
}


rpc {
    protoc()
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(ktorLibs.client.core)
            implementation(libs.kotlinx.rpc.grpc.client)
            implementation(project(":core"))
        }

    }
}
