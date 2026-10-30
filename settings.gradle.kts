pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // :core jvmToolchain(17) kullanır; makinede JDK 17 yoksa otomatik indirir. 1.0.0: Gradle 9 ile çalışan ilk sürüm.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
