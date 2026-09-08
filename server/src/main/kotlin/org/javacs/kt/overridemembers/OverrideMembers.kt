package org.javacs.kt.overridemembers

import org.eclipse.lsp4j.*
import org.javacs.kt.CompiledFile
import org.javacs.kt.util.toPath
import org.javacs.kt.position.position
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection

private const val DEFAULT_TAB_SIZE = 4

fun listOverridableMembers(file: CompiledFile, cursor: Int): List<CodeAction> {
    val kotlinClass = file.parseAtPoint(cursor)

    if (kotlinClass is KtClass) {
        return createOverrideAlternatives(file, kotlinClass)
    }

    return emptyList()
}

private fun createOverrideAlternatives(file: CompiledFile, kotlinClass: KtClass): List<CodeAction> {
    // Get the functions that need to be implemented
    val membersToImplement = getUnimplementedMembersStubs(file, kotlinClass)

    val uri = file.parse.toPath().toUri().toString()

    // Get the padding to be introduced before the member declarations
    val padding = getDeclarationPadding(file, kotlinClass)

    // Get the location where the new code will be placed
    val newMembersStartPosition = getNewMembersStartPosition(file, kotlinClass)

    // loop through the membersToImplement and create code actions
    return membersToImplement.map { member ->
        val newText = System.lineSeparator() + System.lineSeparator() + padding + member
        val textEdit = TextEdit(Range(newMembersStartPosition, newMembersStartPosition), newText)

        val codeAction = CodeAction()
        codeAction.edit = WorkspaceEdit(mapOf(uri to listOf(textEdit)))
        codeAction.title = member

        codeAction
    }
}

private fun getUnimplementedMembersStubs(file: CompiledFile, kotlinClass: KtClass): List<String> =
    getMemberStubsFromSuperTypes(file, kotlinClass) { classDescriptor, member ->
        classDescriptor.isExtendable() && member.canBeOverridden() && !overridesDeclaration(kotlinClass, member)
    }

/**
 * Extracts member stubs from all super types of a class, filtered by the given predicate.
 * @param file The compiled file context.
 * @param kotlinClass The class to get members for.
 * @param filter A predicate that takes a class descriptor and member, returns true if the member should be included.
 */
fun getMemberStubsFromSuperTypes(
    file: CompiledFile,
    kotlinClass: KtClass,
    filter: (ClassDescriptor, MemberDescriptor) -> Boolean
): List<String> =
    kotlinClass.superTypeListEntries
        .mapNotNull { superType ->
            val referenceAtPoint = file.referenceExpressionAtPoint(superType.startOffset)
            val descriptor = referenceAtPoint?.second
            val classDescriptor = getClassDescriptor(descriptor)

            if (classDescriptor != null && classDescriptor.isExtendable()) {
                val superClassTypeArguments = getSuperClassTypeProjections(file, superType)
                classDescriptor
                    .getMemberScope(superClassTypeArguments)
                    .getContributedDescriptors()
                    .filterIsInstance<MemberDescriptor>()
                    .filter { filter(classDescriptor, it) }
                    .mapNotNull { member ->
                        when (member) {
                            is FunctionDescriptor -> createFunctionStub(member)
                            is PropertyDescriptor -> createVariableStub(member)
                            else -> null
                        }
                    }
            } else {
                null
            }
        }
        .flatten()

private fun ClassDescriptor.isExtendable() = this.kind.isInterface ||
    this.modality == Modality.ABSTRACT ||
    this.modality == Modality.OPEN

private fun MemberDescriptor.canBeOverridden() = (Modality.ABSTRACT == this.modality || Modality.OPEN == this.modality) && Modality.FINAL != this.modality && this.visibility != DescriptorVisibilities.PRIVATE && this.visibility != DescriptorVisibilities.PROTECTED

// interfaces are ClassDescriptors by default. When calling AbstractClass super methods, we get a ClassConstructorDescriptor
fun getClassDescriptor(descriptor: DeclarationDescriptor?): ClassDescriptor? =
        when (descriptor) {
            is ClassDescriptor -> descriptor
            is ClassConstructorDescriptor -> descriptor.containingDeclaration
            else -> null
        }

fun getSuperClassTypeProjections(
        file: CompiledFile,
        superType: KtSuperTypeListEntry
): List<TypeProjection> =
        superType
                .typeReference
                ?.typeElement
                ?.children
                ?.filterIsInstance<KtTypeArgumentList>()
                ?.flatMap { it.arguments }
                ?.mapNotNull {
                    (file.referenceExpressionAtPoint(it?.startOffset ?: 0)?.second as?
                                    ClassDescriptor)
                            ?.defaultType?.asTypeProjection()
                }
                ?: emptyList()

