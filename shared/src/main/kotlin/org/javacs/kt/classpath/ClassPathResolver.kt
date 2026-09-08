package org.javacs.kt.classpath

import org.javacs.kt.LOG

import java.nio.file.Path

/** A source for creating class paths */
interface ClassPathResolver {
    val resolverType: String

    val classpath: Set<ClassPathEntry> // may throw exceptions
    val classpathOrEmpty: Set<ClassPathEntry> // does not throw exceptions
        get() = try {
            classpath
        } catch (e: Exception) {
            LOG.warn("Could not resolve classpath using {}: {}", resolverType, e.message)
            emptySet()
        }

    val buildScriptClasspath: Set<Path>
        get() = emptySet()
    val buildScriptClasspathOrEmpty: Set<Path>
        get() = try {
            buildScriptClasspath
        } catch (e: Exception) {
            LOG.warn("Could not resolve buildscript classpath using {}: {}", resolverType, e.message)
            emptySet()
        }

    val classpathWithSources: Set<ClassPathEntry> get() = classpath

    val testClasspath: Set<ClassPathEntry>
        get() = emptySet()
    val testClasspathOrEmpty: Set<ClassPathEntry>
        get() = try {
            testClasspath
        } catch (e: Exception) {
            LOG.warn("Could not resolve test classpath using {}: {}", resolverType, e.message)
            emptySet()
        }

    /**
     * A 64-bit content hash of the resolver's build file (e.g. `build.gradle.kts`, `pom.xml`).
     *
     * Used to detect *content* changes, which is robust to `git checkout`, IDE safe-write,
     * `touch`, etc.
     *
     * Resolvers that don't have a build file return [BuildFileHashing.NO_BUILD_FILE].
     *
     * This is the identity for `xor`, so such resolvers don't perturb
     * the combined value at union layers.
     *
     * Two resolvers that wrap the same file should return the same hash.
     */
    val currentBuildFileVersion: Long
        get() = BuildFileHashing.NO_BUILD_FILE

    companion object {
        /** A default empty classpath implementation */
        val empty = object : ClassPathResolver {
            override val resolverType = "[]"
            override val classpath = emptySet<ClassPathEntry>()
        }
    }
}

val Sequence<ClassPathResolver>.joined get() = fold(ClassPathResolver.empty) { accum, next -> accum + next }

val Collection<ClassPathResolver>.joined get() = fold(ClassPathResolver.empty) { accum, next -> accum + next }

/** Combines two classpath resolvers. */
operator fun ClassPathResolver.plus(other: ClassPathResolver): ClassPathResolver = UnionClassPathResolver(this, other)

/** Uses the left-hand classpath if not empty, otherwise uses the right. */
infix fun ClassPathResolver.or(other: ClassPathResolver): ClassPathResolver = FirstNonEmptyClassPathResolver(this, other)

/** The union of two class path resolvers. */
internal class UnionClassPathResolver(val lhs: ClassPathResolver, val rhs: ClassPathResolver) : ClassPathResolver {
    override val resolverType: String get() = "(${lhs.resolverType} + ${rhs.resolverType})"
    override val classpath get() = lhs.classpath + rhs.classpath
    override val classpathOrEmpty get() = lhs.classpathOrEmpty + rhs.classpathOrEmpty
    override val buildScriptClasspath get() = lhs.buildScriptClasspath + rhs.buildScriptClasspath
    override val buildScriptClasspathOrEmpty get() = lhs.buildScriptClasspathOrEmpty + rhs.buildScriptClasspathOrEmpty
    override val classpathWithSources get() = lhs.classpathWithSources + rhs.classpathWithSources
    override val testClasspath get() = lhs.testClasspath + rhs.testClasspath
    override val testClasspathOrEmpty get() = lhs.testClasspathOrEmpty + rhs.testClasspathOrEmpty
    override val currentBuildFileVersion: Long get() = lhs.currentBuildFileVersion xor rhs.currentBuildFileVersion
}

internal class FirstNonEmptyClassPathResolver(val lhs: ClassPathResolver, val rhs: ClassPathResolver) : ClassPathResolver {
    override val resolverType: String get() = "(${lhs.resolverType} or ${rhs.resolverType})"
    override val classpath get() = lhs.classpath.takeIf { it.isNotEmpty() } ?: rhs.classpath
    override val classpathOrEmpty get() = lhs.classpathOrEmpty.takeIf { it.isNotEmpty() } ?: rhs.classpathOrEmpty
    override val buildScriptClasspath get() = lhs.buildScriptClasspath.takeIf { it.isNotEmpty() } ?: rhs.buildScriptClasspath
    override val buildScriptClasspathOrEmpty get() = lhs.buildScriptClasspathOrEmpty.takeIf { it.isNotEmpty() } ?: rhs.buildScriptClasspathOrEmpty
    override val classpathWithSources get() = lhs.classpathWithSources.takeIf {
        it.isNotEmpty()
    } ?: rhs.classpathWithSources
    override val testClasspath get() = lhs.testClasspath.takeIf { it.isNotEmpty() } ?: rhs.testClasspath
    override val testClasspathOrEmpty get() = lhs.testClasspathOrEmpty.takeIf { it.isNotEmpty() } ?: rhs.testClasspathOrEmpty
    override val currentBuildFileVersion: Long get() = lhs.currentBuildFileVersion xor rhs.currentBuildFileVersion
}
