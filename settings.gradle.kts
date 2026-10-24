// the Koin compiler plugin is published to mavenCentral, so pluginManagement
// must search it (the Gradle plugin portal alone would 404 the marker)
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "srs"