open class Animal(
    open val name: String = "Animal"
)
open class Pet : Animal() {
    override val name: String = "Pet"
}
class Dog : Pet() {
    override val name: String = "Dog"
}

interface CanSpeak
interface CanFetch : CanSpeak
class Labrador : Pet(), CanFetch
