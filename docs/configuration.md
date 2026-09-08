# Configuration

## How Editor Settings Map to the Server

The server accepts settings at runtime through `workspace/didChangeConfiguration`, under a `kotlin` key.

> **All settings are optional.** If you don't provide a setting, the server uses the default value shown in the tables below. Not providing any settings at all is perfectly fine - the server will use all defaults.

**If your editor supports a `kotlin` settings key**:

Most LSP clients (nvim-lspconfig, lsp-mode, etc.) automatically wrap your settings in the `kotlin` key. You just define:

```lua
-- Neovim example
settings = {
    kotlin = {
        diagnostics = { enabled = true }
    }
}
```

This maps to:

```json
{
    "kotlin": {
        "diagnostics": { "enabled": true }
    }
}
```

**If your editor doesn't have a `kotlin` key**:

You can send the `kotlin` key directly in your workspace configuration, or the server will use defaults for all settings. **Not providing any settings is fine** - the server has sensible defaults.

**Mapping example (detailed)**:

When you configure the LSP client in your editor, the `settings` object you define gets sent as the `kotlin` key in `workspace/didChangeConfiguration`.

For example, in Neovim:

```lua
settings = {
    kotlin = { -- This becomes the "kotlin" key the server receives
        diagnostics = {
            enabled = true
        }
    }
}
```

Maps to this JSON the server processes:

```json
{
    "kotlin": {
        "diagnostics": { "enabled": true }
    }
}
```

The `kotlin` wrapper is handled automatically by clients like nvim-lspconfig or lsp-mode.

## Storage

Workspace sessions store indexes and caches in the first workspace root's `.kls/kls_database.db`. Sessions without a workspace use isolated in-memory SQLite, released on shutdown. Invalid storage or database initialization errors fail initialization; the server does not substitute another path or backend.

## Configuration Sections

All sections below go inside the `kotlin` key in your editor's settings.

### completion

