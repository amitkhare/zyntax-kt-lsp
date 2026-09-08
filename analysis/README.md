# Live analysis foundation — in progress

This is the replacement analysis module under development, not another selectable
server backend. The server does not depend on it yet. Its four host files own the
project lifecycle, explicit module graph, immutable document snapshots and source
index. The fixture runner is test-only. No app or SDK code belongs here.

Use Java 21 and the repository wrapper:

```powershell
.\gradlew.bat -p analysis testClasses
.\gradlew.bat -p analysis verifyWorkspace
```

The focused verification covers language/API 1.8 and 2.2: cross-module resolution,
unsaved diagnostics and correction, dependency declaration renames, stable untouched
PSI and document URIs, one parse per edit, unchanged disk files, and shutdown.
Semantic assertions passed before checkpointing. The published compiler's stripped
shutdown API currently makes the full verification fail; the intact upstream
[runtime build](../docs/building.md#modern-analysis-runtime-in-progress) is pending.
Do not use this module in a distribution until the entire check exits successfully.

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

Remaining work: intact runtime and dependency notices; add/remove/reimport lifecycle;
Java sources and per-script template/plugin inputs; feature-preserving server cutover
with removal of the classic engine. Kotlin DSL and Android execution need their own
focused verification. This checkpoint does not claim either is complete.
