@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

package org.javacs.kt.analysis

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.declarations.*
import org.jetbrains.kotlin.analysis.api.platform.packages.*
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.nio.file.Path

internal class SourceIndex(private val project: Project, files: List<KtFile>) {
    internal inner class Entry(val file: KtFile) {
        val declarations = KotlinStandaloneDeclarationProviderFactory(project, listOf(file), skipBuiltins = true)
        val annotations = KotlinStandaloneAnnotationsResolverFactory(project, listOf(file))
        val classes = file.collectDescendantsOfType<KtClassOrObject>()
    }

    private val entries = linkedMapOf<Path, Entry>()
    private val packages = hashMapOf<FqName, MutableMap<Path, Entry>>()
    private var generation = 0L
    private val builtins = KotlinStandaloneDeclarationProviderFactory(project, emptyList())
    private class PackageNode {
        val files = hashSetOf<KtFile>()
        val children = hashSetOf<Name>()
    }
    private val packageTree = hashMapOf<FqName, PackageNode>()

    init {
        builtins.getAdditionalCreatedKtFiles().forEach { updatePackage(it, add = true) }
        files.forEach { publish(prepare(it)) }
    }

    fun prepare(file: KtFile): Entry = Entry(file)

    fun publish(entry: Entry) {
        val path = (entry.file.virtualFile as SnapshotFile).sourcePath
        remove(path)
        entries[path] = entry
        packages.getOrPut(entry.file.packageFqName) { linkedMapOf() }[path] = entry
        updatePackage(entry.file, add = true)
        generation++
    }

    fun remove(path: Path) {
        entries.remove(path)?.let { old ->
            updatePackage(old.file, add = false)
            packages.getValue(old.file.packageFqName).let { bucket ->
                bucket.remove(path)
                if (bucket.isEmpty()) packages.remove(old.file.packageFqName)
            }
            generation++
        }
    }

    private fun updatePackage(file: KtFile, add: Boolean) {
        var name = file.packageFqName
        while (true) {
            val node = if (add) packageTree.getOrPut(name) { PackageNode() } else packageTree.getValue(name)
            if (add) node.files.add(file) else node.files.remove(file)
            if (node.files.isEmpty()) packageTree.remove(name)
            if (name.isRoot) break
            val parent = name.parent()
            if (add) packageTree.getOrPut(parent) { PackageNode() }.children.add(name.shortName())
            else if (node.files.isEmpty()) packageTree[parent]?.children?.remove(name.shortName())
            name = parent
        }
    }

