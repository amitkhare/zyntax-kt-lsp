sealed interface Expr
data class Number(val value: Int) : Expr
data class Add(val left: Expr, val right: Expr) : Expr
object UnitExpr : Expr

class NotSealed
