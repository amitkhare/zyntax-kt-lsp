package org.javacs.kt

import org.javacs.kt.j2k.convertJavaToKotlin
import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Ignore
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.containsString

class JavaToKotlinWithDocumentationTest : LanguageServerTestFixture("j2k") {
    @Test
    fun `test simple javadoc conversion`() {
        val javaCode = """
            package test;

            /**
             * A simple class with documentation.
             */
            public class SimpleClass {
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("A simple class with documentation."))
    }

    @Test
    fun `test param tag conversion`() {
        val javaCode = """
            package test;

            public class Test {
                /**
                 * Adds two numbers.
                 * @param a the first number
                 * @param b the second number
                 * @return the sum
                 */
                public int add(int a, int b) {
                    return a + b;
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("@param a the first number"))
        assertThat(convertedKotlinCode, containsString("@param b the second number"))
        assertThat(convertedKotlinCode, containsString("@return the sum"))
    }

    @Test
    fun `test throws tag conversion`() {
        val javaCode = """
            package test;

            public class Test {
                /**
                 * Processes data.
                 * @param input the input data
                 * @throws IllegalArgumentException if input is null
                 */
                public void process(String input) {
                    if (input == null) {
                        throw new IllegalArgumentException("input cannot be null");
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("@throws IllegalArgumentException if input is null"))
    }

    @Test
    fun `test inline code tag conversion`() {
        val javaCode = """
            package test;

            public class Test {
                /**
                 * Example method.
                 * Use {@code int x = 5} for inline code.
                 */
                public void example() {
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("`int x = 5`"))
    }

    @Test
    fun `test inline link tag conversion`() {
        val javaCode = """
            package test;

            public class Test {
                /**
                 * Example method.
                 * See {@link String} for details.
                 */
                public void example() {
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("[String](String)"))
    }

    @Test
    fun `test author and version tags`() {
        val javaCode = """
            package test;

            /**
             * A documented class.
             * @author John Doe
             * @version 1.0
             */
            public class Test {
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("@author John Doe"))
        assertThat(convertedKotlinCode, containsString("@version 1.0"))
    }

    @Test
    fun `test deprecated tag conversion`() {
        val javaCode = """
            package test;

            public class Test {
                /**
                 * Old method.
                 * @deprecated Use newMethod instead
                 */
                @Deprecated
                public void oldMethod() {
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("@deprecated Use newMethod instead"))
    }

    @Test
    fun `test see tag conversion`() {
        val javaCode = """
            package test;

            public class Test {
                /**
                 * Example method.
                 * @see String
                 * @see "External Documentation"
                 */
                public void example() {
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("@see [String]"))
        assertThat(convertedKotlinCode, containsString("@see \"External Documentation\""))
    }

    @Test
    fun `test comprehensive javadoc conversion`() {
        val javaCode = """
            package test;

            /**
             * A comprehensive example class.
             *
             * This class demonstrates various Javadoc features.
             *
             * @author Jane Smith
             * @version 2.0
             * @since 1.0
             */
            public class Comprehensive {
                /**
                 * Calculates the result.
                 *
                 * @param input the input value
                 * @return the calculated result
                 * @throws IllegalArgumentException if input is negative
                 * @see Math#abs(int)
                 */
                public int calculate(int input) {
                    if (input < 0) {
                        throw new IllegalArgumentException("Input must be positive");
                    }
                    return input * 2;
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler)

        assertThat(convertedKotlinCode, containsString("A comprehensive example class."))
        assertThat(convertedKotlinCode, containsString("@author Jane Smith"))
        assertThat(convertedKotlinCode, containsString("@version 2.0"))
        assertThat(convertedKotlinCode, containsString("@since 1.0"))
        assertThat(convertedKotlinCode, containsString("@param input the input value"))
        assertThat(convertedKotlinCode, containsString("@return the calculated result"))
        assertThat(convertedKotlinCode, containsString("@throws IllegalArgumentException if input is negative"))
    }
}
