package org.javacs.kt.index

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.name.FqName
import org.javacs.kt.LOG
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.progress.Progress
import org.javacs.kt.classpath.ClassPathEntry
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

import kotlin.sequences.Sequence
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.javacs.kt.implementation.directSuperClassifiers
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val MAX_FQNAME_LENGTH = 511
private const val MAX_SHORT_NAME_LENGTH = 80
private const val MAX_URI_LENGTH = 511

private object Symbols : IntIdTable() {
    val fqName = varchar("fqname", length = MAX_FQNAME_LENGTH).index()
    val shortName = varchar("shortname", length = MAX_SHORT_NAME_LENGTH)
    val kind = integer("kind")
    val visibility = integer("visibility")
    val extensionReceiverType = varchar("extensionreceivertype", length = MAX_FQNAME_LENGTH).nullable()
    val location = optReference("location", Locations)

    @Suppress("unused")
    val byShortName = index("symbol_shortname_index", false, shortName)
}

/**
 * Direct supertype relationship between two symbols, keyed by FQN. Populated from
 * source [ClassDescriptor]s (whose supertypes include JAR types visible through the
 * module), enabling JAR-backed hierarchy queries.
 */
private object Supertypes : IntIdTable() {
    val symbolFqName = varchar("symbol_fqname", length = MAX_FQNAME_LENGTH).index()
    val superFqName = varchar("super_fqname", length = MAX_FQNAME_LENGTH).index()
}

private object Locations : IntIdTable() {
    val uri = varchar("uri", length = MAX_URI_LENGTH)
    val range = reference("range", Ranges)
}

private object Ranges : IntIdTable() {
    val start = reference("start", Positions)
    val end = reference("end", Positions)
}

private object Positions : IntIdTable() {
    val line = integer("line")
    val character = integer("character")
}

class SymbolEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SymbolEntity>(Symbols)

    var fqName by Symbols.fqName
    var shortName by Symbols.shortName
    var kind by Symbols.kind
    var visibility by Symbols.visibility
    var extensionReceiverType by Symbols.extensionReceiverType
    var location by LocationEntity optionalReferencedOn Symbols.location
}

class LocationEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LocationEntity>(Locations)

    var uri by Locations.uri
    var range by RangeEntity referencedOn Locations.range
}

class RangeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RangeEntity>(Ranges)

    var start by PositionEntity referencedOn Ranges.start
    var end by PositionEntity referencedOn Ranges.end
}

class PositionEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PositionEntity>(Positions)

    var line by Positions.line
    var character by Positions.character
}

/**
 * A global view of all available symbols across all packages.
 */
