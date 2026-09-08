package org.javacs.kt.j2k

import org.javacs.kt.LOG
import com.intellij.psi.*
import com.intellij.psi.javadoc.*

/**
 * A Psi visitor that converts Java elements into
 * Kotlin code.
 */
@Suppress("LargeClass")
class JavaElementConverter(
    private val indentLevel: Int = 0,
    private val indentSize: Int = 4, // spaces
    private val nullabilityConfig: NullabilityConfig = NullabilityConfig()
) : JavaElementVisitor() {
    /**
     * Contains the translated code. If code has multiple lines,
     * the first line is *not* indented, all subsequent lines are
     * indented by 'indentLevel' levels.
     */
    var translatedKotlinCode: String? = null
        private set
    private val indent: String = "".padStart(indentLevel * indentSize, ' ')

    // =================
    // Extension methods
    // =================

    private val String?.spacePrefixed: String
        get() = this?.let { " $it" } ?: ""

    /** Convenience method to perform construction, visit and translation in one call. */
    private fun PsiElement?.translate(indentDelta: Int = 0): String? = JavaElementConverter(
        indentLevel + indentDelta,
        indentSize,
        nullabilityConfig
    ).also { this?.accept(it) }.translatedKotlinCode

    /** Fetches the indented indent. */
    private fun nextIndent(indentDelta: Int = 1): String = ""
        .padStart((indentLevel + indentDelta) * indentSize, ' ')

    /**
     * Constructs a code block from a list of statements.
     * Note that the 'indentDelta' refers to the layer
     * of indentation *inside* the block.
     */
    private fun Sequence<String>.buildCodeBlock(indentDelta: Int = 1, separatorNewlines: Int = 1): String {
        val indentedStatements = this.joinToString(
            separator = "\n".repeat(separatorNewlines)
        ) { "${nextIndent(indentDelta)}$it" }
        return "{\n$indentedStatements\n${nextIndent(indentDelta - 1)}}"
    }

    private fun List<String>.buildCodeBlock(indentDelta: Int = 1, separatorNewlines: Int = 1): String = asSequence().buildCodeBlock(indentDelta, separatorNewlines)

    /** Converts a PsiType to its Kotlin representation. */
    private fun PsiType.translateType(): String = accept(JavaTypeConverter)

    /** Converts a PsiType to its Kotlin representation with nullability. */
    private fun PsiType.translateType(element: PsiModifierListOwner): String =
        JavaTypeConverter.getTypeWithNullability(this, element)

    /** Fetches the child statements of a potentially composite statement. */
    private val PsiStatement.containedStatements: Sequence<PsiStatement> get() = if (this is PsiBlockStatement) {
        codeBlock.statements.asSequence()
    } else {
        sequenceOf(this)
    }

    /**
     * Converts an unhandled element to a comment containing the original Java code.
     * Use this for elements that don't have a direct Kotlin equivalent or are rare edge cases.
     */
    private fun convertAsComment(element: PsiElement) {
        translatedKotlinCode = "/* ${element.text} */"
    }

    /**
     * Extracts case values from a PsiSwitchLabelStatement without using the deprecated getCaseValue() method.
     * The case values are extracted from the children of the label statement.
     */
    private fun extractCaseValues(labelStmt: PsiSwitchLabelStatement): List<String> {
        return labelStmt.children
            .filterIsInstance<PsiExpression>()
            .mapNotNull { it.translate() }
    }

    /**
     * Extracts annotation attributes from a PsiAnnotation.
     * @return A pair of (simpleName, formattedAttributes) where formattedAttributes includes parentheses if non-empty
     */
    private fun PsiAnnotation.extractAttributes(): Pair<String, String> {
        val qualifiedName = try {
            this.qualifiedName
        } catch (_: Throwable) {
            this.nameReferenceElement?.referenceName
        } ?: return Pair("", "")

        val simpleName = qualifiedName.substringAfterLast('.')
        val attributes = this.parameterList.attributes
            .joinToString(", ") { attr ->
                val name = attr.name?.let { "$it = " } ?: ""
                val value = attr.value?.text ?: ""
                "$name$value"
            }
            .let { if (it.isNotEmpty()) "($it)" else "" }
        return Pair(simpleName, attributes)
    }

    /**
     * Extracts member declarations from a PSI element and formats them as a code block.
     * @param indentDelta Additional indentation level for nested members
     * @return Formatted code block string with member declarations (e.g., "{ ... }" or "{ }")
     */
    private fun PsiElement.extractMembersAsCodeBlock(indentDelta: Int): String {
        val memberElements = this.children.filterIsInstance<PsiMember>()
        val members = memberElements.mapNotNull { it.translate(indentDelta = indentDelta) }
            .filter { it.isNotEmpty() }

        val membersStr = members.joinToString("\n")
        return if (members.isNotEmpty()) {
            "{\n${nextIndent(indentDelta)}$membersStr\n${nextIndent(indentDelta - 1)}}"
        } else "{ }"
    }

    /**
     * Extracts and formats the switch label text from a PsiSwitchLabelStatement.
     * @return Formatted label string (e.g., "case 1, 2 ->" or "else ->")
     */
    private fun PsiSwitchLabelStatement.extractLabelText(): String {
        return if (this.isDefaultCase) {
            "else ->"
        } else {
            val caseValues = extractCaseValues(this)
            if (caseValues.isEmpty()) "->"
            else caseValues.joinToString(", ") + " ->"
        }
    }

    // ============================================
    // Helper methods for modifiers and annotations
    // ============================================

    /**
     * Converts Java modifiers to Kotlin modifiers.
     */
    private fun convertModifiers(element: PsiModifierListOwner, isInInterface: Boolean = false): String {
        val modifierList = element.modifierList ?: return ""
        val modifiers = mutableListOf<String>()

        // Visibility modifiers
        when {
            modifierList.hasModifierProperty(PsiModifier.PUBLIC) -> {
                // Public is default in Kotlin - no modifier needed
            }
            modifierList.hasModifierProperty(PsiModifier.PRIVATE) -> modifiers.add("private")
            modifierList.hasModifierProperty(PsiModifier.PROTECTED) -> modifiers.add("protected")
            modifierList.hasModifierProperty(PsiModifier.PACKAGE_LOCAL) -> {
                // package-private becomes internal in Kotlin
                modifiers.add("internal")
            }
        }

        // Final / Open
        // In Java, classes/methods are non-final by default. In Kotlin, they are final by default.
        // To allow overriding in Kotlin, we add "open" for non-final, non-abstract members.
        if (element is PsiClass) {
            if (!modifierList.hasModifierProperty(PsiModifier.FINAL) &&
                !modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) {
                modifiers.add("open")
            }
        } else if (element is PsiMethod) {
            if (modifierList.hasModifierProperty(PsiModifier.FINAL)) {
                // final methods are just methods in Kotlin - no modifier needed
            } else if (!isInInterface && !modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) {
                // Non-final, non-abstract methods in Java need 'open' in Kotlin
                modifiers.add("open")
            }
        }

        // Abstract
        if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) {
            modifiers.add("abstract")
        }

        return modifiers.joinToString(" ").let { if (it.isNotEmpty()) "$it " else "" }
    }

    /**
     * Converts Java annotations to Kotlin annotations.
     */
    private fun convertAnnotations(element: PsiModifierListOwner): String {
        val modifierList = element.modifierList ?: return ""

        return modifierList.annotations
            .mapNotNull { annotation ->
                // Safely get qualified name - this may require resolve and can fail
                val qualifiedName = try {
                    annotation.qualifiedName
                } catch (_: Throwable) {
                    // If we can't resolve, try to get the name from the reference
                    annotation.nameReferenceElement?.referenceName
                } ?: return@mapNotNull null

                // Skip nullability annotations - they're handled by type conversion
                if (isNullabilityAnnotation(qualifiedName)) return@mapNotNull null

                // Convert common annotations
                when (qualifiedName) {
                    "java.lang.Override" -> "@Override"  // Keep as is
                    "java.lang.Deprecated" -> "@Deprecated"
                    "java.lang.SuppressWarnings" -> "@Suppress"
                    "java.lang.SafeVarargs" -> "@SafeVarargs"
                    "java.lang.FunctionalInterface" -> "@FunctionalInterface"
                    else -> {
                        // For other annotations, use simple name or qualified name
                        val (simpleName, attributes) = annotation.extractAttributes()
                        "@$simpleName$attributes"
                    }
                }
            }
            .joinToString(" ")
            .let { if (it.isNotEmpty()) "$it\n$indent" else "" }
    }

    /**
     * Checks if an annotation is a nullability annotation.
     */
    private fun isNullabilityAnnotation(qualifiedName: String): Boolean {
        val allNullabilityAnnotations = nullabilityConfig.notNullAnnotations + nullabilityConfig.nullableAnnotations
        return allNullabilityAnnotations.any { qualifiedName.endsWith(it) || qualifiedName == it }
    }

    /**
     * Gets the vararg modifier if the parameter is varargs.
     */
    private fun getVarargModifier(parameter: PsiParameter): String =
        if (parameter.isVarArgs) "vararg " else ""

    // =================
    // Visitor methods
    // =================

    override fun visitAnonymousClass(aClass: PsiAnonymousClass) {
        // Anonymous class: new Interface() { ... }
        // Convert to object expression: object : Interface { ... }
        val baseClassRef = aClass.baseClassReference.text ?: "Any"
        val body = aClass.extractMembersAsCodeBlock(indentDelta = 1)

        translatedKotlinCode = "object : $baseClassRef $body"
    }

    override fun visitArrayAccessExpression(expression: PsiArrayAccessExpression) {
        val translatedArray = expression.arrayExpression.translate()
        val translatedIndex = expression.indexExpression.translate()
        translatedKotlinCode = "$translatedArray[$translatedIndex]"
    }

    override fun visitArrayInitializerExpression(expression: PsiArrayInitializerExpression) {
        // Array initializer: {1, 2, 3} or {"a", "b"}
        // Convert to Kotlin arrayOf() or specialized array functions
        val elements = expression.initializers.map { it.translate() }
        val elementsStr = elements.joinToString(", ")

        // Try to infer the array type from context or first element
        val arrayType = try {
            (expression.parent as? PsiVariable)?.type?.let {
                if (it is PsiArrayType) it.componentType.canonicalText else null
            }
        } catch (_: Throwable) { null }

        val kotlinType = when (arrayType) {
            "int" -> "intArrayOf"
            "byte" -> "byteArrayOf"
            "short" -> "shortArrayOf"
            "long" -> "longArrayOf"
            "char" -> "charArrayOf"
            "float" -> "floatArrayOf"
            "double" -> "doubleArrayOf"
            "boolean" -> "booleanArrayOf"
            else -> "arrayOf"
        }

        translatedKotlinCode = "$kotlinType($elementsStr)"
    }

    override fun visitAssertStatement(statement: PsiAssertStatement) {
        val translatedCondition = statement.assertCondition.translate()
        val translatedDescription = statement.assertDescription.translate()?.let { " { $it }" } ?: ""
        translatedKotlinCode = "assert($translatedCondition)$translatedDescription"
    }

    override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
        translatedKotlinCode = "${expression.lExpression.translate()} ${expression.operationSign.text} ${expression.rExpression.translate()}"
    }

    override fun visitBinaryExpression(expression: PsiBinaryExpression) {
        translatedKotlinCode = "${expression.lOperand.translate()} ${expression.operationSign.text} ${expression.rOperand.translate()}"
    }

    override fun visitBlockStatement(statement: PsiBlockStatement) {
        visitCodeBlock(statement.codeBlock)
    }

    override fun visitBreakStatement(statement: PsiBreakStatement) {
        val label = statement.labelIdentifier?.text?.let { "@$it" } ?: ""
        translatedKotlinCode = "break$label"
    }

    override fun visitClass(aClass: PsiClass) {
        val annotations = convertAnnotations(aClass)
        val modifiers = convertModifiers(aClass)

        val translatedTypeParams = aClass.typeParameterList.translate()
            ?.let { if (it.isNotEmpty()) "<$it>" else null }
            ?: ""

        val translatedSuperTypes = sequenceOf(aClass.extendsList.translate(), aClass.implementsList.translate())
            .filterNotNull()
            .filter { it.isNotEmpty() }
            .joinToString(separator = ", ")
            .let { if (it.isNotEmpty()) " : $it" else "" }

        val (staticMembers, instanceMembers) = aClass.children
            .mapNotNull { it as? PsiMember }
            .partition { it.hasModifierProperty(PsiModifier.STATIC) }

        val translatedInstanceMembers = instanceMembers
            .mapNotNull { it.translate(indentDelta = 1) }

        val translatedCompanion = if (!staticMembers.isEmpty()) {
            val translatedCompanionBlock = staticMembers
                .map { "@JvmStatic ${it.translate(indentDelta = 2)}" }
                .buildCodeBlock(indentDelta = 2)
                .spacePrefixed
            "companion object$translatedCompanionBlock"
        } else ""

        val translatedBody = (listOf(translatedCompanion) + translatedInstanceMembers)
            .buildCodeBlock(indentDelta = 1, separatorNewlines = 2)
            .spacePrefixed

        val docComment = aClass.docComment?.let { convertJavadocToKDoc(it) }
        val classDeclaration = "${annotations}${modifiers}class ${aClass.name}$translatedTypeParams$translatedSuperTypes$translatedBody"
        translatedKotlinCode = if (!docComment.isNullOrEmpty()) {
            "$docComment\n$classDeclaration"
        } else {
            classDeclaration
        }
    }

    override fun visitClassInitializer(initializer: PsiClassInitializer) {
        translatedKotlinCode = "init${initializer.body.translate().spacePrefixed}"
    }

    override fun visitClassObjectAccessExpression(expression: PsiClassObjectAccessExpression) {
        // MyClass.class -> MyClass::class.java
        val type = expression.operand.type?.translateType() ?: expression.operand.text
        translatedKotlinCode = "$type::class.java"
    }

    override fun visitCodeBlock(block: PsiCodeBlock) {
        val translated = block.statements.mapNotNull { it.translate(indentDelta = 1) }
        translatedKotlinCode = translated.buildCodeBlock()
    }

    override fun visitConditionalExpression(expression: PsiConditionalExpression) {
        val translatedCondition = expression.condition.translate()
        val translatedThen = expression.thenExpression.translate()
        val translatedElse = expression.elseExpression.translate()
        translatedKotlinCode = "if ($translatedCondition) $translatedThen else $translatedElse"
    }

    override fun visitContinueStatement(statement: PsiContinueStatement) {
        val label = statement.labelIdentifier?.text?.let { "@$it" } ?: ""
        translatedKotlinCode = "continue$label"
    }

    override fun visitDeclarationStatement(statement: PsiDeclarationStatement) {
        translatedKotlinCode = statement.declaredElements.mapNotNull { it.translate() }.joinToString(separator = "\n")
    }

    override fun visitDocComment(comment: PsiDocComment) {
        translatedKotlinCode = convertJavadocToKDoc(comment)
    }

    override fun visitDocTag(tag: PsiDocTag) {
        translatedKotlinCode = convertTag(tag)
    }

    override fun visitDocTagValue(value: PsiDocTagValue) {
        translatedKotlinCode = value.text
    }

    override fun visitDoWhileStatement(statement: PsiDoWhileStatement) {
        translatedKotlinCode = "do ${statement.body.translate()} while (${statement.condition.translate()})"
    }

    override fun visitEmptyStatement(statement: PsiEmptyStatement) {
        translatedKotlinCode = ""
    }

    override fun visitExpression(expression: PsiExpression) {
        translatedKotlinCode = expression.text // Perform no conversion if no concrete visitor could be found
    }

    override fun visitExpressionList(list: PsiExpressionList) {
        translatedKotlinCode = list.expressions.mapNotNull { it.translate() }.joinToString(separator = ", ")
    }

    override fun visitExpressionListStatement(statement: PsiExpressionListStatement) {
        visitExpressionList(statement.expressionList)
    }

    override fun visitExpressionStatement(statement: PsiExpressionStatement) {
        translatedKotlinCode = statement.expression.translate()
    }

    override fun visitField(field: PsiField) {
        visitVariable(field)
    }

    override fun visitForStatement(statement: PsiForStatement) {
        LOG.info("Body: ${statement.body}")
        val translatedBody = ((statement.body?.containedStatements ?: emptySequence()) + sequenceOf(statement.update))
            .mapNotNull { it.translate(indentDelta = 1) }
            .buildCodeBlock()
        translatedKotlinCode = "${statement.initialization.translate()}\n${indent}while (${statement.condition.translate()}) $translatedBody"
    }

    override fun visitForeachStatement(statement: PsiForeachStatement) {
        val translatedParameter = statement.iterationParameter.name
        val translatedIterated = statement.iteratedValue.translate()
        val translatedBody = statement.body.translate()
        translatedKotlinCode = "for ($translatedParameter in $translatedIterated) $translatedBody"
    }

    override fun visitIdentifier(identifier: PsiIdentifier) {
        translatedKotlinCode = identifier.text
    }

    override fun visitIfStatement(statement: PsiIfStatement) {
        val translatedIf = "if (${statement.condition.translate()})${statement.thenBranch.translate().spacePrefixed}"
        val translatedElse = statement.elseBranch.translate()?.let { "else $it" }.spacePrefixed
        translatedKotlinCode = translatedIf + translatedElse
    }

    override fun visitImportList(list: PsiImportList) {
        translatedKotlinCode = list.allImportStatements
            .mapNotNull { it.translate() }
            .joinToString(separator = "\n")
    }

    override fun visitImportStatement(statement: PsiImportStatement) {
        statement.qualifiedName?.let {
            if (!isPlatformImport(it)) {
                translatedKotlinCode = "import $it"
            }
        }
    }

    override fun visitImportStaticStatement(statement: PsiImportStaticStatement) {
        translatedKotlinCode = "import ${statement.referenceName}"
    }

    override fun visitInlineDocTag(tag: PsiInlineDocTag) {
        translatedKotlinCode = convertInlineTags(tag.text)
    }

    override fun visitInstanceOfExpression(expression: PsiInstanceOfExpression) {
        translatedKotlinCode = "${expression.operand.translate()} is ${expression.checkType?.type?.translateType()}"
    }

    override fun visitJavaToken(token: PsiJavaToken) {
        translatedKotlinCode = token.text
    }

    override fun visitKeyword(keyword: PsiKeyword) {
        // Java keyword - map to Kotlin equivalent or pass through
        translatedKotlinCode = when (keyword.text) {
            "null" -> "null"
            "true" -> "true"
            "false" -> "false"
            else -> keyword.text  // Most keywords appear in contexts handled elsewhere
        }
    }

    override fun visitLabeledStatement(statement: PsiLabeledStatement) {
        val label = statement.labelIdentifier.text
        val body = statement.statement.translate()
        translatedKotlinCode = "$body@$label"
    }

    override fun visitLiteralExpression(expression: PsiLiteralExpression) {
        val value = expression.value
        translatedKotlinCode = when (value) {
            is String -> "\"$value\""
            else -> value.toString()
        }
    }

    override fun visitLocalVariable(variable: PsiLocalVariable) {
        visitVariable(variable)
    }

    override fun visitMethod(method: PsiMethod) {
        val annotations = convertAnnotations(method)
        val modifiers = convertModifiers(method)
        val typeParams = method.typeParameterList.translate()?.let { if (it.isNotEmpty()) "<$it> " else "" } ?: ""

        val name = method.name
        val translatedParamList = method.parameterList.parameters.joinToString(", ") { param ->
            val vararg = getVarargModifier(param)
            val paramName = param.name
            val paramType = param.type.translateType(param)
            "$vararg$paramName: $paramType"
        }

        val translatedReturnType = method.returnType?.let { type ->
            val typeStr = type.translateType(method)
            if (typeStr != "Unit") ": $typeStr" else ""
        } ?: ""

        val translatedBody = method.body?.translate()?.spacePrefixed ?: ""
        val docComment = method.docComment?.let { convertJavadocToKDoc(it) }

        val methodDeclaration = "${annotations}${modifiers}fun $typeParams$name($translatedParamList)$translatedReturnType$translatedBody"
        translatedKotlinCode = if (!docComment.isNullOrEmpty()) {
            "$docComment\n$methodDeclaration"
        } else {
            methodDeclaration
        }
    }

    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
        val name = expression.methodExpression.translate()
        val translatedArgList = expression.argumentList.translate()
        translatedKotlinCode = "$name($translatedArgList)"
    }

    override fun visitCallExpression(callExpression: PsiCallExpression) {
        // Generic call expression - handle as method call or constructor call
        when (callExpression) {
            is PsiMethodCallExpression -> visitMethodCallExpression(callExpression)
            is PsiNewExpression -> visitNewExpression(callExpression)
            else -> {
                // Fallback for other call types - just output the original text
                translatedKotlinCode = callExpression.text
            }
        }
    }

    override fun visitModifierList(list: PsiModifierList) {
        // Modifier list is handled by convertAnnotations and convertModifiers
        translatedKotlinCode = ""
    }

    override fun visitNewExpression(expression: PsiNewExpression) {
        // Check for anonymous class first: new Interface() { ... }
        val anonymousClass = expression.anonymousClass
        if (anonymousClass != null) {
            // Delegate to anonymous class handler
            visitAnonymousClass(anonymousClass)
            return
        }

        // Check if this is an array creation with initializer: new int[] {1, 2, 3}
        val arrayInitializer = expression.arrayInitializer
        if (arrayInitializer != null) {
            // This is new Type[] { ... } - let the array initializer handle it
            arrayInitializer.accept(this)
            return
        }

        val qualifier = expression.qualifier?.translate()?.let { "$it." } ?: ""
        val name = expression.type?.translateType()

        val arrayDimensions = expression.arrayDimensions
        val translatedArgs = if (arrayDimensions.isEmpty()) {
            "(${expression.argumentList.translate()})"
        } else {
            arrayDimensions.joinToString { "[$it]" }
        }

        translatedKotlinCode = "$qualifier$name$translatedArgs"
    }

    override fun visitPackage(aPackage: PsiPackage) {
        translatedKotlinCode = aPackage.qualifiedName
    }

    override fun visitPackageStatement(statement: PsiPackageStatement) {
        translatedKotlinCode = "package ${statement.packageName}"
    }

    override fun visitParameter(parameter: PsiParameter) {
        val vararg = getVarargModifier(parameter)
        val paramType = parameter.type.translateType(parameter)
        translatedKotlinCode = "$vararg${parameter.name}: $paramType"
    }

    override fun visitReceiverParameter(parameter: PsiReceiverParameter) {
        // Receiver parameter: OuterClass.this (used in inner classes)
        // In Kotlin: this@OuterClass
        val type = parameter.type.translateType()
        val name = parameter.name  // Usually "this"
        translatedKotlinCode = "$name@$type"
    }

    override fun visitParameterList(list: PsiParameterList) {
        translatedKotlinCode = list.parameters
            .mapNotNull { it.translate() }
            .joinToString(separator = ", ")
    }

    override fun visitParenthesizedExpression(expression: PsiParenthesizedExpression) {
        translatedKotlinCode = "(${expression.expression.translate()})"
    }

    override fun visitUnaryExpression(expression: PsiUnaryExpression) {
        // Handle unary expressions that aren't prefix/postfix (rare)
        val op = expression.operationSign.text
        val operand = expression.operand.translate()
        translatedKotlinCode = "$op$operand"
    }

    override fun visitPostfixExpression(expression: PsiPostfixExpression) {
        translatedKotlinCode = "${expression.operand.translate()}${expression.operationSign.text}"
    }

    override fun visitPrefixExpression(expression: PsiPrefixExpression) {
        translatedKotlinCode = "${expression.operationSign.text}${expression.operand.translate()}"
    }

    override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
        translatedKotlinCode = reference.referenceNameElement.translate()
    }

    override fun visitImportStaticReferenceElement(reference: PsiImportStaticReferenceElement) {
        // Static import reference - convert to Kotlin import
        val refName = reference.referenceName
        val qualifier = reference.qualifier?.text
        translatedKotlinCode = if (qualifier != null) {
            "import $qualifier.$refName"
        } else {
            "import $refName"
        }
    }

    override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        val qualifier = expression.qualifier?.translate()?.let { "$it." } ?: ""
        val name = expression.referenceNameElement.translate()
        translatedKotlinCode = "$qualifier$name"
    }

    override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
        val qualifier = expression.qualifierType.translate()
            ?: expression.qualifierExpression.translate()
        val memberName = expression.potentiallyApplicableMember?.name
        translatedKotlinCode = "$qualifier::$memberName"
    }

    override fun visitReferenceList(list: PsiReferenceList) {
        translatedKotlinCode = list.referenceElements.mapNotNull { it.translate() }.joinToString(separator = ", ")
    }

    override fun visitReferenceParameterList(list: PsiReferenceParameterList) {
        translatedKotlinCode = list.typeArguments.joinToString(separator = ", ") { it.translateType() }
    }

    override fun visitTypeParameterList(list: PsiTypeParameterList) {
        translatedKotlinCode = list.typeParameters.mapNotNull { it.translate() }.joinToString(separator = ", ")
    }

    override fun visitReturnStatement(statement: PsiReturnStatement) {
        translatedKotlinCode = "return${statement.returnValue.translate().spacePrefixed}"
    }

    override fun visitStatement(statement: PsiStatement) {
        // Fallback for unhandled statement types - just pass through the text
        // This ensures we don't lose code even if we don't fully understand it
        translatedKotlinCode = statement.text
    }

    override fun visitSuperExpression(expression: PsiSuperExpression) {
        val translatedQualifier = expression.qualifier.translate()?.let { "@$it" } ?: ""
        translatedKotlinCode = "super$translatedQualifier"
    }

    override fun visitSwitchLabelStatement(statement: PsiSwitchLabelStatement) {
        // In Kotlin: "case X ->" or "X ->" (in when expression)
        // Use extractCaseValues() helper to avoid deprecated getCaseValue()
        val caseValues: List<String> = extractCaseValues(statement)

        translatedKotlinCode = if (caseValues.isNotEmpty()) {
            // Support multiple values: case 1, 2, 3: -> 1, 2, 3 ->
            caseValues.joinToString(", ") + " ->"
        } else {
            "else ->"
        }
    }

    override fun visitSwitchLabeledRuleStatement(statement: PsiSwitchLabeledRuleStatement) {
        // Java 14+ arrow-style case: case 1 -> "value"
        // Convert to: 1 -> "value"
        // The label is a child element
        val labelChild: PsiSwitchLabelStatement? = statement.children.filterIsInstance<PsiSwitchLabelStatement>().firstOrNull()
        val label: String = labelChild?.extractLabelText() ?: "->"
        val body = statement.body?.translate()
        translatedKotlinCode = "$label $body"
    }

    override fun visitSwitchStatement(statement: PsiSwitchStatement) {
        // Convert Java switch to Kotlin when expression
        val selector = statement.expression.translate()
        val cases = statement.body?.statements?.mapNotNull { stmt ->
            when (stmt) {
                is PsiSwitchLabelStatement -> {
                    // Use extractCaseValues() helper to avoid deprecated getCaseValue()
                    val caseValues: List<String> = extractCaseValues(stmt)
                    val label = if (stmt.isDefaultCase) {
                        "else"
                    } else if (caseValues.isNotEmpty()) {
                        caseValues.joinToString(", ")
                    } else {
                        null
                    }
                    label?.let { "$it ->" }
                }
                else -> stmt.translate()
            }
        }?.joinToString("\n${nextIndent()}")

        val body = cases?.let {
            "{\n${nextIndent(1)}$it\n$indent}"
        } ?: "{ }"

        translatedKotlinCode = "when ($selector) $body"
    }

    override fun visitSynchronizedStatement(statement: PsiSynchronizedStatement) {
        val translatedLock = statement.lockExpression.translate()?.let { "($it)" } ?: ""
        val translatedBody = statement.body.translate()
        translatedKotlinCode = "synchronized$translatedLock $translatedBody"
    }

    override fun visitThisExpression(expression: PsiThisExpression) {
        val translatedQualifier = expression.qualifier.translate()?.let { "@$it" } ?: ""
        translatedKotlinCode = "this$translatedQualifier"
    }

    override fun visitThrowStatement(statement: PsiThrowStatement) {
        translatedKotlinCode = "throw ${statement.exception.translate()}"
    }

    override fun visitTryStatement(statement: PsiTryStatement) {
        val translatedTryBlock = statement.tryBlock.translate() ?: "{ }"
        val translatedCatches = statement.catchBlocks.asSequence()
            .mapIndexed { i, block ->
                val param = statement.catchBlockParameters[i]
                val catchType = param.type.translateType(param)
                val blockStr = block.translate() ?: "{ }"
                " catch (${param.name}: $catchType) $blockStr"
            }
            .joinToString()
        val translatedFinally = statement.finallyBlock.translate()?.let { " finally $it" } ?: ""

        // Handle try-with-resources
        val resourceList = statement.resourceList
        translatedKotlinCode = if (resourceList != null) {
            // Get resource variables from the resource list
            val resourceVariables = resourceList.children.filterIsInstance<PsiResourceVariable>()
            val resourceExpressions = resourceList.children.filterIsInstance<PsiResourceExpression>()

            val resources: List<String> = when {
                resourceVariables.isNotEmpty() -> {
                    // Resources declared in try-with-resources: try (BufferedReader r = ...) { }
                    resourceVariables.mapNotNull { it.translate()?.trim() }
                }
                resourceExpressions.isNotEmpty() -> {
                    // Resources as expressions: try (getResource()) { }
                    resourceExpressions.mapNotNull { it.translate()?.trim() }
                }
                else -> emptyList()
            }

            if (resources.isEmpty()) {
                "try $translatedTryBlock$translatedCatches$translatedFinally"
            } else {
                convertTryWithResources(resources, translatedTryBlock, translatedCatches, translatedFinally)
            }
        } else {
            "try $translatedTryBlock$translatedCatches$translatedFinally"
        }
    }

    private fun convertTryWithResources(
        resources: List<String>,
        tryBlock: String,
        catches: String,
        finallyBlock: String
    ): String {
        if (resources.isEmpty()) return "try $tryBlock$catches$finallyBlock"

        // If there are catches or finally, wrap them in a try block; otherwise just use the try block
        val wrappedBody = if (catches.isNotEmpty() || finallyBlock.isNotEmpty()) {
            "try $tryBlock$catches$finallyBlock"
        } else {
            tryBlock
        }

        // Build nested .use calls from outermost to innermost e.g., resource1.use { resource2.use { body } }
        // We work from the last resource (innermost) backwards
        var result = wrappedBody
        for (resource in resources.asReversed()) {
            // Simply wrap the current result without trying to strip braces
            // The indentation will be handled by the formatter
            result = "$resource.use { $result }"
        }

        return result
    }

    override fun visitCatchSection(section: PsiCatchSection) {
        // Catch section: catch (Exception e) { ... }
        // Converted individually (also handled in visitTryStatement for complete try-catch)
        val catchType = section.catchType?.translateType()
        val paramName = section.parameter?.name
        val body = section.catchBlock?.translate()

        translatedKotlinCode = if (catchType != null && paramName != null && body != null) {
            "catch ($paramName: $catchType) $body"
        } else {
            "catch (e: Exception) { }"
        }
    }

    override fun visitResourceList(resourceList: PsiResourceList) {
        // Handled in visitTryStatement
        translatedKotlinCode = ""
    }

    override fun visitResourceVariable(variable: PsiResourceVariable) {
        // Handled in visitTryStatement
        val varName = variable.name
        val varType = variable.type.translateType(variable)
        val initializer = variable.initializer?.translate()
        translatedKotlinCode = if (initializer != null) {
            "val $varName: $varType = $initializer"
        } else {
            "val $varName: $varType"
        }
    }

    override fun visitResourceExpression(expression: PsiResourceExpression) {
        // Resource expression in try-with-resources: try (getResource()) { }
        // Convert to: getResource().use { ... }
        val expr = expression.expression.translate()
        translatedKotlinCode = expr ?: ""
    }

    override fun visitTypeElement(type: PsiTypeElement) {
        translatedKotlinCode = type.type.translateType()
    }

    override fun visitTypeCastExpression(expression: PsiTypeCastExpression) {
        translatedKotlinCode = "${expression.operand.translate()} as ${expression.castType?.type?.translateType()}"
    }

    override fun visitVariable(variable: PsiVariable) {
        val annotations = convertAnnotations(variable)
        val modifiers = convertModifiers(variable)

        val isFinal = variable.hasModifierProperty(PsiModifier.FINAL)
        val keyword = if (isFinal) "val" else "var"

        val varName = variable.name
        val varType = variable.type.translateType(variable)
        val translatedInitializer = variable.initializer.translate()?.let { " = $it" } ?: ""

        val variableDeclaration = "${annotations}${modifiers}$keyword $varName: $varType$translatedInitializer"
        val docComment = (variable as? PsiDocCommentOwner)?.docComment?.let { convertJavadocToKDoc(it) }

        translatedKotlinCode = if (!docComment.isNullOrEmpty()) {
            "$docComment\n$variableDeclaration"
        } else {
            variableDeclaration
        }
    }

    override fun visitWhileStatement(statement: PsiWhileStatement) {
        translatedKotlinCode = "while (${statement.condition.translate()}) ${statement.body.translate()}"
    }

    override fun visitJavaFile(file: PsiJavaFile) {
        // This is the entry point for the J2K converter
        translatedKotlinCode = file.children.asSequence()
            .mapNotNull { it.translate() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n\n")
    }

    override fun visitImplicitVariable(variable: ImplicitVariable) {
        // Implicit variable (e.g., for-each loop variable)
        val name = variable.name
        val type = variable.type.translateType()
        translatedKotlinCode = "$name: $type"
    }

    override fun visitDocToken(token: PsiDocToken) {
        translatedKotlinCode = token.text
    }

    override fun visitTypeParameter(classParameter: PsiTypeParameter) {
        val translatedExtends = classParameter.extendsList.translate()
            ?.let { if (it.isNotEmpty()) " : $it" else "" }
        translatedKotlinCode = "${classParameter.name}$translatedExtends"
    }

    override fun visitAnnotation(annotation: PsiAnnotation) {
        val (simpleName, attributes) = annotation.extractAttributes()
        translatedKotlinCode = "@$simpleName$attributes"
    }

    override fun visitAnnotationParameterList(list: PsiAnnotationParameterList) {
        translatedKotlinCode = list.attributes
            .joinToString(", ") { it.text }
            .let { if (it.isNotEmpty()) "($it)" else "" }
    }

    override fun visitAnnotationArrayInitializer(initializer: PsiArrayInitializerMemberValue) {
        // Array values in annotations: @SuppressWarnings({"unused", "unchecked"})
        // Convert to: @SuppressWarnings("unused", "unchecked")
        val initializers = initializer.initializers.mapNotNull { it.translate() }
        translatedKotlinCode = initializers.joinToString(", ")
    }

    override fun visitNameValuePair(pair: PsiNameValuePair) {
        val name = pair.name?.let { "$it = " } ?: ""
        val value = pair.value?.text ?: ""
        translatedKotlinCode = "$name$value"
    }

    override fun visitAnnotationMethod(method: PsiAnnotationMethod) {
        // Annotation method (method declaration inside annotation)
        val name = method.name
        val returnType = method.returnType?.translateType() ?: "Any"
        val defaultValue = method.defaultValue?.translate()?.let { " = $it" } ?: ""
        translatedKotlinCode = "val $name: $returnType$defaultValue"
    }

    override fun visitEnumConstant(enumConstant: PsiEnumConstant) {
        // Enum constant: VALUE or VALUE(args)
        val name = enumConstant.name
        val args = enumConstant.argumentList?.translate()?.let { "($it)" } ?: ""
        val body = enumConstant.initializingClass?.translate()?.spacePrefixed ?: ""
        translatedKotlinCode = "$name$args$body"
    }

    override fun visitEnumConstantInitializer(enumConstantInitializer: PsiEnumConstantInitializer) {
        // Anonymous class extending enum: ENUM_VALUE { override fun method() { } }
        // Convert to: ENUM_VALUE { override fun method() { } }
        translatedKotlinCode = enumConstantInitializer.extractMembersAsCodeBlock(indentDelta = 1)
    }

    override fun visitCodeFragment(codeFragment: JavaCodeFragment) {
        // Code fragment (partial code) - pass through text
        translatedKotlinCode = codeFragment.text
    }

    override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
        // Polyadic expression: a + b + c + d (chain of same operator)
        val operands = expression.operands.map { it.translate() }
        // Get operator from the first token between operands
        val operator = expression.children.filterIsInstance<PsiJavaToken>().firstOrNull()?.text ?: ""
        translatedKotlinCode = operands.joinToString(separator = " $operator ")
    }

    override fun visitLambdaExpression(expression: PsiLambdaExpression) {
        val translatedParams: String = expression.parameterList.parameters
            .joinToString(separator = ", ") { it.name }
            .ifEmpty { null }
            ?.let { " $it ->" }
            ?: ""

        val translatedBody: String = when (val body = expression.body) {
            null -> " "
            is PsiExpression -> " ${body.translate()} "
            is PsiCodeBlock -> {
                val indentedStatements = body.statements.joinToString(separator = "\n") { "${nextIndent()}${it.translate()}" }
                "\n$indentedStatements\n$indent"
            }
            else -> " ? "
        }

        translatedKotlinCode = "{$translatedParams$translatedBody}"
    }

    override fun visitSwitchExpression(expression: PsiSwitchExpression) {
        // Java 14+ switch expression -> Kotlin when expression
        // Java: String result = switch (x) { case 1 -> "one"; case 2 -> "two"; default -> "other"; };
        // Kotlin: val result = when (x) { 1 -> "one"; 2 -> "two"; else -> "other" }
        val selector = expression.expression.translate()
        val body = expression.body

        val statements = body?.statements?.toList() ?: emptyList()
        val cases = statements.mapIndexedNotNull { index, stmt ->
            when (stmt) {
                is PsiSwitchLabeledRuleStatement -> {
                    // Arrow syntax: case 1 -> "value"
                    // The label is accessed differently in different IntelliJ versions
                    // Try to get it from children
                    val labelChild: PsiSwitchLabelStatement? = stmt.children.filterIsInstance<PsiSwitchLabelStatement>().firstOrNull()
                    val label: String = labelChild?.extractLabelText() ?: "->"
                    val bodyExpr = stmt.body?.translate()
                    "$label $bodyExpr"
                }
                is PsiSwitchLabelStatement -> {
                    // Colon syntax with block - get next statement as body
                    val label: String = stmt.extractLabelText()
                    val nextStmtIndex = index + 1
                    val caseBody = if (nextStmtIndex < statements.size) {
                        statements[nextStmtIndex].translate()
                    } else null
                    "$label -> $caseBody"
                }
                else -> stmt.translate()
            }
        }.joinToString("\n${nextIndent()}")

        val whenBody = if (cases.isNotEmpty()) {
            "{\n${nextIndent(1)}$cases\n$indent}"
        } else "{ }"

        translatedKotlinCode = "when ($selector) $whenBody"
    }

    override fun visitModule(module: PsiJavaModule) {
        // Java 9+ module declaration: module com.example { ... }
        // Kotlin doesn't have modules, convert to comment
        val moduleName = module.name ?: "unknown"
        // Use text-based conversion for module statements
        val moduleText = module.text.lines().drop(1).dropLast(1).joinToString("\n") { "${nextIndent()}/* $it */" }
        translatedKotlinCode = "/* module $moduleName {\n$moduleText\n$indent} */"
    }

    override fun visitModuleReferenceElement(refElement: PsiJavaModuleReferenceElement) {
        // Module reference in requires/provides statements
        translatedKotlinCode = refElement.text
    }

    override fun visitModuleStatement(statement: PsiStatement) {
        // Fallback for module statements - pass through as comment
        convertAsComment(statement)
    }

    override fun visitRequiresStatement(statement: PsiRequiresStatement) {
        // requires java.base; or requires transitive java.base;
        convertAsComment(statement)
    }

    override fun visitPackageAccessibilityStatement(statement: PsiPackageAccessibilityStatement) {
        // exports com.example.package; or opens com.example.package to module;
        convertAsComment(statement)
    }

    override fun visitUsesStatement(statement: PsiUsesStatement) {
        // uses com.example.Service;
        convertAsComment(statement)
    }

    override fun visitProvidesStatement(statement: PsiProvidesStatement) {
        // provides com.example.Service with com.example.ServiceImpl;
        convertAsComment(statement)
    }
}
