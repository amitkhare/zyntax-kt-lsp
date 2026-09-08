# Coding Guidelines

This project follows the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) with the project-specific additions below.

## Code Formatting

The project uses [`.editorconfig`](https://editorconfig.org/) - your IDE should pick it up automatically.

Run `just lint` to check for detekt violations before submitting a PR.

## Project Conventions

### Package Structure

Use singular nouns under `org.javacs.kt`. Keep the structure flat (1-2 levels deep):

```text
org.javacs.kt.completion/
org.javacs.kt.hover/
org.javacs.kt.util/
```

### Extension Functions

Use extension functions for types you don't control (stdlib, PSI, LSP types).
For classes defined in this codebase, prefer adding the function directly as a member instead:

```kotlin
// Good: PsiElement is from the Kotlin compiler, we can't modify it
fun PsiElement.findParent<T>(): T? =
    this.parentsWithSelf.filterIsInstance<T>().firstOrNull()

// Prefer member functions for own classes rather than scattering extensions
```

### Error Handling

**Return nullable types for operations that may fail.** Instead of throwing, return `T?` and log the issue:

```kotlin
fun resolveType(element: PsiElement): Type? {
    return try {
        // resolution logic
    } catch (e: Exception) {
        LOG.warn("Failed to resolve type for {}: {}", element, e.message)
        null
    }
}
```

**Return empty collections instead of null for collections:**

```kotlin
fun findCompletions(...): Sequence<CompletionItem> =
    // return emptySequence() on failure, not null
```

**Use `nullResult()` and `noResult()` helpers.** For LSP feature implementations, use the provided helpers:

```kotlin
return nullResult("Couldn't find expression at ${describePosition(cursor)}")
```

**Catch specific exceptions when possible.** But catching `Exception` is acceptable when the operation is inherently risky. Use `LOG.printStackTrace(e)` for unexpected errors.

### Logging

**Use the LOG singleton.** Access logging via `org.javacs.kt.LOG` at the top of your file.

**Choose appropriate log levels**:

- **`LOG.error`** - Something broke and the operation failed
- **`LOG.warn`** - Something unexpected but recoverable
- **`LOG.info`** - Significant events (server started, classpath resolved)
- **`LOG.debug`** - Detailed internal state useful for debugging
- **`LOG.trace`** - Very fine-grained tracing

**Use structured messages.** Use `{}` placeholders instead of string concatenation:

```kotlin
LOG.info("Resolved classpath for {} in {}ms", file.name, duration)
```

### Testing

**Use JUnit 4 + Hamcrest**:

```kotlin
class HoverTest : SingleFileTestFixture("hover", "Hover.kt") {
    @Test fun `shows type information on hover`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 3, 10)).get()!!
        assertThat(hover.contents.right.value, containsString("val x: String"))
    }
}
```

**Use backtick method names.** Write test names as readable sentences:

```kotlin
@Test fun `complete instance members`()
@Test fun `should not include private members`()
```

### Use test fixtures

The project provides two fixture classes.

**`SingleFileTestFixture(workspaceDir, fileName)`** is the most common choice. It:

- extends `LanguageServerTestFixture`
- automatically opens `src/test/resources/<workspaceDir>/<fileName>` before each test (`@Before`)
- waits for the initial lint pass to complete.

You get a live `languageServer` instance plus helpers like:

- `position(line, col)`,
- `range(...)`,
- `open(path)`,
- `replace(...)`,
- `completionParams(...)`,
- `hoverParams(...)`,
- `definitionParams(...)`, etc.

**`LanguageServerTestFixture(workspaceDir)`** is the base class. Use this directly when a test needs to open multiple files manually or doesn't need a single pre-opened file.

Minimal example:

```kotlin
class DeclarationTest : SingleFileTestFixture("declaration", "DeclarationExample.kt") {

    private fun declarationParams(relativePath: String, line: Int, column: Int): DeclarationParams {
        return textDocumentPosition(relativePath, line, column).run {
            DeclarationParams(textDocument, position)
        }
    }

    @Test
    fun `go to declaration of type alias`() {
        val params = declarationParams(file, 13, 25)
        val declarations = languageServer.textDocumentService
            .declaration(params).get().left
        assertThat(declarations, hasSize(1))
    }
}
```

Test resource files live in `server/src/test/resources/` organized by feature:

```text
src/test/resources/completion/InstanceMembers.kt
src/test/resources/hover/Literals.kt
```

**One assertion concept per test.** Each test should verify one specific behavior.

### Detekt Rules

The project uses detekt with these notable settings:

- **Cyclomatic complexity** threshold: 20
- **Too many functions** threshold: 20 per file/class/interface
- **Return count** max: 5 per function
- **Wildcard imports** allowed for: `java.util.*`, `org.eclipse.lsp4j.*`, `org.jetbrains.kotlin.psi.*`, `org.hamcrest.Matchers.*`
- **Line length** is not enforced
- **TODO/FIXME comments** are allowed

Run `just lint` to check and `just baseline-update` to update the baseline.
