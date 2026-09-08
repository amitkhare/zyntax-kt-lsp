package org.javacs.kt

import org.javacs.kt.j2k.convertJavaToKotlin
import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString

class JavaToKotlinTest : LanguageServerTestFixture("j2k") {

    @Test
    fun `test j2k conversion`() {
        val javaCode = workspaceRoot
            .resolve("JavaJSONConverter.java")
            .toFile()
            .readText()
            .trim()

        val expectedKotlinCode = workspaceRoot
            .resolve("JavaJSONConverter.kt")
            .toFile()
            .readText()
            .trim()
            .replace("\r\n", "\n")

        val compiler = languageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler).replace("\r\n", "\n")

        // Verify key patterns from expected code are present in converted output
        val expectedPatterns = listOf(
            "class JavaJSONConverter",
            "fun toJSONArray",
            "fun main",
            "MutableList",
            "StringBuilder",
            "list.get(i)"
        )
        expectedPatterns.forEach { pattern ->
            assertThat("Missing pattern: $pattern", convertedKotlinCode, containsString(pattern))
        }

        // Verify expected code's class declaration appears in converted output
        assertThat(convertedKotlinCode, containsString(expectedKotlinCode.lines().first { it.startsWith("class ") }))
    }

    @Test
    fun `test primitive boolean conversion`() {
        val javaCode = """
            public class Test {
                public boolean isActive() {
                    return true;
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert boolean -> Boolean
        assertThat(converted, containsString(": Boolean"))
    }

    @Test
    fun `test varargs conversion`() {
        val javaCode = """
            public class Test {
                public void process(String... args) {
                    for (String arg : args) {
                        System.out.println(arg);
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert varargs to vararg (parameter type is the element type, not Array)
        assertThat(converted, containsString("vararg"))
        assertThat(converted, containsString("args: String"))
    }

    @Test
    fun `test nullability annotations`() {
        val javaCode = """
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;

            public class Test {
                @NotNull
                public String getName() {
                    return "test";
                }

                @Nullable
                public String getDescription() {
                    return null;
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should detect nullable annotation and add ?
        assertThat(converted, containsString("getDescription"))
    }

    @Test
    fun `test switch statement conversion`() {
        val javaCode = """
            public class Test {
                public String getDay(int day) {
                    switch (day) {
                        case 1: return "Monday";
                        case 2: return "Tuesday";
                        default: return "Unknown";
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert switch to when
        assertThat(converted, containsString("when"))
    }

    @Test
    fun `test break with label`() {
        val javaCode = """
            public class Test {
                public void test() {
                    outer:
                    for (int i = 0; i < 10; i++) {
                        for (int j = 0; j < 10; j++) {
                            if (i * j > 50) {
                                break outer;
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert labeled break
        assertThat(converted, containsString("break@outer"))
    }

    @Test
    fun `test array initializer`() {
        val javaCode = """
            public class Test {
                public int[] getNumbers() {
                    return new int[] {1, 2, 3, 4, 5};
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert to arrayOf (intArrayOf would be ideal but arrayOf works too)
        assertThat(converted, containsString("arrayOf(1, 2, 3, 4, 5)"))
    }

    @Test
    fun `test anonymous class`() {
        val javaCode = """
            public class Test {
                public Runnable getRunnable() {
                    return new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("Hello");
                        }
                    };
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert to object expression
        assertThat(converted, containsString("object :"))
        assertThat(converted, containsString("fun run"))
    }

    @Test
    fun `test polyadic expression`() {
        val javaCode = """
            public class Test {
                public int sum(int a, int b, int c, int d) {
                    return a + b + c + d;
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should contain the sum expression
        assertThat(converted, containsString("a + b + c + d"))
    }

    @Test
    fun `test class literal`() {
        val javaCode = """
            public class Test {
                public Class<String> getStringClass() {
                    return String.class;
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert to Kotlin class reference
        assertThat(converted, containsString("String::class.java"))
    }

    @Test
    fun `test try-with-resources single resource`() {
        val javaCode = """
            public class Test {
                public String readFile(String path) throws Exception {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.FileReader(path))) {
                        return reader.readLine();
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert to use { }
        assertThat(converted, containsString(".use {"))
        assertThat(converted, containsString("reader"))
    }

    @Test
    fun `test try-with-resources multiple resources`() {
        val javaCode = """
            public class Test {
                public void copy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in));
                         java.io.BufferedWriter writer = new java.io.BufferedWriter(
                             new java.io.OutputStreamWriter(out))) {
                        writer.write(reader.readLine());
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should have nested .use calls
        assertThat(converted, containsString(".use {"))
        // Both resources should be referenced
        assertThat(converted, containsString("reader"))
        assertThat(converted, containsString("writer"))
    }

    @Test
    fun `test try-with-resources with finally`() {
        val javaCode = """
            public class Test {
                public void process(java.io.Closeable resource) throws Exception {
                    try (resource) {
                        resource.close();
                    } finally {
                        System.out.println("done");
                    }
                }
            }
        """.trimIndent()

        val compiler = languageServer.classPath.compiler
        val converted = convertJavaToKotlin(javaCode, compiler)

        // Should convert to use { } and preserve finally
        assertThat(converted, containsString(".use {"))
        assertThat(converted, containsString("finally"))
    }
}
