@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

package org.javacs.kt.analysis

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

private data class Snapshot(val target: String?, val errors: List<String>)

private fun inspect(file: KtFile): Snapshot = analyze(file) {
    val call = checkNotNull(file.findDescendantOfType<KtCallExpression>())
    val target = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId?.asSingleFqName()?.asString()
    val errors = file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
        .filter { it.severity == KaSeverity.ERROR }
        .map { "${it.factoryName}: ${it.defaultMessage}" }
    Snapshot(target, errors)
}

fun main(args: Array<String>) {
    val exitCode = try {
        runProbe(args)
        0
    } catch (failure: Throwable) {
        failure.printStackTrace()
        1
    }
    exitProcess(exitCode)
}

private fun runProbe(args: Array<String>) {
    val root = Paths.get(args.single()).toAbsolutePath()
    val libraryPath = root.resolve("library/Library.kt")
    val appPath = root.resolve("app/Main.kt")
    val originalLibrary = Files.readString(libraryPath)
    val originalApp = Files.readString(appPath)
    val fixtureJdkHome = Paths.get(System.getProperty("java.home"))
    val fixtureClasspath = listOf(Paths.get(Unit::class.java.protectionDomain.codeSource.location.toURI()))
    try {
        for ((languageVersion, apiVersion) in listOf(
            LanguageVersion.KOTLIN_1_8 to ApiVersion.KOTLIN_1_8,
            LanguageVersion.KOTLIN_2_2 to ApiVersion.KOTLIN_2_2,
        )) {
            println("Kotlin language/API $languageVersion")
            val language = LanguageVersionSettingsImpl(languageVersion, apiVersion)
            FirWorkspace(listOf(
            SourceModuleSpec("app", mapOf(appPath to originalApp), listOf("library"), emptyList(),
                fixtureJdkHome, fixtureClasspath, language),
            SourceModuleSpec("library", mapOf(libraryPath to originalLibrary), emptyList(), emptyList(),
                fixtureJdkHome, fixtureClasspath, language),
        )).use { workspace ->
            fun verified(label: String, target: String) {
                val result = workspace.read(appPath, ::inspect)
                check(result.target == target && result.errors.isEmpty()) { "$label: $result" }
                println("PASS $label: ${result.target}")
            }
            fun edit(path: java.nio.file.Path, text: String) {
                val previousCount = workspace.parseCount
                workspace.update(path, text)
                check(workspace.parseCount == previousCount + 1) { "An edit reparsed other files" }
                workspace.read(path) { file ->
                    check(file.text == text)
                    check(file.virtualFile.url == path.toUri().toString()) { "Lost actual source URI" }
                }
            }
            verified("initial cross-module resolution", "dependency.answer")
            workspace.read(libraryPath) { file ->
                val module = KotlinProjectStructureProvider.getModule(file.project, file, useSiteModule = null)
                val packages = KotlinDeclarationProviderFactory.getInstance(file.project)
                    .createDeclarationProvider(module.contentScope, module).computePackageNames().orEmpty()
                check("dependency" in packages && "client" !in packages) { "Package index escaped its module scope: $packages" }
            }
            val initialDependency = workspace.read(libraryPath) { it }
            edit(appPath, "package client\nimport dependency.answer\nfun value(): String = answer()\n")
            check(workspace.read(libraryPath) { it === initialDependency })
            val invalid = workspace.read(appPath, ::inspect)
            check(invalid.errors.isNotEmpty()) { "Unsaved type error was not diagnosed: $invalid" }
            println("PASS unsaved diagnostic: ${invalid.errors}")
            edit(appPath, originalApp)
            verified("unsaved correction", "dependency.answer")
            val unchangedSource = workspace.read(appPath) { it }
            edit(libraryPath, "package dependency\nfun nextAnswer(): Int = 42\n")
            check(initialDependency.text == originalLibrary) { "Old snapshot was mutated" }
            check(workspace.read(appPath) { it === unchangedSource })
            val removed = workspace.read(appPath, ::inspect)
            check(removed.target == null && removed.errors.isNotEmpty()) { "Removed declaration remained visible: $removed" }
            edit(appPath, "package client\nimport dependency.nextAnswer\nfun value(): Int = nextAnswer()\n")
            verified("cross-module unsaved declaration rename", "dependency.nextAnswer")
            check(Files.readString(libraryPath) == originalLibrary)
            check(Files.readString(appPath) == originalApp)
            println("PASS source files unchanged on disk; one parse per edit")
        }
        }
    } finally {
        disposeGlobalStandaloneApplicationServices()
    }
}
