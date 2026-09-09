package org.javacs.kt.analysis

import org.javacs.kt.project.GradleCompilerOptions
import org.javacs.kt.project.GradleProjectModel
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.toLanguageVersionSettings
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.JvmTarget
import java.nio.file.Path

internal data class CompilationId(val buildRoot: Path, val compilation: String) {
    init {
        require(buildRoot == canonical(buildRoot)) { "Build identity must be normalized" }
        require(compilation.isNotBlank()) { "Empty compilation identity" }
    }
    val moduleName: String get() = "${buildRoot.toUri()}#$compilation"
}

/** Missing public Gradle inputs must be supplied, not inferred from the server/Java task. */
internal data class CompilationAnalysisInputs(
    val compilerVersion: String,
    val kotlinJdkHome: Path?,
    val compilerDefaults: GradleCompilerOptions,
    val regularDependencies: List<CompilationId>,
)

/** Only selected compilations become modules. The original model retains inactive variants. */
internal fun evaluatedModules(
    builds: List<GradleProjectModel>,
    selected: Map<CompilationId, CompilationAnalysisInputs>,
    readText: (Path) -> String,
): List<SourceModuleSpec> {
    require(builds.map { canonical(it.buildRoot) }.distinct().size == builds.size) { "Duplicate evaluated build" }
    val compilations = builds.flatMap { build ->
        build.compilations.map { CompilationId(canonical(build.buildRoot), it.id) to it }
    }
    require(compilations.map { it.first }.distinct().size == compilations.size) { "Duplicate evaluated compilation" }
    val available = compilations.toMap()
    return selected.map { (id, inputs) ->
        val compilation = requireNotNull(available[id]) { "Unknown selected compilation: $id" }
        require(inputs.compilerVersion == "2.2.21") { "Unsupported analysis compiler: ${inputs.compilerVersion}" }
        require(compilation.javaFiles.isEmpty()) { "Java source analysis is not integrated: $id" }
        require(compilation.compilerPluginClasspath.isEmpty() && compilation.compilerPluginOptions.isEmpty()) {
            "Compiler plugin analysis is not integrated: $id"
        }
        require(compilation.kotlinFiles.all { it.fileName.toString().endsWith(".kt") }) {
            "Script analysis needs per-script configuration: $id"
        }
        val options = compilation.compilerOptions.resolve(inputs.compilerDefaults)
        require(options.freeCompilerArgs!!.isEmpty()) { "Free compiler argument analysis is not integrated: $id" }
        val arguments = K2JVMCompilerArguments().apply {
            languageVersion = options.languageVersion
            apiVersion = options.apiVersion
            jvmTarget = options.jvmTarget
            jvmDefaultStable = options.jvmDefault
            noJdk = options.noJdk!!
            javaParameters = options.javaParameters!!
            allWarningsAsErrors = options.allWarningsAsErrors!!
            suppressWarnings = options.suppressWarnings!!
            verbose = options.verbose!!
            moduleName = options.moduleName
            optIn = options.optIn!!.toTypedArray()
            progressiveMode = options.progressiveMode!!
        }
        val messages = InputMessages()
        val language = arguments.toLanguageVersionSettings(messages)
        require(!messages.hasErrors()) { "Invalid compiler settings for $id: ${messages.errors.joinToString()}" }
        val target = requireNotNull(JvmTarget.fromString(arguments.jvmTarget!!)) { "Unsupported JVM target: ${arguments.jvmTarget}" }
        require(target in JvmTarget.supportedValues()) { "Unsupported JVM target: ${arguments.jvmTarget}" }
        val jdk = inputs.kotlinJdkHome?.let(::canonical)
        require((jdk == null) == arguments.noJdk) { "Explicit Kotlin JDK and noJdk disagree: $id" }

        val associated = compilation.associatedCompilations.map { CompilationId(id.buildRoot, it) }
        require(associated.distinct().size == associated.size) { "Duplicate associated compilation: $id" }
        val regular = (inputs.regularDependencies + associated).distinct()
        require(inputs.regularDependencies.distinct().size == inputs.regularDependencies.size) { "Duplicate source dependency: $id" }
        require(regular.all { it in selected }) { "Every source dependency must be selected: $id" }
        val outputOwners = mutableMapOf<Path, CompilationId>()
        for (dependency in regular) {
            for (output in available.getValue(dependency).outputs.map(::canonical)) {
                val previous = outputOwners.put(output, dependency)
                require(previous == null || previous == dependency) { "Ambiguous selected compilation output: $output" }
            }
        }
        // A source module replaces its first exact evaluated output slot, never an appended global bucket.
        val dependencies = compilation.classpath.map(::canonical).map { path ->
            outputOwners[path]?.let { ModuleDependency.Source(it.moduleName) } ?: ModuleDependency.Binary(path)
        }.distinct()
        require(regular.all { ModuleDependency.Source(it.moduleName) in dependencies }) {
            "Selected source dependency has no evaluated classpath output: $id"
        }
        val friendPaths = compilation.friendPaths.map(::canonical)
        val sourceFriends = associated.toSet() + friendPaths.mapNotNull(outputOwners::get)
        val binaryFriends = friendPaths.filterNot(outputOwners::containsKey)
        require(binaryFriends.all { ModuleDependency.Binary(it) in dependencies }) {
            "Friend binaries must belong to the evaluated classpath: $id"
        }
        val friends = dependencies.filter { dependency -> when (dependency) {
            is ModuleDependency.Source -> sourceFriends.any { it.moduleName == dependency.name }
            is ModuleDependency.Binary -> dependency.path in binaryFriends
        } }
        val files = compilation.kotlinFiles.map(::canonical)
        require(files.distinct().size == files.size) { "Duplicate evaluated source: $id" }
        SourceModuleSpec(id.moduleName, files.associateWith(readText), dependencies, friends,
            jdk, language, target, options.moduleName!!, options)
    }.also { specs ->
        val owners = mutableSetOf<Path>()
        require(specs.all { spec -> spec.files.keys.all(owners::add) }) { "Selected compilations have overlapping sources" }
    }
}

/** Both sides are explicit input data; no Gradle/Kotlin version-derived defaults are invented. */
private fun GradleCompilerOptions.resolve(defaults: GradleCompilerOptions) = GradleCompilerOptions(
    required(languageVersion, defaults.languageVersion, "languageVersion"),
    required(apiVersion, defaults.apiVersion, "apiVersion"),
    required(jvmTarget, defaults.jvmTarget, "jvmTarget"),
    required(jvmDefault, defaults.jvmDefault, "jvmDefault"),
    required(noJdk, defaults.noJdk, "noJdk"),
    required(javaParameters, defaults.javaParameters, "javaParameters"),
    required(allWarningsAsErrors, defaults.allWarningsAsErrors, "allWarningsAsErrors"),
    required(suppressWarnings, defaults.suppressWarnings, "suppressWarnings"),
    required(verbose, defaults.verbose, "verbose"),
    required(moduleName, defaults.moduleName, "moduleName"),
    required(optIn, defaults.optIn, "optIn").toList(),
    required(progressiveMode, defaults.progressiveMode, "progressiveMode"),
    required(freeCompilerArgs, defaults.freeCompilerArgs, "freeCompilerArgs").toList(),
)

private fun <T> required(value: T?, default: T?, name: String): T =
    requireNotNull(value ?: default) { "Missing explicit compiler input: $name" }

private class InputMessages : MessageCollector {
    val errors = mutableListOf<String>()
    override fun clear() = errors.clear()
    override fun hasErrors() = errors.isNotEmpty()
    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        if (severity.isError) errors.add(message)
    }
}
