/*
 * Bootstrap composition follows Kotlin StandaloneAnalysisAPISessionBuilder:
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Licensed under Apache-2.0; https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt
 */
@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package org.javacs.kt.analysis

import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.declarations.*
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinAlwaysAccessibleLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleStateModificationKind
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModuleStateModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.packages.*
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.standalone.base.KotlinStandalonePlatformSettings
import org.jetbrains.kotlin.analysis.api.standalone.base.modification.KotlinStandaloneModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.permissions.KotlinStandaloneAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.*
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// Load the pinned engine's genuine descriptor, without its static standalone source services.
private object FirEngine : AnalysisApiSimpleServiceRegistrar() {
    private const val DESCRIPTOR = "/META-INF/analysis-api/analysis-api-fir.xml"
    override fun registerApplicationServices(application: MockApplication) {
        PluginStructureProvider.registerApplicationServices(application, DESCRIPTOR)
        application.registerService(KotlinAnalysisPermissionOptions::class.java, KotlinStandaloneAnalysisPermissionOptions::class.java)
    }
    override fun registerProjectExtensionPoints(project: MockProject) = PluginStructureProvider.registerProjectExtensionPoints(project, DESCRIPTOR)
    override fun registerProjectServices(project: MockProject) {
        PluginStructureProvider.registerProjectServices(project, DESCRIPTOR)
        PluginStructureProvider.registerProjectListeners(project, DESCRIPTOR)
    }
}

internal class FirProject(specs: List<SourceModuleSpec>, versions: Map<Path, Long>) : AutoCloseable {
    private val disposable = Disposer.newDisposable("kotlin-analysis")
    private val snapshots: SourceSnapshots
    private val index: SourceIndex
    private val modules: ProjectModules
    private var closed = false
    val parseCount: Int get() = snapshots.parseCount

    init {
        try {
            System.setProperty("java.awt.headless", "true")
            setupIdeaStandaloneExecution()
            val environment = StandaloneProjectFactory.createProjectEnvironment(disposable, KotlinCoreApplicationEnvironmentMode.fromUnitTestModeFlag(false))
            val project = environment.project
            ApplicationServiceRegistration.register(environment.environment.application, listOf(FirEngine), Unit)
            snapshots = SourceSnapshots(project, versions)
            modules = ProjectModules(environment.environment, project, specs, snapshots)
            StandaloneProjectFactory.registerServicesForProjectEnvironment(environment, modules)
            FirEngine.registerProjectExtensionPoints(project)
            FirEngine.registerProjectServices(project)
            FirStandaloneServiceRegistrar.registerProjectModelServices(project, disposable)
            project.registerService(KotlinLifetimeTokenFactory::class.java, KotlinAlwaysAccessibleLifetimeTokenFactory::class.java)
            project.registerService(KotlinPlatformSettings::class.java, KotlinStandalonePlatformSettings::class.java)
            project.registerService(KotlinModificationTrackerFactory::class.java, KotlinStandaloneModificationTrackerFactory::class.java)
            index = SourceIndex(project, snapshots.files)
            project.registerService(KotlinDeclarationProviderFactory::class.java, index.declarations)
            project.registerService(KotlinPackageProviderFactory::class.java, index.packageProvider)
            project.registerService(KotlinAnnotationsResolverFactory::class.java, index.annotations)
            project.registerService(KotlinDirectInheritorsProvider::class.java, index.inheritors)
            project.registerService(KotlinDeclarationProviderMerger::class.java, object : KotlinDeclarationProviderMerger {
                override fun merge(providers: List<KotlinDeclarationProvider>) = KotlinCompositeDeclarationProvider.factory.create(providers)
            })
            project.registerService(KotlinPackageProviderMerger::class.java, object : KotlinPackageProviderMerger {
                override fun merge(providers: List<KotlinPackageProvider>) = KotlinCompositePackageProvider.factory.create(providers)
            })
            val roots = StandaloneProjectFactory.getAllBinaryRoots(modules.allModules, environment.environment)
            val packageParts = StandaloneProjectFactory.createPackagePartsProvider(roots)
            project.registerService(KotlinPackagePartProviderFactory::class.java, object : KotlinPackagePartProviderFactory {
                private val cache = ConcurrentHashMap<GlobalSearchScope, PackagePartProvider>()
                override fun createPackagePartProvider(scope: GlobalSearchScope): PackagePartProvider =
                    cache.computeIfAbsent(scope) { packageParts(it) }
            })
        } catch (failure: Throwable) {
            Disposer.dispose(disposable)
            throw failure
        }
    }

    fun <T> read(path: Path, action: (KtFile) -> T): T = ApplicationManager.getApplication().runReadAction(Computable {
        check(!closed)
        action(snapshots[path].file)
    })

    fun update(path: Path, text: String) = ApplicationManager.getApplication().runWriteAction {
        check(!closed)
        val old = snapshots[path]
        val next = snapshots.parse(path, text, old.version + 1)
        val nextIndex = index.prepare(next.file)
        KaSourceModificationService.getInstance(old.file.project).handleElementModification(old.file, KaElementModificationType.Unknown)
        snapshots.publish(next)
        index.publish(nextIndex)
    }

    override fun close() = ApplicationManager.getApplication().runWriteAction {
        if (!closed) { closed = true; Disposer.dispose(disposable) }
    }

    fun add(moduleName: String, path: Path, text: String, version: Long) = ApplicationManager.getApplication().runWriteAction {
        check(!closed)
        val module = modules.sourceModule(moduleName)
        require(!modules.contains(path)) { "Source already has an owner: $path" }
        val next = snapshots.parse(path, text, version)
        val nextIndex = index.prepare(next.file)
        module.publishModuleStateModificationEvent(KotlinModuleStateModificationKind.UPDATE)
        modules.add(module, path)
        snapshots.publish(next)
        index.publish(nextIndex)
    }

    fun remove(path: Path) = ApplicationManager.getApplication().runWriteAction {
        check(!closed)
        val module = modules.owner(path)
        module.publishModuleStateModificationEvent(KotlinModuleStateModificationKind.UPDATE)
        modules.remove(path)
        snapshots.remove(path)
        index.remove(path)
    }
}
