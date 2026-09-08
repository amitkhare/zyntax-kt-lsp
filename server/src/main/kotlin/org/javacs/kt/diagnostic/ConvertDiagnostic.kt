package org.javacs.kt.diagnostic

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementVisitor

import org.eclipse.lsp4j.Diagnostic as LangServerDiagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.javacs.kt.LOG
import org.javacs.kt.position.range
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.diagnostics.Diagnostic as KotlinDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.psi.KtFile

import java.net.URI

fun convertDiagnostic(diagnostic: KotlinDiagnostic): List<Pair<URI, LangServerDiagnostic>> {
    val uri = diagnostic.psiFile.toPath().toUri()
    val content = diagnostic.psiFile.text

    LOG.info("Converting diagnostic: {} at {} (severity: {})",
        diagnostic.factory.name,
        diagnostic.textRanges.joinToString(", ") { "${it.startOffset}-${it.endOffset}" },
        diagnostic.severity)

    return diagnostic.textRanges.map {
        val d = LangServerDiagnostic(
            range(content, it),
            message(diagnostic),
            severity(diagnostic.severity),
            "kotlin",
            code(diagnostic)
        ).apply {
            val factoryName = diagnostic.factory.name
            tags = mutableListOf<DiagnosticTag>()

            if ("UNUSED_"     in factoryName) tags.add(DiagnosticTag.Unnecessary)
            if ("DEPRECATION" in factoryName) tags.add(DiagnosticTag.Deprecated)
        }
        Pair(uri, d)
    }
}

private fun code(diagnostic: KotlinDiagnostic) =
        diagnostic.factory.name

private fun message(diagnostic: KotlinDiagnostic) =
        DefaultErrorMessages.render(diagnostic)

private fun severity(severity: Severity): DiagnosticSeverity =
        when (severity) {
            Severity.INFO -> DiagnosticSeverity.Information
            Severity.ERROR -> DiagnosticSeverity.Error
            Severity.WARNING -> DiagnosticSeverity.Warning
            Severity.FIXED_WARNING -> DiagnosticSeverity.Hint
        }

fun convertParserErrors(file: KtFile): List<Pair<URI, LangServerDiagnostic>> {
    val errors = mutableListOf<Pair<URI, LangServerDiagnostic>>()
    val content = file.text
    val uri = file.toPath().toUri()

    file.accept(object : PsiRecursiveElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element is PsiErrorElement) {
                val errorDescription = element.errorDescription
                if (errorDescription.isNotEmpty()) {
                    val diagnostic = LangServerDiagnostic(
                        range(content, element.textRange),
                        errorDescription,
                        DiagnosticSeverity.Error,
                        "kotlin",
                        "SYNTAX_ERROR"
                    )
                    errors.add(Pair(uri, diagnostic))
                    LOG.info("Parser error: {} at {} (offset {})",
                        errorDescription,
                        element.textRange,
                        element.textRange.startOffset)
                }
            }
            super.visitElement(element)
        }
    })

    return errors
}
