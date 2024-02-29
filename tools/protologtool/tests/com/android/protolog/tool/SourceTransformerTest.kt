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

import com.android.internal.protolog.common.LogLevel
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.expr.MethodCallExpr
import com.google.common.truth.Truth
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class SourceTransformerTest {
    companion object {

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

        private val TEST_CODE_MULTICALLS = """
            package org.example;

            class Test {
                void test() {
                    ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1); /* ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1); */ ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1);
                    ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1);
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

        private val TRANSFORMED_CODE_TEXT_ENABLED = """
            package org.example;

            class Test {
                void test() {
                    { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, -1473209266730422156L, 9, "test %d %f", protoLogParam0, protoLogParam1); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_MULTILINE_TEXT_ENABLED = """
            package org.example;

            class Test {
                void test() {
                    { long protoLogParam0 = 100; double protoLogParam1 = 0.1; String protoLogParam2 = String.valueOf("test"); org.example.ProtoLogImpl.w(TEST_GROUP, -4447034859795564700L, 9, "test %d %f " + "abc %s\n test", protoLogParam0, protoLogParam1, protoLogParam2); 
            
            }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_MULTICALL_TEXT = """
            package org.example;

            class Test {
                void test() {
                    { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, -1473209266730422156L, 9, "test %d %f", protoLogParam0, protoLogParam1); } /* ProtoLog.w(TEST_GROUP, "test %d %f", 100, 0.1); */ { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, -1473209266730422156L, 9, "test %d %f", protoLogParam0, protoLogParam1); }
                    { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, -1473209266730422156L, 9, "test %d %f", protoLogParam0, protoLogParam1); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_NO_PARAMS = """
            package org.example;

            class Test {
                void test() {
                    { org.example.ProtoLogImpl.w(TEST_GROUP, 3218600869538902408L, 0, "test", (Object[]) null); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_TEXT_DISABLED = """
            package org.example;

            class Test {
                void test() {
                    { long protoLogParam0 = 100; double protoLogParam1 = 0.1; org.example.ProtoLogImpl.w(TEST_GROUP, -1473209266730422156L, 9, null, protoLogParam0, protoLogParam1); }
                }
            }
            """.trimIndent()

        private val TRANSFORMED_CODE_MULTILINE_TEXT_DISABLED = """
            package org.example;

            class Test {
                void test() {
                    { long protoLogParam0 = 100; double protoLogParam1 = 0.1; String protoLogParam2 = String.valueOf("test"); org.example.ProtoLogImpl.w(TEST_GROUP, -4447034859795564700L, 9, null, protoLogParam0, protoLogParam1, protoLogParam2); 
            
            }
                }
            }
            """.trimIndent()

        private const val PATH = "com.example.Test.java"
    }

    private val processor: ProtoLogCallProcessor = Mockito.mock(ProtoLogCallProcessor::class.java)
    private val implName = "org.example.ProtoLogImpl"
    private val sourceJarWriter = SourceTransformer(implName, processor)

    private fun <T> any(type: Class<T>): T = Mockito.any<T>(type)

    @Test
    fun processClass_textEnabled() {
        var code = StaticJavaParser.parse(TEST_CODE)

        Mockito.`when`(processor.process(
            any(CompilationUnit::class.java),
            any(ProtoLogCallVisitor::class.java),
            any(MethodCallVisitor::class.java),
            any(String::class.java))
        ).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(TEST_CODE, PATH, PATH, code)
        code = StaticJavaParser.parse(out)

        val protoLogCalls = code.findAll(MethodCallExpr::class.java).filter {
            it.scope.orElse(null)?.toString() == implName
        }
        Truth.assertThat(protoLogCalls).hasSize(1)
        val methodCall = protoLogCalls[0] as MethodCallExpr
        assertEquals("w", methodCall.name.asString())
        assertEquals(6, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-1473209266730422156L", methodCall.arguments[1].toString())
        assertEquals(0b1001.toString(), methodCall.arguments[2].toString())
        assertEquals("\"test %d %f\"", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals(TRANSFORMED_CODE_TEXT_ENABLED, out)
    }

    @Test
    fun processClass_textEnabledMulticalls() {
        var code = StaticJavaParser.parse(TEST_CODE_MULTICALLS)

        Mockito.`when`(processor.process(
            any(CompilationUnit::class.java),
            any(ProtoLogCallVisitor::class.java),
            any(MethodCallVisitor::class.java),
            any(String::class.java))
        ).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            val calls = code.findAll(MethodCallExpr::class.java)
            visitor.processCall(calls[0], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))
            visitor.processCall(calls[1], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))
            visitor.processCall(calls[2], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(TEST_CODE_MULTICALLS, PATH, PATH, code)
        code = StaticJavaParser.parse(out)

        val protoLogCalls = code.findAll(MethodCallExpr::class.java).filter {
            it.scope.orElse(null)?.toString() == implName
        }
        Truth.assertThat(protoLogCalls).hasSize(3)
        val methodCall = protoLogCalls[0] as MethodCallExpr
        assertEquals("w", methodCall.name.asString())
        assertEquals(6, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-1473209266730422156L", methodCall.arguments[1].toString())
        assertEquals(0b1001.toString(), methodCall.arguments[2].toString())
        assertEquals("\"test %d %f\"", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals(TRANSFORMED_CODE_MULTICALL_TEXT, out)
    }

    @Test
    fun processClass_textEnabledMultiline() {
        var code = StaticJavaParser.parse(TEST_CODE_MULTILINE)

        Mockito.`when`(processor.process(
            any(CompilationUnit::class.java),
            any(ProtoLogCallVisitor::class.java),
            any(MethodCallVisitor::class.java),
            any(String::class.java))
        ).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0],
                    "test %d %f abc %s\n test", LogLevel.WARN, LogGroup("TEST_GROUP",
                    true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(TEST_CODE_MULTILINE, PATH, PATH, code)
        code = StaticJavaParser.parse(out)

        val protoLogCalls = code.findAll(MethodCallExpr::class.java).filter {
            it.scope.orElse(null)?.toString() == implName
        }
        Truth.assertThat(protoLogCalls).hasSize(1)
        val methodCall = protoLogCalls[0] as MethodCallExpr
        assertEquals("w", methodCall.name.asString())
        assertEquals(7, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-4447034859795564700L", methodCall.arguments[1].toString())
        assertEquals(0b001001.toString(), methodCall.arguments[2].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals("protoLogParam2", methodCall.arguments[6].toString())
        assertEquals(TRANSFORMED_CODE_MULTILINE_TEXT_ENABLED, out)
    }

    @Test
    fun processClass_noParams() {
        var code = StaticJavaParser.parse(TEST_CODE_NO_PARAMS)

        Mockito.`when`(processor.process(
            any(CompilationUnit::class.java),
            any(ProtoLogCallVisitor::class.java),
            any(MethodCallVisitor::class.java),
            any(String::class.java))
        ).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, true, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(TEST_CODE_NO_PARAMS, PATH, PATH, code)
        code = StaticJavaParser.parse(out)

        val protoLogCalls = code.findAll(MethodCallExpr::class.java).filter {
            it.scope.orElse(null)?.toString() == implName
        }
        Truth.assertThat(protoLogCalls).hasSize(1)
        val methodCall = protoLogCalls[0] as MethodCallExpr
        assertEquals("w", methodCall.name.asString())
        assertEquals(5, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("3218600869538902408L", methodCall.arguments[1].toString())
        assertEquals(0.toString(), methodCall.arguments[2].toString())
        assertEquals(TRANSFORMED_CODE_NO_PARAMS, out)
    }

    @Test
    fun processClass_textDisabled() {
        var code = StaticJavaParser.parse(TEST_CODE)

        Mockito.`when`(processor.process(
            any(CompilationUnit::class.java),
            any(ProtoLogCallVisitor::class.java),
            any(MethodCallVisitor::class.java),
            any(String::class.java))
        ).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0], "test %d %f",
                    LogLevel.WARN, LogGroup("TEST_GROUP", true, false, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(TEST_CODE, PATH, PATH, code)
        code = StaticJavaParser.parse(out)

        val protoLogCalls = code.findAll(MethodCallExpr::class.java).filter {
            it.scope.orElse(null)?.toString() == implName
        }
        Truth.assertThat(protoLogCalls).hasSize(1)
        val methodCall = protoLogCalls[0] as MethodCallExpr
        assertEquals("w", methodCall.name.asString())
        assertEquals(6, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-1473209266730422156L", methodCall.arguments[1].toString())
        assertEquals(0b1001.toString(), methodCall.arguments[2].toString())
        assertEquals("null", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals(TRANSFORMED_CODE_TEXT_DISABLED, out)
    }

    @Test
    fun processClass_textDisabledMultiline() {
        var code = StaticJavaParser.parse(TEST_CODE_MULTILINE)

        Mockito.`when`(processor.process(
            any(CompilationUnit::class.java),
            any(ProtoLogCallVisitor::class.java),
            any(MethodCallVisitor::class.java),
            any(String::class.java))
        ).thenAnswer { invocation ->
            val visitor = invocation.arguments[1] as ProtoLogCallVisitor

            visitor.processCall(code.findAll(MethodCallExpr::class.java)[0],
                    "test %d %f abc %s\n test", LogLevel.WARN, LogGroup("TEST_GROUP",
                    true, false, "WM_TEST"))

            invocation.arguments[0] as CompilationUnit
        }

        val out = sourceJarWriter.processClass(TEST_CODE_MULTILINE, PATH, PATH, code)
        code = StaticJavaParser.parse(out)

        val protoLogCalls = code.findAll(MethodCallExpr::class.java).filter {
            it.scope.orElse(null)?.toString() == implName
        }
        Truth.assertThat(protoLogCalls).hasSize(1)
        val methodCall = protoLogCalls[0] as MethodCallExpr
        assertEquals("w", methodCall.name.asString())
        assertEquals(7, methodCall.arguments.size)
        assertEquals("TEST_GROUP", methodCall.arguments[0].toString())
        assertEquals("-4447034859795564700L", methodCall.arguments[1].toString())
        assertEquals(0b001001.toString(), methodCall.arguments[2].toString())
        assertEquals("null", methodCall.arguments[3].toString())
        assertEquals("protoLogParam0", methodCall.arguments[4].toString())
        assertEquals("protoLogParam1", methodCall.arguments[5].toString())
        assertEquals("protoLogParam2", methodCall.arguments[6].toString())
        assertEquals(TRANSFORMED_CODE_MULTILINE_TEXT_DISABLED, out)
    }
}
