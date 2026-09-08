package rename

fun main() {
    val list = listOf(1, 2, 3)
    list.forEach { println(it) }
    list.map { it * 2 }
    list.filter { it > 1 }

    val nested = listOf(listOf(1, 2), listOf(3, 4))
    nested.map { inner -> inner.map { it + 1 } }

    // Nested lambdas with implicit it
    val doubleNested = listOf(listOf(listOf(1), listOf(2)), listOf(listOf(3)))
    doubleNested.flatMap { outer -> outer.map { inner -> inner + it } }

    list.forEach { println("no it usage here") }
}
