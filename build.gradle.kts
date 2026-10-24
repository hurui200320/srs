plugins {
    kotlin("jvm") version "2.4.20"
    application
    id("io.insert-koin.compiler.plugin") version "1.2.1"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "info.skyblond"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("info.skyblond.recorder.spotify.Main")
    executableDir = ""
}

// dependency injection: Koin core + the compiler plugin, which validates the
// classic org.koin.dsl definitions in di/AppModule.kt at compile time (KOIN-D002)
val koinVersion = "4.2.2"

dependencies {
    implementation(platform("io.insert-koin:koin-bom:$koinVersion"))
    implementation("io.insert-koin:koin-core")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("ch.qos.logback:logback-classic:1.6.5")
    implementation("io.javalin:javalin:7.2.3")

    implementation("se.michaelthelin.spotify:spotify-web-api-java:10.0.0")
    implementation("net.bramp.ffmpeg:ffmpeg:0.9.2")

    implementation("com.github.ajalt.clikt:clikt:5.0.1")
    // optional support for rendering markdown in help messages
    implementation("com.github.ajalt.clikt:clikt-markdown:5.0.1")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

koinCompiler {
    aiAssist = false
}
