class Parser(private val tokens: List<Token>) {
    private var current = 0

    fun parse(): Program {
        val imports = mutableListOf<ImportDeclaration>()
        val functions = mutableListOf<FunctionDeclaration>()

        while (match(TokenType.Import)) {
            val pathToken = advance()
            val fullPath = when (val type = pathToken.type) {
                is TokenType.StringLiteral -> type.value
                else -> throw RuntimeException("Ошибка: После 'import' ожидал путь в кавычках на строке ${pathToken.line}")
            }

            val alias = fullPath.substringAfterLast('/')
            imports.add(ImportDeclaration(fullPath, alias))
        }

        while (!isAtEnd()) {
            if (match(TokenType.Func)) {
                functions.add(functionDeclaration())
            } else {
                throw RuntimeException("Ошибка: Ожидалось объявление функции на строке ${peek().line}")
            }
        }

        return Program(imports = imports, functions = functions)
    }

    private fun functionDeclaration(): FunctionDeclaration {
        val nameToken = consume(TokenType.Identifier::class.java, "Ожидалось имя функции")
        val name = (nameToken.type as TokenType.Identifier).name

        consume(TokenType.LParen::class.java, "Ожидалась '('")

        val arguments = mutableListOf<Argument>()
        if (!check(TokenType.RParen::class.java)) {
            do {
                val typeToken = advance()
                var type = when (typeToken.type) {
                    is TokenType.IntType    -> "int"
                    is TokenType.LongType   -> "long"
                    is TokenType.FloatType  -> "float"
                    is TokenType.StringType -> "string"
                    is TokenType.BoolType   -> "bool"
                    is TokenType.Identifier -> typeToken.type.name
                    else -> throw RuntimeException("Ожидался тип данных на строке ${typeToken.line}")
                }

                if (match(TokenType.LBracket)) {
                    consume(TokenType.RBracket::class.java, "Ожидалась ']' после '['")
                    type = "$type[]"
                }

                val argNameToken = consume(TokenType.Identifier::class.java, "Ожидалось имя аргумента")
                val argName = (argNameToken.type as TokenType.Identifier).name

                arguments.add(Argument(type, argName))
            } while (match(TokenType.Comma))
        }

        consume(TokenType.RParen::class.java, "Ожидалась ')'")

        // Разбор типа возвращаемого значения функции
        val returnTypeToken = advance()
        val returnType = when (returnTypeToken.type) {
            is TokenType.IntType    -> "int"
            is TokenType.FloatType  -> "float"
            is TokenType.LongType   -> "long"
            is TokenType.StringType -> "string"
            is TokenType.BoolType   -> "bool"
            is TokenType.VoidType   -> "void" // <--- ДОБАВЛЯЕМ ПРОВЕРКУ НА VOID
            else -> throw RuntimeException("На строке ${returnTypeToken.line}: Ожидался тип возвращаемого значения (int, float, string, bool, void)")
        }

        consume(TokenType.LBrace::class.java, "Ожидалась '{'")

        val body = block()

        return FunctionDeclaration(name, arguments, returnType, body)
    }

    private fun block(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        while (!check(TokenType.RBrace::class.java) && !isAtEnd()) {
            statements.add(statement())
        }
        consume(TokenType.RBrace::class.java, "Ожидалась '}' после блока")
        return statements
    }

