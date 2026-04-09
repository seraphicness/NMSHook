rootProject.name = "NMSHook"

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
