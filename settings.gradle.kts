rootProject.name = "NMSHook"

plugins {
    // Auto-downloads the JDK 25 toolchain if it is not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

include(
    "nmshook-api",
    "nmshook-hook",
    "nmshook-mixin",
    "nmshook-transform",
    "nmshook-agent",
    "nmshook-resolver",
    "nmshook-plugin",
)
