package org.javacs.kt.externalsources

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JdkSrcZipLocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val runningMajor: Int = 21

    /** The only search root the locator will scan during this test. */
    private val searchRoots: List<String>
        get() = listOf(tmp.root.absolutePath)

    private fun makeJdk(major: Int, withSrcZip: Boolean = true): File {
        val dir = tmp.newFolder("java-$major-openjdk")
        File(dir, "lib").mkdirs()
        if (withSrcZip) {
            File(dir, "lib/src.zip").writeBytes(ByteArray(0))
        }
        return dir
    }

    @Test
    fun `exact major match returns EXACT direction`() {
        makeJdk(runningMajor)
        makeJdk(runningMajor - 2)
        makeJdk(runningMajor + 2)

        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = null,
            searchRoots = searchRoots,
        )

        assertThat(result, notNullValue())
        assertThat(result!!.majorVersion, equalTo(runningMajor))
        assertThat(result.isExactMatch, equalTo(true))
        assertThat(result.direction, equalTo(JdkSrcZipResult.Direction.EXACT))
    }

    @Test
    fun `closest newer chosen when no exact match`() {
        // Running X; have X-2, X+1, X+3. X+1 should win.
        makeJdk(runningMajor, withSrcZip = false) // no src.zip for the exact version
        val newerMinor = runningMajor + 1
        val olderA = runningMajor - 2
        val newerMajor = runningMajor + 3
        makeJdk(olderA)
        makeJdk(newerMinor)
        makeJdk(newerMajor)

        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = null,
            searchRoots = searchRoots,
        )

        assertThat(result, notNullValue())
        assertThat(result!!.majorVersion, equalTo(newerMinor))
        assertThat(result.isExactMatch, equalTo(false))
        assertThat(result.direction, equalTo(JdkSrcZipResult.Direction.NEWER))
    }

    @Test
    fun `closest older chosen when no newer available`() {
        // Running X; only have X-1 and X-3. X-1 should win.
        makeJdk(runningMajor, withSrcZip = false)
        val olderA = runningMajor - 1
        val olderB = runningMajor - 3
        makeJdk(olderA)
        makeJdk(olderB)

        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = null,
            searchRoots = searchRoots,
        )

        assertThat(result, notNullValue())
        assertThat(result!!.majorVersion, equalTo(olderA))
        assertThat(result.isExactMatch, equalTo(false))
        assertThat(result.direction, equalTo(JdkSrcZipResult.Direction.OLDER))
    }

    @Test
    fun `override wins regardless of running version`() {
        makeJdk(runningMajor)
        makeJdk(runningMajor - 2)
        val customOverride = tmp.newFile("custom-src.zip")
        customOverride.writeBytes(ByteArray(0))

        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = customOverride.absolutePath,
            searchRoots = searchRoots,
        )

        assertThat(result, notNullValue())
        assertThat(result!!.path, equalTo(customOverride.absolutePath))
        assertThat(result.direction, equalTo(JdkSrcZipResult.Direction.EXACT))
    }

    @Test
    fun `none found returns null`() {
        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = null,
            searchRoots = searchRoots,
        )
        assertThat(result, nullValue())
    }

    @Test
    fun `running JVM home src zip is used when search roots yield nothing`() {
        // The injected home's folder name is unparseable as a version, so only the explicit jvmHome param
        // can discover it, proving the running-JVM fallback works independently of directory-name parsing
        // and install-root coverage.
        val jvmHome = tmp.newFolder("injected-jvm-home").also { File(it, "lib").mkdirs() }
        val srcZip = File(jvmHome, "lib/src.zip").apply { writeBytes(ByteArray(0)) }

        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = null,
            searchRoots = searchRoots,
            jvmHome = jvmHome.absolutePath,
        )

        assertThat(result, notNullValue())
        assertThat(result!!.path, equalTo(srcZip.absolutePath))
        assertThat(result.majorVersion, equalTo(runningMajor))
        assertThat(result.isExactMatch, equalTo(true))
        assertThat(result.direction, equalTo(JdkSrcZipResult.Direction.EXACT))
    }

    @Test
    fun `JVM home without src zip is ignored`() {
        // JRE-style home (no lib/src.zip) must not be treated as a source root.
        tmp.newFolder("injected-jvm-home")

        val result = JdkSrcZipLocator.resolve(
            runningMajor = runningMajor,
            override = null,
            searchRoots = searchRoots,
            jvmHome = tmp.root.absolutePath,
        )

        assertThat(result, nullValue())
    }

    @Test
    fun `parseMajorVersion extracts version from common naming schemes`() {
        assertThat(JdkSrcZipLocator.parseMajorVersion("java-25-openjdk"), equalTo(25))
        assertThat(JdkSrcZipLocator.parseMajorVersion("java-21-openjdk"), equalTo(21))
        assertThat(JdkSrcZipLocator.parseMajorVersion("jdk-21.0.4"), equalTo(21))
        assertThat(JdkSrcZipLocator.parseMajorVersion("openjdk-17"), equalTo(17))
        assertThat(JdkSrcZipLocator.parseMajorVersion("temurin-21.jdk"), equalTo(21))
        assertThat(JdkSrcZipLocator.parseMajorVersion("zulu17.50.19-ca-jdk17.0.11"), equalTo(17))
        assertThat(JdkSrcZipLocator.parseMajorVersion("java-1.8.0-openjdk"), equalTo(8))
        assertThat(JdkSrcZipLocator.parseMajorVersion("jdk1.7.0_80"), equalTo(7))
    }

    @Test
    fun `parseMajorVersion returns null for unparseable or null input`() {
        assertThat(JdkSrcZipLocator.parseMajorVersion(null), nullValue())
        assertThat(JdkSrcZipLocator.parseMajorVersion(""), nullValue())
        assertThat(JdkSrcZipLocator.parseMajorVersion("no-version-here"), nullValue())
    }
}
