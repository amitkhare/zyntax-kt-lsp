# Classpath Resolution

To provide smart features like code completion, diagnostics, and navigation, ktlsp needs to understand your project's environment. This process, known as **classpath resolution**, involves identifying all the external libraries (JAR files) and source files your project depends on.

## The Resolution Process

The [`DefaultClassPathResolver`][default-cp-resolver] discovers declared Gradle,
Maven and executable project classpath providers, then combines their results.
Project dependencies are authoritative: the server does not substitute a different
standard library, choose the newest JAR, or add dependencies after an import fails.

When no provider is declared, standalone Kotlin files use the exact standard-library
JAR shipped with the server. Its location comes from the loaded Kotlin runtime and
is validated directly; no `kotlinc`, user cache or global shell script is searched.

## Resolver Types

The server uses several stable resolver classes to handle different project types.

### ShellClassPathResolver (manual override)

For projects with non-standard structures or build systems not natively supported, you can provide an executable script that outputs a list of JAR paths. This is the most reliable way to handle complex environments.

The server discovers executable `kls-classpath` or `kotlinLspClasspath` scripts
inside the project, using the platform's supported script extensions.

The script should print the absolute paths of all required JARs to standard output, separated by your platform's path separator (`:` on Unix-like OSes, `;` on Windows).

**Example (Unix-like)**:

```bash
#!/bin/bash
echo "/path/to/kotlin-stdlib.jar:/path/to/my-lib.jar"
```

**Example (Windows)**:

```cmd
@echo off
echo C:\path\to\kotlin-stdlib.jar;C:\path\to\my-lib.jar
```

### GradleClassPathResolver

For Gradle projects, the server uses the [**Gradle Tooling API**](https://docs.gradle.org/current/userguide/tooling_api.html) as its primary mechanism. This allows it to accurately resolve dependencies, including those in multi-project builds and custom configurations. It automatically detects `build.gradle.kts` and `build.gradle` files.

The server also invokes the **Gradle CLI** (via `gradlew` or the system `gradle`) in specific scenarios:

- **Test dependencies**. To resolve dependencies required for unit and integration tests.
- **Build script dependencies**. To provide completions and diagnostics within `.gradle.kts` files.
- **As a fallback**. As a secondary resolution strategy if the Tooling API fails to initialize or retrieve the project model.

### MavenClassPathResolver

For Maven projects, the server parses the `pom.xml` and may invoke the Maven CLI to determine the full dependency graph, including test-scope dependencies.

## Performance and Caching

Declared project classpaths use `CachedClassPathResolver`. The bundled standalone
classpath is read directly and is not persisted, so a server upgrade cannot retain
an old package location.

- **Persistence**: Resolved classpaths are stored in a local SQLite database (typically in the `.kls/` directory).
- **Validation**: The cache is automatically invalidated if the underlying build files (like `build.gradle`) are modified.
- **In-memory cache**: The server also maintains an in-memory cache to avoid repeated disk access during a single session.

## Debugging Resolution Issues

If features like autocomplete are not working, it usually indicates a classpath issue.

1. **Check the Logs**: The server logs the specific resolver being used and any errors encountered during the process.
2. **Verify build files**: Ensure your build files are valid and located in the workspace root.
3. **Manual test**: If using a custom script, try running it manually in your terminal to ensure it outputs the expected paths.
4. **Clear cache**: In some cases, deleting the `.kls/` directory in your project root can force a clean re-resolution.

## Resolver Composition

The union operator (`+`) combines declared providers without changing dependency
identities. Empty or failed project resolution does not select the standalone
classpath.

---

For technical details on how these resolvers are implemented, see [ClassPathResolver.kt][resolver-source].

[resolver-source]: ../../shared/src/main/kotlin/org/javacs/kt/classpath/ClassPathResolver.kt
[default-cp-resolver]: ../../shared/src/main/kotlin/org/javacs/kt/classpath/DefaultClassPathResolver.kt
