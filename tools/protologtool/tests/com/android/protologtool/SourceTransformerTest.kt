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
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.IfStmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito

class SourceTransformerTest {
    companion object {
        private const val PROTO_LOG_IMPL_PATH = "org.example.ProtoLogImpl"
        private val TEST_CODE = """
            package org.example;

            class Test {
                void test() {
                    ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1);
                }
            }
            """.trimIndent()

        private val TEST_CODE_MULTILINE = """
            package org.example;

            class Test {
                void test() {
                    ProtoLog.w(TEST_GROUP, "test %d %f " + 
                    "abc %s\n test", 100,
                     0.1, "test");
                }
            }
            """.trimIndent()

        private val TEST_CODE_NO_PARAMS = """
            package org.example;

            class Test {
                void test() {
                    ProtoLog.w(TEST_GROUP, "test");
                }
            }
            """.trimIndent()

        /* ktlint-disable max-line-length */
        private val TRANSFORMED_CODE_TEXT_ENABLED = """
            package org.example;

            class Test {
                void test() {
                    if (TEST_GROUP.isLogToAny()) { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, 835524026, 9, "test %d %f", protoLogParam0, protoLogParam1); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_MULTILINE_TEXT_ENABLED = """
            package org.example;

            class Test {
                void test() {
                    if (TEST_GROUP.isLogToAny()) { long protoLogParam0 = 100; double protoLogParam1 = 0.1; String protoLogParam2 = String.valueOf("test"); org.example.ProtoLogImpl.w(TEST_GROUP, -986393606, 9, "test %d %f " + "abc %s\n test", protoLogParam0, protoLogParam1, protoLogParam2); 
            
            }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_NO_PARAMS = """
            package org.example;

            class Test {
                void test() {
                    if (TEST_GROUP.isLogToAny()) { org.example.ProtoLogImpl.w(TEST_GROUP, 1282022424, 0, "test", (Object[]) null); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_TEXT_DISABLED = """
            package org.example;

            class Test {
                void test() {
                    if (TEST_GROUP.isLogToAny()) { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, 835524026, 9, null, protoLogParam0, protoLogParam1); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_MULTILINE_TEXT_DISABLED = """
            package org.example;

            class Test {
                void test() {
                    if (TEST_GROUP.isLogToAny()) { long protoLogParam0 = 100; double protoLogParam1 = 0.1; String protoLogParam2 = String.valueOf("test"); org.example.ProtoLogImpl.w(TEST_GROUP, -986393606, 9, null, protoLogParam0, protoLogParam1, protoLogParam2); 
            
            }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_DISABLED = """
            package org.example;

            class Test {
                void test() {
                    if (false) { /* TEST_GROUP is disabled */ ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_MULTILINE_DISABLED = """
            package org.example;

            class Test {
                void test() {
                    if (false) { /* TEST_GROUP is disabled */ ProtoLog.w(TEST_GROUP, "test %d %f " + "abc %s\n test", 100, 0.1, "test"); 
            
            }
                }
            }
            """.trimIndent()
        /* ktlint-enable max-line-length */
    }

    private val processor: ProtoLogCallProcessor = Mockito.mock(ProtoLogCallProcessor::class.java)
    private val sourceJarWriter = SourceTransformer("org.example.ProtoLogImpl", processor)

    private fun <T> any(type: Class<T>): T = Mockito.any<T>(type)

    @Test
    fun processClass_textEnabled() {
        val code = StaticJavaParser.parse(TEST_CODE)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("TEST_GROUP.${Constants.IS_LOG_TO_ANY_METHOD}()",
                ifStmt.condition.toString())
        assertFalse(ifStmt.elseStmt.isPresent)
        assertEquals(3, ifStmt.thenStmt.childNodes.size)
        val methodCall = ifStmt.thenStmt.findAll(MethodCallExpr::class.java)[0] as MethodCallExpr
        assertEquals(PROTO_LOG_IMPL_PATH, methodCall.scope.get().toString())
        assertEquals("w", methodCall.name.asString())
        assertEquals(6, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("835524026", methodCall.arguments[1].toString())
        assertEquals(0b1001.toString(), methodCall.arguments[2].toString())
        assertEquals("\"test %d %f\"", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals(TRANSFORMED_CODE_TEXT_ENABLED, out)
    }

    @Test
    fun processClass_textEnabledMultiline() {
        val code = StaticJavaParser.parse(TEST_CODE_MULTILINE)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0],
                    "test %d %f abc %s\n test", LogLevel.WARN, LogGroup("TEST_GROUP",
                    true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("TEST_GROUP.${Constants.IS_LOG_TO_ANY_METHOD}()",
                ifStmt.condition.toString())
        assertFalse(ifStmt.elseStmt.isPresent)
        assertEquals(4, ifStmt.thenStmt.childNodes.size)
        val methodCall = ifStmt.thenStmt.findAll(MethodCallExpr::class.java)[1] as MethodCallExpr
        assertEquals(PROTO_LOG_IMPL_PATH, methodCall.scope.get().toString())
        assertEquals("w", methodCall.name.asString())
        assertEquals(7, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-986393606", methodCall.arguments[1].toString())
        assertEquals(0b001001.toString(), methodCall.arguments[2].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals("protoLogParam2", methodCall.arguments[6].toString())
        assertEquals(TRANSFORMED_CODE_MULTILINE_TEXT_ENABLED, out)
    }

    @Test
    fun processClass_noParams() {
        val code = StaticJavaParser.parse(TEST_CODE_NO_PARAMS)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("TEST_GROUP.${Constants.IS_LOG_TO_ANY_METHOD}()",
                ifStmt.condition.toString())
        assertFalse(ifStmt.elseStmt.isPresent)
        assertEquals(1, ifStmt.thenStmt.childNodes.size)
        val methodCall = ifStmt.thenStmt.findAll(MethodCallExpr::class.java)[0] as MethodCallExpr
        assertEquals(PROTO_LOG_IMPL_PATH, methodCall.scope.get().toString())
        assertEquals("w", methodCall.name.asString())
        assertEquals(5, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("1282022424", methodCall.arguments[1].toString())
        assertEquals(0.toString(), methodCall.arguments[2].toString())
        assertEquals(TRANSFORMED_CODE_NO_PARAMS, out)
    }

    @Test
    fun processClass_textDisabled() {
        val code = StaticJavaParser.parse(TEST_CODE)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, false, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("TEST_GROUP.${Constants.IS_LOG_TO_ANY_METHOD}()",
                ifStmt.condition.toString())
        assertFalse(ifStmt.elseStmt.isPresent)
        assertEquals(3, ifStmt.thenStmt.childNodes.size)
        val methodCall = ifStmt.thenStmt.findAll(MethodCallExpr::class.java)[0] as MethodCallExpr
        assertEquals(PROTO_LOG_IMPL_PATH, methodCall.scope.get().toString())
        assertEquals("w", methodCall.name.asString())
        assertEquals(6, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("835524026", methodCall.arguments[1].toString())
        assertEquals(0b1001.toString(), methodCall.arguments[2].toString())
        assertEquals("null", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals(TRANSFORMED_CODE_TEXT_DISABLED, out)
    }

    @Test
    fun processClass_textDisabledMultiline() {
        val code = StaticJavaParser.parse(TEST_CODE_MULTILINE)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0],
                    "test %d %f abc %s\n test", LogLevel.WARN, LogGroup("TEST_GROUP",
                    true, false, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("TEST_GROUP.${Constants.IS_LOG_TO_ANY_METHOD}()",
                ifStmt.condition.toString())
        assertFalse(ifStmt.elseStmt.isPresent)
        assertEquals(4, ifStmt.thenStmt.childNodes.size)
        val methodCall = ifStmt.thenStmt.findAll(MethodCallExpr::class.java)[1] as MethodCallExpr
        assertEquals(PROTO_LOG_IMPL_PATH, methodCall.scope.get().toString())
        assertEquals("w", methodCall.name.asString())
        assertEquals(7, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-986393606", methodCall.arguments[1].toString())
        assertEquals(0b001001.toString(), methodCall.arguments[2].toString())
        assertEquals("null", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals("protoLogParam2", methodCall.arguments[6].toString())
        assertEquals(TRANSFORMED_CODE_MULTILINE_TEXT_DISABLED, out)
    }

    @Test
    fun processClass_disabled() {
        val code = StaticJavaParser.parse(TEST_CODE)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", false, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("false", ifStmt.condition.toString())
        assertEquals(TRANSFORMED_CODE_DISABLED, out)
    }

    @Test
    fun processClass_disabledMultiline() {
        val code = StaticJavaParser.parse(TEST_CODE_MULTILINE)

        Mockito.`when`(processor.process(any(CompilationUnit::class.java),
                any(ProtoLogCallVisitor::class.java))).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0],
                    "test %d %f abc %s\n test", LogLevel.WARN, LogGroup("TEST_GROUP",
                    false, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(code)

        val ifStmts = code.findAll(IfStmt::class.java)
        assertEquals(1, ifStmts.size)
        val ifStmt = ifStmts[0]
        assertEquals("false", ifStmt.condition.toString())
        assertEquals(TRANSFORMED_CODE_MULTILINE_DISABLED, out)
    }
}
