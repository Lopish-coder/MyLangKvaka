sealed class ASTNode

sealed class Expr : ASTNode() {
    // Литералы
    data class IntLit(val value: Int) : Expr()
    data class LongLit(val value: Long) : Expr()
    data class FloatLit(val value: Float) : Expr()
    data class StringLit(val value: String) : Expr()
    data class BoolLit(val value: Boolean) : Expr()
    data class ArrayLit(val elements: List<Expr>) : Expr()
    data class Index(val array: Expr, val index: Expr) : Expr()
    object NullLit : Expr()
    //Переменные
    data class Variable(val name: String) : Expr()
    //Операции
    data class Binary(val left: Expr, val operator: Token, val right: Expr) : Expr()
    data class Logical(val left: Expr, val operator: Token, val right: Expr) : Expr()
    data class Unary(val operator: Token, val right: Expr) : Expr()
    //Вызовы
    data class Call(val target: Expr?, val name: String, val arguments: List<Expr>) : Expr()
}
sealed class Stmt : ASTNode() {
    // Переменные и присваивание
    data class VarDeclaration(val type: String, val name: String, val initializer: Expr) : Stmt()
    data class Assignment(val target: Expr, val expression: Expr) : Stmt()
    data class CompoundAssignment(val name: String, val operatorToken: Token, val expression: Expr) : Stmt()
    // Управление потоком
    data class If(val condition: Expr, val thenBranch: List<Stmt>, val elseBranch: List<Stmt>?) : Stmt()
    data class Loop(val condition: Expr, val body: List<Stmt>) : Stmt()
    object Break : Stmt()
    data class Return(val expression: Expr?) : Stmt()
    // Встроенные команды и Вызовы
    data class Print(val expression: Expr, val newLine: Boolean = true) : Stmt()
    data class FunctionCall(val expression: Expr.Call) : Stmt()
}
data class Argument(val type: String, val name: String)

data class FunctionDeclaration(
    val name: String,
    val arguments: List<Argument>,
    val returnType: String = "void",
    val body: List<Stmt>
) : ASTNode()

data class ImportDeclaration(val fullPath: String, val alias: String)

data class Program(
    val imports: List<ImportDeclaration> = emptyList(),
    val functions: List<FunctionDeclaration>
) : ASTNode()