import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm")
    id("maven-publish")
    id("application")
    alias(libs.plugins.com.github.jk1.tcdeps)
    alias(libs.plugins.com.jaredsburrows.license)
    id("ktlsp.publishing-conventions")
    id("ktlsp.distribution-conventions")
    id("ktlsp.kotlin-conventions")
}

val debugPort = 8000
val debugArgs = "-agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n,quiet=y"

val serverMainClassName = "org.javacs.kt.MainKt"
val applicationName = "kotlin-language-server"

application {
    mainClass.set(serverMainClassName)
    description = "Code completions, diagnostics and more for Kotlin"
    applicationDefaultJvmArgs = listOf("-DkotlinLanguageServer.version=$version")
    applicationDistribution.into("bin") { filePermissions { unix("755".toInt(radix = 8)) } }
}

distributions.main {
    contents.from(rootProject.file("LICENSE.txt"))
}

repositories {
    maven(url = "https://repo.gradle.org/gradle/libs-releases")
    maven("https://jitpack.io")
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    mavenCentral()
}

dependencies {
    // dependencies are constrained to versions defined
    // in /platform/build.gradle.kts
    implementation(platform(project(":platform")))
    annotationProcessor(platform(project(":platform")))

    implementation(project(":shared"))

    implementation(libs.org.eclipse.lsp4j.lsp4j)
    implementation(libs.org.eclipse.lsp4j.jsonrpc)

    implementation(kotlin("compiler"))
    implementation(kotlin("scripting-compiler"))
    implementation(kotlin("scripting-jvm-host-unshaded"))
    implementation(kotlin("sam-with-receiver-compiler-plugin"))
    implementation(kotlin("reflect"))
    implementation(libs.com.jetbrains.intellij.java.decompiler)
    implementation(libs.org.jetbrains.exposed.core)
    implementation(libs.org.jetbrains.exposed.dao)
    implementation(libs.org.jetbrains.exposed.jdbc)
    implementation(libs.com.github.fwcd.ktfmt)
    implementation(libs.com.beust.jcommander)
    implementation(libs.com.dynatrace.hash4j.hash4j)
    implementation(libs.org.jsoup.jsoup)

    implementation(libs.org.sl4fj.slf4j.api)
    runtimeOnly(libs.org.sl4fj.slf4j.simple)

    // Gradle Tooling API for dependency resolution
    implementation(libs.org.gradle.tooling.api)

    testImplementation(libs.hamcrest.all)
    testImplementation(libs.junit.junit)
    testImplementation(libs.org.openjdk.jmh.core)

    // See
    // https://github.com/JetBrains/kotlin/blob/65b0a5f90328f4b9addd3a10c6f24f3037482276/libraries/examples/scripting/jvm-embeddable-host/build.gradle.kts#L8
    compileOnly(kotlin("scripting-jvm-host"))
    testCompileOnly(kotlin("scripting-jvm-host"))

    annotationProcessor(libs.org.openjdk.jmh.generator.annprocess)
}

configurations.forEach { config -> config.resolutionStrategy { preferProjectModules() } }

tasks.startScripts { applicationName = "kotlin-language-server" }

tasks.register<Exec>("fixFilePermissions") {
    // When running on macOS or Linux the start script
    // needs executable permissions to run.

    onlyIf { !System.getProperty("os.name").lowercase().contains("windows") }
    commandLine(
        "chmod",
        "+x",
        "${tasks.installDist.get().destinationDir}/bin/kotlin-language-server",
    )
}

tasks.register<JavaExec>("debugRun") {
    mainClass.set(serverMainClassName)
    classpath(sourceSets.main.get().runtimeClasspath)
    standardInput = System.`in`

    jvmArgs(debugArgs)
    doLast { println("Using debug port $debugPort") }
}

tasks.register<CreateStartScripts>("debugStartScripts") {
    applicationName = "kotlin-language-server"
    mainClass.set(serverMainClassName)
    outputDir = tasks.installDist.get().destinationDir.toPath().resolve("bin").toFile()
    classpath = tasks.startScripts.get().classpath
    defaultJvmOpts = listOf(debugArgs)
}

tasks.register<Sync>("installDebugDist") {
    dependsOn("installDist")
    finalizedBy("debugStartScripts")
}

tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    maxHeapSize = "2g"
}

tasks.installDist { finalizedBy("fixFilePermissions") }

tasks.build { finalizedBy("installDist") }
