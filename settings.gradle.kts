pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle fetch the JDK the build asks for when it is not already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "weartak-meshcore"

// The WearTAK plugin SDK, built from the source WearTAK pins (see sdk/README.md).
include(":sdk")
// The plugin app itself.
include(":plugin")
