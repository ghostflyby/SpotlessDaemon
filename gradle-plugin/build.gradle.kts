plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("spotless-daemon") {
            id = "dev.ghostflyby.spotless.daemon"
            implementationClass = "dev.ghostflyby.spotless.daemon.SpotlessDaemon"
        }
    }
}
repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.spotless)
}
