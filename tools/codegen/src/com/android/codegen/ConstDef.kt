package com.android.codegen

import com.github.javaparser.ast.body.FieldDeclaration

/**
 * `@IntDef` or `@StringDef`
 */
data class ConstDef(val type: Type, val AnnotationName: String, val values: List<FieldDeclaration>) {

    enum class Type {
        INT, INT_FLAGS, STRING;

        val isInt get() = this == INT || this == INT_FLAGS
    }

    val CONST_NAMES get() = values.flatMap { it.variables }.map { it.nameAsString }
}