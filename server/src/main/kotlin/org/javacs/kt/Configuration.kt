package org.javacs.kt

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.DiagnosticSeverity

import java.lang.reflect.Type
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

import org.jetbrains.kotlin.name.FqName

public data class SnippetsConfiguration(
    /** Whether code completion should return VSCode-style snippets. */
    var enabled: Boolean = true
)

public data class CodegenConfiguration(
    /** Whether to enable code generation to a temporary build directory for Java interoperability. */
    var enabled: Boolean = false
)

public data class CompletionConfiguration(
    val snippets: SnippetsConfiguration = SnippetsConfiguration(),
    var filteredTypes: List<String> = listOf(
        "java.awt.*",
        "com.sun.*",
        "sun.*",
        "jdk.*",
        "org.graalvm.*",
        "io.micrometer.shaded.*"
    )
) {
    init {
        validateFilteredTypes(filteredTypes)
    }

    fun isTypeFiltered(fqName: FqName): Boolean = isTypeFiltered(fqName, filteredTypes)
}

internal fun validateFilteredTypes(patterns: List<String>) {
    patterns.forEach { raw ->
        val pattern = raw.trim()
        check(pattern == raw) { "filteredTypes entry '$raw' has leading or trailing whitespace" }
        check(pattern.isNotEmpty()) { "filteredTypes entry must not be empty" }
        check(pattern != ".") { "filteredTypes entry '$pattern' has no package prefix; use a full FQN pattern" }
        check(pattern != ".*") { "filteredTypes entry '.*' has no package prefix; use a full FQN pattern like 'com.example.*'" }
        check(pattern != "*") { "filteredTypes entry '*' is not a valid pattern; use 'foo.*' for wildcard or a full FQN for exact match" }
        check(!pattern.endsWith(".")) { "filteredTypes entry '$pattern' ends with '.'; use '${pattern}*' for wildcard or remove trailing dot for exact match" }
        if (pattern.endsWith(".*")) {
            val prefix = pattern.dropLast(2)
            check(prefix.isNotEmpty()) { "filteredTypes entry '$pattern' has empty prefix; use a full FQN pattern" }
            check(!prefix.endsWith(".")) { "filteredTypes entry '$pattern' has trailing dot before wildcard; use '${prefix.dropLast(1)}.*' instead" }
            check('*' !in prefix) { "filteredTypes entry '$pattern' contains '*' in prefix; wildcards are only supported as '.*' suffix" }
        } else {
            check('*' !in pattern) { "filteredTypes entry '$pattern' contains '*' without '.*' suffix; use '.*' for wildcards or remove '*' for exact match" }
        }
    }
}

fun isTypeFiltered(fqName: FqName, filteredTypes: List<String>): Boolean {
    if (filteredTypes.isEmpty()) return false
    val fqString = fqName.asString()
    return filteredTypes.any { pattern ->
        if (pattern.endsWith(".*")) {
            fqString.startsWith(pattern.dropLast(1))
        } else {
            fqString == pattern
        }
    }
}

public data class DiagnosticsConfiguration(
    /** Whether diagnostics are enabled. */
    var enabled: Boolean = true,
    /** The minimum severity of enabled diagnostics. */
    var level: DiagnosticSeverity = DiagnosticSeverity.Hint,
    /** The time interval between subsequent lints in ms. */
    var debounceTime: Long = 350L
)

public data class JVMConfiguration(
    /** Which JVM target the Kotlin compiler uses. See Compiler.jvmTargetFrom for possible values. */
    var target: String = "default"
)

public data class CompilerConfiguration(
    val jvm: JVMConfiguration = JVMConfiguration()
)

public data class IndexingConfiguration(
    /** Whether an index of global symbols should be built in the background. */
    var enabled: Boolean = true
)

public data class CacheConfiguration(
    /** Maximum number of source files to keep in memory. */
    var maxSourceFiles: Int = 500,
    /** Maximum number of decompiled external files to cache. */
    var maxCachedTempFiles: Int = 100,
    /** Whether to cache workspace state across restarts to skip initial compilation. */
    var workspaceCacheEnabled: Boolean = true
)

