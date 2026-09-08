package org.javacs.kt

import org.eclipse.lsp4j.DiagnosticSeverity

import org.jetbrains.kotlin.config.LanguageVersion
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
    /** JVM bytecode target; "default" uses the pinned compiler's default. */
    var target: String = "default"
)

public data class CompilerConfiguration(
    val jvm: JVMConfiguration = JVMConfiguration(),
    var languageVersion: String = LanguageVersion.LATEST_STABLE.versionString,
    /** Null selects the configured language version's API. */
    var apiVersion: String? = null
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
