# Live analysis foundation

This independent development module is not connected to the existing server and
is not a selectable second backend. It owns project lifecycle, explicit module
graphs, immutable document snapshots and source indexes. The fixture runner is
test-only. No app or SDK code belongs here.

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

`evaluatedModules` consumes the importer's canonical `project-model` DTOs. Callers
select `(buildRoot, compilationId)` identities, supply explicit compiler defaults,
the supported compiler identity and Kotlin-task JDK, and declare source dependency
edges. Evaluated options override those supplied defaults. Associated compilations
retain friendship; exact selected dependency outputs become source edges at their
first evaluated output position, retaining mixed source/binary dependency order.
Android-style friend outputs also become source friends without requiring a
compilation association. Each remaining friend binary refers to its exact library
module, never an aggregate containing unrelated dependencies. Shared source files
in conflicting selected variants are rejected, not assigned arbitrarily.

The supported input subset is Kotlin `.kt` with compiler 2.2.21 and structured
language/API/JVM, opt-in and progressive settings. Java sources, scripts, compiler
plugins and free compiler arguments currently fail explicitly. Missing settings
also fail: neither the Gradle JVM nor Java compilation toolchain supplies an
inferred Kotlin JDK. Warning/code-generation options remain in the canonical
resolved input; request-level diagnostic policy and code generation are later
server-cutover work. This is not full Android project input support.

The focused lifecycle check covers incremental source addition/removal, dependent
resolution invalidation and unchanged unrelated PSI. Graph/classpath reimport
prepares a complete new project generation before publishing it and disposing the
old one; a failed candidate leaves the previous generation usable. Open text and
versions survive reimport and edits while their variant is inactive. Explicit document close
returns ownership to caller-supplied disk text; this module never writes sources.

The Kotlin Analysis API assemblies are pinned to 2.2.21. Their upstream published
assemblies contain the internal modules still named as unpublished POM dependencies,
so those POM transitives are not resolved. The engine-only service descriptor is
loaded through the pinned implementation-detail API; no service replacement,
no-op document commit, or session rebuild is used for ordinary edits/add/remove.

Remaining work: dependency redistribution review; Java sources, free compiler
arguments and per-script template/plugin inputs; feature-preserving server cutover
with removal of the classic engine. Kotlin DSL and Android execution need their own
focused verification. Neither is complete yet.
