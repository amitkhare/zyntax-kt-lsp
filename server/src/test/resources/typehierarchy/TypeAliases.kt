open class Base
class Derived : Base()

typealias MyAlias = Base
typealias AliasChain = MyAlias

fun test(a: MyAlias, b: AliasChain) {
}
