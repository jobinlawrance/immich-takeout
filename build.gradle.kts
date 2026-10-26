plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    id("org.graalvm.buildtools.native") version "1.1.17"
}

group = "wtf.jobin"
version = "1.0.0-SNAPSHOT"

// Explicitly define your main class here so both Gradle and GraalVM find it
application {
    mainClass.set("wtf.jobin.MainKt") // Change this if your file is named differently (e.g. MainKt)
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(libs.logback.classic)
    implementation("com.squareup.okio:okio:3.18.2")
    implementation("org.duckdb:duckdb_jdbc:2.0.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)



    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("takeout-migrator")
            mainClass.set("wtf.jobin.MainKt") // Adjust to match your package/file name
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("--no-fallback")
        }
    }

}
