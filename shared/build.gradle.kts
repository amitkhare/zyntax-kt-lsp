plugins {
    id("maven-publish")
    kotlin("jvm")
    id("ktlsp.publishing-conventions")
    id("ktlsp.kotlin-conventions")
}

repositories {
    mavenCentral()
    maven(url = "https://repo.gradle.org/gradle/libs-releases")
}

dependencies {
    // dependencies are constrained to versions defined
    // in /platform/build.gradle.kts
    implementation(platform(project(":platform")))

    implementation(kotlin("stdlib"))
    implementation(libs.org.jetbrains.exposed.core)
    implementation(libs.org.jetbrains.exposed.dao)
    implementation(libs.com.h2database.h2)

    implementation(libs.com.dynatrace.hash4j.hash4j)

    // Gradle Tooling API for dependency resolution
    implementation(libs.org.gradle.tooling.api)

    testImplementation(libs.hamcrest.all)
    testImplementation(libs.junit.junit)
    testImplementation(libs.org.jetbrains.exposed.jdbc)
    testImplementation(libs.org.xerial.sqlite.jdbc)
}
