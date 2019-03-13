package com.android.codegen

import com.github.javaparser.JavaParser
import com.github.javaparser.ParseProblemException
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration

open class ClassInfo(val sourceLines: List<String>) {

    private val userSourceCode = (sourceLines + "}").joinToString("\n")
    val fileAst = try {
        JavaParser.parse(userSourceCode)!!
    } catch (e: ParseProblemException) {
        throw RuntimeException("Failed to parse code:\n" +
                userSourceCode
                        .lines()
                        .mapIndexed { lnNum, ln -> "/*$lnNum*/$ln" }
                        .joinToString("\n"),
                e)
    }
    val classAst = fileAst.types[0] as ClassOrInterfaceDeclaration

    fun hasMethod(name: String, vararg argTypes: String): Boolean {
        return classAst.methods.any {
            it.name.asString() == name &&
                    it.parameters.map { it.type.asString() } == argTypes.toList()
        }
    }

    val superInterfaces = (fileAst.types[0] as ClassOrInterfaceDeclaration)
            .implementedTypes.map { it.asString() }

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
    val lazyTransientFields = classAst.fields
            .filter { it.isTransient && !it.isStatic }
            .mapIndexed { i, node -> FieldInfo(index = i, fieldAst = node, classInfo = this) }
            .filter { hasMethod("lazyInit${it.NameUpperCamel}") }
}