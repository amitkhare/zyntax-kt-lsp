# Troubleshooting

This guide covers common issues and how to diagnose problems with ktlsp.

## Viewing Logs

Logs are your best tool for diagnosing issues. KLS uses a custom logging system built on `java.util.logging`.

### Log Levels

| Level | When to Use |
|-------|-------------|
| `ERROR` | Something broke and the operation failed |
| `WARN` | Something unexpected but recoverable |
| `INFO` | Significant events (default level) |
| `DEBUG` | Detailed internal state for debugging |
| `TRACE` | Very fine-grained tracing |

### Enabling Debug Logging

Set `KLS_LOG_LEVEL` as an environment variable or JVM system property:

**Linux/macOS:**

```bash
export KLS_LOG_LEVEL=DEBUG
kotlin-language-server

# or one-liner:
KLS_LOG_LEVEL=ALL kotlin-language-server
```

**Windows (PowerShell):**

```powershell
$env:KLS_LOG_LEVEL="DEBUG"
kotlin-language-server
```

**Via JVM argument:**

```bash
java -DKLS_LOG_LEVEL=DEBUG -jar kotlin-language-server.jar
```

Valid values: `NONE`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`, `DEEP_TRACE`, `ALL`

### Disabling Stdio Logging

When using TCP mode (not stdio), you may want to disable console output:

```bash
export KLS_LOG_STDIO=false
kotlin-language-server
```

## Where logs are located

### LSP client messages

After connecting to your editor, KLS sends log messages directly to the client. Where you see them depends on your editor:

- **VSCode**: Output panel → "Kotlin" channel
- **Neovim**: `:LspLog` or check `~/.local/state/nvim/lsp.log`
- **Emacs (lsp-mode)**: `*lsp-log*` buffer
- **Helix**: Check editor logs per Helix documentation

### Log files

Enable file logging by setting `KLS_LOG_FILE`:

```bash
export KLS_LOG_FILE=true
kotlin-language-server
```

**Default log file locations:**

| Platform | Log Directory |
|----------|---------------|
| Linux | `~/.local/share/kotlin-language-server/logs/` |
| macOS | `~/Library/Logs/kotlin-language-server/` |
| Windows | `%APPDATA%/kotlin-language-server/logs/` |

**Custom log directory:**

```bash
export KLS_LOG_DIR=/path/to/logs
kotlin-language-server
```

**Workspace-relative logs**: When file logging is enabled and no custom `KLS_LOG_DIR` is set, KLS automatically uses `<workspaceRoot>/.kls/logs/` during workspace initialization.

**Log file naming**: Files are named `{timestamp}_{pid}.log`, e.g., `2026-05-07_143022_12345.log`.

## java.lang.OutOfMemoryError when running language server

The language server is currently a memory hog, mostly due to its use of an in-memory database for symbols (ALL symbols from dependencies etc.!). This makes it not work well for machines with little RAM. If you experience out of memory issues, and still have lots of RAM, the default heap space might be too low.

You might want to try tweaking the maximum heap space setting by setting `-Xmx8g` (which sets the heap size to 8GB. Change the number to your needs). This can be done by setting the `JAVA_OPTS` environment variable.

In [the VSCode extension](https://github.com/fwcd/vscode-kotlin), this is in the extension settings in the setting `Kotlin > Java: Opts`.

If you use Emacs, you can try the `setenv` function to set environment variables. Example: `(setenv "JAVA_OPTS" "-Xmx8g")`.

## Dependencies not resolving on Java 25

When using changing your system to use Java 25, you may notice that no dependencies outside your own project are being resolved. This is because dependency resolution fails because the Gradle version used by your project does not support Java 25.

This occurs because older Gradle versions lack support for newer Java versions. To use Java 25, your project must meet **BOTH** of these minimum requirements:

- **Gradle 9.1.0** (or newer)
- **Kotlin 2.3.0** (or newer, for project compatibility)

Make sure your project's `gradle-wrapper.properties` and Kotlin plugin version are updated to these minimums.

> [!WARNING]
> Projects using Kotlin versions newer than 2.3.0 **have not been tested**. We do not guarantee compatibility with such versions.

## Diagnostics Not Appearing

### Symptoms

- No errors or warnings show up in the editor
- Code with obvious errors shows no squiggles

### Steps to Diagnose

1. **Check if diagnostics are enabled**: Verify `diagnostics.enabled` is `true` in your configuration.

2. **Check the debounce time**: Diagnostics run after a delay (default 350ms). Wait a moment after typing.

3. **Save the file**: Saving triggers an immediate diagnostic run.

4. **Check logs**: Set `KLS_LOG_LEVEL=DEBUG` and look for compilation messages.

5. **Verify compilation**: If the compiler can't run due to classpath issues, diagnostics won't appear.

## Server Won't Start

### Symptoms

- Editor reports it can't connect to the language server
- Server crashes immediately on startup

### Steps to Diagnose

1. **Check Java version**: KLS requires Java 21 or newer.
   ```bash
   java -version
   ```

2. **Verify the executable is on PATH**:
   ```bash
   which kotlin-language-server
   # or on Windows:
   where kotlin-language-server
   ```

3. **Run manually**: Try running the server directly to see any startup errors:
   ```bash
   kotlin-language-server
   ```

4. **Check JAVA_OPTS**: If you have `JAVA_OPTS` set with problematic JVM flags, it may prevent startup.

## Slow Performance

Symptoms:

- High CPU usage
- Slow diagnostics
- Editor feels sluggish

### Steps to Improve Performance

1. **Increase heap size**: If you have RAM available, give KLS more heap:
   ```bash
   export JAVA_OPTS="-Xmx8g"
   ```

2. **Disable indexing**: If workspace symbol search isn't critical:
   ```json
   { "kotlin": { "indexing": { "enabled": false } } }
   ```

3. **Reduce diagnostic debounce time**: A longer debounce reduces CPU usage during typing:
   ```json
   { "kotlin": { "diagnostics": { "debounceTime": 500 } } }
   ```

4. **Disable file logging**: File logging adds I/O overhead. Only enable when debugging.

5. **Clear the cache**: A bloated cache can slow things down. Delete `.kls/` and let it rebuild.

## Debugging the Server

For developers or when filing bug reports, you may need to debug the server directly.

### Running in Debug Mode

**Using Gradle:**

```bash
./gradlew :server:debugRun
# or
just debug
```

This starts the server with JDWP debugger attached on port 8000.

**Using a debug distribution:**

```bash
./gradlew :server:installDebugDist
just package-debug
```

This creates a distribution with debug-friendly startup scripts.

### Attaching a Debugger

Attach your IDE's debugger to `localhost:8000` (or the port configured in `server/build.gradle.kts`).

**VSCode**: Use the "Attach running KLS" launch configuration.

### Creating a Reproduction

When filing a bug:

1. Enable debug logging: `export KLS_LOG_LEVEL=DEBUG`
2. Enable file logging: `export KLS_LOG_FILE=true`
3. Reproduce the issue
4. Share the log file and a minimal reproduction project

For long logs, use [GitHub Gist](https://gist.github.com) or [Pastebin](https://pastebin.com).

### Normalizing Stack Traces

When copying logs or stack traces, line endings may be escaped as literal text (e.g., `\r\n` instead of actual CRLF), which makes exception traces unreadable. A utility script is provided:

```bash
python3 scripts/normalize_line_endings.py input_log.txt normalized_log.txt
```
