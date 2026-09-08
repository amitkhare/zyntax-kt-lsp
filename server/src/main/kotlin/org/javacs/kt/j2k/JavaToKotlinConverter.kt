package org.javacs.kt.j2k

import com.intellij.lang.java.JavaLanguage
import org.javacs.kt.LOG
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.compiler.CompilationKind

/**
 * Converts Java code to Kotlin code.
 *
 * @param javaCode The Java source code to convert
 * @param compiler The compiler instance for PSI parsing
 * @param nullabilityConfig Configuration for nullability annotation detection
 * @return The converted Kotlin code
 */
fun convertJavaToKotlin(
    javaCode: String,
    compiler: Compiler,
    nullabilityConfig: NullabilityConfig = NullabilityConfig()
): String {
    val psiFactory = compiler.psiFileFactoryFor(CompilationKind.DEFAULT)
    val javaAST = psiFactory.createFileFromText("snippet.java", JavaLanguage.INSTANCE, javaCode)
    LOG.info("Parsed {} to {}", javaCode, javaAST)

    return JavaElementConverter(
        nullabilityConfig = nullabilityConfig
    ).also(javaAST::accept).translatedKotlinCode ?: run {
        LOG.warn("Could not translate code")
        ""
    }
}
