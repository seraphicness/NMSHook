// Plugin entry point — Paper plugin bootstrap + Composition Root
//
// Targets Paper 26.1 – 26.3. Since 26.1 the server is unobfuscated and Paper no longer
// remaps plugins, so there is no reobf step: the jar is shipped Mojang-named as-is.
// This module does not reference NMS classes directly, so paper-api is enough
// (no paperweight dev bundle). Compiling against the lowest supported line (26.1)
// keeps the jar binary-compatible with 26.2 and 26.3.
plugins {
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    implementation(project(":nmshook-api"))
    implementation(project(":nmshook-hook"))
    implementation(project(":nmshook-mixin"))
    implementation(project(":nmshook-transform"))
    implementation(project(":nmshook-agent"))
    implementation(project(":nmshook-resolver"))
    implementation(project(":nmshook-core"))
    implementation("net.bytebuddy:byte-buddy:1.18.16")
}

val agentManifest = mapOf(
    "Premain-Class" to "dev.seraphicness.nmshook.agent.NMSAgent",
    "Agent-Class" to "dev.seraphicness.nmshook.agent.NMSAgent",
    "Can-Retransform-Classes" to "true",
    "Can-Redefine-Classes" to "true",
)

tasks {
    processResources {
        val pluginVersion = project.version.toString()
        inputs.property("version", pluginVersion)
        filesMatching("paper-plugin.yml") { expand("version" to pluginVersion) }
    }

    jar {
        manifest { attributes(agentManifest) }
    }

    shadowJar {
        archiveBaseName.set("NMSHook")
        archiveClassifier.set("")
        mergeServiceFiles()
        manifest { attributes(agentManifest) }
        // relocate("net.bytebuddy", "dev.seraphicness.nmshook.shaded.bytebuddy")
    }

    assemble {
        dependsOn(shadowJar)
    }
}
