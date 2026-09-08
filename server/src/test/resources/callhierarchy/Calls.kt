fun caller() {
    callee()
}

fun callee() {
    // empty
}

fun crossFileCaller() {
    a()
}

class MyClass {
    fun methodCaller() {
        methodCallee()
    }

    fun methodCallee() {
        // empty
    }
}
