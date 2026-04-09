plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

subprojects {
    apply(plugin = "java")

    group = "dev.velvet.nmshook"
    version = "0.1.0-SNAPSHOT"

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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
