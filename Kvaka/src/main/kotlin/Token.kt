sealed class TokenType {
    // Ключевые слова управления
    object Import : TokenType()
    object Func : TokenType()
    object If : TokenType()
    object Else : TokenType()
    object Loop : TokenType()
    object Break : TokenType()
    object Return : TokenType()
    // Типы данных
    object IntType : TokenType()
    object LongType : TokenType()
    object FloatType : TokenType()
    object StringType : TokenType()
    object BoolType : TokenType()
    // Литералы
    object True : TokenType()
    object False : TokenType()
    object Null : TokenType()
    data class Identifier(val name: String) : TokenType()
    data class IntLiteral(val value: Int) : TokenType()
    data class LongLiteral(val value: Long) : TokenType()
    data class FloatLiteral(val value: Float) : TokenType()
    data class StringLiteral(val value: String) : TokenType()
    // Разделители и пунктуация
    object Comma : TokenType()
    object Dot : TokenType()
    object LParen : TokenType()
    object RParen : TokenType()
    object LBrace : TokenType()
    object RBrace : TokenType()
    object LBracket : TokenType()
    object RBracket : TokenType()
    // Операторы
    object Equal : TokenType()
    object EqualEqual : TokenType()
    object Less : TokenType()
    object Greater : TokenType()
    object LessEqual : TokenType()
    object GreaterEqual : TokenType()
    object And : TokenType()
    object Or : TokenType()
    object Not : TokenType()
    object Plus : TokenType()
    object Minus : TokenType()
    object Star : TokenType()
    object Slash : TokenType()
    object PlusEqual : TokenType()
    object MinusEqual : TokenType()
    object StarEqual : TokenType()
    object SlashEqual : TokenType()
    object Percent : TokenType()
    // Системные
    object EOF : TokenType()
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val line: Int
)