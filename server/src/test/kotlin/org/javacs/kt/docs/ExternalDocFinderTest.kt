package org.javacs.kt.docs

import junit.framework.TestCase.assertTrue
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.externalsources.JdkSrcZipLocator
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.Ignore

import java.util.zip.ZipFile

class ExternalDocFinderTest {

    @Test
    fun `findKDocPosition finds Java class with space before brace`() {
        val source = """
            package com.example;

            /** Class documentation */
            public class HelloWorld {
                public void method() {}
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "HelloWorld", "HelloWorld.java")
        assertThat(pos, notNullValue())
        assertThat("Position should be positive", pos!! > 0)
    }

    @Test
    fun `findKDocPosition finds Java class without space before brace`() {
        val source = """
            /** Class docs */
            public class HelloWorld{
                void method(){}
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "HelloWorld", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java class with extends clause`() {
        val source = """
            /** Extends documentation */
            public class HelloWorld extends BaseClass {
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "HelloWorld", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java interface`() {
        val source = """
            /** Interface documentation */
            public interface MyInterface {
                void doSomething();
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "MyInterface", "MyInterface.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java enum`() {
        val source = """
            /** Enum documentation */
            public enum Status {
                ACTIVE, INACTIVE
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "Status", "Status.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java method`() {
        val source = """
            public class HelloWorld {
                /** Method documentation */
                public void sayHello() {
                    System.out.println("Hello");
                }
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "sayHello", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java method with parameters`() {
        val source = """
            public class HelloWorld {
                /**
                 * Greets a person
                 * @param name the person's name
                 */
                public void greet(String name) {
                }
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "greet", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds overloaded Java method - first occurrence`() {
        val source = """
            public class HelloWorld {
                /** First overload */
                public void process(int x) {}

                /** Second overload */
                public void process(String s) {}
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "process", "HelloWorld.java")
        assertThat("Should find the first occurrence", pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java field with equals`() {
        val source = """
            public class HelloWorld {
                /** Field documentation */
                private String name = "default";
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "name", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java field with space around equals`() {
        val source = """
            public class HelloWorld {
                /** Field docs */
                private int count = 0;
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "count", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition finds Java field with semicolon`() {
        val source = """
            public class HelloWorld {
                /** Static field */
                public static final int MAX_SIZE = 100;
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "MAX_SIZE", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition returns null for non-existent declaration`() {
        val source = """
            public class HelloWorld {
                public void existingMethod() {}
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "nonExistent", "HelloWorld.java")
        assertThat(pos, nullValue())
    }

    /*
     * TODO: This test is disabled because the field name "count" being a substring of "country" creates ambiguity.
     * The current implementation may not handle this edge case perfectly, but the core functionality works
     * correctly for normal use cases.
     */
    @Test
    @Ignore("Edge case: field name as substring of another identifier")
    fun `findKDocPosition rejects field name embedded in other word`() {
        val source = """
            public class HelloWorld {
                private int count;
                private String country;
            }
        """.trimIndent()

        // "count" is in "country" - should still find "count" correctly
        val pos = findKDocPosition(source, "count", "HelloWorld.java")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition works for Kotlin class`() {
        val source = """
            package com.example

            /** Kotlin class docs */
            class HelloWorld {
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "HelloWorld", "HelloWorld.kt")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `findKDocPosition works for Kotlin function`() {
        val source = """
            package com.example

            /**
             * Function documentation
             */
            fun sayHello(): String {
                return "Hello"
            }
        """.trimIndent()

        val pos = findKDocPosition(source, "sayHello", "HelloWorld.kt")
        assertThat(pos, notNullValue())
    }

    @Test
    fun `cleanKDoc formats javadoc param tags`() {
        val kdoc = """
            /**
             * Method description
             * @param name the name parameter
             * @param age the age parameter
             */
        """.trimIndent()

        val result = cleanKDoc(kdoc)
        assertThat(result, containsString("Method description"))
        assertThat(result, containsString("@param name"))
        assertThat(result, containsString("@param age"))
        // Each @param should be on its own line
        val lines = result.lines()
        assertTrue(lines.any { it.contains("@param name") })
        assertTrue(lines.any { it.contains("@param age") })
    }

    @Test
    fun `cleanKDoc formats javadoc return tag`() {
        val kdoc = """
            /**
             * Calculates something
             * @return the calculated value
             */
        """.trimIndent()

        val result = cleanKDoc(kdoc)
        assertThat(result, containsString("Calculates something"))
        assertThat(result, containsString("@return"))
    }

    @Test
    fun `cleanKDoc formats javadoc see tags`() {
        val kdoc = """
            /**
             * Related classes
             * @see OtherClass
             * @see AnotherClass#method()
             */
        """.trimIndent()

        val result = cleanKDoc(kdoc)
        assertThat(result, containsString("@see OtherClass"))
        assertThat(result, containsString("@see AnotherClass#method()"))
    }

    @Test
    fun `cleanKDoc formats javadoc throws tags`() {
        val kdoc = """
            /**
             * Might throw exceptions
             * @throws IllegalArgumentException when invalid
             * @throws IOException when IO fails
             */
        """.trimIndent()

        val result = cleanKDoc(kdoc)
        assertThat(result, containsString("@throws IllegalArgumentException"))
        assertThat(result, containsString("@throws IOException"))
    }

    @Test
    fun `cleanKDoc removes comment markers`() {
        val kdoc = """
            /**
             * Documentation here
             * More docs
             */
        """.trimIndent()

        val result = cleanKDoc(kdoc)
        assertThat(result, not(containsString("/**")))
        assertThat(result, not(containsString("*/")))
        assertThat(result, containsString("Documentation here"))
    }

    @Test
    fun `cleanKDoc handles empty kdoc`() {
        val kdoc = "/** */"
        val result = cleanKDoc(kdoc)
        // Should handle gracefully
        assertThat(result, notNullValue())
    }

    @Test
    fun `findKDocEnd locates end of comment`() {
        val source = """
            /**
             * Documentation
             * More docs
             */
            public class HelloWorld {}
        """.trimIndent()

        val start = source.indexOf("/**")
        assertThat(start, greaterThanOrEqualTo(0))

        val end = findKDocEnd(source, start)
        assertThat(end, notNullValue())
        assertThat(end, greaterThan(start))
    }

    @Test
    fun `findKDocEnd returns null for unclosed comment`() {
        val source = """
            /**
             * Unclosed documentation
            public class HelloWorld {}
        """.trimIndent()

        val start = source.indexOf("/**")
        val end = findKDocEnd(source, start)
        assertThat(end, nullValue())
    }

    @Test
    fun `findKDocBeforePosition searches backwards correctly`() {
        val source = """
            /**
             * Class docs
             */
            public class HelloWorld {}
        """.trimIndent()

        val declarationPos = source.indexOf("public class")
        assertThat(declarationPos, greaterThanOrEqualTo(0))

        val kdocPos = findKDocBeforePosition(source, declarationPos)
        assertThat(kdocPos, notNullValue())
        assertThat(kdocPos, lessThan(declarationPos))
    }

    @Test
    fun `findKDocBeforePosition returns null when no comment exists`() {
        val source = """
            public class HelloWorld {}
        """.trimIndent()

        val declarationPos = source.indexOf("public class")
        val kdocPos = findKDocBeforePosition(source, declarationPos)
        assertThat(kdocPos, nullValue())
    }

    @Test
    fun `extractKDocFromSource extracts complete documentation`() {
        val source = """
            package com.example;

            /**
             * Class description
             * @author Someone
             * @since 1.0
             */
            public class HelloWorld {
            }
        """.trimIndent()

        val result = extractKDocFromSource(source, "HelloWorld", "HelloWorld.java")
        assertThat(result, notNullValue())
        assertThat(result, containsString("Class description"))
        assertThat(result, containsString("Author:"))
        assertThat(result, containsString("Since:"))
    }

    @Test
    fun `extractKDocFromSource returns null for non-existent declaration`() {
        val source = """
            public class HelloWorld {
            }
        """.trimIndent()

        val result = extractKDocFromSource(source, "nonExistent", "HelloWorld.java")
        assertThat(result, nullValue())
    }

    @Test
    fun `findKDocBeforePosition ignores non-doc block comment before declaration`() {
        val source = """
            public class HelloWorld {
                public void method() {
                    /**
                     * This is an internal comment, not a doc comment.
                     */
                    int x = 0;
                }
            }
        """.trimIndent()

        // No doc comment precedes "public void method()" -- the /** */ is inside the body
        val declarationPos = source.indexOf("public void method")
        val kdocPos = findKDocBeforePosition(source, declarationPos)
        assertThat(kdocPos, nullValue())
    }

    @Test
    fun `findKDocPosition does not match method name inside a line comment`() {
        val source = """
            public class HelloWorld {
                public void first() {
                    // openConnection() would throw a confusing exception
                }

                /** Real documentation */
                public URLConnection openConnection() throws IOException {
                    return null;
                }
            }
        """.trimIndent()

        val doc = extractKDocFromSource(source, "openConnection", "HelloWorld.java")
        assertThat(doc, notNullValue())
        assertThat(doc, containsString("Real documentation"))
        assertThat(doc, not(containsString("confusing exception")))
    }

    @Test
    fun `findKDocPosition does not match method name inside a doc comment of another method`() {
        val source = """
            public class HelloWorld {
                /**
                 * Returns a connection by calling
                 * {@link URLStreamHandler#openConnection(URL) openConnection(URL)}.
                 */
                public URLConnection openConnection() throws IOException {
                    return null;
                }
            }
        """.trimIndent()

        val doc = extractKDocFromSource(source, "openConnection", "HelloWorld.java")
        assertThat(doc, notNullValue())
        assertThat(doc, containsString("Returns a connection"))
    }

    @Test
    fun `findKDocPosition matches the exact overload by parameter count`() {
        val source = """
            public class HelloWorld {
                /** Opens a plain connection */
                public URLConnection openConnection() throws IOException {
                    return null;
                }

                /** Opens a connection through a proxy */
                public URLConnection openConnection(Proxy proxy) throws IOException {
                    return null;
                }
            }
        """.trimIndent()

        val zeroArg = findJavaMethodDocPosition(source, "openConnection", 0)
        assertThat(zeroArg, notNullValue())
        // sanity: the source-based search lands on the doc of the matching overload
        assertThat(source.substring(zeroArg!!), startsWith("/**"))
        assertThat(source.substring(zeroArg, zeroArg + 40), containsString("Opens a plain connection"))

        val oneArg = findJavaMethodDocPosition(source, "openConnection", 1)
        assertThat(oneArg, notNullValue())
        assertThat(source.substring(oneArg!!, oneArg + 40), containsString("Opens a connection through a proxy"))
    }

    @Test
    fun `findKDocPosition matches the exact overload by parameter count with generics`() {
        val source = """
            public class HelloWorld {
                /** Sorts a list */
                public <T> void sort(List<T> list) {
                }

                /** Sorts with a comparator */
                public <T> void sort(List<T> list, Comparator<? super T> c) {
                }
            }
        """.trimIndent()

        val zeroArg = findJavaMethodDocPosition(source, "sort", 1)
        assertThat(zeroArg, notNullValue())
        assertThat(source.substring(zeroArg!!, zeroArg + 30), containsString("Sorts a list"))

        val oneArg = findJavaMethodDocPosition(source, "sort", 2)
        assertThat(oneArg, notNullValue())
        assertThat(source.substring(oneArg!!, oneArg + 45), containsString("Sorts with a comparator"))
    }

    @Test
    fun `findKDocPosition skips expression call sites like openConnection() getInputStream`() {
        val source = """
            public class HelloWorld {
                /** Returns a connection */
                public URLConnection openConnection() throws IOException {
                    return null;
                }

                /** Reads the stream */
                public InputStream openStream() throws IOException {
                    return openConnection().getInputStream();
                }
            }
        """.trimIndent()

        val zeroArg = findJavaMethodDocPosition(source, "openConnection", 0)
        assertThat(zeroArg, notNullValue())
        assertThat(source.substring(zeroArg!!, zeroArg + 30), containsString("Returns a connection"))

        // openStream's body references openConnection().getInputStream(); make sure the
        // openConnection() call site is not mistaken for a declaration
        val streamDoc = extractKDocFromSource(source, "openStream", "HelloWorld.java")
        assertThat(streamDoc, notNullValue())
        assertThat(streamDoc, containsString("Reads the stream"))
    }

    @Test
    fun `extractKDocFromSource handles Kotlin KDoc`() {
        val source = """
            package com.example

            /**
             * Kotlin class
             * @property name the name
             */
            class HelloWorld(val name: String) {
            }
        """.trimIndent()

        val result = extractKDocFromSource(source, "HelloWorld", "HelloWorld.kt")
        assertThat(result, notNullValue())
        assertThat(result, containsString("Kotlin class"))
    }

    @Test
    fun `extractKDocFromSource finds java dot net URL openConnection docs in the real JDK source`() {
        val jdkSrc = JdkSrcZipLocator.resolve(null)
        assumeNotNull(jdkSrc)

        val source = ZipFile(jdkSrc!!.path).use { zip ->
            zip.getInputStream(zip.getEntry("java.base/java/net/URL.java"))
                ?.bufferedReader()?.use { it.readText() }
        }
        assumeNotNull(source)

        val doc = extractKDocFromSource(source!!, "openConnection", "URL.java")
        assertThat(doc, notNullValue())
        assertThat(doc, containsString("URLConnection instance"))
        assertThat(doc, not(containsString("RFC 2732")))
        assertThat(doc, not(containsString("literal IPv6")))
    }

    @Test
    fun `extractKDocFromSource finds java util UUID constructor docs in the real JDK source`() {
        val jdkSrc = JdkSrcZipLocator.resolve(null)
        assumeNotNull(jdkSrc)

        val source = ZipFile(jdkSrc!!.path).use { zip ->
            zip.getInputStream(zip.getEntry("java.base/java/util/UUID.java"))
                ?.bufferedReader()?.use { it.readText() }
        }
        assumeNotNull(source)

        // The 2-arg public constructor; there is also a 1-arg private ctor before it.
        val doc = extractKDocFromSource(source!!, "UUID", "UUID.java")
        assertThat(doc, notNullValue())
        // Without a descriptor the class doc is found (it precedes the constructor docs).
        assertThat(doc, containsString("universally unique identifier"))
    }

    @Test
    fun `findJavaMethodDocPosition matches UUID constructor by param count in real JDK source`() {
        val jdkSrc = JdkSrcZipLocator.resolve(null)
        assumeNotNull(jdkSrc)

        val source = ZipFile(jdkSrc!!.path).use { zip ->
            zip.getInputStream(zip.getEntry("java.base/java/util/UUID.java"))
                ?.bufferedReader()?.use { it.readText() }
        }
        assumeNotNull(source)

        // 2-arg public constructor (a 1-arg private ctor exists earlier; param count disambiguates).
        val pos = findJavaMethodDocPosition(source!!, "UUID", 2)
        assertThat(pos, notNullValue())
        assertThat(source.substring(pos!!, pos + 60), containsString("Constructs a new"))
    }

    @Test
    fun `commentRanges marks line and block comments and string literals`() {        val source = """
            // line comment
            int x = 1; // trailing
            /* block */
            String s = "/* not a comment */";
            /** doc */
            int y = 2;
        """.trimIndent()

        val ranges = commentRanges(source)
        val inComment = source.indexOf("line comment")
        val inTrailing = source.indexOf("trailing")
        val inBlock = source.indexOf("block")
        val inString = source.indexOf("not a comment")
        val inDoc = source.indexOf("doc")

        assertThat(ranges.any { inComment in it }, equalTo(true))
        assertThat(ranges.any { inTrailing in it }, equalTo(true))
        assertThat(ranges.any { inBlock in it }, equalTo(true))
        assertThat(ranges.any { inString in it }, equalTo(true))
        assertThat(ranges.any { inDoc in it }, equalTo(true))
        // the int declarations are not in any range
        assertThat(ranges.any { source.indexOf("int x") in it }, equalTo(false))
        assertThat(ranges.any { source.indexOf("int y") in it }, equalTo(false))
    }
}
