# Features

This table lists all LSP features implemented by ktlsp and their current status.

## Standard LSP Features

| Feature | LSP Method | Status | Notes |
|---------|-----------|--------|-------|
| Code Completion | `textDocument/completion` | Stable | Up to 75 items, fuzzy matching, auto-imports |
| Resolve Completion Item | `completionItem/resolve` | Stable | Lazy resolution for additional details |
| Diagnostics | `textDocument/publishDiagnostics` | Stable | Debounced, severity-filterable |
| Hover | `textDocument/hover` | Stable | Type info, KDoc/Javadoc, smart casts |
| Go to Definition | `textDocument/definition` | Stable | Includes decompiled JAR sources |
| Go to Declaration | `textDocument/declaration` | Stable | Optimized for type aliases, constructors |
| Go to Type Definition | `textDocument/typeDefinition` | Stable | Resolves the type of a symbol and jumps to its declaration |
| Find References | `textDocument/references` | Stable | Operator conventions, scoped search |
| Document Symbols | `textDocument/documentSymbol` | Stable | Hierarchical, classes/functions/properties |
| Workspace Symbols | `workspace/symbol` | Stable | Fuzzy matching across workspace |
| Signature Help | `textDocument/signatureHelp` | Stable | Overload detection, active parameter |
| Code Actions | `textDocument/codeAction` | Stable | Quick fixes + Java-to-Kotlin conversion |
| Formatting | `textDocument/formatting` | Stable | ktfmt-based, full/range/on-type |
| Range Formatting | `textDocument/rangeFormatting` | Stable | |
| On-Type Formatting | `textDocument/onTypeFormatting` | Stable | Triggers on `}` |
| Inlay Hints | `textDocument/inlayHint` | Stable | Type hints, parameter hints, chained hints |
| Semantic Tokens | `textDocument/semanticTokens/*` | Stable | Full + range, delta-encoded |
| Document Highlight | `textDocument/documentHighlight` | Stable | Per-file symbol occurrences |
| Code Lens | `textDocument/codeLens` | Stable | Reference/implementation/subclass counts |
| Rename | `textDocument/rename` | Stable | Multi-file, lambda params, validation |
| Prepare Rename | `textDocument/prepareRename` | Stable | Validates rename target before applying |
| Folding Ranges | `textDocument/foldingRange` | Stable | Imports, bodies, comments |
| Go to Implementation | `textDocument/implementation` | Stable | Concrete subclasses, overrides |

## Custom Extension Features

| Feature | Method | Status | Notes |
|---------|--------|--------|-------|
| JAR Class Contents | `kotlin/jarClassContents` | Stable | Decompile external classes |
| Build Output Location | `kotlin/buildOutputLocation` | Stable | Returns build output directory |
| Main Class Resolution | `kotlin/mainClass` | Stable | Finds `main` functions for run/debug |
| Override Members | `kotlin/overrideMember` | Stable | Lists overridable super members |

## Additional Capabilities

| Capability | Status | Notes |
|------------|--------|-------|
| Java to Kotlin Converter | Stable | Code action, handles nullability + Javadoc |
| Symbol Index | Stable | Background indexing for cross-file features |
| External Sources / Decompilation | Stable | Fernflower decompiler, `kls://` URI scheme |
| Import Management | Stable | Lexicographic ordering, backtick escaping |

## Configuration Quick Reference

Most features can be toggled or configured. Key settings:

| Setting | Default | Controls |
|---------|---------|----------|
| `diagnostics.enabled` | `true` | Compiler diagnostics |
| `completion.snippets.enabled` | `true` | Snippet completions |
| `formatting.formatter` | `"ktfmt"` | Formatter (`"ktfmt"` or `"none"`) |
| `inlayHints.typeHints` | `false` | Inline type annotations |
| `inlayHints.parameterHints` | `false` | Parameter name hints |
| `inlayHints.chainedHints` | `false` | Chain expression types |
| `indexing.enabled` | `true` | Background symbol index |
| `externalSources.useKlsScheme` | `false` | `kls://` URIs for JAR classes |
| `scripts.enabled` | `false` | `.kts` file support |

See [Configuration](configuration.md) for the complete reference.
