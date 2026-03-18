plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("com.google.devtools.ksp") version "2.3.4"
    kotlin("plugin.serialization") version "2.2.21"
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

dependencies {
    implementation("com.google.dagger:dagger:2.57.2")
    ksp("com.google.dagger:dagger-compiler:2.57.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("ch.qos.logback:logback-classic:1.5.21")
    implementation("io.javalin:javalin:6.7.0")

    implementation("se.michaelthelin.spotify:spotify-web-api-java:9.4.0")
    implementation("net.bramp.ffmpeg:ffmpeg:0.8.0")

    implementation("com.github.ajalt.clikt:clikt:5.0.1")
    // optional support for rendering markdown in help messages
    implementation("com.github.ajalt.clikt:clikt-markdown:5.0.1")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}