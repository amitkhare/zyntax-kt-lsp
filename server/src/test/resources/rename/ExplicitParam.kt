package rename

fun main() {
    val list = listOf(1, 2, 3)

    // Explicit parameter in forEach
    list.forEach { item -> println(item) }

    // Explicit parameter with multiple usages
    list.filter { triState -> triState > 0 }

    // Nested lambdas with explicit params
    val nested = listOf(listOf(1, 2), listOf(3, 4))
    nested.map { outer -> outer.map { inner -> inner * 2 } }
}
