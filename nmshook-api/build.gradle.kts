// Shared Kernel — annotations, callbacks, port interfaces.
// No external dependencies (pure Java). This is the only artifact plugin developers compile against.
plugins {
    `maven-publish`
}

// JitPack builds tags of this repository and serves them as
// com.github.seraphicness.NMSHook:nmshook-api:<tag>
if (System.getenv("JITPACK") == "true") {
    group = "${System.getenv("GROUP")}.${System.getenv("ARTIFACT")}"
    version = System.getenv("VERSION")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "nmshook-api"
            from(components["java"])
            pom {
                name.set("NMSHook API")
                description.set("API for NMSHook: Mixin-style runtime hooks into Minecraft server internals for Paper plugins.")
                url.set("https://github.com/seraphicness/NMSHook")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("seraphicness")
                        name.set("seraphicness")
                        url.set("https://github.com/seraphicness")
                    }
                }
                scm {
                    url.set("https://github.com/seraphicness/NMSHook")
                    connection.set("scm:git:https://github.com/seraphicness/NMSHook.git")
                    developerConnection.set("scm:git:ssh://git@github.com/seraphicness/NMSHook.git")
                }
            }
        }
    }
    repositories {
        // ./gradlew :nmshook-api:publishAllPublicationsToGitHubPackagesRepository
        // needs GITHUB_ACTOR / GITHUB_TOKEN (set automatically in GitHub Actions)
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/seraphicness/NMSHook")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
