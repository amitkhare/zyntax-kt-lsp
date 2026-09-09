# Zyntax Kotlin Language Server

An editor-independent Kotlin language-server fork, based on
[ktlsp](https://codeberg.org/winlogon/ktlsp). It can be used by LSP clients;
it is not the Zyntax app, an extension package, or an Android build manager.

[Repository](https://github.com/amitkhare/zyntax-kt-lsp) ·
[Issues](https://github.com/amitkhare/zyntax-kt-lsp/issues)

## Current status

The existing server's standalone Kotlin completion, hover, definition, diagnostics
and unsaved corrections passed a focused Android ARM64 USB check. Full modern Kotlin, Android project and
Gradle Kotlin DSL support are **not yet verified**.

## Roadmap

- [x] Java 21 server build and pinned dependency license notices.
- [x] Android ARM64 initialization, SQLite loading and clean LSP shutdown/exit,
  verified with managed Java 21 on a USB device.
- [x] Open-document text/version ownership across saves and filesystem events,
  plus independent ordinary-source and build-script classpath cache invalidation.
- [x] An evaluated Gradle importer that keeps compilations and individual script
  models separate. Its integration into editor analysis is still pending.
- [x] Independent modern workspace lifecycle and selected evaluated-compilation
  inputs, with host checks for reimport, open text and module/friend/settings isolation.
- [x] Verify standalone `.kt` completion, hover, diagnostics and unsaved corrections
  on Android ARM64 using the server's bundled standard library.
- [x] Package the verified server as an optional managed tool and update the existing
  Zyntax Kotlin extension, reusing managed Java 21 and public SDK contracts.
- [ ] Connect evaluated compilation inputs for dependency-aware project analysis.
- [ ] Complete the modern analysis-engine replacement and its feature integration.
- [ ] Verify Gradle Kotlin DSL intelligence with each script's evaluated context.

The separate [modern analysis module](analysis/README.md) passes focused
host-side live-edit, module-scope and shutdown checks for language/API 1.8 and 2.2.
It is **not connected to the server**. Its evaluated-input subset explicitly rejects
Java, scripts, compiler plugins and free compiler arguments. Full feature-preserving
engine replacement remains pending; there is no selectable second backend.

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

Clients that launch Java directly can use
`java -jar server/build/install/server/lib/server-1.4.0-rc1.jar`.
The JAR manifest preserves the build's dependency order; do not replace it with
an unordered `lib/*` classpath.

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
- `project-model/`: canonical evaluated compilation DTOs shared by importer and analysis.
- `platform/`: dependency constraints.
- `analysis/`: modern-engine development, separate from the server build.

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