    private fun statement(): Stmt {
        return when {
            match(TokenType.Print) -> printStatement(false)
            match(TokenType.Println) -> printStatement(true)
            match(TokenType.IntType) -> varDeclaration("int")
            match(TokenType.LongType) -> varDeclaration("long")
            match(TokenType.FloatType) -> varDeclaration("float")
            match(TokenType.StringType) -> varDeclaration("string")
            match(TokenType.BoolType) -> varDeclaration("bool")
            match(TokenType.If) -> ifStatement()
            match(TokenType.Loop) -> loopStatement()
            match(TokenType.Break) -> Stmt.Break
            match(TokenType.Return) -> {
                val value = if (check(TokenType.RBrace::class.java) || isAtEnd()) {
                    null
                } else {
                    expression()
                }
                Stmt.Return(value)
            }
            check(TokenType.Identifier::class.java) -> {
                val nameToken = peek()
                // 1. Проверяем, не объявление ли это переменной с кастомным типом (например, Thread t = ...)
                if (current + 1 < tokens.size && tokens[current + 1].type is TokenType.Identifier) {
                    advance() // Съедаем тип (Thread)
                    return varDeclaration(nameToken.lexeme) // Передаем тип в varDeclaration
                }
                // 2. Смотрим вперёд (Lookahead), чтобы понять, что это за конструкция
                var i = 1
                // Пропускаем квадратные скобки, если это массив (например, arr[0] = 5)
                while (current + i < tokens.size && tokens[current + i].type is TokenType.LBracket) {
                    i++
                    // Пропускаем выражение внутри скобок до ]
                    while (current + i < tokens.size && tokens[current + i].type !is TokenType.RBracket) {
                        i++
                    }
                    if (current + i < tokens.size && tokens[current + i].type is TokenType.RBracket) {
                        i++ // пропускаем саму ]
                    }
                }

                val nextToken = if (current + i < tokens.size) tokens[current + i] else null
                // 3. Если следующий значимый токен — '=', это ПРИСВАИВАНИЕ
                if (nextToken?.type is TokenType.Equal) {
                    val target = expression() // Парсим левую часть (arr[0] или просто x)
                    consume(TokenType.Equal::class.java, "Ожидался '='")
                    val value = expression()  // Парсим правую часть
                    return Stmt.Assignment(target, value)
                }
                // 4. Если это +=, -=, *=, /= (только для простых переменных)
                if (nextToken?.type is TokenType.PlusEqual || nextToken?.type is TokenType.MinusEqual ||
                    nextToken?.type is TokenType.StarEqual || nextToken?.type is TokenType.SlashEqual) {
                    val nameToken = advance() // съедаем имя
                    val opToken = advance()   // съедаем оператор
                    val value = expression()
                    return Stmt.CompoundAssignment(nameToken.lexeme, opToken, value)
                }
                // 5. Иначе это просто вызов функции (например, myFunc(5))
                when (val expr = expression()) {
                    is Expr.Call -> Stmt.FunctionCall(expr)
                    else -> throw RuntimeException("Ошибка: Выражение на строке ${peek().line} не может использоваться как отдельное действие")
                }
            }
            else -> throw RuntimeException("Ошибка: Неверный синтаксис на строке ${peek().line}")
        }
    }

    private fun printStatement(newLine: Boolean): Stmt {
        consume(TokenType.LParen::class.java, "Ожидалась '('")
        val expr = expression()
        consume(TokenType.RParen::class.java, "Ожидалась ')'")
        return Stmt.Print(expr, newLine)
    }

    private fun ifStatement(): Stmt {
        consume(TokenType.LParen::class.java, "Ожидалась '(' перед условием if")
        val condition = expression()
        consume(TokenType.RParen::class.java, "Ожидалась ')' после условия if")

        consume(TokenType.LBrace::class.java, "Ожидалась '{' перед телом if")
        val thenBranch = block()

        var elseBranch: List<Stmt>? = null
        if (match(TokenType.Else)) {
            elseBranch = if (match(TokenType.If))  {
                listOf(ifStatement())
            } else {
                consume(TokenType.LBrace::class.java, "Ожидалась '{' перед телом else")
                block()
            }
        }

        return Stmt.If(condition, thenBranch, elseBranch)
    }

    private fun loopStatement(): Stmt {
        consume(TokenType.LParen::class.java, "Ожидалась '(' перед условием loop")
        val condition = expression()
        consume(TokenType.RParen::class.java, "Ожидалась ')' после условия loop")

        consume(TokenType.LBrace::class.java, "Ожидалась '{' перед телом loop")
        val body = block()

        return Stmt.Loop(condition, body)
    }

    private fun varDeclaration(baseType: String): Stmt {
        var type = baseType
        if (match(TokenType.LBracket)) {
            consume(TokenType.RBracket::class.java, "Ожидалась ']' после '['")
            type = "$baseType[]" // Делаем тип "int[]"
        }

        val nameToken = consume(TokenType.Identifier::class.java, "Ожидалось имя переменной")
        val name = nameToken.lexeme
        consume(TokenType.Equal::class.java, "Ожидался знак '='")
        val initializer = expression()
        return Stmt.VarDeclaration(type, name, initializer)
    }

    private fun expression(): Expr {
        var expr = logicAnd()
        while (match(TokenType.Or)) {
            val operator = previous()
            val right = logicAnd()
            expr = Expr.Logical(expr, operator, right)
        }
        return expr
    }

