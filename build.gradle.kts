plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "wtf.jobin"
version = "1.0.0"

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

tasks.jar {
    manifest {
        attributes["Main-Class"] = "wtf.jobin.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Bundle all runtime dependencies into the jar
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })

    // Exclude security signature files from dependencies to prevent security exceptions
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
