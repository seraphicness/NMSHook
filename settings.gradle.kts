rootProject.name = "NMSHook"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // Auto-downloads the JDK 25 toolchain if it is not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(
    "nmshook-api",
    "nmshook-hook",
    "nmshook-mixin",
    "nmshook-transform",
    "nmshook-agent",
    "nmshook-resolver",
    "nmshook-core",
    "nmshook-plugin",
)
