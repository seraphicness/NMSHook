// Agent domain — JVM Instrumentation acquisition and management
dependencies {
    implementation(project(":nmshook-api"))
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")
}
