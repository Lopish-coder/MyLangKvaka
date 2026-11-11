import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Label

class Compiler(private val program: Program, private val className: String) {
    private val functionDescriptors = mutableMapOf<String, String>()

    private fun getTypeDescriptor(type: String): String = when (type) {
        "int"    -> "I"
        "long"   -> "J"
        "float"  -> "F"
        "bool"   -> "Z"
        "string" -> "Ljava/lang/String;"
        "void"   -> "V"
        "int[]"    -> "[I"
        "long[]"   -> "[J"
        "float[]"  -> "[F"
        "bool[]"   -> "[Z"
        "string[]" -> "[Ljava/lang/String;"
        else     -> "L${resolveJavaClass(type, program.imports).name.replace('.', '/')};"
    }

    private val builtinMethods = mapOf(
        "length"    to Pair("length", "()I"),
        "trim"      to Pair("trim", "()Ljava/lang/String;"),
        "split"     to Pair("split", "(Ljava/lang/String;)[Ljava/lang/String;"), // Для 1 аргумента
        "substring" to Pair("substring", "(II)Ljava/lang/String;")
    )
    // Словарь: Имя метода -> Какой тип он возвращает в Kvaka
    private val builtinReturnTypes = mapOf(
        "length"    to "int",
        "trim"      to "string",
        "split"     to "string[]",
        "substring" to "string"
    )

