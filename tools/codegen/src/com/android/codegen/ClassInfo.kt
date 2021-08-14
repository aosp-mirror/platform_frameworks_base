package com.android.codegen

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration

open class ClassInfo(val classAst: ClassOrInterfaceDeclaration, val fileInfo: FileInfo) {

    val fileAst = fileInfo.fileAst

    val nestedClasses = classAst.members.filterIsInstance<ClassOrInterfaceDeclaration>()
    val nestedTypes = classAst.members.filterIsInstance<TypeDeclaration<*>>()

    val superInterfaces = classAst.implementedTypes.map { it.asString() }
    val superClass = classAst.extendedTypes.getOrNull(0)

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