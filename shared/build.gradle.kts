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
    implementation(libs.org.jetbrains.exposed.jdbc)
    runtimeOnly(libs.org.xerial.sqlite.jdbc)

    implementation(libs.com.dynatrace.hash4j.hash4j)

    // Gradle Tooling API for dependency resolution
    implementation(libs.org.gradle.tooling.api)

    testImplementation(libs.hamcrest.all)
    testImplementation(libs.junit.junit)
}
