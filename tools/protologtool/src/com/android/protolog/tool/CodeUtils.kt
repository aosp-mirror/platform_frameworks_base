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

package com.android.protolog.tool

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.StringLiteralExpr

object CodeUtils {
    /**
     * Returns a stable hash of a string.
     * We reimplement String::hashCode() for readability reasons.
     */
    fun hash(position: String, messageString: String, logLevel: LogLevel, logGroup: LogGroup): Int {
        return (position + messageString + logLevel.name + logGroup.name)
                .map { c -> c.toInt() }.reduce { h, c -> h * 31 + c }
    }

    fun checkWildcardStaticImported(code: CompilationUnit, className: String, fileName: String) {
        code.findAll(ImportDeclaration::class.java)
                .forEach { im ->
                    if (im.isStatic && im.isAsterisk && im.name.toString() == className) {
                        throw IllegalImportException("Wildcard static imports of $className " +
                                "methods are not supported.", ParsingContext(fileName, im))
                    }
                }
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

    fun concatMultilineString(expr: Expression, context: ParsingContext): String {
        return when (expr) {
            is StringLiteralExpr -> expr.asString()
            is BinaryExpr -> when {
                expr.operator == BinaryExpr.Operator.PLUS ->
                    concatMultilineString(expr.left, context) +
                            concatMultilineString(expr.right, context)
                else -> throw InvalidProtoLogCallException(
                        "expected a string literal " +
                                "or concatenation of string literals, got: $expr", context)
            }
            else -> throw InvalidProtoLogCallException("expected a string literal " +
                    "or concatenation of string literals, got: $expr", context)
        }
    }
}
