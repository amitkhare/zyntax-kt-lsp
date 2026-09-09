@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

package org.javacs.kt.analysis

import org.javacs.kt.project.GradleCompilerOptions
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.KotlinStaticProjectStructureProvider
import org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Path

internal sealed interface ModuleDependency {
    data class Source(val name: String) : ModuleDependency
    data class Binary(val path: Path) : ModuleDependency
}

internal fun ModuleDependency.normalized(): ModuleDependency = when (this) {
    is ModuleDependency.Source -> this
    is ModuleDependency.Binary -> copy(path = canonical(path))
}

internal data class SourceModuleSpec(
    val name: String,
    val files: Map<Path, String>,
    val dependencies: List<ModuleDependency>,
    val friends: List<ModuleDependency>,
    /** Null deliberately omits the JDK; target boot classes then belong in binary dependencies. */
    val jdkHome: Path?,
    val language: LanguageVersionSettings,
    val jvmTarget: JvmTarget,
    val stableName: String = name,
    /** Retained for request-level diagnostic policy; code generation is not part of this module. */
    val compilerOptions: GradleCompilerOptions? = null,
)

internal class SourceModule(
    override val project: Project,
    override val name: String,
    val paths: MutableSet<Path>,
    override val directRegularDependencies: List<KaModule>,
    override val directFriendDependencies: List<KaModule>,
    override val languageVersionSettings: LanguageVersionSettings,
    private val snapshots: SourceSnapshots,
    jvmTarget: JvmTarget,
    override val stableModuleName: String,
) : KaModuleBase(), KaSourceModule {
    override val targetPlatform = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)
    override val directDependsOnDependencies = emptyList<KaModule>()
    override val psiRoots: List<PsiFileSystemItem> get() = paths.map { snapshots[it].file }
    override val baseContentScope = object : GlobalSearchScope(project) {
        override fun contains(file: VirtualFile): Boolean = file is SnapshotFile && file.sourcePath in paths
        override fun isSearchInModuleContent(module: com.intellij.openapi.module.Module): Boolean = true
        override fun isSearchInLibraries(): Boolean = false
    }
}

internal class ProjectModules(
    environment: CoreApplicationEnvironment,
    project: Project,
    specs: List<SourceModuleSpec>,
    private val snapshots: SourceSnapshots,
) : KotlinStaticProjectStructureProvider() {
    private val sourceModules = linkedMapOf<String, SourceModule>()
    private val owners = hashMapOf<Path, SourceModule>()
    private val binaryRouting: KotlinStaticProjectStructureProvider
    override val allModules: List<KaModule> get() = binaryRouting.allModules
    override val allSourceFiles: List<PsiFileSystemItem> get() = snapshots.files

    init {
        val builder = KtModuleProviderBuilder(environment, project)
        builder.platform = JvmPlatforms.defaultJvmPlatform
        val definitions = specs.associateBy { it.name }
        require(definitions.size == specs.size) { "Duplicate module identity" }
        val sdks = hashMapOf<Path, KaLibraryModule>()
        val libraries = hashMapOf<Path, KaLibraryModule>()
        val visiting = linkedSetOf<String>()

        fun create(name: String): SourceModule {
            sourceModules[name]?.let { return it }
            val spec = requireNotNull(definitions[name]) { "Unknown module dependency: $name" }
            require(name.isNotBlank()) { "Empty module identity" }
            require(visiting.add(name)) { "Cyclic module dependencies: ${visiting.joinToString(" -> ")} -> $name" }
            require(spec.jvmTarget in JvmTarget.supportedValues()) { "Unsupported JVM target: ${spec.jvmTarget}" }
            val dependencies = spec.dependencies.map { it.normalized() }
            val friendDependencies = spec.friends.map { it.normalized() }
            for (references in listOf(dependencies, friendDependencies)) {
                require(references.size == references.toSet().size) { "Duplicate dependency in module $name" }
            }
            require(friendDependencies.all { it in dependencies }) { "Friends must be regular dependencies in module $name" }
            val sdk = spec.jdkHome?.let { home ->
                require(home.isAbsolute) { "JDK home must be absolute: $home" }
                sdks.getOrPut(home.normalize()) {
                    builder.addModule(builder.buildKtSdkModule {
                        libraryName = "jdk:$home"
                        platform = JvmPlatforms.defaultJvmPlatform
                        addBinaryRootsFromJdkHome(home.normalize(), isJre = false)
                    })
                }
            }
            fun library(root: Path) = libraries.getOrPut(root) {
                builder.addModule(builder.buildKtLibraryModule {
                    libraryName = "binary:$root"
                    platform = JvmPlatforms.defaultJvmPlatform
                    addBinaryRoot(root)
                })
            }
            val dependencyModules = dependencies.associateWith { dependency ->
                when (dependency) {
                    is ModuleDependency.Source -> create(dependency.name)
                    is ModuleDependency.Binary -> library(dependency.path)
                }
            }
            val regular = dependencies.map(dependencyModules::getValue)
            val friends = friendDependencies.map(dependencyModules::getValue)
            val paths = spec.files.keys.mapTo(linkedSetOf(), ::canonical)
            val module = SourceModule(project, spec.name, paths,
                listOfNotNull(sdk) + regular, friends,
                spec.language, snapshots, spec.jvmTarget, spec.stableName)
            for ((path, text) in spec.files) {
                val snapshot = snapshots.initial(path, text)
                require(snapshot.path !in owners) { "Ambiguous source ownership: ${snapshot.path}" }
                owners[snapshot.path] = module
                snapshots.publish(snapshot)
            }
            sourceModules[spec.name] = module
            builder.addModule(module)
            visiting.remove(name)
            return module
        }
        specs.forEach { create(it.name) }
        binaryRouting = builder.build()
    }

    fun sourceModule(name: String): SourceModule = sourceModules.getValue(name)
    fun contains(path: Path): Boolean = canonical(path) in owners
    fun owner(path: Path): SourceModule = owners.getValue(canonical(path))
    fun add(module: SourceModule, path: Path) {
        module.paths.add(canonical(path))
        owners[canonical(path)] = module
    }
    fun remove(path: Path) {
        owners.remove(canonical(path))!!.paths.remove(canonical(path))
    }

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
        val file = element.containingFile?.virtualFile
        return if (file is SnapshotFile) owners.getValue(file.sourcePath) else binaryRouting.getModule(element, useSiteModule)
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> = binaryRouting.getImplementingModules(module)

    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule =
        error("Module lookup requires a source or library file")
}
