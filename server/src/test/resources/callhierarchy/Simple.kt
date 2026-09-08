fun a() {
    b()
    c()
}

fun b() {
    // no outgoing calls
}

fun c() {
    // no outgoing calls
}

fun d() {
    b()
    b()
}

fun e() {
    // never called
}

fun f() {
    println(x)
}

val x = 1

fun g() {
    val fn = ::b
    b()
}

val y = b()

class WithConstructorCalls {
    constructor() {
        b()
    }
}