public data class ExternalSourcesConfiguration(
    /** Whether kls-URIs should be sent to the client to describe classes in JARs. */
    var useKlsScheme: Boolean = false,
    /** Whether external classes should be automatically converted to Kotlin. */
    var autoConvertToKotlin: Boolean = false,
    /** Glob patterns for directories and files to exclude from indexing.
     *  Defaults include common build output directories like 'build' and 'target'.
     *  These patterns match against any path segment, not just the root level.
     *  Examples: "build", "target", "node_modules", ".gradle" */
    var excludedPatterns: List<String> = emptyList(),
    /** Paths to directories containing generated sources that should be indexed
     *  despite being under an excluded parent directory (e.g. build/ or target/).
     *  Paths are relative to workspace root and match as prefixes.
     *  Examples: "build/generated", "target/generated-sources" */
    var generatedSourceRoots: List<String> = emptyList(),
    /** Absolute path to a JDK `lib/src.zip` to use for `java.*` / `javax.*` documentation
     *  lookups. When unset, the server auto-detects a `src.zip` from common JDK install
     *  locations (e.g. `/usr/lib/jvm/java-21-openjdk/lib/src.zip`). Set this when your
     *  running JVM's `lib/src.zip` is missing or when you want docs from a specific JDK.
     *  When the chosen src.zip's major version differs from the running JVM's, the hover
     *  output is annotated with the version mismatch. */
    var jdkSourceOverride: String? = null
)

data class InlayHintsConfiguration(
    var typeHints: Boolean = false,
    var parameterHints: Boolean = false,
    var chainedHints: Boolean = false
)

data class KtfmtConfiguration(
    var style: String = "google",
    var indent: Int = 4,
    var maxWidth: Int = 100,
    var continuationIndent: Int = 8,
    var removeUnusedImports: Boolean = true,
)

data class FormattingConfiguration(
    var formatter: String = "ktfmt",
    var ktfmt: KtfmtConfiguration = KtfmtConfiguration()
)

fun getStoragePath(params: InitializeParams): Path? {
    params.initializationOptions?.let { initializationOptions ->
        // Handle case where initializationOptions is an array instead of an object
        if (initializationOptions !is JsonObject) {
            LOG.warn("Initialization options is not an object, ignoring: {}", initializationOptions)
            return null
        }
        val gson = GsonBuilder().registerTypeHierarchyAdapter(Path::class.java, GsonPathConverter()).create()
        val options = gson.fromJson(initializationOptions as JsonElement, InitializationOptions::class.java)

        // NOTE: storagePath is deprecated. All persistent state now lives in
        // <workspaceRoot>/.kls/ regardless of this field.
        //
        // This WARN is logged once per initialize() call so clients
        // (e.g., nvim-lspconfig's default of storagePath = <projectRoot>) are
        // aware their override is ignored.
        if (options?.storagePath != null) {
            LOG.warn(
                "init_options.storagePath is deprecated and ignored."
                    + "ktlsp uses <workspaceRoot>/.kls/ for persistent state."
                    + "Please remove storagePath from your editor config."
            )
        }

        return options?.storagePath
    }

    return null
}

data class InitializationOptions(
    // NOTE: Deprecated and ignored.
    //
    // The LSP always uses <workspaceRoot>/.kls/ for the database.
    //
    // This field is kept for backward compatibility. Clients that send it
    // (e.g., nvim-lspconfig's default) won't error, but the value is discarded
    // and a WARN is logged.
    val storagePath: Path?,
    // Additional paths to exclude from source files.
    val additionalSourceExclusions: List<String>?
)

class GsonPathConverter : JsonDeserializer<Path?> {

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, type: Type?, context: JsonDeserializationContext?): Path? {
        return try {
            Paths.get(json.asString)
        } catch (ex: InvalidPathException) {
            LOG.printStackTrace(ex)
            null
        }
    }
}

public data class Configuration(
    val codegen: CodegenConfiguration = CodegenConfiguration(),
    val compiler: CompilerConfiguration = CompilerConfiguration(),
    val completion: CompletionConfiguration = CompletionConfiguration(),
    val diagnostics: DiagnosticsConfiguration = DiagnosticsConfiguration(),
    val scripts: ScriptsConfiguration = ScriptsConfiguration(),
    val indexing: IndexingConfiguration = IndexingConfiguration(),
    val cache: CacheConfiguration = CacheConfiguration(),
    val externalSources: ExternalSourcesConfiguration = ExternalSourcesConfiguration(),
    val inlayHints: InlayHintsConfiguration = InlayHintsConfiguration(),
    val formatting: FormattingConfiguration = FormattingConfiguration()
)
