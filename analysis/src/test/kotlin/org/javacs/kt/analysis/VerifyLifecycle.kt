package org.javacs.kt.analysis

import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import java.nio.file.Files
import java.nio.file.Path

internal fun verifyLifecycle(root: Path, jdk: Path, classpath: List<Path>) {
    val app = root.resolve("app/Main.kt")
    val library = root.resolve("library/Library.kt")
    val added = root.resolve("library/Added.kt")
    val appText = Files.readString(app)
    val libraryText = Files.readString(library)
    val language = LanguageVersionSettingsImpl(LanguageVersion.KOTLIN_2_2, ApiVersion.KOTLIN_2_2)
    val binaries = classpath.map { ModuleDependency.Binary(it) }
    val specs = listOf(
        SourceModuleSpec("app", mapOf(app to appText), listOf(ModuleDependency.Source("library")) + binaries,
            emptyList(), jdk, language, JvmTarget.JVM_17),
        SourceModuleSpec("library", mapOf(library to libraryText), binaries, emptyList(), jdk, language, JvmTarget.JVM_17),
    )
    val workspace = FirWorkspace(specs)
    workspace.use {
        fun target(expected: String?) {
            val result = workspace.read(app, ::inspect)
            check(result.target == expected) { "Unexpected lifecycle resolution: $result" }
            check((expected != null) == result.errors.isEmpty()) { "Unexpected lifecycle diagnostics: $result" }
        }
        val originalPsi = workspace.read(library) { it }
        var parses = workspace.parseCount
        workspace.addSource("library", added, "package dependency\nfun added(): Int = 3\n")
        check(workspace.parseCount == parses + 1)
        check(workspace.read(library) { it === originalPsi })
        val openApp = "package client\nimport dependency.added\nfun value(): Int = added()\n"
        workspace.update(app, openApp)
        target("dependency.added")
        parses = workspace.parseCount
        workspace.removeSource(added)
        check(workspace.parseCount == parses)
        check(runCatching { workspace.read(added) { it.text } }.isFailure)
        check(workspace.read(library) { it === originalPsi })
        target(null)
        workspace.addSource("library", added, "package dependency\nfun added(): Int = 4\n")
        target("dependency.added")
        println("PASS incremental source addition/removal: dependency invalidation, stable untouched PSI")

        val nextSpecs = specs.map { spec ->
            if (spec.name == "library") spec.copy(files = spec.files + (added to "package dependency\nfun added(): Int = 5\n")) else spec
        }
        val stamp = workspace.read(app) { it.virtualFile.modificationStamp }
        workspace.reimport(nextSpecs)
        check(workspace.generation == 1L)
        check(workspace.read(app) { it.text == openApp && it.virtualFile.modificationStamp == stamp })
        target("dependency.added")
        val currentPsi = workspace.read(app) { it }
        check(runCatching { workspace.reimport(nextSpecs + nextSpecs.first().copy(name = "overlap")) }.isFailure)
        check(workspace.generation == 1L)
        check(workspace.read(app) { it === currentPsi })
        target("dependency.added")
        workspace.reimport(nextSpecs.map { if (it.name == "app") it.copy(dependencies = binaries) else it })
        target(null)
        workspace.reimport(nextSpecs)
        target("dependency.added")
        println("PASS transactional reimport: retained open text/version, refreshed dependency graph, failed candidate rollback")

        workspace.reimport(nextSpecs.filterNot { it.name == "app" })
        check(runCatching { workspace.read(app) { it.text } }.isFailure)
        val inactiveEdit = openApp + "// edited while inactive\n"
        val previousCount = workspace.parseCount
        workspace.update(app, inactiveEdit)
        check(workspace.parseCount == previousCount)
        workspace.reimport(nextSpecs)
        check(workspace.read(app) { it.text == inactiveEdit && it.virtualFile.modificationStamp > stamp })
        target("dependency.added")
        workspace.closeDocument(app, appText)
        target("dependency.answer")
        workspace.reimport(nextSpecs.map { if (it.name == "app") it.copy(files = mapOf(app to openApp)) else it })
        target("dependency.added")
        check(Files.readString(app) == appText && Files.readString(library) == libraryText && !Files.exists(added))
        println("PASS inactive-variant open-text retention and document close; source disk unchanged")
    }
    workspace.close()
    check(runCatching { workspace.read(app) { it.text } }.isFailure)
    check(runCatching { workspace.reimport(specs) }.isFailure)
    println("PASS idempotent workspace close and rejected use after close")
}
