open class Base {
    constructor()
    constructor(x: Int)
}

class Derived : Base {
    constructor() : super()
    constructor(x: Int) : this()
    constructor(s: String) : super(x = s.length)
}
