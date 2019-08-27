/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.protologtool

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type

object CodeUtils {
    /**
     * Returns a stable hash of a string.
     * We reimplement String::hashCode() for readability reasons.
     */
    fun hash(str: String, level: LogLevel): Int {
        return (level.name + str).map { c -> c.toInt() }.reduce { h, c -> h * 31 + c }
    }

    fun isWildcardStaticImported(code: CompilationUnit, className: String): Boolean {
        return code.findAll(ImportDeclaration::class.java)
                .any { im -> im.isStatic && im.isAsterisk && im.name.toString() == className }
    }

    fun isClassImportedOrSamePackage(code: CompilationUnit, className: String): Boolean {
        val packageName = className.substringBeforeLast('.')
        return code.packageDeclaration.isPresent &&
                code.packageDeclaration.get().nameAsString == packageName ||
                code.findAll(ImportDeclaration::class.java)
                        .any { im ->
                            !im.isStatic &&
                                    ((!im.isAsterisk && im.name.toString() == className) ||
                                            (im.isAsterisk && im.name.toString() == packageName))
                        }
    }

    fun staticallyImportedMethods(code: CompilationUnit, className: String): Set<String> {
        return code.findAll(ImportDeclaration::class.java)
                .filter { im ->
                    im.isStatic &&
                            im.name.toString().substringBeforeLast('.') == className
                }
                .map { im -> im.name.toString().substringAfterLast('.') }.toSet()
    }

    fun concatMultilineString(expr: Expression): String {
        return when (expr) {
            is StringLiteralExpr -> expr.asString()
            is BinaryExpr -> when {
                expr.operator == BinaryExpr.Operator.PLUS ->
                    concatMultilineString(expr.left) + concatMultilineString(expr.right)
                else -> throw InvalidProtoLogCallException(
                        "messageString must be a string literal " +
                                "or concatenation of string literals.", expr)
            }
            else -> throw InvalidProtoLogCallException("messageString must be a string literal " +
                    "or concatenation of string literals.", expr)
        }
    }

    enum class LogDataTypes(
        val type: Type,
        val toType: (Expression) -> Expression = { expr -> expr }
    ) {
        // When adding new LogDataType make sure to update {@code logDataTypesToBitMask} accordingly
        STRING(StaticJavaParser.parseClassOrInterfaceType("String"),
                { expr ->
                    MethodCallExpr(TypeExpr(StaticJavaParser.parseClassOrInterfaceType("String")),
                            SimpleName("valueOf"), NodeList(expr))
                }),
        LONG(PrimitiveType.longType()),
        DOUBLE(PrimitiveType.doubleType()),
        BOOLEAN(PrimitiveType.booleanType());
    }

    fun parseFormatString(messageString: String): List<LogDataTypes> {
        val types = mutableListOf<LogDataTypes>()
        var i = 0
        while (i < messageString.length) {
            if (messageString[i] == '%') {
                if (i + 1 >= messageString.length) {
                    throw InvalidFormatStringException("Invalid format string in config")
                }
                when (messageString[i + 1]) {
                    'b' -> types.add(CodeUtils.LogDataTypes.BOOLEAN)
                    'd', 'o', 'x' -> types.add(CodeUtils.LogDataTypes.LONG)
                    'f', 'e', 'g' -> types.add(CodeUtils.LogDataTypes.DOUBLE)
                    's' -> types.add(CodeUtils.LogDataTypes.STRING)
                    '%' -> {
                    }
                    else -> throw InvalidFormatStringException("Invalid format string field" +
                            " %${messageString[i + 1]}")
                }
                i += 2
            } else {
                i += 1
            }
        }
        return types
    }

    fun logDataTypesToBitMask(types: List<LogDataTypes>): Int {
        if (types.size > 16) {
            throw InvalidFormatStringException("Too many log call parameters " +
                    "- max 16 parameters supported")
        }
        var mask = 0
        types.forEachIndexed { idx, type ->
            val x = LogDataTypes.values().indexOf(type)
            mask = mask or (x shl (idx * 2))
        }
        return mask
    }
}
