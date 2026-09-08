package org.javacs.kt.j2k

import com.intellij.psi.PsiModifierListOwner

/**
 * Configuration for nullability annotation detection.
 * Allows customization of which annotations to detect.
 */
data class NullabilityConfig(
    val notNullAnnotations: Set<String> = DEFAULT_NOT_NULL_ANNOTATIONS,
    val nullableAnnotations: Set<String> = DEFAULT_NULLABLE_ANNOTATIONS
) {
    companion object {
        // JetBrains annotations
        private val JETBRAINS_NOT_NULL = setOf(
            "org.jetbrains.annotations.NotNull",
            "NotNull"
        )

        private val JETBRAINS_NULLABLE = setOf(
            "org.jetbrains.annotations.Nullable",
            "Nullable"
        )

        // Standard JSR-305 annotations
        private val JSR305_NOT_NULL = setOf(
            "javax.annotation.Nonnull",
            "androidx.annotation.NonNull",
            "android.support.annotation.NonNull"
        )

        private val JSR305_NULLABLE = setOf(
            "javax.annotation.Nullable",
            "androidx.annotation.Nullable",
            "android.support.annotation.Nullable"
        )

        // JSpecify annotations (new standard)
        private val JSPECIFY_NOT_NULL = setOf(
            "org.jspecify.annotations.NonNull"
        )

        private val JSPECIFY_NULLABLE = setOf(
            "org.jspecify.annotations.Nullable"
        )

        // Other common annotations
        private val OTHER_NOT_NULL = setOf(
            "lombok.NonNull",
            "org.eclipse.jdt.annotation.NonNull",
            "org.checkerframework.checker.nullness.qual.NonNull"
        )

        private val OTHER_NULLABLE = setOf(
            "org.eclipse.jdt.annotation.Nullable",
            "org.checkerframework.checker.nullness.qual.Nullable"
        )

        val DEFAULT_NOT_NULL_ANNOTATIONS =
            JETBRAINS_NOT_NULL + JSR305_NOT_NULL + JSPECIFY_NOT_NULL + OTHER_NOT_NULL

        val DEFAULT_NULLABLE_ANNOTATIONS =
            JETBRAINS_NULLABLE + JSR305_NULLABLE + JSPECIFY_NULLABLE + OTHER_NULLABLE
    }
}

/**
 * Detects nullability annotations on PSI elements.
 */
class NullabilityDetector(private val config: NullabilityConfig = NullabilityConfig()) {

    /**
     * Returns the nullability suffix for a type based on annotations.
     * @return "?" if nullable, "" if not-null or unknown
     */
    fun getNullabilitySuffix(element: PsiModifierListOwner): String {
        val modifierList = element.modifierList ?: return ""

        // Check for nullable annotations
        for (annotation in modifierList.annotations) {
            val qualifiedName = try {
                annotation.qualifiedName
            } catch (_: Exception) {
                annotation.nameReferenceElement?.referenceName
            } ?: continue

            if (config.nullableAnnotations.any { qualifiedName.endsWith(it) || qualifiedName == it }) {
                return "?"
            }
        }

        // Check for not-null annotations - returns empty string (non-nullable in Kotlin)
        // We don't add !! suffix, just no ?
        return ""
    }

    /**
     * Checks if the element has a nullable annotation.
     */
    @Suppress("unused")
    fun isNullable(element: PsiModifierListOwner): Boolean =
        hasAnnotation(element, config.nullableAnnotations)

    /**
     * Checks if the element has a not-null annotation.
     */
    @Suppress("unused")
    fun isNotNull(element: PsiModifierListOwner): Boolean =
        hasAnnotation(element, config.notNullAnnotations)

    private fun hasAnnotation(element: PsiModifierListOwner, annotationNames: Set<String>): Boolean {
        val modifierList = element.modifierList ?: return false

        for (annotation in modifierList.annotations) {
            val qualifiedName = try {
                annotation.qualifiedName
            } catch (_: Exception) {
                annotation.nameReferenceElement?.referenceName
            } ?: continue

            if (annotationNames.any { qualifiedName.endsWith(it) || qualifiedName == it }) {
                return true
            }
        }

        return false
    }
}
