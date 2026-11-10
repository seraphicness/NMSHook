// Engine core — wires the hook, mixin and transform domains together.
// Pure Java (no Bukkit), so it is covered by real instrumentation tests on a plain JVM.
dependencies {
    implementation(project(":nmshook-api"))
    implementation(project(":nmshook-hook"))
    implementation(project(":nmshook-mixin"))
    implementation(project(":nmshook-transform"))
    implementation("net.bytebuddy:byte-buddy:1.18.16")

    testImplementation(project(":nmshook-resolver"))
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.16")
}

tasks.test {
    // Tests self-attach an agent to retransform their own target classes.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
}
