// File with type alias for testing go to declaration
typealias StringList = List<String>

interface MyInterface {
    fun doSomething(): String
}

class DeclarationExample : MyInterface {
    override fun doSomething(): String {
        return "hello"
    }

    fun useTypeAlias(): StringList {
        return listOf("a", "b")
    }
}
