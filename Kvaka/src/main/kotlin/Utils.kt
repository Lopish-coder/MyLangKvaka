import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.lang.reflect.Method

/** 1. Превращает внутреннее имя типа (string, int) или алиас класса в Java Class */
fun resolveJavaClass(typeName: String, imports: List<ImportDeclaration>): Class<*> {
    return when (typeName) {
        "int"    -> Int::class.javaPrimitiveType!!
        "long"   -> Long::class.javaPrimitiveType!!
        "float"  -> Float::class.javaPrimitiveType!!
        "string" -> String::class.java
        "bool"   -> Boolean::class.javaPrimitiveType!!
        "void"   -> Void::class.javaPrimitiveType!!
        "System" -> System::class.java
        else -> {
            val fullPath = imports.find { it.alias == typeName }?.fullPath ?: typeName
            try {
                Class.forName(fullPath.replace('/', '.'))
            } catch (_: ClassNotFoundException) {
                throw RuntimeException("Ошибка компиляции: Класс '$typeName' не найден.")
            }
        }
    }
}

/** 2. Получает внутреннее имя класса для ASM */
fun getInternalName(clazz: Class<*>): String = Type.getInternalName(clazz)

/** 3. Поиск метода в Java-классе с учетом varargs */
fun findMethod(clazz: Class<*>, name: String, argClasses: Array<Class<*>>): Method {
    // 1. Точное совпадение
    try {
        return clazz.getMethod(name, *argClasses)
    } catch (_: NoSuchMethodException) {}

    // 2. Авто конвертация (int -> long, int -> float, float -> double)
    for (method in clazz.methods) {
        if (method.name != name || method.isVarArgs || method.parameterTypes.size != argClasses.size) continue

        var match = true
        val paramTypes = method.parameterTypes

        for (i in paramTypes.indices) {
            val param = paramTypes[i]
            val arg = argClasses[i]

            if (param == arg) continue

            if (arg == Int::class.javaPrimitiveType || arg == Int::class.javaObjectType) {
                if (param == Long::class.javaPrimitiveType ||
                    param == Float::class.javaPrimitiveType ||
                    param == Double::class.javaPrimitiveType) continue
            }

            if ((arg == Float::class.javaPrimitiveType || arg == Float::class.javaObjectType) &&
                param == Double::class.javaPrimitiveType) continue

            match = false
            break
        }
        if (match) return method
    }

    // 3. Поиск с учетом Boxed-типов (как было раньше)
    val boxedArgs = argClasses.map { boxType(it) }.toTypedArray()
    var bestMatch: Method? = null
    var bestMatchScore = -1

    for (method in clazz.methods) {
        if (method.name != name || method.isVarArgs || method.parameterTypes.size != boxedArgs.size) continue

        var match = true
        var currentScore = 0
        val paramTypes = method.parameterTypes

        for (i in paramTypes.indices) {
            val paramType = boxType(paramTypes[i])
            val argType = boxedArgs[i]

            if (paramType == argType) {
                currentScore += 2
            } else if (paramType.isAssignableFrom(argType)) {
                currentScore += 1
            } else {
                match = false
                break
            }
        }

        if (match && currentScore > bestMatchScore) {
            bestMatchScore = currentScore
            bestMatch = method
        }
    }
    if (bestMatch != null) return bestMatch

    // 4. Varargs
    for (method in clazz.methods) {
        if (method.name != name || !method.isVarArgs) continue

        val paramTypes = method.parameterTypes
        val normalParamCount = paramTypes.size - 1

        if (boxedArgs.size >= normalParamCount) {
            var match = true
            for (i in 0 until normalParamCount) {
                if (!boxType(paramTypes[i]).isAssignableFrom(boxedArgs[i])) {
                    match = false
                    break
                }
            }
            if (match) {
                val varargComponentType = boxType(paramTypes[normalParamCount].componentType)
                for (i in normalParamCount until boxedArgs.size) {
                    if (!varargComponentType.isAssignableFrom(boxedArgs[i])) {
                        match = false
                        break
                    }
                }
                if (match) return method
            }
        }
    }

    val argNames = argClasses.joinToString(", ") { it.simpleName }
    throw RuntimeException("Метод '$name($argNames)' не найден в классе ${clazz.name}")
}

