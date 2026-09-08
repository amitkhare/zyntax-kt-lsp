package typedefinition

class SameFile {
    fun method() {}
}

class GoFrom {
    val typed: GoTo = GoTo()
    val other = GoTo()
    val sameFile: SameFile = SameFile()

    fun main() {
        val local = GoTo()
        println(local)
    }
}
