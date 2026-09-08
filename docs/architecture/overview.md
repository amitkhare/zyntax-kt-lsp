# Architecture Overview

ktlsp is a [Language Server Protocol (LSP)][lsp-spec] implementation that provides intelligent code editing features for Kotlin, including:

- Smart code completion
- Diagnostics (errors, warnings, hints)
- Hover information
- Document symbols
- Definition lookup
- Method signature help
- And more

## High-Level Architecture

```text
+-------------------------------------------------------------+
|                      LSP Client                             |
|  (VSCode, Neovim, Emacs, IntelliJ, etc.)                    |
+-------------------------+-----------------------------------+
                          | JSON-RPC (LSP)
                          | (stdio / TCP / WebSocket)
+-------------------------v-----------------------------------+
|                         ktlsp                              |
|                                                             |
|  +-------------+  +--------------+  +---------------------+ |
|  |   :server   |  |   :shared    |  |     :platform       | |
|  |             |  |              |  |                     | |
|  | - LSP impl  |  | - Classpath  |  | - Dependency        | |
|  | - Compiler  |  |   resolution |  |   versions          | |
|  | - Index     |  | - Database   |  |                     | |
|  | - Features  |  | - Utilities  |  |                     | |
|  +-------------+  +--------------+  +---------------------+ |
+-------------------------------------------------------------+
```

## Build System

The project uses [Gradle][gradle] as its build system with a multi-module structure:

- **Gradle Wrapper**: `./gradlew` (Unix-like) or `gradlew.bat` (Windows)
- **Requirements**: Java 21+

```bash
# Build and install the language server
./gradlew :server:installDist

# Run tests
./gradlew test

# Run with debugging
./gradlew :server:debugRun

# Create distribution zip
./gradlew :server:distZip
```

The project also has a [justfile](https://just.systems). A few key subcommands are:

```bash
# Package the language server into server/build/install
just install

# Run the language server in debug mode (port 8000)
just debug

# Run linter (this project uses Detekt)
just lint
```

For unit testing:

```bash
# Run a specific test class in the `:server` module
just test-class CompletionsTest

# Do the same but with info logging
just test-class-debug CompletionsTest
```

There's more subcommands like these, which you can check out with `just --list`.

### Language Server (`:server`)

The main module that implements the LSP server. It:

- Handles LSP requests from clients
- Manages the Kotlin compiler instance
- Provides code completion, diagnostics, hovers, etc.
- Uses the `:shared` module for classpath resolution

### Shared (`:shared`)

Contains common utilities and services:

- **Classpath Resolution**: Resolves dependencies using Gradle, Maven, or shell scripts
- **Database Service**: SQLite-based caching for symbols and metadata
- **Logging**: Centralized logging configuration
- **Utilities**: Common helper functions

### Platform (`:platform`)

A Java platform module that centralizes dependency version management using Gradle's `java-platform` plugin. All dependency versions are defined here as constraints.

## Communication

The language server supports three communication modes:

| Mode       | Description                     | Use Case          |
|------------|---------------------------------|-------------------|
| stdio      | Standard input/output (default) | Most editors      |
| TCP Server | Listens on a port               | Docker containers |
| TCP Client | Connects to a client            | Remote scenarios  |

See [Communication Modes](../contributing/communication-modes.md) for details.

## External Dependencies

The server depends on several key libraries:

- **Kotlin Compiler**: Core Kotlin compiler APIs
- **LSP4J**: Language Server Protocol for Java implementation
- **Exposed**: Kotlin SQL framework
- **ktfmt**: Kotlin code formatter
- **Gradle Tooling API**: For Gradle-based classpath resolution

---

[lsp-spec]: https://microsoft.github.io/language-server-protocol/
[gradle]: https://gradle.org/

## Related Documentation

- [Features](../features.md) — All available LSP features
- [Configuration](../configuration.md) — Server configuration reference
- [Troubleshooting](../troubleshooting.md) — Common issues and solutions
- [Coding Guidelines](../contributing/coding-guidelines.md) — Code conventions