/** Хелпер для маппинга примитивов в объекты-обертки (Unified Object System) */
private fun boxType(c: Class<*>): Class<*> = when (c) {
    Int::class.javaPrimitiveType     -> Int::class.javaObjectType
    Long::class.javaPrimitiveType    -> Long::class.javaObjectType
    Float::class.javaPrimitiveType   -> Float::class.javaObjectType
    Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
    else                             -> c
}

/** 4. Генерация байткода для обычных параметров и упаковки varargs в массив Object[] */
fun prepareArgsAndVarargs(
    method: Method,
    arguments: List<Expr>,
    mv: MethodVisitor,
    variables: Map<String, Int>,
    variableTypes: Map<String, String>,
    compileLambda: (Expr, MethodVisitor, Map<String, Int>, Map<String, String>) -> Unit,
    getExprTypeRef: (Expr, Map<String, Int>, Map<String, String>) -> String
) {
    val paramTypes = method.parameterTypes
    val normalParamCount = if (method.isVarArgs) method.parameterTypes.size - 1 else arguments.size

    for (i in 0 until normalParamCount) {
        compileLambda(arguments[i], mv, variables, variableTypes)
        val currentExprType = getExprTypeRef(arguments[i], variables, variableTypes)

        if (currentExprType !in listOf("int", "long", "float", "bool")) {
            unwrapIfRequired(paramTypes[i], mv)
        } else {
            convertPrimitive(currentExprType, paramTypes[i], mv)
        }
    }

    // Если это не varargs, мы просто выходим (все аргументы уже обработаны)
    if (!method.isVarArgs) return

    val varargsCount = arguments.size - normalParamCount
    val varargArrayType = if (method.parameterTypes.isNotEmpty() && method.parameterTypes.last().isArray) {
        getInternalName(method.parameterTypes.last().componentType)
    } else {
        "java/lang/Object"
    }
    mv.visitLdcInsn(varargsCount)
    mv.visitTypeInsn(ANEWARRAY, varargArrayType)

    for (i in 0 until varargsCount) {
        mv.visitInsn(DUP)
        mv.visitLdcInsn(i)

        val argExpr = arguments[normalParamCount + i]
        compileLambda(argExpr, mv, variables, variableTypes)

        val currentExprType = getExprTypeRef(argExpr, variables, variableTypes)
        if (currentExprType in listOf("int", "long", "float", "bool")) {
            mv.boxFromPrimitive(currentExprType)
        }

        mv.visitInsn(AASTORE)
    }
}

private fun convertPrimitive(
    fromType: String,
    toType: Class<*>,
    mv: MethodVisitor
) {
    // Приводим объект-обертку (Long) к примитиву (long), чтобы when работал идеально
    val target = when (toType) {
        Long::class.javaObjectType    -> Long::class.javaPrimitiveType
        Float::class.javaObjectType   -> Float::class.javaPrimitiveType
        Double::class.javaObjectType  -> Double::class.javaPrimitiveType
        else                          -> toType
    }

    // А теперь красивый and понятный when!
    when (fromType to target) {
        "int" to Long::class.javaPrimitiveType   -> mv.visitInsn(I2L)
        "int" to Float::class.javaPrimitiveType  -> mv.visitInsn(I2F)
        "int" to Double::class.javaPrimitiveType -> mv.visitInsn(I2D)

        "long" to Float::class.javaPrimitiveType  -> mv.visitInsn(L2F)
        "long" to Double::class.javaPrimitiveType -> mv.visitInsn(L2D)

        "float" to Double::class.javaPrimitiveType -> mv.visitInsn(F2D)
    }
}

private fun unwrapIfRequired(expectedType: Class<*>, mv: MethodVisitor) {
    if (expectedType.isPrimitive) {
        val typeStr = when (expectedType) {
            Int::class.javaPrimitiveType   -> "int"
            Long::class.javaPrimitiveType  -> "long"
            Float::class.javaPrimitiveType -> "float"
            else                           -> "bool"
        }
        mv.unboxToPrimitive(typeStr)
    }
}

