package org.javacs.kt.classpath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class HomeTest {
    @get:Rule val tempFolder = TemporaryFolder()

    // --- interpolatePath ---
    @Test
    fun `interpolatePath returns path for plain value`() {
        assertThat(interpolatePath("/usr/share/maven"), equalTo(Paths.get("/usr/share/maven")))
    }

    @Test
    fun `interpolatePath resolves system property`() {
        val expected = Paths.get(System.getProperty("user.home")).resolve(".m2/repository")
        assertThat(interpolatePath("\${user.home}/.m2/repository"), equalTo(expected))
    }

    @Test
    fun `interpolatePath resolves env variable`() {
        val home = System.getenv("HOME") ?: return
        assertThat(interpolatePath("\${env.HOME}/repo"), equalTo(Paths.get(home).resolve("repo")))
    }

    @Test
    fun `interpolatePath returns null for unknown property`() {
        assertThat(interpolatePath("\${nonexistent.prop}"), nullValue())
    }

    @Test
    fun `interpolatePath returns null when mixed known and unknown properties`() {
        assertThat(interpolatePath("\${user.home}/\${unknown}"), nullValue())
    }

    @Test
    fun `interpolatePath returns null when value is blank`() {
        assertThat(interpolatePath(""), equalTo(Paths.get("")))
    }

    // --- tryParseLocalRepository ---
    @Test
    fun `tryParseLocalRepository returns null when file does not exist`() {
        val result = tryParseLocalRepository(Paths.get("/nonexistent/settings.xml"))
        assertThat(result, nullValue())
    }

    @Test
    fun `tryParseLocalRepository parses localRepository element`() {
        val xml = """
            <settings>
                <localRepository>/custom/maven/repo</localRepository>
            </settings>
        """.trimIndent()
        val file = createSettingsXml(xml)

        assertThat(tryParseLocalRepository(file), equalTo(Paths.get("/custom/maven/repo")))
    }

    @Test
    fun `tryParseLocalRepository resolves properties in path`() {
        val userHome = System.getProperty("user.home")
        val repoValue = "\${user.home}/.m2/repository"
        val xml = """
            <settings>
                <localRepository>$repoValue</localRepository>
            </settings>
        """.trimIndent()
        val file = createSettingsXml(xml)

        assertThat(tryParseLocalRepository(file), equalTo(Paths.get(userHome, ".m2", "repository")))
    }

    @Test
    fun `tryParseLocalRepository returns null when no localRepository element`() {
        val xml = """
            <settings>
                <offline>true</offline>
            </settings>
        """.trimIndent()
        val file = createSettingsXml(xml)

        assertThat(tryParseLocalRepository(file), nullValue())
    }

    @Test
    fun `tryParseLocalRepository returns null when element is empty`() {
        val xml = "<settings><localRepository/></settings>"
        val file = createSettingsXml(xml)

        assertThat(tryParseLocalRepository(file), nullValue())
    }

    @Test
    fun `tryParseLocalRepository returns null for malformed XML`() {
        val file = createSettingsXml("not xml at all")

        assertThat(tryParseLocalRepository(file), nullValue())
    }

    @Test
    fun `tryParseLocalRepository ignores other elements`() {
        val xml = """
            <settings>
                <interactiveMode>true</interactiveMode>
                <localRepository>/workspace/repo</localRepository>
                <offline>false</offline>
            </settings>
        """.trimIndent()
        val file = createSettingsXml(xml)

        assertThat(tryParseLocalRepository(file), equalTo(Paths.get("/workspace/repo")))
    }

    // --- helpers ---
    private fun createSettingsXml(content: String): Path {
        val file = tempFolder.newFile("settings.xml").toPath()
        Files.writeString(file, content)
        return file
    }
}
