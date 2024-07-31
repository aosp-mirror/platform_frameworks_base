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

import com.android.internal.protolog.common.LogDataType
import com.android.internal.protolog.common.LogLevel
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.printer.PrettyPrinter
import com.github.javaparser.printer.PrettyPrinterConfiguration

class SourceTransformer(
    protoLogImplClassName: String,
    private val protoLogCallProcessor: ProtoLogCallProcessor
) {
    private val inlinePrinter: PrettyPrinter
    private val objectType = StaticJavaParser.parseClassOrInterfaceType("Object")

    init {
        val config = PrettyPrinterConfiguration()
        config.endOfLineCharacter = " "
        config.indentSize = 0
        config.tabWidth = 1
        inlinePrinter = PrettyPrinter(config)
    }

    fun processClass(
        code: String,
        path: String,
        packagePath: String,
        compilationUnit: CompilationUnit =
            StaticJavaParser.parse(code)
    ): String {
        this.path = path
        this.packagePath = packagePath
        processedCode = code.split('\n').toMutableList()
        offsets = IntArray(processedCode.size)
        protoLogCallProcessor.process(compilationUnit, protoLogCallVisitor, otherCallVisitor, path)
        return processedCode.joinToString("\n")
    }

    private val protoLogImplClassNode =
            StaticJavaParser.parseExpression<FieldAccessExpr>(protoLogImplClassName)
    private val protoLogImplCacheClassNode =
        StaticJavaParser.parseExpression<FieldAccessExpr>("$protoLogImplClassName.Cache")
    private var processedCode: MutableList<String> = mutableListOf()
    private var offsets: IntArray = IntArray(0)
    /** The path of the file being processed, relative to $ANDROID_BUILD_TOP */
    private var path: String = ""
    /** The path of the file being processed, relative to the root package */
    private var packagePath: String = ""

    private val protoLogCallVisitor = object : ProtoLogCallVisitor {
        override fun processCall(
            call: MethodCallExpr,
            messageString: String,
            level: LogLevel,
            group: LogGroup
        ) {
            validateCall(call)
            val processedCallStatement =
                createProcessedCallStatement(call, group, level, messageString)
            val parentStmt = call.parentNode.get() as ExpressionStmt
            injectProcessedCallStatementInCode(processedCallStatement, parentStmt)
        }
    }

    private fun validateCall(call: MethodCallExpr) {
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
    }

    private fun createProcessedCallStatement(
        call: MethodCallExpr,
        group: LogGroup,
        level: LogLevel,
        messageString: String
    ): Statement {
        val hash = CodeUtils.hash(packagePath, messageString, level, group)

        val newCall = call.clone()
        // Remove message string.
        // Out: ProtoLog.e(GROUP, args)
        newCall.arguments.removeAt(1)
        // Insert message string hash as a second argument.
        // Out: ProtoLog.e(GROUP, 1234, args)
        newCall.arguments.add(1, LongLiteralExpr("" + hash + "L"))
        val argTypes = LogDataType.parseFormatString(messageString)
        val typeMask = LogDataType.logDataTypesToBitMask(argTypes)
        // Insert bitmap representing which Number parameters are to be considered as
        // floating point numbers.
        // Out: ProtoLog.e(GROUP, 1234, 0, args)
        newCall.arguments.add(2, IntegerLiteralExpr(typeMask))
        // Replace call to a stub method with an actual implementation.
        // Out: ProtoLogImpl.e(GROUP, 1234, 0, args)
        newCall.setScope(protoLogImplClassNode)
        if (argTypes.size != call.arguments.size - 2) {
            throw InvalidProtoLogCallException(
                "Number of arguments (${argTypes.size} does not match format" +
                        " string in: $call", ParsingContext(path, call))
        }
        val argsOffset = 3
        val blockStmt = BlockStmt()
        if (argTypes.isNotEmpty()) {
            // Assign every argument to a variable to check its type in compile time
            // (this is assignment is optimized-out by dex tool, there is no runtime impact)/
            // Out: long protoLogParam0 = arg
            argTypes.forEachIndexed { idx, type ->
                val varName = "protoLogParam$idx"
                val declaration = VariableDeclarator(getASTTypeForDataType(type), varName,
                    getConversionForType(type)(newCall.arguments[idx + argsOffset].clone()))
                blockStmt.addStatement(ExpressionStmt(VariableDeclarationExpr(declaration)))
                newCall.setArgument(idx + argsOffset, NameExpr(SimpleName(varName)))
            }
        } else {
            // Assign (Object[])null as the vararg parameter to prevent allocating an empty
            // object array.
            val nullArray = CastExpr(ArrayType(objectType), NullLiteralExpr())
            newCall.addArgument(nullArray)
        }
        blockStmt.addStatement(ExpressionStmt(newCall))

        val isLogEnabled = ArrayAccessExpr()
            .setName(NameExpr("$protoLogImplCacheClassNode.${group.name}_enabled"))
            .setIndex(IntegerLiteralExpr(level.ordinal))

        return IfStmt(isLogEnabled, blockStmt, null)
    }

    private fun injectProcessedCallStatementInCode(
        processedCallStatement: Statement,
        parentStmt: ExpressionStmt
    ) {
        // Inline the new statement.
        val printedBlockStmt = inlinePrinter.print(processedCallStatement)
        // Append blank lines to preserve line numbering in file (to allow debugging)
        val parentRange = parentStmt.range.get()
        val newLines = parentRange.end.line - parentRange.begin.line
        val newStmt = printedBlockStmt.substringBeforeLast('}') + ("\n".repeat(newLines)) + '}'
        // pre-workaround code, see explanation below

        /** Workaround for a bug in JavaParser (AST tree invalid after replacing a node when using
         * LexicalPreservingPrinter (https://github.com/javaparser/javaparser/issues/2290).
         * Replace the code below with the one commended-out above one the issue is resolved. */
        if (!parentStmt.range.isPresent) {
            // Should never happen
            throw RuntimeException("Unable to process log call in $parentStmt " +
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

    private val otherCallVisitor = object : MethodCallVisitor {
        override fun processCall(call: MethodCallExpr) {
            val newCall = call.clone()
            newCall.setScope(protoLogImplClassNode)

            val range = call.range.get()
            val begin = range.begin.line - 1
            val oldLines = processedCode.subList(begin, range.end.line)
            val oldCode = oldLines.joinToString("\n")
            val newCode = oldCode.replaceRange(
                offsets[begin] + range.begin.column - 1,
                oldCode.length - oldLines.lastOrNull()!!.length +
                        range.end.column + offsets[range.end.line - 1], newCall.toString())
            newCode.split("\n").forEachIndexed { idx, line ->
                offsets[begin + idx] += line.length - processedCode[begin + idx].length
                processedCode[begin + idx] = line
            }
        }
    }

    companion object {
        private val stringType: ClassOrInterfaceType =
            StaticJavaParser.parseClassOrInterfaceType("String")

        fun getASTTypeForDataType(type: Int): Type {
            return when (type) {
                LogDataType.STRING -> stringType.clone()
                LogDataType.LONG -> PrimitiveType.longType()
                LogDataType.DOUBLE -> PrimitiveType.doubleType()
                LogDataType.BOOLEAN -> PrimitiveType.booleanType()
                else -> {
                    // Should never happen.
                    throw RuntimeException("Invalid LogDataType")
                }
            }
        }

        fun getConversionForType(type: Int): (Expression) -> Expression {
            return when (type) {
                LogDataType.STRING -> { expr ->
                    MethodCallExpr(TypeExpr(StaticJavaParser.parseClassOrInterfaceType("String")),
                        SimpleName("valueOf"), NodeList(expr))
                }
                else -> { expr -> expr }
            }
        }
    }
}