    val declarations = object : KotlinDeclarationProviderFactory {
        override fun createDeclarationProvider(scope: GlobalSearchScope, contextualModule: KaModule?): KotlinDeclarationProvider =
            object : KotlinDeclarationProvider {
                private val builtinProvider = builtins.createDeclarationProvider(scope, contextualModule)
                private var cachedGeneration = -1L
                private val providers = hashMapOf<FqName, KotlinDeclarationProvider>()
                @Synchronized private fun inPackage(name: FqName): KotlinDeclarationProvider {
                    if (cachedGeneration != generation) {
                        providers.clear()
                        cachedGeneration = generation
                    }
                    return providers.getOrPut(name) {
                        KotlinCompositeDeclarationProvider.factory.create(
                            listOf(builtinProvider) + packages[name].orEmpty().values
                                .filter { scope.contains(it.file.virtualFile) }
                                .map { it.declarations.createDeclarationProvider(scope, contextualModule) })
                    }
                }
                override fun getClassLikeDeclarationByClassId(classId: ClassId) = inPackage(classId.packageFqName).getClassLikeDeclarationByClassId(classId)
                override fun getAllClassesByClassId(classId: ClassId) = inPackage(classId.packageFqName).getAllClassesByClassId(classId)
                override fun getAllTypeAliasesByClassId(classId: ClassId) = inPackage(classId.packageFqName).getAllTypeAliasesByClassId(classId)
                override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName) = inPackage(packageFqName).getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName)
                override fun getTopLevelCallableNamesInPackage(packageFqName: FqName) = inPackage(packageFqName).getTopLevelCallableNamesInPackage(packageFqName)
                override fun getTopLevelProperties(callableId: CallableId) = inPackage(callableId.packageName).getTopLevelProperties(callableId)
                override fun getTopLevelFunctions(callableId: CallableId) = inPackage(callableId.packageName).getTopLevelFunctions(callableId)
                override fun getTopLevelCallableFiles(callableId: CallableId) = inPackage(callableId.packageName).getTopLevelCallableFiles(callableId)
                override fun findFilesForFacadeByPackage(packageFqName: FqName) = inPackage(packageFqName).findFilesForFacadeByPackage(packageFqName)
                override fun findFilesForFacade(facadeFqName: FqName) = inPackage(facadeFqName.parent()).findFilesForFacade(facadeFqName)
                override fun findInternalFilesForFacade(facadeFqName: FqName) = inPackage(facadeFqName.parent()).findInternalFilesForFacade(facadeFqName)
                override fun findFilesForScript(scriptFqName: FqName) = inPackage(scriptFqName.parent()).findFilesForScript(scriptFqName)
                override val hasSpecificClassifierPackageNamesComputation = false
                override val hasSpecificCallablePackageNamesComputation = false
                override fun computePackageNames(): Set<String> =
                    builtinProvider.computePackageNames().orEmpty() + packages.entries
                        .filter { (_, entries) -> entries.values.any { scope.contains(it.file.virtualFile) } }
                        .map { it.key.asString() }
            }
    }

    val packageProvider = object : KotlinPackageProviderFactory {
        override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider =
            object : KotlinPackageProviderBase(project, searchScope) {
                override fun doesKotlinOnlyPackageExist(packageFqName: FqName) = packageFqName.isRoot ||
                    packageTree[packageFqName]?.files.orEmpty().any { searchScope.contains(it.virtualFile) }
                override fun getKotlinOnlySubpackageNames(packageFqName: FqName) = packageTree[packageFqName]?.children.orEmpty()
                    .filterTo(hashSetOf()) { doesKotlinOnlyPackageExist(packageFqName.child(it)) }
            }
    }

    val annotations = object : KotlinAnnotationsResolverFactory {
        override fun createAnnotationResolver(searchScope: GlobalSearchScope): KotlinAnnotationsResolver = object : KotlinAnnotationsResolver {
            private val syntax = KotlinStandaloneAnnotationsResolverFactory(project, emptyList()).createAnnotationResolver(searchScope)
            private var cachedGeneration = -1L
            private var resolvers = emptyList<KotlinAnnotationsResolver>()
            @Synchronized private fun currentResolvers(): List<KotlinAnnotationsResolver> {
                if (cachedGeneration != generation) {
                    resolvers = entries.values.filter { searchScope.contains(it.file.virtualFile) }
                        .map { it.annotations.createAnnotationResolver(searchScope) }
                    cachedGeneration = generation
                }
                return resolvers
            }
            override fun declarationsByAnnotation(annotationClassId: ClassId): Set<KtAnnotated> = currentResolvers()
                .flatMapTo(hashSetOf()) { it.declarationsByAnnotation(annotationClassId) }
            override fun annotationsOnDeclaration(declaration: KtAnnotated): Set<ClassId> {
                return syntax.annotationsOnDeclaration(declaration)
            }
        }
    }

    val inheritors = object : KotlinDirectInheritorsProvider {
        override fun getDirectKotlinInheritors(ktClass: KtClass, scope: GlobalSearchScope, includeLocalInheritors: Boolean): Iterable<KtClassOrObject> {
            val baseId = ktClass.getClassId()
            val baseModule = KotlinProjectStructureProvider.getModule(project, ktClass, useSiteModule = null)
            return entries.values.asSequence().filter { scope.contains(it.file.virtualFile) }.flatMap { it.classes.asSequence() }
                .filter { includeLocalInheritors || it.getClassId() != null }
                .filter { candidate -> analyze(candidate) {
                    candidate.classSymbol?.superTypes.orEmpty().any { type ->
                        val symbol = type.expandedSymbol
                        symbol != null && symbol.containingModule == baseModule &&
                            (symbol.psi === ktClass || (baseId != null && symbol.classId == baseId))
                    }
                } }.toList()
        }
    }
}
