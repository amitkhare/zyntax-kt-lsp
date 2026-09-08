package org.javacs.kt.j2k

import org.javacs.kt.LOG
import com.intellij.psi.*

/**
 * Type visitor that converts Java types to Kotlin type representations.
 * Handles primitives, arrays, generics, wildcards, and varargs.
 */
object JavaTypeConverter : PsiTypeVisitor<String>() {
    private val nullabilityDetector = NullabilityDetector()

    override fun visitType(type: PsiType): String {
        return type.presentableText
    }

    override fun visitPrimitiveType(primitiveType: PsiPrimitiveType): String = when (primitiveType.canonicalText) {
        "void" -> "Unit"
        "boolean" -> "Boolean"
        "byte" -> "Byte"
        "short" -> "Short"
        "int" -> "Int"
        "long" -> "Long"
        "char" -> "Char"
        "float" -> "Float"
        "double" -> "Double"
        else -> primitiveType.canonicalText.replaceFirstChar { it.uppercaseChar() }
    }

    override fun visitArrayType(arrayType: PsiArrayType): String = when (try {
        arrayType.componentType.canonicalText
    } catch (e: IllegalStateException) {
        LOG.warn("Error while fetching text representation of array type: {}", e)
        "?"
    }) {
        "byte" -> "ByteArray"
        "short" -> "ShortArray"
        "int" -> "IntArray"
        "long" -> "LongArray"
        "char" -> "CharArray"
        "boolean" -> "BooleanArray"
        "float" -> "FloatArray"
        "double" -> "DoubleArray"
        else -> "Array<${arrayType.componentType.accept(this)}>"
    }

    override fun visitClassType(classType: PsiClassType): String {
        val translatedTypeArgs = classType.parameters
            .joinToString(separator = ", ") { it.accept(this) }
            .let { if (it.isNotEmpty()) "<$it>" else "" }
        val className = classType.className ?: "Any"
        return "${platformType(className)}$translatedTypeArgs"
    }

    override fun visitCapturedWildcardType(capturedWildcardType: PsiCapturedWildcardType): String {
        return super.visitCapturedWildcardType(capturedWildcardType) ?: "?"
    }

    override fun visitWildcardType(wildcardType: PsiWildcardType): String =
        if (wildcardType.isSuper) {
            "in ${wildcardType.bound?.accept(this)}"
        } else if (wildcardType.isExtends) {
            "out ${wildcardType.bound?.accept(this)}"
        } else {
            super.visitWildcardType(wildcardType) ?: "*"
        }

    override fun visitEllipsisType(ellipsisType: PsiEllipsisType): String {
        // Varargs: convert to vararg in Kotlin
        // In parameter context, this will be handled specially
        return ellipsisType.componentType.accept(this)
    }

    override fun visitDisjunctionType(disjunctionType: PsiDisjunctionType): String {
        return super.visitDisjunctionType(disjunctionType) ?: "?"
    }

    override fun visitIntersectionType(intersectionType: PsiIntersectionType): String {
        return super.visitIntersectionType(intersectionType) ?: "?"
    }

    override fun visitDiamondType(diamondType: PsiDiamondType): String {
        return super.visitDiamondType(diamondType) ?: "?"
    }

    override fun visitLambdaExpressionType(lambdaExpressionType: PsiLambdaExpressionType): String {
        return super.visitLambdaExpressionType(lambdaExpressionType) ?: "?"
    }

    /**
     * Gets the type string with nullability suffix based on annotations.
     */
    fun getTypeWithNullability(type: PsiType, element: PsiModifierListOwner): String {
        val baseType = type.accept(this)
        val nullabilitySuffix = nullabilityDetector.getNullabilitySuffix(element)
        return "$baseType$nullabilitySuffix"
    }
}