```json
{
    "completion": {
        "snippets": { "enabled": true },
        "filteredTypes": [
            "java.awt.*",
            "com.sun.*",
            "sun.*",
            "jdk.*",
            "org.graalvm.*",
            "io.micrometer.shaded.*"
        ]
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `snippets.enabled` | `Boolean` | `true` | Include VSCode-style snippets in completions |
| `filteredTypes` | `String[]` | `["java.awt.*", "com.sun.*", "sun.*", "jdk.*", "org.graalvm.*", "io.micrometer.shaded.*"]` | FQN patterns to exclude from completions and import suggestions. **Not regex.** Two forms: `"com.example.*"` blocks all types under that package; `"com.example.Foo"` blocks only that exact type. Entries with `.*` suffix match all subpackages transitively. |

### compiler

```json
{
    "compiler": {
        "jvm": { "target": "17" },
        "languageVersion": "2.2",
        "apiVersion": "2.2"
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `jvm.target` | `String` | `"default"` | JVM bytecode target recognized by the pinned compiler. `"default"` selects its default target (1.8). |
| `languageVersion` | `String` | `"2.2"` | Exact major.minor language version supported by the pinned Kotlin 2.2.21 compiler (1.8 through 2.2). Uses that version's normal feature settings, without forcing experimental features on. |
| `apiVersion` | `String` or `null` | `null` | Exact major.minor API version (1.8 through 2.2), no higher than `languageVersion`. `null` selects the configured language version. |

Compiler updates are validated together. Invalid values report an error and leave the previous compiler settings unchanged. Omitted fields retain their current values; use `apiVersion: null` or `jvm.target: "default"` to reset those fields.

Version acceptance validates configuration against the pinned compiler, **not** verified K2, Android project, or Gradle Kotlin DSL intelligence. The server's frontend and exact project-model work are still being developed; accepting a 2.x setting does not certify 2.x analysis.

### diagnostics

```json
{
    "diagnostics": {
        "enabled": true,
        "level": "hint",
        "debounceTime": 350
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | `Boolean` | `true` | Whether diagnostics are enabled |
| `level` | `String` | `"hint"` | Minimum severity: `"error"`, `"warning"`, `"information"`, `"hint"` |
| `debounceTime` | `Long` | `350` | Milliseconds between diagnostic runs |

### indexing

```json
{
    "indexing": { "enabled": true }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | `Boolean` | `true` | Build a global symbol index in the background |

### cache

```json
{
    "cache": {
        "maxSourceFiles": 500,
        "maxCachedTempFiles": 100
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `maxSourceFiles` | `Int` | `500` | Maximum source files to keep in memory |
| `maxCachedTempFiles` | `Int` | `100` | Maximum decompiled external files to cache |

### scripts

```json
{
    "scripts": {
        "enabled": false,
        "buildScriptsEnabled": false,
        "enableJdkSymbols": false
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | `Boolean` | `false` | Handle `.kts` script files |
| `buildScriptsEnabled` | `Boolean` | `false` | Handle `.gradle.kts` build scripts (requires `enabled`) |
| `enableJdkSymbols` | `Boolean` | `false` | Include JDK symbols when analyzing scripts |

### externalSources

```json
{
    "externalSources": {
        "useKlsScheme": false,
        "autoConvertToKotlin": false,
        "excludedPatterns": [],
        "generatedSourceRoots": [],
        "jdkSourceOverride": null
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `useKlsScheme` | `Boolean` | `false` | Use `kls://` URIs for classes in JARs |
| `autoConvertToKotlin` | `Boolean` | `false` | Auto-convert Java to Kotlin in external sources |
| `excludedPatterns` | `String[]` | `[]` | Additional glob patterns for directories and files to exclude from indexing. These patterns match against any path segment. Examples: `"build"`, `"target"`, `"node_modules"`. Note: Default exclusion patterns (`build`, `target`, etc.) are always applied in addition to these. |
| `generatedSourceRoots` | `String[]` | `[]` | Paths to directories containing generated sources that should be indexed despite being under an excluded parent directory (e.g. `build/` or `target/`). Paths are relative to workspace root and match as prefixes. Examples: `"build/generated"`, `"target/generated-sources"`. This is useful for projects using KSP, protobuf, or other code generators. |
| `jdkSourceOverride` | `String?` | `null` | Absolute path to a JDK `lib/src.zip` to use for `java.*` / `javax.*` hover and signature docs. When unset, the server auto-detects a `src.zip` from common JDK install locations (`/usr/lib/jvm/`, `/Library/Java/JavaVirtualMachines/`, `C:\Program Files\Java\`, etc.), preferring the JDK matching the running JVM and falling back to the closest version otherwise. If the chosen src.zip is from a different JDK major than the running JVM, the hover output is annotated with a version‑mismatch notice. Set this to e.g. `"/usr/lib/jvm/java-21-openjdk/lib/src.zip"` to force a specific source archive. |

### inlayHints

```json
{
    "inlayHints": {
        "typeHints": false,
        "parameterHints": false,
        "chainedHints": false
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `typeHints` | `Boolean` | `false` | Show type hints |
| `parameterHints` | `Boolean` | `false` | Show parameter name hints |
| `chainedHints` | `Boolean` | `false` | Show chained call hints |

### formatting

```json
{
    "formatting": {
        "formatter": "ktfmt",
        "ktfmt": {
            "style": "google",
            "indent": 4,
            "maxWidth": 100,
            "continuationIndent": 8,
            "removeUnusedImports": true
        }
    }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `formatter` | `String` | `"ktfmt"` | Formatter: `"ktfmt"` or `"none"` (disables formatting) |
| `ktfmt.style` | `String` | `"google"` | Style: `"google"` or `"default"` |
| `ktfmt.indent` | `Int` | `4` | Indent size |
| `ktfmt.maxWidth` | `Int` | `100` | Maximum line width |
| `ktfmt.continuationIndent` | `Int` | `8` | Continuation indent |
| `ktfmt.removeUnusedImports` | `Boolean` | `true` | Remove unused imports |

**To disable formatting**, set `"formatter": "none"`. The server will still accept formatting requests but return the code unchanged.

### codegen

```json
{
    "codegen": { "enabled": false }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | `Boolean` | `false` | Enable code generation to temp directory for Java interop |

## Deprecated Keys

For backwards compatibility, the server still accepts these top-level keys (now under `kotlin`):

| Deprecated Key | Replacement |
|---------------|-------------|
| `debounceTime` | `diagnostics.debounceTime` |
| `snippetsEnabled` | `completion.snippets.enabled` |
| `linting` | `diagnostics` |

## Client Configuration Examples

### Neovim (nvim-lspconfig)

```lua
require('lspconfig').kotlin_language_server.setup({
    settings = {
        kotlin = {
            diagnostics = { enabled = true, level = "warning" },
            inlayHints = { typeHints = true }
        }
    }
})
```

### Neovim (vim.lsp)

```lua
vim.lsp.config('kotlin_language_server', {
    settings = {
        kotlin = {
            compiler = { jvm = { target = "21" } }
        }
    }
})
```

### Helix

In `languages.toml`:

```toml
[language-server.kotlin-language-server]
command = "kotlin-language-server"

[language-server.kotlin-language-server.settings.kotlin]
```

### VSCode

The [vscode-kotlin](https://github.com/fwcd/vscode-kotlin) extension manages its own settings via the `Kotlin` section in settings UI.

To configure LSP server settings (like `diagnostics`, `completion`, `formatting`), add them under the `kotlin` key in your workspace settings or use the extension's configuration.

For extension-specific configuration, see the [extension documentation](https://github.com/fwcd/vscode-kotlin/blob/master/README.md).

---

For implementation details, see [Configuration.kt][config-source].

[config-source]: ../../server/src/main/kotlin/org/javacs/kt/Configuration.kt
