package org.javacs.kt.classpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClassPathResolverBuildFileHashTest {
    /** A trivial resolver that returns a fixed build-file hash. */
    private class FixedHashResolver(val hash: Long) : ClassPathResolver {
        override val resolverType = "fixed-$hash"
        override val classpath = emptySet<ClassPathEntry>()
        override val currentBuildFileVersion: Long get() = hash
    }

    @Test fun `default currentBuildFileVersion is NO_BUILD_FILE`() {
        val resolver: ClassPathResolver = object : ClassPathResolver {
            override val resolverType = "anonymous"
            override val classpath = emptySet<ClassPathEntry>()
        }
        assertEquals(BuildFileHashing.NO_BUILD_FILE, resolver.currentBuildFileVersion)
    }

    @Test fun `UnionClassPathResolver xors two child hashes`() {
        val a = FixedHashResolver(0x5AAAAAAA0BBBBBBBL)
        val b = FixedHashResolver(0x1234567890ABCDEFL)
        val union = a + b // operator+ produces UnionClassPathResolver
        assertEquals(0x5AAAAAAA0BBBBBBBL xor 0x1234567890ABCDEFL, union.currentBuildFileVersion)
    }

    @Test fun `UnionClassPathResolver with a no-build-file child returns the other hash`() {
        val real = FixedHashResolver(0xDEADBEEFL)
        val none = FixedHashResolver(BuildFileHashing.NO_BUILD_FILE)
        assertEquals(real.currentBuildFileVersion, (real + none).currentBuildFileVersion)
        assertEquals(real.currentBuildFileVersion, (none + real).currentBuildFileVersion)
    }

    @Test fun `UnionClassPathResolver changes when either child changes`() {
        val a = FixedHashResolver(0x111L)
        val b1 = FixedHashResolver(0x222L)
        val b2 = FixedHashResolver(0x333L)

        val combined1 = a + b1
        val combined2 = a + b2

        assertNotEquals(combined1.currentBuildFileVersion, combined2.currentBuildFileVersion)
    }

    @Test fun `FirstNonEmptyClassPathResolver also xors two child hashes`() {
        val a = FixedHashResolver(0xCAFE_BABE)
        val b = FixedHashResolver(0xC0FF_EE00)
        val first = a or b // infix `or` produces FirstNonEmptyClassPathResolver
        assertEquals(0xCAFE_BABE xor 0xC0FF_EE00, first.currentBuildFileVersion)
    }
}