// Checks if the class overrides the given declaration
fun overridesDeclaration(kotlinClass: KtClass, descriptor: MemberDescriptor): Boolean =
    when (descriptor) {
        is FunctionDescriptor -> kotlinClass.declarations.any {
            it.name == descriptor.name.asString()
            && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
            && ((it as? KtNamedFunction)?.let { fn -> parametersMatch(fn, descriptor) } ?: true)
        }
        is PropertyDescriptor -> kotlinClass.declarations.any {
            it.name == descriptor.name.asString() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        }
        else -> false
    }

// Checks if two functions have matching parameters
private fun parametersMatch(
        function: KtNamedFunction,
        functionDescriptor: FunctionDescriptor
): Boolean {
    if (function.valueParameters.size == functionDescriptor.valueParameters.size) {
        for ((index, psiParam) in function.valueParameters.withIndex()) {
            val paramName = psiParam.name
            val descriptorParamName = functionDescriptor.valueParameters[index].name.asString()
            if (paramName != descriptorParamName) {
                return false
            }

            val psiTypeName = psiParam.typeReference?.typeName()
            val descriptorTypeName = functionDescriptor.valueParameters[index].type.unwrappedType().toString()
            if (psiTypeName != descriptorTypeName) {
                return false
            }
        }

        if (function.typeParameters.size == functionDescriptor.typeParameters.size) {
            for ((index, psiTypeParam) in function.typeParameters.withIndex()) {
                val actualVariance = psiTypeParam.variance
                val expectedVariance = functionDescriptor.typeParameters[index].variance

                if (actualVariance != expectedVariance) {
                    return false
                }
            }
        }

        return true
    }

    return false
}

private fun KtTypeReference.typeName(): String = this.getTypeText()

fun createFunctionStub(function: FunctionDescriptor): String {
    val name = function.name
    val arguments =
            function.valueParameters
                    .joinToString(", ") { "${it.name}: ${it.type.unwrappedType()}" }
    val returnType = function.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }

    return "override fun $name($arguments)${returnType?.let { ": $it" } ?: ""} { }"
}

fun createVariableStub(variable: PropertyDescriptor): String {
    val variableType = variable.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }
    return "override val ${variable.name}${variableType?.let { ": $it" } ?: ""} = TODO(\"SET VALUE\")"
}

// about types: regular Kotlin types are marked T or T?, but types from Java are (T...T?) because
// nullability cannot be decided.
// Therefore, we have to unpack in case we have the Java type. Fortunately, the Java types are not
// marked nullable, so we default to non-nullable types. Let the user decide if they want nullable
// types instead. With this implementation Kotlin types also keeps their nullability
private fun KotlinType.unwrappedType(): KotlinType =
        this.unwrap().makeNullableAsSpecified(this.isMarkedNullable)

fun getDeclarationPadding(file: CompiledFile, kotlinClass: KtClass): String {
    // If the class is not empty, the amount of padding is the same as the one in the last
    // declaration of the class
    val paddingSize =
            if (kotlinClass.declarations.isNotEmpty()) {
                val lastFunctionStartOffset = kotlinClass.declarations.last().startOffset
                position(file.content, lastFunctionStartOffset).character
            } else {
                // Otherwise, we just use a default tab size in addition to any existing padding
                // on the class itself (note that the class could be inside another class, for
                // example)
                position(file.content, kotlinClass.startOffset).character + DEFAULT_TAB_SIZE
            }

    return " ".repeat(paddingSize)
}

fun getNewMembersStartPosition(file: CompiledFile, kotlinClass: KtClass): Position? =
        // If the class is not empty, the new member will be put right after the last declaration
        if (kotlinClass.declarations.isNotEmpty()) {
            val lastFunctionEndOffset = kotlinClass.declarations.last().endOffset
            position(file.content, lastFunctionEndOffset)
        } else { // Otherwise, the member is put at the beginning of the class
            val body = kotlinClass.body
            if (body != null) {
                position(file.content, body.startOffset + 1)
            } else {
                // function has no body. We have to create one. New position is right after entire
                // kotlin class text (with space)
                val newPosCorrectLine = position(file.content, kotlinClass.startOffset + 1)
                newPosCorrectLine.character = (kotlinClass.text.length + 2)
                newPosCorrectLine
            }
        }

fun KtClass.hasNoBody() = null == this.body
