import java.io.File
import java.io.FileOutputStream

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))

    if (args.isEmpty()) {
        println("Использование: Kvaka <имя_файла.kvaka>")
        return
    }

    val sourceFileName = args[0]
    val sourceFile = File(sourceFileName)

    if (!sourceFile.exists()) {
        println("Ошибка: Файл '${sourceFileName}' не найден в текущей папке.")
        return
    }

    val code = sourceFile.readText()

    try {
        val tokens = Lexer(code).scanTokens()
        val program = Parser(tokens).parse()

        val baseClassName = sourceFile.nameWithoutExtension
        val bytecode = Compiler(program, baseClassName).compile()

        val outputClassFile = File("${baseClassName}.class")
        FileOutputStream(outputClassFile).use { it.write(bytecode) }

        val loader = object : ClassLoader() {
            fun defineClass(name: String, b: ByteArray) = defineClass(name, b, 0, b.size)
        }

        val generatedClass = loader.defineClass(baseClassName, bytecode)
        val mainMethod = generatedClass.getMethod("main", Array<String>::class.java)

        mainMethod.invoke(null, arrayOf<String>())

    } catch (e: RuntimeException) {
        println("Ошибка компиляции: ${e.message}")
    } catch (e: Exception) {
        println("Внутренняя ошибка компилятора: ${e.message}")
        e.printStackTrace()
    }
}
