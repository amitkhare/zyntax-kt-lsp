import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm")
}

val javaVersion = property("javaVersion") as String

kotlin {
    jvmToolchain(javaVersion.toInt())
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
