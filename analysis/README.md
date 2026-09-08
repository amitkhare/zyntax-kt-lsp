# Live analysis foundation — paused

This development work is paused while initial integration uses the existing server.
It is not another selectable backend, and the server does not depend on it.
Its four host files own the
project lifecycle, explicit module graph, immutable document snapshots and source
index. The fixture runner is test-only. No app or SDK code belongs here.

Use Java 21 and the repository wrapper:

```powershell
$compilerJar = 'D:/path/to/kotlin/prepare/compiler/build/libs/kotlin-compiler-2.2.21.jar'
.\gradlew.bat -p analysis verifyWorkspace "-PanalysisCompilerJar=$compilerJar"
```

`analysisCompilerJar` is required and points to the final intact compiler built
from the pinned upstream source. The shrunk Maven compiler is excluded, including
transitive requests; there is no alternate compiler runtime.

The focused verification passes with language/API 1.8 and 2.2: cross-module resolution,
unsaved diagnostics and correction, dependency declaration renames, stable untouched
PSI and document URIs, module-scoped package lookup, one parse per edit, unchanged
disk files, and clean global shutdown. The published compiler's stripped shutdown
API failed; the intact upstream [runtime build](../docs/building.md#modern-analysis-runtime-in-progress)
passes the complete check. This is host verification, not Android verification.

Source modules require explicit target JDK/boot inputs, ordered classpaths, language
settings and regular/friend dependencies. Each source has one owner in the selected
compilation graph; inactive variants remain import data. Fixture runtime discovery
is confined to the test runner. No guessed host-JDK or standard-library dependency
is added by the engine.

The Kotlin Analysis API assemblies are pinned to 2.2.21. Their upstream published
assemblies contain the internal modules still named as unpublished POM dependencies,
so those POM transitives are not resolved. The engine-only service descriptor is
loaded through the pinned implementation-detail API; no service replacement,
no-op document commit, or session rebuild is used to hide unsupported live edits.

Remaining work: dependency redistribution review; add/remove/reimport lifecycle;
Java sources and per-script template/plugin inputs; feature-preserving server cutover
with removal of the classic engine. Kotlin DSL and Android execution need their own
focused verification. Neither is complete yet.
