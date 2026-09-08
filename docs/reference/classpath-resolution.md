# Classpath Resolution

To provide smart features like code completion, diagnostics, and navigation, ktlsp needs to understand your project's environment. This process, known as **classpath resolution**, involves identifying all the external libraries (JAR files) and source files your project depends on.

## The Resolution Process

When you open a workspace, the server automatically attempts to determine the classpath using a prioritized sequence of strategies. It uses the [`DefaultClassPathResolver`][default-cp-resolver] to coordinate several specialized resolvers.

The search follows a specific order of precedence:

1. **Custom scripts**: The server first checks for user-provided scripts that manually define the classpath.
2. **Build systems**: If no custom script is found, it looks for standard build configuration files like `build.gradle`, `build.gradle.kts`, or `pom.xml`.
3. **Environmental fallbacks**: If no build system is detected, it attempts to find the Kotlin standard library in common system locations.

## Resolver Types

The server uses several stable resolver classes to handle different project types.

### ShellClassPathResolver (manual override)

For projects with non-standard structures or build systems not natively supported, you can provide an executable script that outputs a list of JAR paths. This is the most reliable way to handle complex environments.

The server searches for these scripts in the following order:

- **Project Root**: `kls-classpath`, `kls-classpath.sh`, or `kls-classpath.bat`
- **Global Config**: `~/.config/kotlin-language-server/classpath` (or the equivalent `.sh`/`.bat` variants)

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

### BackupClassPathResolver

When no build system or script is detected, this resolver attempts to find the Kotlin standard library and reflect library in your local Maven repository, Gradle cache, or the `kotlinc` installation directory.

## Performance and Caching

Classpath resolution can be time-consuming, especially for large projects. To ensure a fast startup, the server uses the `CachedClassPathResolver`.

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

For technical integration, resolvers can be combined using standard Kotlin operators:

- **Union** (`+`): Combines the classpath results of two resolvers,
- **Fallback** (`or`): Uses the first resolver that returns a non-empty classpath.

This allows the server to build complex resolution chains, such as merging Gradle dependencies with a manual shell script override.

---

For technical details on how these resolvers are implemented, see [ClassPathResolver.kt][resolver-source].

[resolver-source]: ../../shared/src/main/kotlin/org/javacs/kt/classpath/ClassPathResolver.kt
[default-cp-resolver]: ../../shared/src/main/kotlin/org/javacs/kt/classpath/DefaultClassPathResolver.kt
