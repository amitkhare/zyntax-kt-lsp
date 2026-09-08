# Zyntax Kotlin Language Server

An editor-independent Kotlin language-server fork, based on
[ktlsp](https://codeberg.org/winlogon/ktlsp). It can be used by LSP clients;
it is not the Zyntax app, an extension package, or an Android build manager.

[Repository](https://github.com/amitkhare/zyntax-kt-lsp) ·
[Issues](https://github.com/amitkhare/zyntax-kt-lsp/issues)

## Current status

The existing server provides Kotlin completion, hover, diagnostics, navigation
and other inherited language features. Full modern Kotlin, Android project and
Gradle Kotlin DSL support are **not yet verified**.

Completed work:

- Java 21 server build and pinned dependency license notices.
- Android ARM64 initialization, SQLite loading and clean LSP shutdown/exit,
  verified with managed Java 21 on a USB device.
- Open-document text/version ownership across saves and filesystem events,
  plus independent ordinary-source and build-script classpath cache invalidation.
- An evaluated Gradle importer that keeps compilations and individual script
  models separate. Its integration into editor analysis is still pending.

The separate [modern analysis module](analysis/README.md) passes focused
host-side live-edit, module-scope and shutdown checks for language/API 1.8 and 2.2.
It is **not connected to the server**. The full engine replacement is paused
while initial integration is prioritized; there is no selectable second backend.

## Next steps

1. Verify basic `.kt` completion, hover and diagnostics on USB.
2. Package the verified server as an optional managed tool, using the existing
   managed Java runtime, and connect it through the existing Zyntax Kotlin extension.
3. Improve dependency-aware project analysis and Gradle Kotlin DSL incrementally.

Initial integration uses existing public extension contracts; it does not require
Kotlin-specific app or SDK code. Server improvements belong in this repository,
and packaging/integration belong in the separate extensions repository.

See the [Android readiness track](docs/android-readiness.md) for evidence,
limitations and deferred work. Initialization alone does not establish working
Kotlin intelligence, and this LSP does not build or sign APKs/AABs.

## Build and run

Use Java 21 with `JAVA_HOME` configured and UTF-8 source files.

```powershell
.\gradlew.bat :server:installDist
```

Configure your LSP client to launch
`server/build/install/server/bin/kotlin-language-server.bat` on Windows, or
`server/build/install/server/bin/kotlin-language-server` on Linux/macOS.
The server uses stdio by default. Keep the entire generated distribution together,
including its `lib` directory and license files.

To create the server ZIP:

```powershell
.\gradlew.bat :server:distZip
```

On Linux/macOS, use `./gradlew` instead of `.\gradlew.bat`.
These tasks build the current server, not the separate analysis module.
See [building](docs/building.md) for development details.

## Source layout and documentation

- `server/`: executable language server and distribution notices.
- `shared/`: project import, classpath resolution and shared utilities.
- `platform/`: dependency constraints.
- `analysis/`: deferred modern-engine work, separate from the server build.

[Configuration](docs/configuration.md) ·
[Gradle project import](docs/gradle-project-import.md) ·
[Protocol extensions](docs/reference/lsp-extensions.md) ·
[Contributing](CONTRIBUTING.md)

## License and provenance

This fork descends from [winlogon/ktlsp](https://codeberg.org/winlogon/ktlsp),
which continues [fwcd/kotlin-language-server](https://github.com/fwcd/kotlin-language-server).
Original copyright notices are retained in [LICENSE.txt](LICENSE.txt).

The server's MIT license does not replace dependency or per-file licenses.
Distributions include [third-party notices](server/src/main/dist/THIRD-PARTY-NOTICES.md)
and the required license texts and source-access information.
