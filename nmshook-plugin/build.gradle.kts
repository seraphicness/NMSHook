// Plugin entry point — Paper plugin bootstrap + Composition Root
plugins {
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")

    implementation(project(":nmshook-api"))
    implementation(project(":nmshook-hook"))
    implementation(project(":nmshook-mixin"))
    implementation(project(":nmshook-transform"))
    implementation(project(":nmshook-agent"))
    implementation(project(":nmshook-resolver"))
    implementation("net.bytebuddy:byte-buddy:1.14.18")
}

tasks {
    jar {
        manifest {
            attributes(
                "Premain-Class" to "dev.seraphicness.nmshook.agent.NMSAgent",
                "Agent-Class" to "dev.seraphicness.nmshook.agent.NMSAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true",
            )
        }
    }

    shadowJar {
        mergeServiceFiles()
        // ByteBuddy relocate disabled — shadow plugin's ASM 9.7 can't process Java 25 class files
        // Enable when shadow plugin updates ASM to support class file version 69+
        // relocate("net.bytebuddy", "dev.seraphicness.nmshook.shaded.bytebuddy")
    }

    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/NMSHook-${project.version}.jar"))
    }

    assemble {
        dependsOn(reobfJar)
    }
}