class SymbolIndex(
    private val databaseService: DatabaseService
) {
    private val updateIndexesLock = ReentrantLock()

    private val db: Database get() = checkNotNull(databaseService.db) { "Database is not initialized" }

    fun setup() {
        transaction(db) {
            SchemaUtils.create(Symbols, Locations, Ranges, Positions, Supertypes)
        }
    }

    var progressFactory: Progress.Factory = Progress.Factory.None

    /**
     * Rebuilds the entire index including external library symbols from JARs.
     */
    fun refreshWithClasspath(
        module: ModuleDescriptor,
        exclusions: Sequence<DeclarationDescriptor>,
        classPath: List<ClassPathEntry>
    ) {
        val started = System.currentTimeMillis()
        LOG.info("Updating full symbol index...")

        progressFactory.create("Indexing").thenApplyAsync { progress ->
            try {
                updateIndexesLock.withLock {
                    transaction(db) {
                        // Remove everything first.
                        Symbols.deleteAll()
                        Supertypes.deleteAll()

                        // Add symbols from Kotlin source files.
                        addDeclarations(allDescriptors(module, exclusions))

                        // Add external library symbols from JARs.
                        if (classPath.isNotEmpty()) {
                            val jarStarted = System.currentTimeMillis()
                            LOG.info("Scanning {} JARs for external symbols...", classPath.size)

                            val jarEntries = classPath.asSequence()
                                .map { it.compiledJar }
                                .filter { it.toFile().exists() }

                            var jarsScanned = 0
                            for (jarPath in jarEntries) {
                                addExternalSymbols(JarSymbolScanner.scanJarForSymbols(jarPath).asSequence())
                                jarsScanned++
                            }

                            val jarFinished = System.currentTimeMillis()
                            LOG.info("Indexed external symbols from {} JARs in {} ms", jarsScanned, jarFinished - jarStarted)
                        } else {
                            LOG.warn(
                                "classPath is empty, skipping JAR symbol scanning. Only indexing {} workspace symbols",
                                symbolCount()
                            )
                        }

                        val finished = System.currentTimeMillis()
                        val count = symbolCount()
                        LOG.info("Updated full symbol index in ${finished - started} ms! (${count} symbol(s))")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error while updating symbol index")
                LOG.printStackTrace(e)
            }

            progress.close()
        }
    }

    private fun symbolCount(): Long =
        Symbols.select(Symbols.fqName.count()).first()[Symbols.fqName.count()]

    // Removes a list of indexes and adds another list. Everything is done in the same transaction.
    fun updateIndexes(remove: Sequence<DeclarationDescriptor>, add: Sequence<DeclarationDescriptor>) {
        val started = System.currentTimeMillis()
        LOG.info("Updating symbol index...")

        updateIndexesLock.withLock {
            try {
                transaction(db) {
                    removeDeclarations(remove)
                    addDeclarations(add)

                    val finished = System.currentTimeMillis()
                    val count = symbolCount()
                    LOG.info("Updated symbol index in ${finished - started} ms! (${count} symbol(s))")
                }
            } catch (e: Exception) {
                LOG.error("Error while updating symbol index")
                LOG.printStackTrace(e)
            }
        }
    }

    private fun removeDeclarations(declarations: Sequence<DeclarationDescriptor>) =
        declarations.forEach { declaration ->
            val (descriptorFqn, extensionReceiverFqn) = getFqNames(declaration)

            if (validFqName(descriptorFqn) && (extensionReceiverFqn?.let { validFqName(it) } != false)) {
                val fqNameStr = descriptorFqn.toString()
                Symbols.deleteWhere {
                    (fqName eq fqNameStr) and (extensionReceiverType eq extensionReceiverFqn?.toString())
                }
                Supertypes.deleteWhere { symbolFqName eq fqNameStr }
            } else {
                LOG.warn("Excluding symbol {} from index since its name is too long", descriptorFqn.toString())
            }
        }

    private fun addDeclarations(declarations: Sequence<DeclarationDescriptor>) =
        declarations.forEach { declaration ->
            val (descriptorFqn, extensionReceiverFqn) = getFqNames(declaration)

            if (validFqName(descriptorFqn) && (extensionReceiverFqn?.let { validFqName(it) } != false)) {
                SymbolEntity.new {
                    fqName = descriptorFqn.toString()
                    shortName = descriptorFqn.shortName().toString()
                    kind = declaration.accept(ExtractSymbolKind, Unit).rawValue
                    visibility = declaration.accept(ExtractSymbolVisibility, Unit).rawValue
                    extensionReceiverType = extensionReceiverFqn?.toString()
                }

                addSupertypeRelations(declaration, descriptorFqn)
            } else {
                LOG.warn("Excluding symbol {} from index since its name is too long", descriptorFqn.toString())
            }
        }

    // Record the direct supertype relationship so JAR-backed hierarchy queries can be answered from the index.
    private fun addSupertypeRelations(declaration: DeclarationDescriptor, descriptorFqn: FqName) {
        if (declaration !is ClassDescriptor) return
        val symbolFqn = descriptorFqn.toString()
        for (superDesc in declaration.directSuperClassifiers()) {
            val superFqn = superDesc.fqNameSafe.toString()
            if (validFqName(FqName(superFqn))) {
                Supertypes.insert {
                    it[symbolFqName] = symbolFqn
                    it[superFqName] = superFqn
                }
            }
        }
    }

    private fun getFqNames(declaration: DeclarationDescriptor): Pair<FqName, FqName?> {
        val descriptorFqn = declaration.fqNameSafe
        val extensionReceiverFqn = declaration.accept(ExtractSymbolExtensionReceiverType, Unit)?.takeIf { !it.isRoot }

        return Pair(descriptorFqn, extensionReceiverFqn)
    }

    private fun validFqName(fqName: FqName) =
        fqName.toString().length <= MAX_FQNAME_LENGTH
            && fqName.shortName().toString().length <= MAX_SHORT_NAME_LENGTH

    fun addExternalSymbols(externalSymbols: Sequence<ExternalSymbol>) =
        externalSymbols.forEach { addExternalSymbol(it) }

    private fun addExternalSymbol(symbol: ExternalSymbol) {
        val symbolFqName = FqName(symbol.fqName)

        if (!validFqName(symbolFqName)) {
            LOG.debug("Excluding external symbol {} from index - name too long", symbol.fqName)
            return
        }

        try {
            val existing = SymbolEntity.find { Symbols.fqName eq symbol.fqName }.firstOrNull()
            if (existing != null) return

            SymbolEntity.new {
                fqName = symbol.fqName
                shortName = symbol.shortName
                kind = symbol.kind.rawValue
                visibility = symbol.visibility.rawValue
            }
        } catch (e: Exception) {
            LOG.debug("Skipping symbol {}: {}", symbol.fqName, e.message)
        }
    }

    fun query(prefix: String, receiverType: FqName? = null, limit: Int = 20, suffix: String = "%"): List<Symbol> = transaction(db) {
        // TODO: Extension completion currently only works if the receiver matches exactly,
        //       ideally this should work with subtypes as well
        SymbolEntity.find {
            (Symbols.shortName like "$prefix$suffix") and (Symbols.extensionReceiverType eq receiverType?.toString())
        }.limit(limit)
            .map { entity ->
                Symbol(
                    fqName = FqName(entity.fqName),
                    kind = Symbol.Kind.fromRaw(entity.kind),
                    visibility = Symbol.Visibility.fromRaw(entity.visibility),
                    extensionReceiverType = entity.extensionReceiverType?.let(::FqName),
                    location = entity.location?.let { loc ->
                        Symbol.Location(
                            uri = loc.uri,
                            startLine = loc.range.start.line,
                            startCharacter = loc.range.start.character,
                            endLine = loc.range.end.line,
                            endCharacter = loc.range.end.character
                        )
                    }
                )
            }
    }

    fun supertypesOf(fqName: FqName): List<FqName> = transaction(db) {
        Supertypes.selectAll().where { Supertypes.symbolFqName eq fqName.toString() }
            .map { FqName(it[Supertypes.superFqName]) }
    }

    fun subtypesOf(fqName: FqName): List<FqName> = transaction(db) {
        Supertypes.selectAll().where { Supertypes.superFqName eq fqName.toString() }
            .map { FqName(it[Supertypes.symbolFqName]) }
    }

    private fun allDescriptors(module: ModuleDescriptor, exclusions: Sequence<DeclarationDescriptor>): Sequence<DeclarationDescriptor> = allPackages(module)
        .map(module::getPackage)
        .flatMap {
            try {
                it.memberScope.getContributedDescriptors(
                    DescriptorKindFilter.ALL
                ) { name -> !exclusions.any { declaration -> declaration.name == name } }
            } catch (_: IllegalStateException) {
                LOG.warn("Could not query descriptors in package $it")
                emptyList()
            }
        }

    private fun allPackages(module: ModuleDescriptor, pkgName: FqName = FqName.ROOT): Sequence<FqName> = module
        .getSubPackagesOf(pkgName) { it.toString() != "META-INF" }
        .asSequence()
        .flatMap { sequenceOf(it) + allPackages(module, it) }
}
