pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // :core jvmToolchain(17) kullanır; makinede JDK 17 yoksa otomatik indirir.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // desktop-kit
    }
}

rootProject.name = "WalkmanSync"
include(":core", ":app")
