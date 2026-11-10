plugins {
    java
    id("com.gradleup.shadow") version "9.6.1" apply false
}

subprojects {
    apply(plugin = "java")

    group = "dev.seraphicness.nmshook"
    // gradle.properties holds the release version; -PreleaseVersion=x.y.z overrides it
    version = (rootProject.findProperty("releaseVersion") ?: rootProject.property("nmshookVersion")).toString()

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
