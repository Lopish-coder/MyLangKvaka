class Lexer(private val source: String) {
    private val tokens = mutableListOf<Token>()
    private var start = 0
    private var current = 0
    private var line = 1

    private val keywords = mapOf(
        // Ключевые слова
        "import" to TokenType.Import,
        "func" to TokenType.Func,
        "if" to TokenType.If,
        "else" to TokenType.Else,
        "loop" to TokenType.Loop,
        "break" to TokenType.Break,
        "return" to TokenType.Return,
        // Типы данных
        "int" to TokenType.IntType,
        "long" to TokenType.LongType,
        "float" to TokenType.FloatType,
        "string" to TokenType.StringType,
        "bool" to TokenType.BoolType,

        "true" to TokenType.True,
        "false" to TokenType.False,
        "null" to TokenType.Null,

        "and" to TokenType.And,
        "or" to TokenType.Or,
        "not" to TokenType.Not,
    )

    fun scanTokens(): List<Token> {
        while (!isAtEnd()) {
            start = current
            scanToken()
        }
        tokens.add(Token(TokenType.EOF, "", line))
        return tokens
    }

    private fun scanToken() {
        when (val c = advance()) {
            '(' -> addToken(TokenType.LParen)
            ')' -> addToken(TokenType.RParen)
            '{' -> addToken(TokenType.LBrace)
            '}' -> addToken(TokenType.RBrace)
            '[' -> addToken(TokenType.LBracket)
            ']' -> addToken(TokenType.RBracket)
            '+' -> addToken(if (match('=')) TokenType.PlusEqual else TokenType.Plus)
            '-' -> addToken(if (match('=')) TokenType.MinusEqual else TokenType.Minus)
            '*' -> addToken(if (match('=')) TokenType.StarEqual else TokenType.Star)
            '/' -> handleSlash()
            ',' -> addToken(TokenType.Comma)
            '.' -> addToken(TokenType.Dot)
            '=' -> addToken(if (match('=')) TokenType.EqualEqual else TokenType.Equal)
            '<' -> addToken(if (match('=')) TokenType.LessEqual else TokenType.Less)
            '>' -> addToken(if (match('=')) TokenType.GreaterEqual else TokenType.Greater)
            '%' -> addToken(TokenType.Percent)
            '"' -> readString()
            ' ', '\r', '\t' -> {}
            '\n' -> line++
            else -> handleComplexTokens(c)
        }
    }

    private fun handleSlash() {
        if (match('/')) {
            while (peek() != '\n' && !isAtEnd()) advance()
        } else if (match('=')) {
            addToken(TokenType.SlashEqual)
        } else {
            addToken(TokenType.Slash)
        }
    }

    private fun handleComplexTokens(c: Char) {
        when {
            c.isDigit() -> readNumber()
            c.isLetter() || c == '_' -> readIdentifier()
            else -> throw RuntimeException("Лексическая ошибка: Неизвестный символ '$c' на строке $line")
        }
    }

    private fun readString() {
        while (!isAtEnd()) {
            val next = peek()
            if (next == '"') break
            if (next == '\n') line++

            val c = advance()
            if (c == '\\') {
                if (isAtEnd()) throw RuntimeException("Лексическая ошибка: Незакрытый символ экранирования на строке $line")
                advance()
            }
        }

        if (isAtEnd()) throw RuntimeException("Лексическая ошибка: Незакрытая строка на строке $line")
        advance()

        val rawValue = source.substring(start + 1, current - 1)
        val unescapedValue = unescapeString(rawValue)
        tokens.add(Token(TokenType.StringLiteral(unescapedValue), source.substring(start, current), line))
    }

    private fun readNumber() {
        while (peek().isDigit()) advance()

        if (peek() == '.' && peekNext().isDigit()) {
            advance()
            while (peek().isDigit()) advance()
            val text = source.substring(start, current)
            tokens.add(Token(TokenType.FloatLiteral(text.toFloat()), text, line))
            return
        }

        val text = source.substring(start, current)

        // Пытаемся превратить в Int. Если не влезает — это автоматически Long!
        try {
            val intVal = text.toInt()
            tokens.add(Token(TokenType.IntLiteral(intVal), text, line))
        } catch (_: NumberFormatException) {
            val longVal = text.toLong()
            tokens.add(Token(TokenType.LongLiteral(longVal), text, line))
        }
    }

    private fun readIdentifier() {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val text = source.substring(start, current)
        val type = keywords[text] ?: TokenType.Identifier(text)
        tokens.add(Token(type, text, line))
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd()) return false
        if (source[current] != expected) return false
        current++
        return true
    }

    private fun advance(): Char = source[current++]
    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]
    private fun peekNext(): Char = if (current + 1 >= source.length) '\u0000' else source[current + 1]
    private fun isAtEnd(): Boolean = current >= source.length
    private fun addToken(type: TokenType) = tokens.add(Token(type, source.substring(start, current), line))
}
