# Evaluated Gradle project import

`importGradleProject(root, gradleJavaHome, output, cancellation)` evaluates one selected
Gradle build through the Tooling API. It owns and removes its temporary init script
and JSON result, propagates failures/cancellation, and has no shell or CLI fallback.
Import executes the project's build configuration and resolves dependencies; callers
must obtain project trust before invoking it.

The result keeps two independent inputs:

- Each Kotlin JVM/Android compilation: project/target/name identity, registered
  roots, actual source files, ordered dependencies, outputs, source-set edges,
  associated compilations, actual friend binaries and public compiler/plugin options.
- Each Kotlin DSL script: Gradle's evaluated classpath, sources, implicit imports,
  editor reports and exceptions, obtained with `prepareKotlinBuildScriptModel` and
  strict-classpath mode. No Gradle cache scan or fixed import list supplies this data.

Compilation IDs are local to the returned build root. Main, test and Android
variants are never unioned. A client must retain that identity when selecting
analysis contexts; dependencies on another included build need that build's model.

The export task does not compile application sources, run tests or generate an APK.
Gradle's script-model preparation may prepare its required build-logic inputs.
Registered generated paths may not exist yet. Android generated roots come from
the public component source model; standard JVM roots have no such classification,
so `generatedRoots` is null rather than inferred from directory names.

Public option providers that are unset remain null. `javaCompileHome` identifies
the Java task only. The public Kotlin task API does not expose its resolved JDK
home or full serialized compiler arguments; callers must supply missing analysis
inputs explicitly, not assume the Gradle/Java JDK or mislabel KGP's plugin version
as the compiler version. Compiler-plugin paths/options are exported unchanged.

Verified on 8 September 2026:

- Gradle 8.12 / KGP 2.2.21: six independent compilations and five script models in
  this fork, including separate main/test dependencies and friend paths.
- The supplied Kotlin-DSL Android sample, AGP 9.2.1: debug, release, unit-test and
  instrumentation-test compilations plus three script models; no APK build/tests.
- Two focused script projection checks preserve ordering, reports and identities.
- Gradle 9.4.1's modern public templates load with Kotlin 2.2.21 metadata checks
  enabled; all three sample scripts select exactly one template. Their annotated
  configuration supplies language/API settings and implicit receivers. Integration
  must use these settings and each script's model, not a fixed import list or the
  deprecated `KotlinBuildScript` template. FIR script analysis remains unverified.

This is the replacement input boundary, not a claim of working modern Kotlin or
Gradle Kotlin DSL editor intelligence. Its analysis-engine integration remains
tracked in [Android readiness](android-readiness.md). The app and SDK are unchanged.
