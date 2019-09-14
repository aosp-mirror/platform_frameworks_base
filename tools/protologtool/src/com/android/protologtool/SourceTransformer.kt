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

import com.android.protologtool.Constants.IS_LOG_TO_ANY_METHOD
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.printer.PrettyPrinter
import com.github.javaparser.printer.PrettyPrinterConfiguration
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

class SourceTransformer(
    protoLogImplClassName: String,
    private val protoLogCallProcessor: ProtoLogCallProcessor
) : ProtoLogCallVisitor {
    override fun processCall(
        call: MethodCallExpr,
        messageString: String,
        level: LogLevel,
        group: LogGroup
    ) {
        // Input format: ProtoLog.e(GROUP, "msg %d", arg)
        if (!call.parentNode.isPresent) {
            // Should never happen
            throw RuntimeException("Unable to process log call $call " +
                    "- no parent node in AST")
        }
        if (call.parentNode.get() !is ExpressionStmt) {
            // Should never happen
            throw RuntimeException("Unable to process log call $call " +
                    "- parent node in AST is not an ExpressionStmt")
        }
        val parentStmt = call.parentNode.get() as ExpressionStmt
        if (!parentStmt.parentNode.isPresent) {
            // Should never happen
            throw RuntimeException("Unable to process log call $call " +
                    "- no grandparent node in AST")
        }
        val ifStmt: IfStmt
        if (group.enabled) {
            val hash = CodeUtils.hash(messageString, level)
            val newCall = call.clone()
            if (!group.textEnabled) {
                // Remove message string if text logging is not enabled by default.
                // Out: ProtoLog.e(GROUP, null, arg)
                newCall.arguments[1].replace(NameExpr("null"))
            }
            // Insert message string hash as a second argument.
            // Out: ProtoLog.e(GROUP, 1234, null, arg)
            newCall.arguments.add(1, IntegerLiteralExpr(hash))
            val argTypes = CodeUtils.parseFormatString(messageString)
            val typeMask = CodeUtils.logDataTypesToBitMask(argTypes)
            // Insert bitmap representing which Number parameters are to be considered as
            // floating point numbers.
            // Out: ProtoLog.e(GROUP, 1234, 0, null, arg)
            newCall.arguments.add(2, IntegerLiteralExpr(typeMask))
            // Replace call to a stub method with an actual implementation.
            // Out: com.android.server.wm.ProtoLogImpl.e(GROUP, 1234, null, arg)
            newCall.setScope(protoLogImplClassNode)
            // Create a call to GROUP.isLogAny()
            // Out: GROUP.isLogAny()
            val isLogAnyExpr = MethodCallExpr(newCall.arguments[0].clone(),
                    SimpleName(IS_LOG_TO_ANY_METHOD))
            if (argTypes.size != call.arguments.size - 2) {
                throw InvalidProtoLogCallException(
                        "Number of arguments does not mach format string", call)
            }
            val blockStmt = BlockStmt()
            if (argTypes.isNotEmpty()) {
                // Assign every argument to a variable to check its type in compile time
                // (this is assignment is optimized-out by dex tool, there is no runtime impact)/
                // Out: long protoLogParam0 = arg
                argTypes.forEachIndexed { idx, type ->
                    val varName = "protoLogParam$idx"
                    val declaration = VariableDeclarator(type.type, varName,
                            type.toType(newCall.arguments[idx + 4].clone()))
                    blockStmt.addStatement(ExpressionStmt(VariableDeclarationExpr(declaration)))
                    newCall.setArgument(idx + 4, NameExpr(SimpleName(varName)))
                }
            } else {
                // Assign (Object[])null as the vararg parameter to prevent allocating an empty
                // object array.
                val nullArray = CastExpr(ArrayType(objectType), NullLiteralExpr())
                newCall.addArgument(nullArray)
            }
            blockStmt.addStatement(ExpressionStmt(newCall))
            // Create an IF-statement with the previously created condition.
            // Out: if (GROUP.isLogAny()) {
            //          long protoLogParam0 = arg;
            //          com.android.server.wm.ProtoLogImpl.e(GROUP, 1234, 0, null, protoLogParam0);
            //      }
            ifStmt = IfStmt(isLogAnyExpr, blockStmt, null)
        } else {
            // Surround with if (false).
            val newCall = parentStmt.clone()
            ifStmt = IfStmt(BooleanLiteralExpr(false), BlockStmt(NodeList(newCall)), null)
            newCall.setBlockComment(" ${group.name} is disabled ")
        }
        // Inline the new statement.
        val printedIfStmt = inlinePrinter.print(ifStmt)
        // Append blank lines to preserve line numbering in file (to allow debugging)
        val newLines = LexicalPreservingPrinter.print(parentStmt).count { c -> c == '\n' }
        val newStmt = printedIfStmt.substringBeforeLast('}') + ("\n".repeat(newLines)) + '}'
        // pre-workaround code, see explanation below
        /*
        val inlinedIfStmt = StaticJavaParser.parseStatement(newStmt)
        LexicalPreservingPrinter.setup(inlinedIfStmt)
        // Replace the original call.
        if (!parentStmt.replace(inlinedIfStmt)) {
            // Should never happen
            throw RuntimeException("Unable to process log call $call " +
                    "- unable to replace the call.")
        }
        */
        /** Workaround for a bug in JavaParser (AST tree invalid after replacing a node when using
         * LexicalPreservingPrinter (https://github.com/javaparser/javaparser/issues/2290).
         * Replace the code below with the one commended-out above one the issue is resolved. */
        if (!parentStmt.range.isPresent) {
            // Should never happen
            throw RuntimeException("Unable to process log call $call " +
                    "- unable to replace the call.")
        }
        val range = parentStmt.range.get()
        val begin = range.begin.line - 1
        val oldLines = processedCode.subList(begin, range.end.line)
        val oldCode = oldLines.joinToString("\n")
        val newCode = oldCode.replaceRange(
                offsets[begin] + range.begin.column - 1,
                oldCode.length - oldLines.lastOrNull()!!.length +
                        range.end.column + offsets[range.end.line - 1], newStmt)
        newCode.split("\n").forEachIndexed { idx, line ->
            offsets[begin + idx] += line.length - processedCode[begin + idx].length
            processedCode[begin + idx] = line
        }
    }

    private val inlinePrinter: PrettyPrinter
    private val objectType = StaticJavaParser.parseClassOrInterfaceType("Object")

    init {
        val config = PrettyPrinterConfiguration()
        config.endOfLineCharacter = " "
        config.indentSize = 0
        config.tabWidth = 1
        inlinePrinter = PrettyPrinter(config)
    }

    private val protoLogImplClassNode =
            StaticJavaParser.parseExpression<FieldAccessExpr>(protoLogImplClassName)
    private var processedCode: MutableList<String> = mutableListOf()
    private var offsets: IntArray = IntArray(0)

    fun processClass(
        code: String,
        compilationUnit: CompilationUnit =
               StaticJavaParser.parse(code)
    ): String {
        processedCode = code.split('\n').toMutableList()
        offsets = IntArray(processedCode.size)
        LexicalPreservingPrinter.setup(compilationUnit)
        protoLogCallProcessor.process(compilationUnit, this)
        // return LexicalPreservingPrinter.print(compilationUnit)
        return processedCode.joinToString("\n")
    }
}
