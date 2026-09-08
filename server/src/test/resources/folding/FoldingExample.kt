// KDoc comment
// that spans multiple lines

import java.io.File
import java.net.URL
import kotlin.collections.List

/**
 * This is a sample class
 * with multi-line KDoc
 */
class FoldingExample {
    val name: String = "test"

    /**
     * A method with KDoc
     */
    fun doSomething() {
        val x = 1
        val y = 2
        println(x + y)
    }

    fun anotherMethod() {
        if (true) {
            println("inside block")
        }
    }
}

object Companion {
    fun helper() {
        println("helper")
    }
}

fun topLevelFunction() {
    val list = listOf(1, 2, 3)
    for (item in list) {
        println(item)
    }
}
