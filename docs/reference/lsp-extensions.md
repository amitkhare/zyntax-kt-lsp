# LSP Protocol Extensions

ktlsp supports custom LSP requests beyond the standard Language Server Protocol. These extensions are namespaced under `kotlin/` and provide Kotlin-specific functionality.

## Custom Methods

All custom methods are prefixed with `kotlin/` and are defined in the [`KotlinProtocolExtensions`][extensions-source] interface.

### `kotlin/jarClassContents`

Retrieves the decompiled contents of a class from a JAR file.

**Request:**

```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "kotlin/jarClassContents",
    "params": {
        "textDocument": {
            "uri": "file:///path/to/file.kt"
        }
    }
}
```

**Response:**

```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": "/* decompiled class source */"
}
```

### `kotlin/buildOutputLocation`

Returns the location of the build output directory.

**Request:**

```json
{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "kotlin/buildOutputLocation",
    "params": null
}
```

**Response:**

```json
{
    "jsonrpc": "2.0",
    "id": 2,
    "result": "file:///path/to/project/build"
}
```

### `kotlin/mainClass`

Finds the main class in a Kotlin project.

**Request:**

```json
{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "kotlin/mainClass",
    "params": {
        "textDocument": {
            "uri": "file:///path/to/Main.kt"
        }
    }
}
```

**Response:**

```json
{
    "jsonrpc": "2.0",
    "id": 3,
    "result": {
        "mainClass": "com.example.MainKt",
        "projectRoot": "file:///path/to/project"
    }
}
```

### `kotlin/overrideMember`

Returns a list of overridable members for a class that can be used to implement/override.

**Request:**

```json
{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "kotlin/overrideMember",
    "params": {
        "textDocument": {
            "uri": "file:///path/to/MyClass.kt"
        },
        "position": {
            "line": 10,
            "character": 5
        }
    }
}
```

**Response:**

```json
{
    "jsonrpc": "2.0",
    "id": 4,
    "result": [
        {
            "title": "Override fun toString(): String",
            "kind": "quickfix",
            "edit": {
                "changes": {
                    "file:///path/to/MyClass.kt": [
                        {
                            "range": {
                                "start": { "line": 10, "character": 0 },
                                "end": { "line": 10, "character": 0 }
                            },
                            "newText": "    override fun toString(): String = TODO()\n"
                        }
                    ]
                }
            }
        }
    ]
}
```

## KLS URI Scheme

When [`useKlsScheme`][external-sources-config] is enabled, the server uses a custom `kls://` URI scheme to reference classes in JAR files:

```text
kls://jar:file:/path/to/library.jar!/com/example/Class.class
```

This allows clients to:

- Fetch decompiled sources
- Navigate to external library code

## Client Support

### Emacs (lsp-mode)

```elisp
;; Enable protocol extensions
(setq lsp-kotlin-extensions-enabled t)
```

### Neovim (nvim-lspconfig)

The `kotlin_language_server` config automatically advertises these capabilities.

---

[extensions-source]: ../../server/src/main/kotlin/org/javacs/kt/KotlinProtocolExtensions.kt
[external-sources-config]: ../configuration.md#externalsourcesconfiguration
