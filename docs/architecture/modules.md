# Modules

ktlsp is organized into three Gradle modules:

- `:server`: Main language server executable
- `:shared`: Classpath resolution and other utilities
- `:platform`: Dependency version constraints

`:server` and `:shared` have a lot of unit tests to ensure LSP features are consistent and correct.

## `:server`

The main language server module provides the LSP server implementation. It's in `server/src/main/kotlin/org/javacs/kt`.

The main files are:

- Main.kt - the entry point which starts the LSP server
- compiler/Compiler.kt - contains Kotlin compiler integrations

It also contains multiple packages, with each package containing the implementation for each LSP feature. Examples:

- `org/javacs/kt/completions` will contain logic related to code completion,
- `org/javacs/kt/j2k` will contain logic for converting Java to Kotlin,
- `org/javacs/kt/definition` will contain logic for go-to-definition.

The `:server` module also re-compiles files incrementally as you type to provide fresh diagnostics.

## `:shared`

A library module containing shared utilities and services used by the server.

### Source Structure

This module (at `shared/src/main/kotlin/org/javacs/kt/`) contains multiple classes related to classpath resolution.

It also contains logic for the debouncer (`utils/Debouncer.kt`). As you type, it recompiles the code with a delay to avoid excessive CPU usage.

This module also holds:

- `Logger.kt`, responsible for sending log messages to the LSP client (like your editor). This integrates with JUL,
- Several extension functions for error handling and Java classes,
- SQLite-based caching for symbols and metadata

#### Classpath resolution

In order to provide a smooth coding experience, the LSP must understand the project's dependencies and their location within your system. This helps provide features like code completion, diagnostics, etc. This section explains its capabilities in doing this.

The logic for classpath resolution is in `org/javacs/kt/classpath`. The `ClassPathResolver` interface defines how to get classpaths, and the default resolver combines multiple sources:

|Resolver|Description|Use case / Notes|
|:---:|---|---|
|GradleClassPathResolver|For `.gradle` and `.gradle.kts` files|Uses Gradle Tooling API with CLI fallback. Can find source JARs automatically.|
|MavenClassPathResolver|For `pom.xml` files|Uses the Maven CLI. Can resolve test scope dependencies.|
|ShellClassPathResolver|For custom scripts|Discovers executable `kls-classpath` or `kotlinLspClasspath` inside the project. Outputs classpath entries on stdout.|
|StandaloneClassPathResolver|For standalone files|Uses the validated stdlib JAR shipped with the server only when no project provider is declared.|
|CachedClassPathResolver|Caching layer|Stores independently fingerprinted classpaths in the workspace's `.kls/kls_database.db`, avoiding re-resolution on every request.|

Here's **how they combine**:

`DefaultClassPathResolver#defaultClassPathResolver()` creates the resolver chain:

1. Searches the workspace for build files (`.gradle`, `.gradle.kts`, `pom.xml`, scripts)
2. Joins declared providers without changing their dependency identities or substituting a failed result
3. Uses the bundled standalone classpath only if no providers were declared
4. Caches declared project classpaths in `CachedClassPathResolver`; the standalone classpath is never persisted

## `:platform`

The platform module is a Gradle Java platform -- similar to a Maven BOM (also Bill of Materials) -- that centralizes all dependency versions for the project. It defines which versions of Kotlin, LSP4J, Exposed, SQLite JDBC, and other libraries everyone should use.

### Why it exists

Without a centralized platform, each module can resolve different versions of the same library. For example, `:server` might get Exposed 0.50.0 while `:shared` gets 0.51.0. This causes:

- **Version conflicts** - Gradle may fail to resolve dependencies at all
- **Inconsistent behavior** - Bug fixes or features in one version don't appear in another
- **Maintenance burden** - Updating a version would require editing both `server/build.gradle.kts` and `shared/build.gradle.kts`

By having `:server` and `:shared` both reference the platform, Gradle forces them to use the same version of every shared library. Everyone agrees on "this is Exposed 0.51.3" - no ambiguity.

### How to use it

All other modules reference the platform for dependency versions:

```kotlin
dependencies {
    implementation(platform(project(":platform")))
    // Then use libs.XXX without specifying versions
    implementation(libs.org.eclipse.lsp4j.lsp4j)
}
```

### Version Constraints

The platform module declares constraints for:

- Kotlin compiler and libraries
- LSP4J
- Exposed (SQL)
- SQLite JDBC
- ktfmt
- And others...

See [platform/build.gradle.kts][platform-gradle] for the complete list.

## Module Dependencies

```text
+-----------+      +---------+      +----------+
| :platform | <----| :shared | <----| :server  |
+-----------+      +---------+      +----------+
                        ^
                        |
                    (no deps)
```

The `:server` module depends on `:shared`, and both depend on `:platform` for dependency versions. There are no circular dependencies.

---

[platform-gradle]: ../../platform/build.gradle.kts
