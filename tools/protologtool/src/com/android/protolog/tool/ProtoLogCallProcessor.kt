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
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr

/**
 * Helper class for visiting all ProtoLog calls.
 * For every valid call in the given {@code CompilationUnit} a {@code ProtoLogCallVisitor} callback
 * is executed.
 */
open class ProtoLogCallProcessor(
    private val protoLogClassName: String,
    private val protoLogGroupClassName: String,
    private val groupMap: Map<String, LogGroup>
) {
    private val protoLogSimpleClassName = protoLogClassName.substringAfterLast('.')
    private val protoLogGroupSimpleClassName = protoLogGroupClassName.substringAfterLast('.')

    private fun getLogGroupName(
        expr: Expression,
        isClassImported: Boolean,
        staticImports: Set<String>,
        fileName: String
    ): String {
        val context = ParsingContext(fileName, expr)
        return when (expr) {
            is NameExpr -> when {
                expr.nameAsString in staticImports -> expr.nameAsString
                else ->
                    throw InvalidProtoLogCallException("Unknown/not imported ProtoLogGroup: $expr",
                            context)
            }
            is FieldAccessExpr -> when {
                expr.scope.toString() == protoLogGroupClassName
                        || isClassImported &&
                        expr.scope.toString() == protoLogGroupSimpleClassName -> expr.nameAsString
                else ->
                    throw InvalidProtoLogCallException("Unknown/not imported ProtoLogGroup: $expr",
                            context)
            }
            else -> throw InvalidProtoLogCallException("Invalid group argument " +
                    "- must be ProtoLogGroup enum member reference: $expr", context)
        }
    }

    private fun isProtoCall(
        call: MethodCallExpr,
        isLogClassImported: Boolean,
        staticLogImports: Collection<String>
    ): Boolean {
        return call.scope.isPresent && call.scope.get().toString() == protoLogClassName ||
                isLogClassImported && call.scope.isPresent &&
                call.scope.get().toString() == protoLogSimpleClassName ||
                !call.scope.isPresent && staticLogImports.contains(call.name.toString())
    }

    open fun process(code: CompilationUnit, callVisitor: ProtoLogCallVisitor?, fileName: String):
            CompilationUnit {
        CodeUtils.checkWildcardStaticImported(code, protoLogClassName, fileName)
        CodeUtils.checkWildcardStaticImported(code, protoLogGroupClassName, fileName)

        val isLogClassImported = CodeUtils.isClassImportedOrSamePackage(code, protoLogClassName)
        val staticLogImports = CodeUtils.staticallyImportedMethods(code, protoLogClassName)
        val isGroupClassImported = CodeUtils.isClassImportedOrSamePackage(code,
                protoLogGroupClassName)
        val staticGroupImports = CodeUtils.staticallyImportedMethods(code, protoLogGroupClassName)

        code.findAll(MethodCallExpr::class.java)
                .filter { call ->
                    isProtoCall(call, isLogClassImported, staticLogImports)
                }.forEach { call ->
                    val context = ParsingContext(fileName, call)
                    if (call.arguments.size < 2) {
                        throw InvalidProtoLogCallException("Method signature does not match " +
                                "any ProtoLog method: $call", context)
                    }

                    val messageString = CodeUtils.concatMultilineString(call.getArgument(1),
                            context)
                    val groupNameArg = call.getArgument(0)
                    val groupName =
                            getLogGroupName(groupNameArg, isGroupClassImported,
                                    staticGroupImports, fileName)
                    if (groupName !in groupMap) {
                        throw InvalidProtoLogCallException("Unknown group argument " +
                                "- not a ProtoLogGroup enum member: $call", context)
                    }

                    callVisitor?.processCall(call, messageString, LogLevel.getLevelForMethodName(
                            call.name.toString(), call, context), groupMap.getValue(groupName))
                }
        return code
    }
}
