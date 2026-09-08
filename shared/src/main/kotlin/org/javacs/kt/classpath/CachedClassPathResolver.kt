package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.nio.file.Paths

private const val MAX_PATH_LENGTH = 2047

private object ResolvedClassPathMetadata : IntIdTable() {
    val includesSources = bool("includessources")
    val buildFileVersion = long("buildfileversion").nullable()
}

private object ClassPathCacheEntry : IntIdTable() {
    val compiledJar = varchar("compiledjar", length = MAX_PATH_LENGTH)
    val sourceJar = varchar("sourcejar", length = MAX_PATH_LENGTH).nullable()
}

private object BuildScriptClassPathCacheEntry : IntIdTable() {
    val jar = varchar("jar", length = MAX_PATH_LENGTH)
}

class ResolvedClassPathMetadataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ResolvedClassPathMetadataEntity>(ResolvedClassPathMetadata)

    var includesSources by ResolvedClassPathMetadata.includesSources
    var buildFileVersion by ResolvedClassPathMetadata.buildFileVersion
}

class ClassPathCacheEntryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ClassPathCacheEntryEntity>(ClassPathCacheEntry)

    var compiledJar by ClassPathCacheEntry.compiledJar
    var sourceJar by ClassPathCacheEntry.sourceJar
}

class BuildScriptClassPathCacheEntryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BuildScriptClassPathCacheEntryEntity>(BuildScriptClassPathCacheEntry)

    var jar by BuildScriptClassPathCacheEntry.jar
}

private enum class CachedPath(val metadataId: Int) {
    CLASSPATH(1), BUILD_SCRIPT(2)
}

/** Stores each resolved classpath with its own build-file fingerprint. */
internal class CachedClassPathResolver(
    private val wrapped: ClassPathResolver,
    private val db: Database
) : ClassPathResolver {
    override val resolverType: String get() = "Cached + ${wrapped.resolverType}"

    init {
        transaction(db) {
            SchemaUtils.create(ResolvedClassPathMetadata, ClassPathCacheEntry, BuildScriptClassPathCacheEntry)
        }
    }

    override val classpath: Set<ClassPathEntry> get() = resolveClasspath(includesSources = false)

    override val classpathWithSources: Set<ClassPathEntry> get() = resolveClasspath(includesSources = true)

    override val buildScriptClasspath: Set<Path> get() = cached(
        CachedPath.BUILD_SCRIPT,
        read = { BuildScriptClassPathCacheEntryEntity.all().map { Paths.get(it.jar) }.toSet() },
        resolve = { wrapped.buildScriptClasspath },
        write = { entries ->
            BuildScriptClassPathCacheEntry.deleteAll()
            entries.forEach { entry -> BuildScriptClassPathCacheEntryEntity.new { jar = entry.toString() } }
        }
    )

    override val currentBuildFileVersion: Long get() = wrapped.currentBuildFileVersion

    private fun resolveClasspath(includesSources: Boolean): Set<ClassPathEntry> = cached(
        CachedPath.CLASSPATH,
        includesSources,
        read = {
            ClassPathCacheEntryEntity.all().map {
                ClassPathEntry(Paths.get(it.compiledJar), it.sourceJar?.let(Paths::get))
            }.toSet()
        },
        resolve = { if (includesSources) wrapped.classpathWithSources else wrapped.classpath },
        write = { entries ->
            ClassPathCacheEntry.deleteAll()
            entries.forEach { entry ->
                ClassPathCacheEntryEntity.new {
                    compiledJar = entry.compiledJar.toString()
                    sourceJar = entry.sourceJar?.toString()
                }
            }
        }
    )

    private fun <T> cached(
        path: CachedPath,
        includesSources: Boolean = false,
        read: () -> Set<T>,
        resolve: () -> Set<T>,
        write: (Set<T>) -> Unit
    ): Set<T> {
        val version = currentBuildFileVersion
        val cachedEntries = transaction(db) {
            val metadata = ResolvedClassPathMetadataEntity.findById(path.metadataId)
            if (metadata?.buildFileVersion == version && (!includesSources || metadata.includesSources)) {
                read()
            } else {
                null
            }
        }
        if (cachedEntries != null) return cachedEntries

        LOG.info("Resolving {} using {}", path, wrapped.resolverType)
        val entries = resolve()
        transaction(db) {
            write(entries)
            val metadata = ResolvedClassPathMetadataEntity.findById(path.metadataId)
                ?: ResolvedClassPathMetadataEntity.new(path.metadataId) { }
            metadata.buildFileVersion = version
            metadata.includesSources = includesSources
        }
        return entries
    }
}
