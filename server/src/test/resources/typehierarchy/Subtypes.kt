interface Shape {
    fun area(): Double
}
class Circle : Shape {
    override fun area(): Double = 0.0
}
class Square : Shape {
    override fun area(): Double = 0.0
}

open class Vehicle
open class Car : Vehicle()
class Sedan : Car()
class SUV : Car()
