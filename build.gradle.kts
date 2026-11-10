plugins {
    java
    id("com.gradleup.shadow") version "9.6.1" apply false
}

subprojects {
    apply(plugin = "java")

    group = "dev.seraphicness.nmshook"
    // Release builds pass -PreleaseVersion=x.y.z (see .github/workflows/release.yml)
    version = rootProject.findProperty("releaseVersion")?.toString() ?: "0.1.0-SNAPSHOT"

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
