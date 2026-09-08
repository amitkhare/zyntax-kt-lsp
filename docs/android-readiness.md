# Android readiness track

This public fork remains an editor-independent Kotlin language server under its
existing MIT license. It is not a Zyntax extension or an Android build manager.
Zyntax integration and optional tool packaging belong in the extensions repository;
no Kotlin-specific app or SDK code is planned.

## Checklist

Standalone Kotlin verification and optional tool/extension integration are complete.
Full engine replacement and Gradle Kotlin DSL analysis remain deferred, not
prerequisites for that first integration. No app/SDK change was needed for this scope.

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
- [x] Preserve client-owned open text/version across filesystem events and saves;
  reject duplicate/out-of-order document changes.
- [x] Implement and verify evaluated Gradle compilation and per-script import on the
  fork's JVM build and the supplied Kotlin-DSL Android sample.
- [ ] Replace classic compiler analysis with a coherent modern analysis engine;
  keep compilation and script contexts separate, without parallel legacy engines.
- [ ] Replace guessed/global Gradle classpaths with evaluated compilation inputs and
  project compiler settings. Keep module, source-set and variant boundaries explicit.
- [ ] Use Gradle's per-script model for Kotlin DSL dependencies, imports and generated
  accessors; remove the global cache scan and unsupported template assumptions.
- [x] Verify standalone Kotlin completion, hover, diagnostics and unsaved corrections
  on USB Android using app-private managed Java. No UI navigation or terminal installs.
- [ ] Verify evaluated Android project intelligence and Kotlin DSL separately.
- [x] Package the verified server as an optional tool and language extension using
  existing public SDK contracts. Record supported versions and remaining limitations.

## Findings (8 September 2026)

Baseline: `cc77957`, server `1.4.0-rc1`, Kotlin compiler `2.2.21`, Java 21 build.

- Fixed: ordinary and build-script caches now store independent validity fingerprints
  atomically with their results, including valid empty results.
- Fixed: standalone files use the server's packaged standard library. Ambient
  `kotlinc`/cache searches, guessed library versions and global shell recovery were
  removed. Declared project dependencies are not replaced after a failed import.
  Both focused resolver tests passed; standalone paths are not persisted in SQLite.
- The packaged launcher passed one USB RMX3710 session with managed Java 21.0.14:
  SQLite initialization, completion, hover, definition, an unsaved type error and
  correction, unchanged disk text, shutdown and exit. The generic instrumentation
  test passed in 64.681 seconds including archive staging. Only the temporary test
  APK was installed and removed; no UI navigation, terminal install or app-data
  clearing occurred. Two focused extension packaging checks also passed.
- Gradle import combines module/variant classpaths and guesses Android output paths.
  Kotlin DSL import scans all cached dependencies instead of resolving each script.
  The replacement [project importer](gradle-project-import.md) is verified as an
  independent input boundary; editor analysis has not yet switched to it.
- Compiler settings now validate language/API/JVM values and use normal feature
  settings. Six focused compiler-setting, refresh and lifecycle tests pass;
  evaluated project settings remain pending.
- The fork still uses classic `BindingContext`/descriptor analysis. Depending on
  compiler 2.2.21 does not establish correct K2 language support.
- A separate live Analysis API host passed unsaved diagnostics, corrections and
  cross-module declaration renames with language/API 1.8 and 2.2, module-scoped
  package lookup, one parse per edit, unchanged disk files, and global shutdown.
  The published compiler had removed a required IntelliJ shutdown method. Building
  the exact upstream compiler with its supported shrinking option disabled fixed
  that runtime mismatch without a source patch or shutdown bypass. The tracked
  [analysis module](../analysis/README.md) requires the intact artifact; the server
  is not yet connected to it, and Android analysis verification remains pending.
- The supplied Android sample's three Gradle 9.4.1 scripts loaded the modern public
  project/settings/init template definitions under Kotlin 2.2.21. Each actual
  script matched exactly one template and retained its evaluated imports and
  dependencies. Template loading is not yet script-analysis verification.
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
  Managed tool distributions retain these notices and license texts.

The compiler also embeds components that overlap separate runtime JARs. The standard
executable-JAR manifest preserves Gradle's dependency order; packaging does not use
an unordered wildcard classpath. Broader native and project capabilities remain unverified.

A successful build or LSP startup alone does not establish Android project or Kotlin
DSL correctness. APK/AAB building and signing remain a separate optional-extension track.

## Deferred modern-analysis work

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
   Keep PSI-only folding, document symbols, formatting and text-edit utilities.
   Completion, navigation/refactoring, diagnostics, semantic tokens and indexes
   must return plain results from request-scoped analysis, not retained symbols.
   Preserve quick fixes, hierarchy, main-class/override-member protocol methods
   and optional code generation during the same cutover; do not silently drop them.
3. Consume Gradle's evaluated Kotlin DSL script model for classpaths, source paths,
   implicit imports and script errors. Keep Zyntax's project-model file format in
   the extension adapter, not in the editor-independent server.

Validate and label ordinary Kotlin and Gradle Kotlin DSL independently. Initial
packaging covers only verified ordinary Kotlin; script syntax does not imply DSL intelligence.
No new app/SDK capability has been identified for this integration.
