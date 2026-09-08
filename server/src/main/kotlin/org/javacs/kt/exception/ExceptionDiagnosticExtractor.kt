package org.javacs.kt.exception

import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range
import org.javacs.kt.LOG
import org.javacs.kt.position.range
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.resolve.lazy.NoDescriptorForDeclarationException
import org.jetbrains.kotlin.utils.exceptions.KotlinExceptionWithAttachments
import java.net.URI

/**
 * Extracts diagnostic information from Kotlin compiler exceptions.
 * This allows providing meaningful error messages when internal exceptions occur.
 */

/**
 * Data class containing diagnostic information extracted from an exception
 */
data class DiagnosticInfo(
    val uri: URI?,
    val message: String,
    val range: Range?,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    val details: String? = null
)

/**
 * Attempts to extract diagnostic information from a Kotlin compiler exception.
 * Currently, this handles:
 * - NoDescriptorForDeclarationException: provides location of the problematic declaration
 */
fun extractDiagnosticInfo(exception: Throwable): DiagnosticInfo? {
    return when (exception) {
        is NoDescriptorForDeclarationException -> extractFromNoDescriptorException(exception)
        is KotlinExceptionWithAttachments -> extractFromKotlinExceptionWithAttachments(exception)
        else -> null
    }
}

/**
 * Extract diagnostic info from NoDescriptorForDeclarationException
 */
private fun extractFromNoDescriptorException(
    exception: NoDescriptorForDeclarationException
): DiagnosticInfo? {
    return try {
        val msg = exception.message ?: return null

        // Extract the declaration name from the message
        // Pattern: "Descriptor wasn't found for declaration KtNamedFunction: toHSL"
        val declarationName = extractDeclarationName(msg)

        DiagnosticInfo(
            uri = null,
            message = if (declarationName != null) {
                "Failed to analyze declaration: $declarationName"
            } else {
                "Failed to analyze code"
            },
            range = null,
            details = msg
        )
    } catch (e: Exception) {
        LOG.debug("Failed to extract diagnostic info from NoDescriptorForDeclarationException", e)
        null
    }
}

/**
 * Extract diagnostic info from KotlinExceptionWithAttachments
 */
private fun extractFromKotlinExceptionWithAttachments(
    exception: KotlinExceptionWithAttachments
): DiagnosticInfo? {
    return try {
        // Get the message from the underlying RuntimeException
        // KotlinExceptionWithAttachments is an interface, so we need to get the message differently
        val msg = (exception as? RuntimeException)?.message ?: return null

        DiagnosticInfo(
            uri = null,
            message = msg.take(200),
            range = null,
            details = null
        )
    } catch (e: Exception) {
        LOG.debug("Failed to extract diagnostic info from KotlinExceptionWithAttachments", e)
        null
    }
}

/**
 * Extract declaration name from error message
 */
private fun extractDeclarationName(message: String): String? {
    // Pattern: "Descriptor wasn't found for declaration KtNamedFunction: toHSL"
    val pattern = """declaration ([^:]+):?\s*(\S*)""".toRegex()
    val match = pattern.find(message)

    return match?.let {
        val type = it.groupValues[1]
        val name = it.groupValues[2]
        if (name.isNotEmpty()) "$name ($type)" else type
    }
}

/**
 * Creates a Diagnostic from a PsiElement with error information.
 * Used when we have a specific location but the compiler failed to resolve it.
 */
fun createDiagnosticFromElement(
    element: PsiElement,
    message: String,
    code: String = "COMPILER_ERROR"
): Pair<URI, Diagnostic>? {
    return try {
        val file = element.containingFile ?: return null
        val content = file.text
        val uri = file.toPath().toUri() ?: return null

        val textRange = element.textRange
        val diagnostic = Diagnostic(
            range(content, textRange),
            message,
            DiagnosticSeverity.Error,
            "kotlin",
            code
        )

        Pair(uri, diagnostic)
    } catch (e: Exception) {
        LOG.debug("Failed to create diagnostic from element", e)
        null
    }
}

/**
 * Converts an LSP Range to a readable string format for logging.
 */
fun Range.toDisplayString(): String {
    return "(${start.line + 1}:${start.character + 1})-(${end.line + 1}:${end.character + 1})"
}
