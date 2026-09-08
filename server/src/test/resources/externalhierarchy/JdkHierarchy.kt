import kotlin.collections.AbstractList

class MyList : AbstractList<String>() {
    override val size: Int get() = 0
    override fun get(index: Int): String = ""
}
