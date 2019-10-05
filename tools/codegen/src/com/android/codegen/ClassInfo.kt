package com.android.codegen

import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParseResult
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration

open class ClassInfo(val sourceLines: List<String>) {

    private val userSourceCode = (sourceLines + "}").joinToString("\n")
    val fileAst: CompilationUnit = try {
        JAVA_PARSER.parse(userSourceCode).throwIfFailed()
    } catch (e: ParseProblemException) {
        throw parseFailed(cause = e)
    }

    fun <T> ParseResult<T>.throwIfFailed(): T {
        if (problems.isNotEmpty()) {
            throw parseFailed(
                    desc = this@throwIfFailed.problems.joinToString("\n"),
                    cause = this@throwIfFailed.problems.mapNotNull { it.cause.orElse(null) }.firstOrNull())
        }
        return result.get()
    }

    private fun parseFailed(cause: Throwable? = null, desc: String = ""): RuntimeException {
        return RuntimeException("Failed to parse code:\n" +
                userSourceCode
                        .lines()
                        .mapIndexed { lnNum, ln -> "/*$lnNum*/$ln" }
                        .joinToString("\n") + "\n$desc",
                cause)
    }

    val classAst = fileAst.types[0] as ClassOrInterfaceDeclaration
    val nestedClasses = classAst.members.filterIsInstance<ClassOrInterfaceDeclaration>()

    val superInterfaces = (fileAst.types[0] as ClassOrInterfaceDeclaration)
            .implementedTypes.map { it.asString() }

    val superClass = run {
        val superClasses = (fileAst.types[0] as ClassOrInterfaceDeclaration).extendedTypes
        if (superClasses.isNonEmpty) superClasses[0] else null
    }

    val ClassName = classAst.nameAsString
    private val genericArgsAst = classAst.typeParameters
    val genericArgs = if (genericArgsAst.isEmpty()) "" else {
        genericArgsAst.map { it.nameAsString }.joinToString(", ").let { "<$it>" }
    }
    val ClassType = ClassName + genericArgs

    val constDefs = mutableListOf<ConstDef>()

    val fields = classAst.fields
            .filterNot { it.isTransient || it.isStatic }
            .mapIndexed { i, node -> FieldInfo(index = i, fieldAst = node, classInfo = this) }
            .apply { lastOrNull()?.isLast = true }
}