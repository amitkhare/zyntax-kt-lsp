package org.javacs.kt.codeaction.quickfix

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.position.offset
import org.javacs.kt.util.toPath
import org.javacs.kt.overridemembers.createFunctionStub
import org.javacs.kt.overridemembers.createVariableStub
import org.javacs.kt.overridemembers.getClassDescriptor
import org.javacs.kt.overridemembers.getDeclarationPadding
import org.javacs.kt.overridemembers.getMemberStubsFromSuperTypes
import org.javacs.kt.overridemembers.getNewMembersStartPosition
import org.javacs.kt.overridemembers.hasNoBody
import org.javacs.kt.overridemembers.overridesDeclaration
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics

class ImplementAbstractMembersQuickFix : QuickFix {
    @Suppress("UNUSED_PARAMETER")
    override fun compute(file: CompiledFile, index: SymbolIndex, range: Range, diagnostics: List<Diagnostic>, filteredTypes: List<String>): List<Either<Command, CodeAction>> {
        val diagnostic = findDiagnosticMatch(diagnostics, range)

        val startCursor = offset(file.content, range.start)
        val endCursor = offset(file.content, range.end)
        val kotlinDiagnostics = file.compile.diagnostics

        // If the client side and the server side diagnostics contain a valid diagnostic for this range.
        if (diagnostic != null && anyDiagnosticMatch(kotlinDiagnostics, startCursor, endCursor)) {
            // Get the class with the missing members
            val kotlinClass = file.parseAtPoint(startCursor)
            if (kotlinClass is KtClass) {
                // Get the functions that need to be implemented
                val membersToImplement = getAbstractMembersStubs(file, kotlinClass)

                val uri = file.parse.toPath().toUri().toString()
                // Get the padding to be introduced before the member declarations
                val padding = getDeclarationPadding(file, kotlinClass)

                // Get the location where the new code will be placed
                val newMembersStartPosition = getNewMembersStartPosition(file, kotlinClass)
                val bodyAppendBeginning = listOf(TextEdit(Range(newMembersStartPosition, newMembersStartPosition), "{")).takeIf { kotlinClass.hasNoBody() } ?: emptyList()
                val bodyAppendEnd = listOf(TextEdit(Range(newMembersStartPosition, newMembersStartPosition), System.lineSeparator() + "}")).takeIf { kotlinClass.hasNoBody() } ?: emptyList()

                val textEdits = bodyAppendBeginning + membersToImplement.map {
                    // We leave two new lines before the member is inserted
                    val newText = System.lineSeparator() + System.lineSeparator() + padding + it
                    TextEdit(Range(newMembersStartPosition, newMembersStartPosition), newText)
                } + bodyAppendEnd

                val codeAction = CodeAction()
                codeAction.edit = WorkspaceEdit(mapOf(uri to textEdits))
                codeAction.kind = CodeActionKind.QuickFix
                codeAction.title = "Implement abstract members"
                codeAction.diagnostics = listOf(diagnostic)
                return listOf(Either.forRight(codeAction))
            }
        }
        return listOf()
    }
}

fun findDiagnosticMatch(diagnostics: List<Diagnostic>, range: Range) =
    diagnostics.find { diagnosticMatch(it, range, hashSetOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED")) }

private fun anyDiagnosticMatch(diagnostics: Diagnostics, startCursor: Int, endCursor: Int) =
    diagnostics.any { diagnosticMatch(it, startCursor, endCursor, hashSetOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED")) }

private fun getAbstractMembersStubs(file: CompiledFile, kotlinClass: KtClass): List<String> =
    getMemberStubsFromSuperTypes(file, kotlinClass) { classDescriptor, member ->
        val isAbstractSuperType = classDescriptor.kind.isInterface || classDescriptor.modality == Modality.ABSTRACT
        val isAbstractMember = member.modality == Modality.ABSTRACT
        isAbstractSuperType && isAbstractMember && !overridesDeclaration(kotlinClass, member)
    }
