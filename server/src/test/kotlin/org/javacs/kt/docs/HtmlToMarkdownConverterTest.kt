package org.javacs.kt.docs

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test

class HtmlToMarkdownConverterTest {

    private val converter = HtmlToMarkdownConverter()

    @Test
    fun `multi-line pre code block preserves generics and indentation`() {
        val input = """
            /** Processes a {@link Request} and produces a result.
             * <p>
             * This method handles both GET and POST requests. It validates
             * the input before delegating to the appropriate handler.
             * <p>
             * Example usage:
             * <pre>{@code
             * Result result = processor.process(request);
             * if (result.isValid()) {
             *     Map<String, List<Result>> grouped = groupByType(results);
             *     handler.handle(result);
             * }
             * }</pre>
             * <p>
             * The {@code processor} must be <b>fully initialized</b> before calling
             * this method. See the {@link ProcessorFactory} documentation for setup
             * instructions and {@link ProcessorConfig} for available options.
             *
             * @see com.example.processor.ProcessorFactory#createProcessor
             */
        """.trimIndent()

        val result = converter.convert(input)

        // Generics in code block are preserved (nesting of < and >)
        assertThat(result, containsString("Map<String, List<Result>>"))

        // Code block is present as fenced java block
        assertThat(result, containsString("```java"))

        // Code indentation is preserved
        assertThat(result, containsString("    handler.handle(result)"))

        // Brace-depth tracking: code block with nested { }
        assertThat(result, containsString("if (result.isValid()) {"))
        assertThat(result, containsString("groupByType(results)"))

        // {@link} converted to bracket style
        assertThat(result, containsString("[Request]"))
        assertThat(result, containsString("[ProcessorFactory]"))
        assertThat(result, containsString("[ProcessorConfig]"))

        // @see converted to "See also" section with method reference
        assertThat(result, containsString("**See also:**"))
        assertThat(result, containsString("[ProcessorFactory.createProcessor]"))

        // <b> tags are bold
        assertThat(result, containsString("**fully initialized**"))

        // Paragraph breaks preserved
        assertThat(result, containsString("Processes a [Request]"))
        assertThat(result, containsString("This method handles"))
        assertThat(result, containsString("The `processor` must be"))
    }

    @Test
    fun `param return see and throws tags are grouped`() {
        val input = """
            /**
             * Validates a {@link User} account and applies the given action.
             * <p>
             * This method checks that the user has the required permissions
             * before executing the action. A warning is logged if the user
             * lacks sufficient privileges.
             * <p>
             * This is used by the admin dashboard to enforce access control
             * on sensitive operations.
             *
             * @param user the user account to validate (must not be null)
             * @return the result of the validation check
             * @throws AccessDeniedException if the user lacks required permissions
             * @throws IllegalArgumentException if the user is null
             */
        """.trimIndent()

        val result = converter.convert(input)

        // {@link User}
        assertThat(result, containsString("[User]"))

        // @param tag section with backtick name
        assertThat(result, containsString("**Parameters:**"))
        assertThat(result, containsString("`user`"))

        // @return section
        assertThat(result, containsString("**Returns:**"))
        assertThat(result, containsString("validation check"))

        // @throws section with multiple entries
        assertThat(result, containsString("**Throws:**"))
        assertThat(result, containsString("`AccessDeniedException`"))
        assertThat(result, containsString("`IllegalArgumentException`"))

        // Paragraphs are separate
        assertThat(result, containsString("This method checks"))
        assertThat(result, containsString("This is used by the admin"))
    }

    @Test
    fun `single-line code inline`() {
        val input = """
            /** Use the {@code register} method to register commands. */
        """.trimIndent()

        val result = converter.convert(input)

        assertThat(result, containsString("`register`"))
    }

    @Test
    fun `value and literal tags`() {
        val input = """
            /** Default value is {@value DEFAULT_TIMEOUT}. Use {@literal <literal> text}. */
        """.trimIndent()

        val result = converter.convert(input)

        assertThat(result, containsString("`DEFAULT_TIMEOUT`"))
        assertThat(result, containsString("<literal> text"))
    }

    @Test
    fun `link with space-separated label`() {
        val input = """
            /** See {@link String#format(String, Object...) The format method} for details. */
        """.trimIndent()

        val result = converter.convert(input)

        assertThat(result, containsString("[The format method]"))
    }

    @Test
    fun `multiple see references`() {
        val input = """
            /**
             * Related utilities.
             *
             * @see StringUtils
             * @see NumberUtils#isDigits(String)
             */
        """.trimIndent()

        val result = converter.convert(input)

        assertThat(result, containsString("**See also:**"))
        assertThat(result, containsString("[StringUtils]"))
        assertThat(result, containsString("[NumberUtils.isDigits]"))
    }

    @Test
    fun `single-line pre code`() {
        val input = """
            /** Example: <pre>{@code int x = 1;}</pre> */
        """.trimIndent()

        val result = converter.convert(input)

        assertThat(result, containsString("`int x = 1;`"))
    }

    @Test
    fun `code inline inside return and throws tags`() {
        val input = """
            /**
             * Creates a {@code Duration} from the given parameters.
             *
             * @param days the number of days, positive or negative
             * @return a {@code Duration}, not null
             * @throws ArithmeticException if the input days exceeds the capacity of {@code Duration}
             */
        """.trimIndent()

        val result = converter.convert(input)

        // Body {@code} -> backtick
        assertThat(result, containsString("`Duration`"))

        // @param tag: {@code} inside description -> backtick
        assertThat(result, containsString("`days`"))

        // @return tag: {@code Duration} -> backtick, not placeholder
        assertThat(result, containsString("**Returns:**"))
        assertThat(result, containsString("`Duration`, not null"))
        assertThat(result, not(containsString("CODE0")))
        assertThat(result, not(containsString("CODE1")))
        assertThat(result, not(containsString("_KTLSP_CODE_")))

        // @throws tag: {@code Duration} -> backtick
        assertThat(result, containsString("**Throws:**"))
        assertThat(result, containsString("`Duration`"))
    }

    @Test
    fun `table is converted to markdown table`() {
        val input = """
            /**
             * <table>
             * <tr><th>Name</th><th>Value</th></tr>
             * <tr><td>a</td><td>1</td></tr>
             * </table>
             */
        """.trimIndent()

        val result = converter.convert(input)

        assertThat(result, containsString("Name"))
        assertThat(result, containsString("---"))
    }
}
