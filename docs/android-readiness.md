# Android readiness track

This public fork remains an editor-independent Kotlin language server under its
existing MIT license. It is not a Zyntax extension or an Android build manager.
Zyntax integration and optional tool packaging belong in the extensions repository;
no Kotlin-specific app or SDK code is planned.

## Checklist

- [x] Audit the inherited runtime, classpath importer and Kotlin DSL implementation.
- [x] Build the baseline on Java 21 and include the root MIT license in distributions.
- [x] Fix ordinary/build-script classpath cache invalidation; one SQLite regression passed.
- [x] Inspect the actual runtime JARs and embedded components; update the broken license reporter.
- [x] Complete pinned runtime dependency notices and corresponding-source information.
- [x] Use SQLite only; remove inherited H2 fallback and storage migration/reset paths.
- [x] Verify Android SQLite loading and LSP initialization with managed Java 21 on USB.
- [x] Verify corrected LSP shutdown/exit lifecycle on the same device.
- [x] Validate compiler settings without forcing experimental language features;
  preserve unsaved source text when refreshing analysis.
- [ ] Replace classic compiler analysis with a coherent modern analysis engine;
  keep compilation and script contexts separate, without parallel legacy engines.
- [ ] Replace guessed/global Gradle classpaths with evaluated compilation inputs and
  project compiler settings. Keep module, source-set and variant boundaries explicit.
- [ ] Use Gradle's per-script model for Kotlin DSL dependencies, imports and generated
  accessors; remove the global cache scan and unsupported template assumptions.
- [ ] Verify Kotlin source intelligence and Kotlin DSL separately on a USB-connected
  Android device using app-private managed Java. No UI navigation or terminal installs.
- [ ] Package the verified server as an optional tool and language extension using
  existing public SDK contracts. Record supported versions and remaining limitations.

## Findings (8 September 2026)

Baseline: `cc77957`, server `1.4.0-rc1`, Kotlin compiler `2.2.21`, Java 21 build.

- Fixed: ordinary and build-script caches now store independent validity fingerprints
  atomically with their results, including valid empty results.
- Gradle import combines module/variant classpaths and guesses Android output paths.
  Kotlin DSL import scans all cached dependencies instead of resolving each script.
- Compiler settings now validate language/API/JVM values and use normal feature
  settings. Six focused compiler-setting, refresh and lifecycle tests pass;
  evaluated project settings remain pending.
- The fork still uses classic `BindingContext`/descriptor analysis. Depending on
  compiler 2.2.21 does not establish correct K2 language support.
- SQLite's packaged Android ARM64 JNI library loaded successfully through its
  documented native-path setting. Initialization created a real workspace database.
  The strict USB probe exposed an empty LSP `exit` handler. After correction, the
  same initialization/database/shutdown/exit probe passed on USB RMX3710 with
  managed Java 21. No UI navigation, terminal package installation, app replacement
  or app-data clearing was used; the temporary instrumentation APK was removed.
- The inherited generated license report has been replaced by pinned runtime
  notices and upstream license texts. MIT covers this fork's code, not every
  dependency or separately packaged JVM.

## Redistribution review

The current distribution contains 40 third-party JARs plus the server/shared JARs.
The license reporter also includes compile-only dependencies; its POM report is
evidence, not a complete inventory of shipped or embedded code. Exact notices and
source provenance are in `server/src/main/dist/THIRD-PARTY-NOTICES.md`.

- Kotlin compiler 2.2.21 embeds third-party components not covered solely by its
  Apache POM entry. Its [upstream license manifest](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/README.md)
  identifies the bundled Rhino-derived parser as Netscape Public License 1.1.
  Required notices and pinned source-access information are now included. A binary
  distributor must maintain the covered source access and retention obligations.
- H2 is no longer included.
- [LSP4J 1.0.0](https://github.com/eclipse-lsp4j/lsp4j/blob/v1.0.0/LICENSE) permits BSD-3-Clause,
  and [JNA 4.2.2](https://github.com/java-native-access/jna/blob/4.2.2/LICENSE) permits Apache-2.0.
  Preserve their license texts/notices rather than relying on incomplete POM labels.
- The decompiler's Apache license and embedded compiler components have been
  reconciled against exact source/artifacts, including vendor JNA and JDOM.
  No binary release has been published by this work.

The compiler also embeds JNA/JLine while separate versions occur in the distribution;
resolve duplicate classes and assess the native libraries before declaring Android support.

A successful build or LSP startup alone does not establish Android project or Kotlin
DSL correctness. APK/AAB building and signing remain a separate optional-extension track.

## Next implementation boundary

1. Import evaluated builds, compilations and individual scripts. Preserve ordered
   dependencies, source roots, compiler settings, friend compilations and build identity;
   do not merge main/test or Android variants into one workspace classpath.
2. Replace the classic analysis engine and route semantic features through the owning
   compilation/script context. Kotlin's [Analysis API](https://kotlin.github.io/analysis-api/index_md.html)
   provides module-scoped analysis; its standalone integration is still evolving and
   must be pinned and checked on Android. The pinned compiler already includes a
   [standalone session builder](https://github.com/JetBrains/kotlin/blob/v2.2.21/analysis/analysis-api-standalone/src/org/jetbrains/kotlin/analysis/api/standalone/StandaloneAnalysisAPISessionBuilder.kt).
   Replace long-lived `BindingContext`/descriptor caches and port their feature
   consumers together; do not retain a second legacy engine or compatibility adapter.
3. Consume Gradle's evaluated Kotlin DSL script model for classpaths, source paths,
   implicit imports and script errors. Keep Zyntax's project-model file format in
   the extension adapter, not in the editor-independent server.

Validate ordinary Kotlin and Gradle Kotlin DSL independently before packaging.
No new app/SDK capability has been identified for this integration.
