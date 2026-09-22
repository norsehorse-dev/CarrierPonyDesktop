// CarrierPony Desktop: a SEPARATE Gradle build from the Android app (which is AGP-only).
// Standing on its own lets it use a plain kotlin("jvm") toolchain and compile the exact portable
// CarrierPony sources via vendored source sets (see build.gradle.kts), with none of AGP's variant
// machinery. Same pattern and plugin set as PGPonyDesktop and RelayPonyDesktop.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "carrierpony-desktop"