    private fun logicAnd(): Expr {
        var expr = equality()
        while (match(TokenType.And)) {
            val operator = previous()
            val right = equality()
            expr = Expr.Logical(expr, operator, right)
        }
        return expr
    }

    private fun equality(): Expr {
        var expr = arithmetic()
        while (match(TokenType.EqualEqual, TokenType.Less, TokenType.Greater, TokenType.LessEqual, TokenType.GreaterEqual)) {
            val operator = previous()
            val right = arithmetic()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun arithmetic(): Expr {
        var expr = term()
        while (match(TokenType.Plus, TokenType.Minus)) {
            val operator = previous()
            val right = term()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun term(): Expr {
        var expr = unary()
        while (match(TokenType.Star, TokenType.Slash, TokenType.Percent)) {
            val operator = previous()
            val right = unary()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun unary(): Expr {
        if (match(TokenType.Not)) {
            val operator = previous()
            val right = unary()
            return Expr.Unary(operator, right)
        }
        if (match(TokenType.Minus)) {
            val operator = previous()
            val right = unary()
            return Expr.Unary(operator, right)
        }
        return call()
    }

    private fun call(): Expr {
        var expr = primary()

        while (true) {
            if (match(TokenType.Dot)) {
                val methodToken = consume(TokenType.Identifier::class.java, "Ожидалось имя метода или поля после точки")
                val name = (methodToken.type as TokenType.Identifier).name

                if (match(TokenType.LParen)) {
                    val args = parseArguments()
                    expr = Expr.Call(expr, name, args)
                } else {
                    expr = Expr.Call(expr, name, emptyList())
                }
            } else if (match(TokenType.LBracket)) {
                // Новый блок для массивов: numbers[0]
                val index = expression()
                consume(TokenType.RBracket::class.java, "Ожидалась ']' после индекса")
                expr = Expr.Index(expr, index)
            } else {
                break
            }
        }

        return expr
    }

    private fun primary(): Expr {
        if (match(TokenType.LParen)) {
            val expr = expression()
            consume(TokenType.RParen::class.java, "Ожидалась закрывающая ')'")
            return expr
        }

        if (match(TokenType.LBracket)) {
            val elements = mutableListOf<Expr>()
            if (!check(TokenType.RBracket::class.java)) {
                do {
                    elements.add(expression())
                } while (match(TokenType.Comma))
            }
            consume(TokenType.RBracket::class.java, "Ожидалась ']'")
            return Expr.ArrayLit(elements)
        }

        val token = advance()
        return when (val type = token.type) {
            is TokenType.IntLiteral -> Expr.IntLit(type.value)
            is TokenType.LongLiteral -> Expr.LongLit(type.value)
            is TokenType.FloatLiteral -> Expr.FloatLit(type.value)
            is TokenType.StringLiteral -> Expr.StringLit(type.value)
            TokenType.True -> Expr.BoolLit(true)
            TokenType.False -> Expr.BoolLit(false)
            TokenType.Null -> Expr.NullLit
            is TokenType.Identifier -> {
                if (match(TokenType.LParen)) {
                    val args = parseArguments()
                    Expr.Call(null, type.name, args)
                } else {
                    Expr.Variable(type.name)
                }
            }
            else -> throw RuntimeException("Ошибка: Ожидалось выражение на строке ${token.line}")
        }
    }

    private fun parseArguments(): List<Expr> {
        val args = mutableListOf<Expr>()
        if (!check(TokenType.RParen::class.java)) {
            do {
                args.add(expression())
            } while (match(TokenType.Comma))
        }
        consume(TokenType.RParen::class.java, "Ожидалась ')'")
        return args
    }

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (isAtEnd()) continue
            if (peek().type::class == type::class) {
                advance()
                return true
            }
        }
        return false
    }

    private fun check(typeClass: Class<out TokenType>): Boolean {
        if (isAtEnd()) return false
        return typeClass.isInstance(peek().type)
    }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun consume(typeClass: Class<out TokenType>, message: String): Token {
        if (check(typeClass)) return advance()
        throw RuntimeException("$message на строке ${peek().line}")
    }

    private fun isAtEnd() = peek().type is TokenType.EOF
    private fun peek() = tokens[current]
    private fun previous() = tokens[current - 1]
}