    fun compile(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V21, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

        for (function in program.functions) {
            if (function.name == "main") {
                functionDescriptors["main"] = "([Ljava/lang/String;)V"
            } else {
                val argsDesc = function.arguments.joinToString("") { getTypeDescriptor(it.type) }
                val returnDesc = getTypeDescriptor(function.returnType)
                functionDescriptors[function.name] = "($argsDesc)$returnDesc"
            }
        }

        cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        for (function in program.functions) {
            val isMain = function.name == "main"
            val descriptor = functionDescriptors[function.name]!!

            val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, function.name, descriptor, null, null)
            mv.visitCode()

            val variables = mutableMapOf<String, Int>()
            val variableTypes = mutableMapOf<String, String>()

            var nextVariableIndex = 0
            if (isMain) {
                variables["args"] = 0
                variableTypes["args"] = "string[]"
                nextVariableIndex = 1
            } else {
                for (arg in function.arguments) {
                    variables[arg.name] = nextVariableIndex
                    variableTypes[arg.name] = arg.type
                    nextVariableIndex += getTypeSlots(arg.type)
                }
            }

            val hasReturned = compileStatementList(function.body, mv, variables, variableTypes, nextVariableIndex, function.returnType, isMain, null)

            if (!hasReturned) {
                if (isMain || function.returnType == "void") {
                    mv.visitInsn(RETURN)
                } else {
                    when (function.returnType) {
                        "int", "bool" -> {
                            mv.visitInsn(ICONST_0)
                            mv.visitInsn(IRETURN)
                        }
                        "long" -> {
                            mv.visitInsn(LCONST_0)
                            mv.visitInsn(LRETURN)
                        }
                        "float" -> {
                            mv.visitInsn(FCONST_0)
                            mv.visitInsn(FRETURN)
                        }
                        else -> {
                            mv.visitInsn(ACONST_NULL)
                            mv.visitInsn(ARETURN)
                        }
                    }
                }
            }

            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun compileStatementList(
        body: List<Stmt>,
        mv: MethodVisitor,
        variables: MutableMap<String, Int>,
        variableTypes: MutableMap<String, String>,
        initialVariableIndex: Int,
        returnType: String,
        isMain: Boolean,
        currentEndLabel: Label? = null
    ): Boolean {
        var nextVariableIndex = initialVariableIndex
        var methodTerminated = false

        for (stmt in body) {
            if (methodTerminated) break
            when (stmt) {
                is Stmt.VarDeclaration -> {
                    compileExpression(stmt.initializer, mv, variables, variableTypes)
                    variables[stmt.name] = nextVariableIndex
                    variableTypes[stmt.name] = stmt.type

                    val exprType = getExpressionType(stmt.initializer, variables, variableTypes)
                    if (stmt.type == "long" && exprType == "int") {
                        mv.visitInsn(I2L)
                    } else if (stmt.type == "float" && exprType == "int") {
                        mv.visitInsn(I2F)
                    } else if (stmt.type == "float" && exprType == "long") {
                        mv.visitInsn(L2F)
                    }

                    mv.visitVarStore(stmt.type, nextVariableIndex)
                    nextVariableIndex += getTypeSlots(stmt.type)
                }
                is Stmt.Assignment -> {
                    if (stmt.target is Expr.Variable) {
                        // Обычное присваивание переменной: a = 5
                        val name = stmt.target.name
                        val index = variables[name] ?: throw RuntimeException("Переменная '$name' не объявлена")
                        val type = variableTypes[name]!!

                        compileExpression(stmt.expression, mv, variables, variableTypes)
                        mv.visitVarStore(type, index)
                    } else if (stmt.target is Expr.Index) {
                        // Присваивание в массив: arr[0] = 5
                        val indexExpr = stmt.target
                        // 1. Кладем на стек сам массив
                        compileExpression(indexExpr.array, mv, variables, variableTypes)
                        // 2. Кладем на стек индекс
                        compileExpression(indexExpr.index, mv, variables, variableTypes)
                        // 3. Кладем на стек значение
                        compileExpression(stmt.expression, mv, variables, variableTypes)
                        // 4. Выбираем нужную инструкцию в зависимости от типа элементов массива
                        val arrType = getExpressionType(indexExpr.array, variables, variableTypes)
                        when (arrType) {
                            "int[]" -> mv.visitInsn(IASTORE)
                            "long[]" -> mv.visitInsn(LASTORE)
                            "float[]" -> mv.visitInsn(FASTORE)
                            "bool[]" -> mv.visitInsn(BASTORE)
                            else -> mv.visitInsn(AASTORE)
                        }
                    } else {
                        throw RuntimeException("Недопустимая цель для присваивания")
                    }
                }
                is Stmt.CompoundAssignment -> {
                    val index = variables[stmt.name] ?: throw RuntimeException("Переменная '${stmt.name}' не объявлена")
                    val type = variableTypes[stmt.name]!!

                    if (type == "bool" || type == "string") {
                        throw RuntimeException("На строке ${stmt.operatorToken.line}: Оператор '${stmt.operatorToken.lexeme}' неприменим к типу $type")
                    }

                    val isIntOne = type == "int" && stmt.expression is Expr.IntLit && stmt.expression.value == 1

                    when {
                        isIntOne && stmt.operatorToken.type == TokenType.PlusEqual -> mv.visitIincInsn(index, 1)
                        isIntOne && stmt.operatorToken.type == TokenType.MinusEqual -> mv.visitIincInsn(index, -1)
                        else -> {
                            mv.visitVarLoad(type, index)

                            compileExpression(stmt.expression, mv, variables, variableTypes)
                            val exprType = getExpressionType(stmt.expression, variables, variableTypes)

                            if (type == "float" && exprType == "int") {
                                mv.visitInsn(I2F)
                            }

                            val opcode = when (stmt.operatorToken.type) {
                                TokenType.PlusEqual -> if (type == "float") FADD else IADD
                                TokenType.MinusEqual -> if (type == "float") FSUB else ISUB
                                TokenType.StarEqual -> if (type == "float") FMUL else IMUL
                                TokenType.SlashEqual -> if (type == "float") FDIV else IDIV
                                else -> throw RuntimeException("Неизвестный оператор")
                            }
                            mv.visitInsn(opcode)

                            mv.visitVarStore(type, index)
                        }
                    }
                }
                is Stmt.Print -> {
                    mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
                    compileExpression(stmt.expression, mv, variables, variableTypes)

                    val exprType = getExpressionType(stmt.expression, variables, variableTypes)

                    if (exprType == "int" || exprType == "long" || exprType == "float" || exprType == "bool") {
                        mv.boxFromPrimitive(exprType)
                    }

                    val descriptor = if (exprType == "string") "(Ljava/lang/String;)V" else "(Ljava/lang/Object;)V"

                    val methodName = if (stmt.newLine) "println" else "print"

                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", methodName, descriptor, false)
                }
                is Stmt.FunctionCall -> {
                    compileExpression(stmt.expression, mv, variables, variableTypes)

                    val returnType = getExpressionType(stmt.expression, variables, variableTypes)
                    if (returnType != "void") {
                        mv.visitInsn(POP)
                    }
                }
                is Stmt.If -> {
                    val condition = stmt.condition
                    val elseLabel = Label()
                    val endLabel = Label()
                    val targetLabel = if (stmt.elseBranch != null) elseLabel else endLabel

                    compileExpression(condition, mv, variables, variableTypes)
                    mv.visitJumpInsn(IFEQ, targetLabel)

                    val thenVariables = variables.toMutableMap()
                    val thenTerminated = compileStatementList(stmt.thenBranch, mv, thenVariables, variableTypes.toMutableMap(), nextVariableIndex, returnType, isMain, currentEndLabel)

                    var maxIndex = nextVariableIndex + (thenVariables.size - variables.size)
                    var elseTerminated = false

                    if (stmt.elseBranch != null) {
                        mv.visitJumpInsn(GOTO, endLabel)
                        mv.visitLabel(elseLabel)
                        val elseVariables = variables.toMutableMap()
                        elseTerminated = compileStatementList(stmt.elseBranch, mv, elseVariables, variableTypes.toMutableMap(), nextVariableIndex, returnType, isMain, currentEndLabel)
                        val indexAfterElse = nextVariableIndex + (elseVariables.size - variables.size)
                        if (indexAfterElse > maxIndex) maxIndex = indexAfterElse
                    } else {
                        mv.visitLabel(elseLabel)
                    }

                    nextVariableIndex = maxIndex
                    mv.visitLabel(endLabel)

                    if (thenTerminated && elseTerminated) {
                        methodTerminated = true
                    }
                }
                is Stmt.Loop -> {
                    val condition = stmt.condition
                    val startLabel = Label()
                    val endLabel = Label()

                    mv.visitLabel(startLabel)

                    compileExpression(condition, mv, variables, variableTypes)
                    mv.visitJumpInsn(IFEQ, endLabel)

                    val loopVariables = mutableMapOf<String, Int>().apply { putAll(variables) }
                    compileStatementList(stmt.body, mv, loopVariables, variableTypes.toMutableMap(), nextVariableIndex, returnType, isMain, endLabel)

                    mv.visitJumpInsn(GOTO, startLabel)
                    mv.visitLabel(endLabel)
                }
                is Stmt.Break -> {
                    if (currentEndLabel == null) {
                        throw RuntimeException("Оператор 'break' вызван вне цикла loop.")
                    }
                    mv.visitJumpInsn(GOTO, currentEndLabel)
                    methodTerminated = true
                }
                is Stmt.Return -> {
                    if (returnType == "void" || isMain) {
                        if (stmt.expression != null) {
                            compileExpression(stmt.expression, mv, variables, variableTypes)
                            val exprType = getExpressionType(stmt.expression, variables, variableTypes)
                            if (exprType != "void") mv.visitInsn(POP)
                        }
                        mv.visitInsn(RETURN)
                    } else {
                        if (stmt.expression == null) {
                            throw RuntimeException("Функция с типом '$returnType' должна вернуть значение")
                        }

                        compileExpression(stmt.expression, mv, variables, variableTypes)
                        val exprType = getExpressionType(stmt.expression, variables, variableTypes)

                        // --- АВТО-КОНВЕРТАЦИЯ ПРИ ВОЗВРАТЕ ---
                        when {
                            returnType == "long" && exprType == "int" -> mv.visitInsn(I2L)
                            returnType == "float" && exprType == "int" -> mv.visitInsn(I2F)
                            returnType == "float" && exprType == "long" -> mv.visitInsn(L2F)
                            returnType != exprType -> throw RuntimeException("Ожидался возврат '$returnType', получен '$exprType'")
                        }

                        val returnOpcode = when (returnType) {
                            "int", "bool" -> IRETURN
                            "long"        -> LRETURN
                            "float"       -> FRETURN
                            else          -> ARETURN
                        }
                        mv.visitInsn(returnOpcode)
                    }
                    methodTerminated = true
                }
            }
        }
        return methodTerminated
    }
    private fun compileExpression(expr: Expr, mv: MethodVisitor, variables: Map<String, Int>, variableTypes: Map<String, String>) {
        when (expr) {
            is Expr.IntLit -> { mv.visitLdcInsn(expr.value) }
            is Expr.LongLit -> { mv.visitLdcInsn(expr.value) }
            is Expr.FloatLit -> { mv.visitLdcInsn(expr.value) }
            is Expr.StringLit -> { mv.visitLdcInsn(expr.value) }
            is Expr.BoolLit -> { mv.visitInsn(if (expr.value) ICONST_1 else ICONST_0) }
            is Expr.NullLit -> { mv.visitInsn(ACONST_NULL) }
            is Expr.ArrayLit -> {
                val elemType = getExpressionType(expr, variables, variableTypes).dropLast(2)
                mv.visitLdcInsn(expr.elements.size)

                when (elemType) {
                    "int"    -> mv.visitIntInsn(NEWARRAY, T_INT)
                    "long"   -> mv.visitIntInsn(NEWARRAY, T_LONG)
                    "float"  -> mv.visitIntInsn(NEWARRAY, T_FLOAT)
                    "bool"   -> mv.visitIntInsn(NEWARRAY, T_BOOLEAN)
                    else     -> mv.visitTypeInsn(ANEWARRAY, "java/lang/String") // Для строк
                }

                for (i in expr.elements.indices) {
                    mv.visitInsn(DUP)
                    mv.visitLdcInsn(i)
                    compileExpression(expr.elements[i], mv, variables, variableTypes)
                    when (elemType) {
                        "int"   -> mv.visitInsn(IASTORE)
                        "long"  -> mv.visitInsn(LASTORE)
                        "float" -> mv.visitInsn(FASTORE)
                        "bool"  -> mv.visitInsn(BASTORE)
                        else    -> mv.visitInsn(AASTORE)
                    }
                }
            }
            is Expr.Variable -> {
                val index = variables[expr.name]
                val type = variableTypes[expr.name]
                if (index != null && type != null) {
                    mv.visitVarLoad(type, index)
                }
            }
            is Expr.Call -> {
                if (expr.target != null) {
                    val builtin = builtinMethods[expr.name]
                    if (builtin != null) {
                        val targetType = getExpressionType(expr.target, variables, variableTypes)

                        // Для строк
                        if (targetType == "string") {
                            compileExpression(expr.target, mv, variables, variableTypes)

                            // Если есть аргументы, компилируем их
                            for (arg in expr.arguments) {
                                compileExpression(arg, mv, variables, variableTypes)
                            }

                            // Определяем точный дескриптор (для split есть 2 варианта)
                            var descriptor = builtin.second
                            if (expr.name == "split" && expr.arguments.size == 2) {
                                descriptor = "(Ljava/lang/String;I)[Ljava/lang/String;"
                            }

                            // Вызываем Java-метод
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", builtin.first, descriptor, false)
                            return
                        }

                        // Для массивов (только length)
                        if (targetType.endsWith("[]") && expr.name == "length" && expr.arguments.isEmpty()) {
                            compileExpression(expr.target, mv, variables, variableTypes)
                            mv.visitInsn(ARRAYLENGTH)
                            return
                        }
                    }
                    // 1. Пытаемся понять, что такое expr.target: переменная или класс?
                    val isTargetVariable = expr.target is Expr.Variable && variables.containsKey((expr.target).name)
                    var targetJavaClass: Class<*>? = null
                    var isStatic = false

                    if (isTargetVariable) {
                        // Это метод у обычной переменной (например, frame.setSize)
                        val targetTypeName = getExpressionType(expr.target, variables, variableTypes)
                        targetJavaClass = resolveJavaClass(targetTypeName, program.imports)
                        isStatic = false
                    } else if (expr.target is Expr.Variable) {
                        // Это может быть статический класс (например, Thread или System)
                        val className = (expr.target).name
                        targetJavaClass = try { resolveJavaClass(className, program.imports) } catch (_: Exception) { null }
                        if (targetJavaClass != null) {
                            isStatic = true
                        }
                    }
                    // Если это не статический класс и не переменная, но у нас есть выражение (например, цепочка вызовов)
                    if (targetJavaClass == null) {
                        val targetTypeName = getExpressionType(expr.target, variables, variableTypes)
                        targetJavaClass = resolveJavaClass(targetTypeName, program.imports)
                        isStatic = false
                    }

                    // 2. Если метод не статический, кладем на стек сам объект (this)
                    if (!isStatic) {
                        compileExpression(expr.target, mv, variables, variableTypes)
                    }
                    // 3. Ищем поле или метод
                    val field = try { targetJavaClass.getField(expr.name) } catch (_: Exception) { null }

                    if (field != null && expr.arguments.isEmpty()) {
                        // Это обращение к полю (например, System.out)
                        val opcode = if (isStatic) GETSTATIC else GETFIELD
                        val fieldDesc = org.objectweb.asm.Type.getDescriptor(field.type)
                        mv.visitFieldInsn(opcode, getInternalName(targetJavaClass), expr.name, fieldDesc)
                    } else {
                        // Это вызов метода
                        val argClasses = expr.arguments.map { resolveJavaClass(getExpressionType(it, variables, variableTypes), program.imports) }.toTypedArray()
                        val method = findMethod(targetJavaClass, expr.name, argClasses)
                        // Подготавливаем аргументы (упаковка/распаковка varargs)
                        prepareArgsAndVarargs(
                            method, expr.arguments, mv, variables, variableTypes,
                            { e, visitor, vars, types -> compileExpression(e, visitor, vars, types) },
                            ::getExpressionType
                        )

                        val opcode = if (isStatic) INVOKESTATIC else INVOKEVIRTUAL
                        val methodDesc = org.objectweb.asm.Type.getMethodDescriptor(method)
                        mv.visitMethodInsn(opcode, getInternalName(targetJavaClass), expr.name, methodDesc, false)
                    }
                } else {
                    val possibleClass = program.imports.find { it.alias == expr.name }
                    // Проверяем, является ли это созданием объекта (если класс есть в импортах)
                    if (possibleClass != null) {
                        val clazz = resolveJavaClass(possibleClass.alias, program.imports)
                        val internalName = getInternalName(clazz)
                        // 1. Создаем объект в памяти
                        mv.visitTypeInsn(NEW, internalName)
                        mv.visitInsn(DUP)
                        // 2. Ищем конструктор по типам аргументов
                        val argClasses = expr.arguments.map { resolveJavaClass(getExpressionType(it, variables, variableTypes), program.imports) }.toTypedArray()
                        val constructor = clazz.getConstructor(*argClasses)
                        val paramTypes = constructor.parameterTypes
                        // 3. Передаем аргументы (с распаковкой, если нужно)
                        for (i in expr.arguments.indices) {
                            compileExpression(expr.arguments[i], mv, variables, variableTypes)
                            if (i < paramTypes.size && paramTypes[i].isPrimitive) {
                                mv.unboxToPrimitive(when (paramTypes[i]) {
                                    Int::class.javaPrimitiveType -> "int"
                                    Long::class.javaPrimitiveType -> "long"
                                    Float::class.javaPrimitiveType -> "float"
                                    else -> "bool"
                                })
                            }
                        }
                        // 4. Вызываем конструктор <init>
                        mv.visitMethodInsn(INVOKESPECIAL, internalName, "<init>", org.objectweb.asm.Type.getConstructorDescriptor(constructor), false)
                    } else {
                        // Это вызов обычной функции Kvaka
                        val funcDecl = program.functions.find { it.name == expr.name }
                            ?: throw RuntimeException("Функция '${expr.name}' не найдена")

                        for (i in expr.arguments.indices) {
                            compileExpression(expr.arguments[i], mv, variables, variableTypes)

                            // --- ВОТ ТУТ МАГИЯ КОНВЕРТАЦИИ ---
                            if (i < funcDecl.arguments.size) {
                                val paramType = funcDecl.arguments[i].type
                                val exprType = getExpressionType(expr.arguments[i], variables, variableTypes)

                                when (exprType) {
                                    "int" -> when (paramType) {
                                        "long" -> mv.visitInsn(I2L)
                                        "float" -> mv.visitInsn(I2F)
                                    }
                                    "long" -> when (paramType) {
                                        "float" -> mv.visitInsn(L2F)
                                    }
                                }
                            }
                        }

                        val targetDescriptor = functionDescriptors[expr.name] ?: throw RuntimeException("Функция '${expr.name}' не найдена")
                        mv.visitMethodInsn(INVOKESTATIC, className, expr.name, targetDescriptor, false)
                    }
                }
            }
            is Expr.Binary -> {
                val (leftType, rightType) = getBinaryTypes(expr, variables, variableTypes)
                val opType = expr.operator.type

                val isComparison = opType == TokenType.EqualEqual ||
                        opType == TokenType.Less ||
                        opType == TokenType.Greater ||
                        opType == TokenType.LessEqual ||
                        opType == TokenType.GreaterEqual

                if (isComparison) {
                    val isFloatOperation = (leftType == "float" || rightType == "float")
                    val isLongOperation = (leftType == "long" || rightType == "long")

                    // Компилируем левую и правую часть с авто конвертацией
                    compileOperandWithCasting(expr.left, leftType, isFloatOperation, isLongOperation, mv, variables, variableTypes)
                    compileOperandWithCasting(expr.right, rightType, isFloatOperation, isLongOperation, mv, variables, variableTypes)

                    val trueLabel = Label()
                    val endLabel = Label()

                    when {
                        isFloatOperation -> {
                            mv.visitInsn(FCMPG)
                            val jumpOpcode = opType.floatJumpOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемый оператор")
                            mv.visitJumpInsn(jumpOpcode, trueLabel)
                        }
                        isLongOperation -> {
                            mv.visitInsn(LCMP) // Сравнение двух long
                            val jumpOpcode = opType.intJumpOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемый оператор")
                            mv.visitJumpInsn(jumpOpcode, trueLabel)
                        }
                        leftType == "int" || leftType == "bool" -> {
                            val jumpOpcode = opType.intJumpOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемый оператор")
                            mv.visitJumpInsn(jumpOpcode, trueLabel)
                        }
                        leftType == "string" && rightType == "string" -> {
                            if (opType != TokenType.EqualEqual) {
                                throw RuntimeException("На строке ${expr.operator.line}: Строки можно сравнивать только на равенство (==)")
                            }
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                            mv.visitInsn(ICONST_1)
                            mv.visitJumpInsn(IF_ICMPEQ, trueLabel)
                        }
                        else -> {
                            val jumpOpcode = opType.objectJumpOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Объекты можно сравнивать только на равенство (==)")
                            mv.visitJumpInsn(jumpOpcode, trueLabel)
                        }
                    }

                    mv.visitInsn(ICONST_0)
                    mv.visitJumpInsn(GOTO, endLabel)

                    mv.visitLabel(trueLabel)
                    mv.visitInsn(ICONST_1)

                    mv.visitLabel(endLabel)
                } else {
                    // --- ПОДДЕРЖКА КОНКАТЕНАЦИИ СТРОК ---
                    if ((leftType == "string" || rightType == "string") && opType == TokenType.Plus) {
                        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
                        mv.visitInsn(DUP)
                        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)

                        compileExpression(expr.left, mv, variables, variableTypes)
                        val leftDesc = getTypeDescriptor(leftType)
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "($leftDesc)Ljava/lang/StringBuilder;", false)

                        compileExpression(expr.right, mv, variables, variableTypes)
                        val rightDesc = getTypeDescriptor(rightType)
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "($rightDesc)Ljava/lang/StringBuilder;", false)

                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
                    } else {
                        // --- АРИФМЕТИКА ---
                        val isFloatOperation = (leftType == "float" || rightType == "float")
                        val isLongOperation = (leftType == "long" || rightType == "long")
                        val isIntOperation = (leftType == "int" && rightType == "int")

                        if (isFloatOperation) {
                            // compileOperandWithCasting сам сделает I2F или L2F, если нужно
                            compileOperandWithCasting(expr.left, leftType, isFloatOp = true, isLongOp = false, mv, variables, variableTypes)
                            compileOperandWithCasting(expr.right, rightType, isFloatOp = true, isLongOp = false, mv, variables, variableTypes)

                            val opcode = opType.floatOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Оператор не поддерживается")
                            mv.visitInsn(opcode)
                        } else if (isLongOperation) {
                            // compileOperandWithCasting сам сделает I2L, если нужно
                            compileOperandWithCasting(expr.left, leftType, isFloatOp = false, isLongOp = true, mv, variables, variableTypes)
                            compileOperandWithCasting(expr.right, rightType, isFloatOp = false, isLongOp = true, mv, variables, variableTypes)

                            val opcode = opType.longOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Оператор не поддерживается для long")
                            mv.visitInsn(opcode)
                        } else if (isIntOperation) {
                            // Обычные int
                            compileExpression(expr.left, mv, variables, variableTypes)
                            compileExpression(expr.right, mv, variables, variableTypes)

                            val opcode = opType.intOpcode ?: throw RuntimeException("На строке ${expr.operator.line}: Оператор не поддерживается")
                            mv.visitInsn(opcode)
                        } else {
                            // Если это строка или bool, которые не прошли проверки раньше
                            throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемые типы для арифметики: $leftType и $rightType")
                        }
                    }
                }
            }
            is Expr.Logical -> {
                val endLabel = Label()
                val shortcutLabel = Label()
                val opType = expr.operator.type

                val (jumpOpcode, finalValueOnShortcut) = when (opType) {
                    TokenType.And -> IFEQ to ICONST_0
                    TokenType.Or  -> IFNE to ICONST_1
                    else -> throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемый оператор: ${expr.operator.lexeme}")
                }

                compileExpression(expr.left, mv, variables, variableTypes)
                mv.visitJumpInsn(jumpOpcode, shortcutLabel)

                compileExpression(expr.right, mv, variables, variableTypes)
                mv.visitJumpInsn(jumpOpcode, shortcutLabel)

                val normalValue = if (opType == TokenType.And) ICONST_1 else ICONST_0
                mv.visitInsn(normalValue)
                mv.visitJumpInsn(GOTO, endLabel)

                mv.visitLabel(shortcutLabel)
                mv.visitInsn(finalValueOnShortcut)

                mv.visitLabel(endLabel)
            }
            is Expr.Unary -> {
                when (expr.operator.type) {
                    TokenType.Not -> {
                        compileExpression(expr.right, mv, variables, variableTypes)
                        mv.visitInsn(ICONST_1)
                        mv.visitInsn(IXOR)
                    }
                    TokenType.Minus -> {
                        val type = getExpressionType(expr.right, variables, variableTypes)
                        compileExpression(expr.right, mv, variables, variableTypes)
                        if (type == "int") mv.visitInsn(INEG) else mv.visitInsn(FNEG)
                    }
                    else -> throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемый унарный оператор")
                }
            }
            is Expr.Index -> {
                compileExpression(expr.array, mv, variables, variableTypes)
                compileExpression(expr.index, mv, variables, variableTypes)
                val arrType = getExpressionType(expr.array, variables, variableTypes)
                when (arrType) {
                    "int[]" -> mv.visitInsn(IALOAD)
                    "long[]" -> mv.visitInsn(LALOAD)
                    "float[]" -> mv.visitInsn(FALOAD)
                    "bool[]" -> mv.visitInsn(BALOAD)
                    else -> mv.visitInsn(AALOAD)
                }
            }
        }
    }
    private fun getExpressionType(
        expr: Expr,
        variables: Map<String, Int>,
        variableTypes: Map<String, String>
    ): String {
        return when (expr) {
            is Expr.IntLit    -> "int"
            is Expr.LongLit   -> "long"
            is Expr.FloatLit  -> "float"
            is Expr.StringLit -> "string"
            is Expr.BoolLit   -> "bool"
            is Expr.Logical   -> "bool"
            is Expr.NullLit   -> "null"
            is Expr.ArrayLit -> {
                if (expr.elements.isEmpty()) "int[]"
                else {
                    val elemType = getExpressionType(expr.elements[0], variables, variableTypes)
                    "$elemType[]"
                }
            }
            is Expr.Binary -> {
                val left = getExpressionType(expr.left, variables, variableTypes)
                val right = getExpressionType(expr.right, variables, variableTypes)
                val opType = expr.operator.type

                val isComparison = opType == TokenType.EqualEqual ||
                        opType == TokenType.Less ||
                        opType == TokenType.Greater ||
                        opType == TokenType.LessEqual ||
                        opType == TokenType.GreaterEqual

                if (isComparison) {
                    val primitives = listOf("int", "float", "bool")
                    if ((left in primitives && right == "null") || (right in primitives && left == "null")) {
                        throw RuntimeException("На строке ${expr.operator.line}: Примитивный тип '$left' не может быть null.")
                    }
                    "bool"
                } else {
                    if ((left == "string" || right == "string") && opType == TokenType.Plus) {
                        return "string"
                    }

                    if (opType == TokenType.Percent || opType == TokenType.Minus ||
                        opType == TokenType.Star || opType == TokenType.Slash) {
                        if (left == "string" || right == "string" || left == "bool" || right == "bool") {
                            throw RuntimeException("На строке ${expr.operator.line}: Оператор '${expr.operator.lexeme}' применим только к числовым типам (int, float).")
                        }
                    }

                    if (left == "null" || right == "null") {
                        throw RuntimeException("На строке ${expr.operator.line}: Операция ${expr.operator.lexeme} неприменима к null.")
                    }

                    when {
                        left == "float" || right == "float" -> "float"
                        left == "long" || right == "long" -> "long"
                        else -> "int"
                    }
                }
            }
            is Expr.Variable -> {
                // Сначала проверяем переменную
                val type = variableTypes[expr.name]
                if (type != null) return type
                // Потом проверяем импорты
                val importAlias = program.imports.find { it.alias == expr.name }?.alias
                if (importAlias != null) return importAlias
                // Потом проверяем System
                if (expr.name == "System") return "System"
                // Если ничего не нашли — падаем
                throw RuntimeException("Переменная или статический класс '${expr.name}' не найдены")
            }
            is Expr.Call -> {
                if (expr.target != null) {
                    // 1. Проверяем встроенные методы (length, trim, substring)
                    val builtinType = builtinReturnTypes[expr.name]
                    if (builtinType != null) {
                        val targetType = getExpressionType(expr.target, variables, variableTypes)
                        if (targetType == "string" || targetType.endsWith("[]")) {
                            return builtinType
                        }
                    }

                    // 2. Если это не встроенный метод, ищем в Java-классах
                    val targetTypeName = getExpressionType(expr.target, variables, variableTypes)
                    val targetJavaClass = resolveJavaClass(targetTypeName, program.imports)

                    val field = try { targetJavaClass.getField(expr.name) } catch (_: Exception) { null }

                    val javaReturnType = if (field != null && expr.arguments.isEmpty()) {
                        field.type
                    } else {
                        val argClasses = expr.arguments.map { resolveJavaClass(getExpressionType(it, variables, variableTypes), program.imports) }.toTypedArray()
                        val method = findMethod(targetJavaClass, expr.name, argClasses)
                        method.returnType
                    }

                    mapJavaTypeToKvaka(javaReturnType, program.imports)
                } else {
                    val possibleClass = program.imports.find { it.alias == expr.name }
                    if (possibleClass != null || expr.name == "Scanner") {
                        possibleClass?.alias ?: "Scanner"
                    } else {
                        val descriptor = functionDescriptors[expr.name] ?: throw RuntimeException("Функция '${expr.name}' не найдена")

                        val asmReturnType = org.objectweb.asm.Type.getReturnType(descriptor)
                        val returnClass = asmTypeToClass(asmReturnType, program.imports)

                        mapJavaTypeToKvaka(returnClass, program.imports)
                    }
                }
            }
            is Expr.Unary -> {
                val rightType = getExpressionType(expr.right, variables, variableTypes)
                when (expr.operator.type) {
                    TokenType.Not -> {
                        if (rightType != "bool") throw RuntimeException("На строке ${expr.operator.line}: Оператор 'not' применим только к bool")
                        "bool"
                    }
                    TokenType.Minus -> {
                        if (rightType != "int" && rightType != "float") throw RuntimeException("Унарный минус применим только к числу")
                        rightType
                    }
                    else -> throw RuntimeException("На строке ${expr.operator.line}: Неподдерживаемый унарный оператор при вычислении типов")
                }
            }
            is Expr.Index -> {
                val arrayType = getExpressionType(expr.array, variables, variableTypes)
                if (arrayType.endsWith("[]")) {
                    arrayType.dropLast(2)
                } else {
                    throw RuntimeException("Попытка взять индекс у не-массива")
                }
            }
        }
    }
    /** Дополнительные функции */
    private fun getBinaryTypes(
        expr: Expr.Binary,
        variables: Map<String, Int>,
        variableTypes: Map<String, String>
    ): Pair<String, String> {
        val left = getExpressionType(expr.left, variables, variableTypes)
        val right = getExpressionType(expr.right, variables, variableTypes)
        return left to right
    }
    private fun compileOperandWithCasting(
        expr: Expr,
        type: String,
        isFloatOp: Boolean,
        isLongOp: Boolean,
        mv: MethodVisitor,
        variables: Map<String, Int>,
        variableTypes: Map<String, String>
    ) {
        compileExpression(expr, mv, variables, variableTypes)

        if (isLongOp && type == "int") mv.visitInsn(I2L)
        else if (isFloatOp && type == "int") mv.visitInsn(I2F)
        else if (isFloatOp && type == "long") mv.visitInsn(L2F)
    }
    private fun getTypeSlots(type: String): Int {
        return if (type == "long" || type == "double") 2 else 1
    }
}
