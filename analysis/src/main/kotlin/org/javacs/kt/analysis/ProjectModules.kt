@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

package org.javacs.kt.analysis

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
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Path

internal data class SourceModuleSpec(
    val name: String,
    val files: Map<Path, String>,
    val regularDependencies: List<String>,
    val friendDependencies: List<String>,
    /** Null deliberately omits the JDK; target boot classes then belong in binaryClasspath. */
    val jdkHome: Path?,
    val binaryClasspath: List<Path>,
    val language: LanguageVersionSettings,
)

internal class SourceModule(
    override val project: Project,
    override val name: String,
    val paths: Set<Path>,
    override val directRegularDependencies: List<KaModule>,
    override val directFriendDependencies: List<KaModule>,
    override val languageVersionSettings: LanguageVersionSettings,
    private val snapshots: SourceSnapshots,
) : KaModuleBase(), KaSourceModule {
    override val targetPlatform = JvmPlatforms.defaultJvmPlatform
    override val directDependsOnDependencies = emptyList<KaModule>()
    override val psiRoots: List<PsiFileSystemItem> get() = paths.map { snapshots[it].file }
    override val stableModuleName: String get() = name
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
        val libraries = hashMapOf<List<Path>, KaLibraryModule>()
        val visiting = linkedSetOf<String>()

        fun create(name: String): SourceModule {
            sourceModules[name]?.let { return it }
            val spec = requireNotNull(definitions[name]) { "Unknown module dependency: $name" }
            require(name.isNotBlank()) { "Empty module identity" }
            require(visiting.add(name)) { "Cyclic module dependencies: ${visiting.joinToString(" -> ")} -> $name" }
            for (dependencies in listOf(spec.regularDependencies, spec.friendDependencies)) {
                require(dependencies.size == dependencies.toSet().size) { "Duplicate dependency in module $name" }
            }
            val regular = spec.regularDependencies.map(::create)
            val friends = spec.friendDependencies.map(::create)
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
            val roots = spec.binaryClasspath.map { root ->
                require(root.isAbsolute) { "Binary classpath must be absolute: $root" }
                root.normalize()
            }
            val binaries = if (roots.isEmpty()) null else libraries.getOrPut(roots) {
                builder.addModule(builder.buildKtLibraryModule {
                    libraryName = "classpath:$name"
                    platform = JvmPlatforms.defaultJvmPlatform
                    roots.forEach(::addBinaryRoot)
                })
            }
            val paths = spec.files.keys.mapTo(linkedSetOf()) { it.toAbsolutePath().normalize() }
            val module = SourceModule(project, spec.name, paths,
                listOfNotNull(sdk, binaries) + regular, friends, spec.language, snapshots)
            for ((path, text) in spec.files) {
                val snapshot = snapshots.parse(path, text, 0)
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

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
        val file = element.containingFile?.virtualFile
        return if (file is SnapshotFile) owners.getValue(file.sourcePath) else binaryRouting.getModule(element, useSiteModule)
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> = binaryRouting.getImplementingModules(module)

    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule =
        error("Module lookup requires a source or library file")
}
