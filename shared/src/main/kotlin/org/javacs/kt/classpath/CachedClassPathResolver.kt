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

private object ClassPathMetadataCache : IntIdTable() {
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

class ClassPathMetadataCacheEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ClassPathMetadataCacheEntity>(ClassPathMetadataCache)

    var includesSources by ClassPathMetadataCache.includesSources
    var buildFileVersion by ClassPathMetadataCache.buildFileVersion
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

/** A classpath resolver that caches another resolver */
internal class CachedClassPathResolver(
    private val wrapped: ClassPathResolver,
    private val db: Database
) : ClassPathResolver {
    override val resolverType: String get() = "Cached + ${wrapped.resolverType}"

    private var cacheBroken = false

    private var cachedClassPathEntries: Set<ClassPathEntry>
        get() = transaction(db) {
            ClassPathCacheEntryEntity.all().map {
                ClassPathEntry(
                    compiledJar = Paths.get(it.compiledJar),
                    sourceJar = it.sourceJar?.let(Paths::get)
                )
            }.toSet()
        }
        set(newEntries) = transaction(db) {
            ClassPathCacheEntry.deleteAll()
            newEntries.map {
                ClassPathCacheEntryEntity.new {
                    compiledJar = it.compiledJar.toString()
                    sourceJar = it.sourceJar?.toString()
                }
            }
        }

    private var cachedBuildScriptClassPathEntries: Set<Path>
        get() = transaction(db) { BuildScriptClassPathCacheEntryEntity.all().map { Paths.get(it.jar) }.toSet() }
        set(newEntries) = transaction(db) {
            BuildScriptClassPathCacheEntry.deleteAll()
            newEntries.map { BuildScriptClassPathCacheEntryEntity.new { jar = it.toString() } }
        }

    private var cachedClassPathMetadata
        get() = transaction(db) {
            ClassPathMetadataCacheEntity.all().map {
                ClasspathMetadata(
                    includesSources = it.includesSources,
                    buildFileVersion = it.buildFileVersion
                )
            }.firstOrNull()
        }
        set(newClassPathMetadata) = transaction(db) {
            ClassPathMetadataCache.deleteAll()
            val newClassPathMetadataRow = newClassPathMetadata ?: ClasspathMetadata()
            ClassPathMetadataCacheEntity.new {
                includesSources = newClassPathMetadataRow.includesSources
                buildFileVersion = newClassPathMetadataRow.buildFileVersion
            }
        }

    init {
        initializeTables()
    }

    private fun initializeTables() {
        try {
            transaction(db) {
                SchemaUtils.create(
                    ClassPathMetadataCache, ClassPathCacheEntry, BuildScriptClassPathCacheEntry
                )
            }
        } catch (e: Exception) {
            LOG.error("Failed to initialize classpath cache tables: ${e.message}. Will recreate tables.", e)
            try {
                transaction(db) {
                    // Drop and recreate tables if there's a schema issue
                    SchemaUtils.drop(ClassPathMetadataCache, ClassPathCacheEntry, BuildScriptClassPathCacheEntry)
                    SchemaUtils.create(ClassPathMetadataCache, ClassPathCacheEntry, BuildScriptClassPathCacheEntry)
                }
            } catch (e2: Exception) {
                LOG.error("Failed to recreate classpath cache tables: ${e2.message}. Cache will be disabled.", e2)
                cacheBroken = true
            }
        }
    }

    override val classpath: Set<ClassPathEntry> get() {
        if (cacheBroken) {
            LOG.warn("Classpath cache is broken, using wrapped resolver directly")
            return wrapped.classpath
        }

        return try {
            val cached = cachedClassPathEntries
            if (!dependenciesChanged() && cached.isNotEmpty()) {
                LOG.info("Classpath has not changed. Fetching ${cached.size} entries from cache")
                return cached
            }

            LOG.info("Cached classpath is outdated or not found. Resolving again")

            val newClasspath = wrapped.classpath
            updateClasspathCache(newClasspath, false)

            newClasspath
        } catch (e: Exception) {
            LOG.error("Failed to access classpath cache: ${e.message}. Falling back to wrapped resolver.", e)
            cacheBroken = true
            wrapped.classpath
        }
    }

    override val buildScriptClasspath: Set<Path> get() {
        if (cacheBroken) {
            LOG.warn("Build script classpath cache is broken, using wrapped resolver directly")
            return wrapped.buildScriptClasspath
        }

        return try {
            if (!dependenciesChanged()) {
                LOG.info("Build script classpath has not changed. Fetching from cache")
                return cachedBuildScriptClassPathEntries
            }

            LOG.info("Cached build script classpath is outdated or not found. Resolving again")

            val newBuildScriptClasspath = wrapped.buildScriptClasspath

            updateBuildScriptClasspathCache(newBuildScriptClasspath)
            newBuildScriptClasspath
        } catch (e: Exception) {
            LOG.error("Failed to access build script classpath cache: ${e.message}. Falling back to wrapped resolver.", e)
            cacheBroken = true
            wrapped.buildScriptClasspath
        }
    }

    override val classpathWithSources: Set<ClassPathEntry> get() {
        if (cacheBroken) {
            LOG.warn("Classpath with sources cache is broken, using wrapped resolver directly")
            return wrapped.classpathWithSources
        }

        return try {
            val metadata = cachedClassPathMetadata
            if (!dependenciesChanged() && metadata?.includesSources == true) {
                LOG.info("Classpath with sources has not changed. Fetching from cache")
                return cachedClassPathEntries
            }

            LOG.info("Cached classpath with sources is outdated or not found. Resolving again")

            val newClasspath = wrapped.classpathWithSources
            updateClasspathCache(newClasspath, true)

            newClasspath
        } catch (e: Exception) {
            LOG.error("Failed to access classpath with sources cache: ${e.message}. Falling back to wrapped resolver.", e)
            cacheBroken = true
            wrapped.classpathWithSources
        }
    }

    override val currentBuildFileVersion: Long get() = wrapped.currentBuildFileVersion

    private fun updateClasspathCache(newClasspathEntries: Set<ClassPathEntry>, includesSources: Boolean) {
        if (cacheBroken) return

        try {
            transaction(db) {
                cachedClassPathEntries = newClasspathEntries
                cachedClassPathMetadata = cachedClassPathMetadata?.copy(
                    includesSources = includesSources,
                    buildFileVersion = currentBuildFileVersion
                ) ?: ClasspathMetadata()
            }
        } catch (e: Exception) {
            LOG.error("Failed to update classpath cache: ${e.message}. Cache will be disabled.", e)
            cacheBroken = true
        }
    }

    private fun updateBuildScriptClasspathCache(newClasspath: Set<Path>) {
        if (cacheBroken) return

        try {
            transaction(db) {
                cachedBuildScriptClassPathEntries = newClasspath
                cachedClassPathMetadata = cachedClassPathMetadata?.copy(
                    buildFileVersion = currentBuildFileVersion
                ) ?: ClasspathMetadata()
            }
        } catch (e: Exception) {
            LOG.error("Failed to update build script classpath cache: ${e.message}. Cache will be disabled.", e)
            cacheBroken = true
        }
    }

    private fun dependenciesChanged(): Boolean {
        if (cacheBroken) return true

        return try {
            // The cached `buildFileVersion` is a content hash; if the wrapped resolver now reports a
            // different hash, the build file has changed and the cached classpath is stale.
            //
            // Older DB rows may contain a stale mtime value here. That won't match a hash, so the cache
            // invalidates once on first run after this migration.
            cachedClassPathMetadata?.buildFileVersion != wrapped.currentBuildFileVersion
        } catch (e: Exception) {
            LOG.error("Failed to check if dependencies changed: ${e.message}", e)
            true
        }
    }
}

private data class ClasspathMetadata(
    val includesSources: Boolean = false,
    val buildFileVersion: Long? = null
)
