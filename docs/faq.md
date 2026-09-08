# Frequently Asked Questions (FAQs)

## General

### What is ktlsp?

ktlsp is a [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) implementation for Kotlin. It provides IDE-like features - code completion, diagnostics, hover information, go-to-definition, and more - to any editor that supports LSP.

### What editors are supported?

Any editor that implements the Language Server Protocol. This includes VSCode, Neovim, Emacs, Helix, Sublime Text, Vim, and many more. See [Editor Integration](editors.md) for setup instructions.

### What Kotlin and Java versions are supported?

- **Java**: 21 or newer is required to run ktlsp
- **Kotlin**: Projects using Kotlin 2.3.0 or newer have not been fully tested. ktlsp works best with Kotlin 1.9.x - 2.2.x projects.
- **Gradle**: 8.x and newer are supported. For Java 25, Gradle 9.1.0+ is required.

## Features

<!-- TODO: Kotlin script support along with *.gradle.kts -->

### Can ktlsp decompile external library code?

Yes. When you navigate to a symbol from a JAR file, ktlsp uses the Fernflower decompiler to show the source.

### Does ktlsp support formatting?

Yes, using ktfmt. You can also disable formatting if you prefer another tool. See [Configuration](configuration.md) for the `formatting` section.

### Does ktlsp support refactoring?

Currently, ktlsp provides:

- Rename (across the workspace)
- Convert Java to Kotlin
- Implement abstract members quick fix
- Add missing imports quick fix

More refactoring features are planned.

## Configuration

### How do I configure ktlsp?

Configuration is sent via LSP workspace configuration under the `kotlin` key. Most LSP clients (nvim-lspconfig, lsp-mode, etc.) handle this automatically. See [Configuration](configuration.md) for the full reference.

### Can I use ktlsp without a build system?

Yes. ktlsp falls back to looking for the Kotlin stdlib in common locations, like:

- Maven local repo
- Gradle caches
- `kotlinc` lib directory

However, your own project's dependencies won't be resolved without a build system or custom classpath script.

### How do I set up a custom classpath?

Create an executable script named `kls-classpath` in your project root (or `~/.config/kotlin-language-server/`) that outputs JAR paths to stdout. See [Classpath Resolution](reference/classpath-resolution.md).

## Performance

### Why is ktlsp using so much memory?

ktlsp maintains an in-memory database of all symbols from your project and its dependencies. This enables fast completion and symbol search but can be memory-intensive for large projects. You can increase the heap size with `JAVA_OPTS="-Xmx8g"`.

### Why is ktlsp slow on large projects?

Common causes:

- **Initial classpath resolution**: Can take time for projects with many dependencies
- **Symbol indexing**: Runs in the background but may impact performance temporarily
- **Diagnostic compilation**: Full compilation runs on document save

Mitigations:

- Increase heap size
- Disable indexing if you don't need workspace symbol search
- Increase diagnostic debounce time

### Where is the cache stored?

By default, in `.kls/kls_database.db` in your workspace root. You can change this with the `storagePath` initialization option.

## Debugging

### How do I enable debug logging?

Set `KLS_LOG_LEVEL=DEBUG` as an environment variable before starting the server. See [Troubleshooting](troubleshooting.md) for details.

### Where are the logs?

- **In your editor**: Sent via LSP `window/logMessage` (check your editor's LSP log output)
- **To files**: Enable with `KLS_LOG_FILE=true`. Default location is platform-specific (see [Troubleshooting](troubleshooting.md)).

### How do I attach a debugger?

Run `./gradlew :server:debugRun` and attach your IDE's debugger to `localhost:8000`.

## Contributing

### How can I contribute?

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines. We encourage contributions of any size, from typo fixes to new features.

### How do I build ktlsp from source?

```bash
./gradlew :server:installDist
# or
just install
```

The executable will be at `server/build/install/server/bin/kotlin-language-server`.

See [Building](building.md) and [Coding Guidelines](contributing/coding-guidelines.md) for development details.

## Known Issues

### Why are some features missing compared to IntelliJ?

IntelliJ uses the full Kotlin compiler infrastructure with IDE-specific integrations. ktlsp uses the compiler's APIs but operates independently of the IntelliJ platform, so some features are harder to implement. ktlsp focuses on core LSP features rather than IDE-specific ones.
