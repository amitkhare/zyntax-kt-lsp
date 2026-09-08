plugins {
    `maven-publish`
}

repositories {
    mavenCentral()
}

publishing {
    repositories {
        maven {
            name = "CodebergPackages"
            url = uri("https://codeberg.org/api/packages/winlogon/maven")
            credentials {
                username = "winlogon"
                password = System.getenv("CODEBERG_PACKAGES_TOKEN") ?: ""
            }
        }
    }

    publications {
        register("ktlsp", MavenPublication::class) {
            // java for jvm modules, javaPlatform for the platform BOM
            from(components.findByName("javaPlatform") ?: components["java"]!!)
            groupId = "org.javacs.kt"
            artifactId = project.name
            version = project.version.toString()
        }
    }
}
