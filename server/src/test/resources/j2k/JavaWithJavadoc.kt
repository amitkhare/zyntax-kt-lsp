package j2k

import java.util.List

/**
 * This is a sample class with comprehensive Javadoc documentation.
 * It demonstrates various Javadoc tags and formatting options.
 *
 * @author John Doe
 * @version 1.0
 * @since 1.5
 */
class JavaWithJavadoc {
    /**
     * A simple field with basic documentation.
     */
    private var simpleField: String? = null

    /**
     * Calculates the sum of two integers.
     * This method demonstrates basic parameter and return documentation.
     *
     * @param a the first integer to add
     * @param b the second integer to add
     * @return the sum of a and b
     */
    fun add(a: Int, b: Int): Int {
        return a + b
    }

    /**
     * Processes a list of strings and returns a formatted result.
     * This method demonstrates more complex documentation with multiple
     * parameters and exception documentation.
     *
     * @param items the list of items to process
     * @param prefix the prefix to add to each item
     * @return a formatted string containing all processed items
     * @throws IllegalArgumentException if items is null
     * @throws NullPointerException if prefix is null
     */
    fun processItems(items: MutableList<String>, prefix: String): String {
        if (items == null) {
            throw IllegalArgumentException("items cannot be null")
        }
        if (prefix == null) {
            throw NullPointerException("prefix cannot be null")
        }

        var result: StringBuilder = StringBuilder()
        for (item in items) {
            result.append(prefix).append(item).append(" ")
        }
        return result.toString().trim()
    }

    /**
     * Demonstrates inline code tags.
     * Use `int x = 5` for inline code examples.
     * Reference other methods using [add(Int, Int)](#add(int, int)).
     * Use `<html>` for literal text.
     *
     * @return the result value
     */
    fun demonstrateInlineTags(): Int {
        return 42
    }

    /**
     * A method with deprecated documentation.
     *
     * @param value the input value
     * @return the processed value
     * @deprecated Use [demonstrateInlineTags()] instead
     */
    @Deprecated("")
    fun deprecatedMethod(value: Int): Int {
        return value * 2
    }

    /**
     * Demonstrates complex documentation with multiple paragraphs
     * and various formatting.
     *
     * This is the first paragraph. It provides an overview
     * of the method's purpose and behavior.
     *
     * This is the second paragraph. It provides additional
     * details about edge cases and usage examples.
     *
     * @param input the input string to process
     * @return the processed string
     * @see [String.toUpperCase()]
     * @see "Java String Documentation"
     */
    fun complexDocumentation(input: String): String {
        return input.toUpperCase()
    }

    /**
     * Inner class with its own documentation.
     *
     * @param <T> the type parameter
     */
    class InnerClass<T> {
        /**
         * A generic method in the inner class.
         *
         * @param value the value to process
         * @return the processed value
         */
        fun process(value: T): T {
            return value
        }
    }
}
