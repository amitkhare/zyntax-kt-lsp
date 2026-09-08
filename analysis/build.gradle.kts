plugins {
    kotlin("jvm") version "2.2.21"
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
}

dependencies {
    implementation(files(providers.gradleProperty("analysisCompilerJar")))
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("script-runtime"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation(kotlin("scripting-compiler"))
    implementation(kotlin("assignment-compiler-plugin"))
    // Upstream assembles these jars from the internal modules still named in their POMs.
    // The published assemblies are the dependencies, not those unpublished project modules.
    for (artifact in listOf(
        "analysis-api-for-ide",
        "analysis-api-platform-interface-for-ide",
        "analysis-api-impl-base-for-ide",
        "analysis-api-k2-for-ide",
        "low-level-api-fir-for-ide",
        "symbol-light-classes-for-ide",
        "analysis-api-standalone-for-ide"
    )) {
        implementation("org.jetbrains.kotlin:$artifact:2.2.21") { isTransitive = false }
    }
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    // The compiler already embeds OpenTelemetry API; it does not embed its Context API.
    implementation("io.opentelemetry:opentelemetry-context:1.41.0")
    // IntelliJ 241.19416.19 util-xml-dom requires JSON and its transitive core runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.6.3")
}

// The source-built compiler is the only compiler runtime, including scripting dependencies.
configurations.configureEach { exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler") }

kotlin { jvmToolchain(21) }
tasks.register<JavaExec>("verifyWorkspace") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.javacs.kt.analysis.VerifyWorkspaceKt")
    maxHeapSize = "768m"
    args(project.file("src/test/resources/fixtures").absolutePath)
}
