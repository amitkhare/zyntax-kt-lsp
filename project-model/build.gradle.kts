plugins { kotlin("jvm") }

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