/** 5. Превращение экранированных строк типа \" или \n в спецсимволы */
fun unescapeString(str: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < str.length) {
        val c = str[i]
        if (c == '\\' && i + 1 < str.length) {
            when (val next = str[i + 1]) {
                'n'  -> sb.append('\n')
                't'  -> sb.append('\t')
                'r'  -> sb.append('\r')
                '"'  -> sb.append('"')
                '\\' -> sb.append('\\')
                else -> sb.append(next)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/** 6. Расширения для MethodVisitor: Распаковка (Unboxing) объектов-оберток */
fun MethodVisitor.unboxToPrimitive(type: String) {
    when (type) {
        "int"   -> this.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
        "long"  -> this.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
        "float" -> this.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false)
        "bool"  -> this.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
    }
}

/** 7. Расширения для MethodVisitor: Упаковка (Boxing) примитивов. */
fun MethodVisitor.boxFromPrimitive(type: String) {
    when (type) {
        "int"   -> this.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
        "long"  -> this.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
        "float" -> this.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
        "bool"  -> this.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
    }
}

fun MethodVisitor.visitVarLoad(type: String, index: Int) {
    val opcode = when (type) {
        "int", "bool" -> ILOAD
        "long"        -> LLOAD
        "float"       -> FLOAD
        else          -> ALOAD
    }
    this.visitVarInsn(opcode, index)
}

fun MethodVisitor.visitVarStore(type: String, index: Int) {
    val opcode = when (type) {
        "int", "bool" -> ISTORE
        "long"        -> LSTORE
        "float"       -> FSTORE
        else          -> ASTORE
    }
    this.visitVarInsn(opcode, index)
}

fun mapJavaTypeToKvaka(clazz: Class<*>, imports: List<ImportDeclaration>): String {
    return when (clazz) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType         -> "int"
        Long::class.javaPrimitiveType, Long::class.javaObjectType       -> "long"
        Float::class.javaPrimitiveType, Float::class.javaObjectType     -> "float"
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> "bool"
        Void::class.javaPrimitiveType, Void::class.javaObjectType       -> "void"
        String::class.java                                              -> "string"
        else -> {
            val foundImport = imports.find { it.fullPath.replace('/', '.') == clazz.name }
            foundImport?.alias ?: clazz.name
        }
    }
}

fun asmTypeToClass(asmType: Type, imports: List<ImportDeclaration>): Class<*> {
    return when (asmType.sort) {
        Type.INT     -> Int::class.javaPrimitiveType!!
        Type.LONG    -> Long::class.javaPrimitiveType!!
        Type.FLOAT   -> Float::class.javaPrimitiveType!!
        Type.BOOLEAN -> Boolean::class.javaPrimitiveType!!
        Type.VOID    -> Void::class.javaPrimitiveType!!
        Type.OBJECT  -> {
            if (asmType.internalName == "java/lang/String") String::class.java
            else {
                val typeName = asmType.className.substringAfterLast('.')
                resolveJavaClass(typeName, imports)
            }
        }
        else -> String::class.java
    }
}

/** =================================================================
    --- СВОЙСТВА-РАСШИРЕНИЯ ТОКЕНОВ ДЛЯ КОДОГЕНЕРАЦИИ JVM -----------
    ================================================================= */

val TokenType.intJumpOpcode: Int?
    get() = when (this) {
        TokenType.EqualEqual   -> IF_ICMPEQ
        TokenType.Less         -> IF_ICMPLT
        TokenType.Greater      -> IF_ICMPGT
        TokenType.LessEqual    -> IF_ICMPLE
        TokenType.GreaterEqual -> IF_ICMPGE
        else -> null
    }

val TokenType.floatJumpOpcode: Int?
    get() = when (this) {
        TokenType.EqualEqual   -> IFEQ
        TokenType.Less         -> IFLT
        TokenType.Greater      -> IFGT
        TokenType.LessEqual    -> IFLE
        TokenType.GreaterEqual -> IFGE
        else -> null
    }

val TokenType.objectJumpOpcode: Int?
    get() = when (this) {
        TokenType.EqualEqual -> IF_ACMPEQ
        else -> null
    }

val TokenType.intOpcode: Int?
    get() = when (this) {
        TokenType.Plus    -> IADD
        TokenType.Minus   -> ISUB
        TokenType.Star    -> IMUL
        TokenType.Slash   -> IDIV
        TokenType.Percent -> IREM
        else -> null
    }

val TokenType.longOpcode: Int?
    get() = when (this) {
        TokenType.Plus    -> LADD
        TokenType.Minus   -> LSUB
        TokenType.Star    -> LMUL
        TokenType.Slash   -> LDIV
        TokenType.Percent -> LREM
        else -> null
    }

val TokenType.floatOpcode: Int?
    get() = when (this) {
        TokenType.Plus    -> FADD
        TokenType.Minus   -> FSUB
        TokenType.Star    -> FMUL
        TokenType.Slash   -> FDIV
        TokenType.Percent -> FREM
        else -> null
    }
