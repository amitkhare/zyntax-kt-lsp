# Building

Describes how to build and run the language server and the editor extensions.

## Setup

* Java 21+ should be installed and located under `JAVA_HOME` or `PATH`.
* Note that you might need to use `gradlew` instead of `./gradlew` for the commands on Windows.
* The build toolchain is Java 21. Android runtime verification currently covers
  managed Java 21; other runtimes/project targets need their own verification.

## Language Server

If you just want to build the language server and use its binaries in your client of choice, run:

>`./gradlew :server:installDist`

The language server executable is now located under `server/build/install/server/bin/kotlin-language-server`. (Depending on your language client, you might want to add it to your `PATH`)

Server distributions include the root MIT license and pinned dependency notices in
`THIRD-PARTY-NOTICES.md`. Review the actual runtime, including embedded components,
whenever dependencies change; a generated POM license report alone is insufficient.
See the [Android readiness track](android-readiness.md).

Note that there are external dependent libraries, so if you want to put the server somewhere else, you have to move the entire `install`-directory.

### Packaging

To create a ZIP-archive of the language server, run:

>`./gradlew :server:distZip`

### Modern analysis runtime (in progress)

The standalone Analysis API needs an intact IntelliJ runtime. The published Kotlin
2.2.21 CLI compiler has removed a shutdown API through ProGuard; adding overlapping
IntelliJ JARs is not the replacement. Build the normal compiler artifact from the
unmodified upstream `v2.2.21` source (`2146684dcba708e5a304758b41a9e4ec9c7eff71`)
with its supported shrinking option disabled:

```powershell
# Run inside the pinned Kotlin source checkout, using its own Gradle wrapper.
.\gradlew.bat :kotlin-compiler:jar '-Pkotlin.build.proguard=false' '-Pkotlin.build.jar.compression=true' '-Pbuild.number=2.2.21'
```

Use the final compiler JAR, not the `before-proguard` intermediate. The upstream
build uses Java 21 and provisions its compilation toolchains. Record the source
commit and build options: this is a locally built artifact, not JetBrains' published
binary. This runtime is still under verification and is not the server's active
dependency; no compiler source patch or shutdown bypass is used.

## Gradle Tasks

This paragraph assumes that you are familiar with Gradle's [task system](https://docs.gradle.org/current/userguide/build_lifecycle.html).

In short: Every task describes an atomic piece of work and may depend on other tasks. Task dependencies will automatically be executed.

The following subsections describe the available tasks for each module of this project.

### Language Server (:server)

| Task | Command | Description |
| ---- | ------- | ----------- |
| Package | `./gradlew :server:installDist` | Packages the language server as a bundle of JAR files (e.g. for use with an editor extension) |
| Package for Debugging | `./gradlew :server:installDebugDist` | Packages the language server with a debug-friendly launch script that enables debugger attachment (port 8000) for troubleshooting server issues |
| Test | `./gradlew :server:test` | Executes all unit tests |
| Run | `./gradlew :server:run` | Runs the standalone language server from the command line |
| Debug | `./gradlew :server:debugRun` | Launches the standalone language server from the command line using a debug configuration |
| Build | `./gradlew :server:build` | Builds, tests and packages the language server |
| Package for Release | `./gradlew :server:distZip` | Creates a release zip in `server/build/distributions`. If any dependencies have changed since the last release, a new license report should be generated and placed in `src/main/dist` before creating the distribution. |
| Generate License Report | `./gradlew :server:licenseReport` | Generates a license report from the dependencies in `server/build/reports/licenses` |
