package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.userHome
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.SAXException

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Creates a [Path] from the value of an environment variable.
 *
 * @param envVar the name of the environment variable to read
 * @return a `Path` representing the variable's value, or `null` if the variable is not set
 */
private fun createPathOrNull(envVar: String): Path? = System.getenv(envVar)?.let(Paths::get)

private val possibleMavenRepositoryPaths =
    sequenceOf(
        createPathOrNull("MAVEN_REPOSITORY"),
        createPathOrNull("MAVEN_HOME")?.resolve("repository"),
        createPathOrNull("M2_HOME")?.resolve("repository"),
        userHome.resolve(".m2/repository"),
    )
    .filterNotNull()

/**
 * Pattern matching Maven-style property placeholders (e.g. `${user.home}`, `${env.HOME}`).
 * Escaped placeholders (`\${...}`) are not matched.
 */
private val PROPERTY_PATTERN = Regex("""(?<!\\)\$\{([^}]+)\}""")

/**
 * Resolves Maven-style property references in a path string.
 *
 * Supports system properties (`${...}`), environment variables (`${env....}`),
 * and falls back to the default Maven repository path when a property cannot be resolved.
 *
 * @param value the path string possibly containing `${...}` placeholders
 * @return a `Path` with all resolvable properties substituted, or `null` if any property is unresolvable
 */
internal fun interpolatePath(value: String): Path? {
    val interpolated = PROPERTY_PATTERN.replace(value) { match ->
        val propName = match.groupValues[1]
        when {
            propName.startsWith("env.") -> System.getenv(propName.removePrefix("env."))
            else -> System.getProperty(propName)
        } ?: match.value
    }
    if (PROPERTY_PATTERN.containsMatchIn(interpolated)) return null
    return try {
        Paths.get(interpolated)
    } catch (_: Exception) {
        null
    }
}

/**
 * Reads the `<localRepository>` element from a Maven `settings.xml` file.
 *
 * The XML parser is configured with strict security features to prevent XXE attacks.
 *
 * @param settingsFile the path to the Maven `settings.xml` file
 * @return the resolved local repository path, or `null` if the file does not exist,
 *         cannot be parsed, or contains no valid `<localRepository>` element
 */
internal fun tryParseLocalRepository(settingsFile: Path): Path? {
    if (!Files.exists(settingsFile)) return null

    return try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(settingsFile.toFile())
        parseRepositoryPath(doc)
    } catch (e: ParserConfigurationException) {
        LOG.warn("Could not configure parser for {}: {}", settingsFile, e.message)
        null
    } catch (e: IOException) {
        LOG.warn("Could not read {}: {}", settingsFile, e.message)
        null
    } catch (e: SAXException) {
        LOG.warn("Could not parse {}: {}", settingsFile, e.message)
        null
    }
}

/**
 * Extracts the `<localRepository>` value from an already-parsed XML document.
 *
 * @param doc the parsed XML document (expected to be a Maven `settings.xml`)
 * @return the interpolated repository path, or `null` if no `<localRepository>` element is found
 */
private fun parseRepositoryPath(doc: Document): Path? {
    val children = doc.documentElement?.childNodes ?: return null
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "localRepository") {
            val text = node.textContent?.trim()
            if (!text.isNullOrEmpty()) {
                return interpolatePath(text)
            }
        }
    }
    return null
}

/**
 * The local Maven repository path, resolved from the following sources (in priority order):
 *
 * 1. `<localRepository>` in `~/.m2/settings.xml` (with property interpolation)
 * 2. `MAVEN_REPOSITORY` environment variable
 * 3. `MAVEN_HOME/repository` environment variable
 * 4. `M2_HOME/repository` environment variable
 * 5. `~/.m2/repository` (default)
 */
internal val mavenRepository: Path by lazy {
    val userSettings = userHome.resolve(".m2/settings.xml")
    tryParseLocalRepository(userSettings)
        ?: possibleMavenRepositoryPaths.firstOrNull { Files.exists(it) }
        ?: userHome.resolve(".m2/repository")
}

internal val gradleHome = createPathOrNull("GRADLE_USER_HOME") ?: userHome.resolve(".gradle")
