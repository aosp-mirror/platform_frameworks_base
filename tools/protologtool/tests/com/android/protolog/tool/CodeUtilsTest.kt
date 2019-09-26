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

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeUtilsTest {
    @Test
    fun hash() {
        assertEquals(-1259556708, CodeUtils.hash("Test.java:50", "test",
                LogLevel.DEBUG, LogGroup("test", true, true, "TAG")))
    }

    @Test
    fun hash_changeLocation() {
        assertEquals(15793504, CodeUtils.hash("Test.java:10", "test2",
                LogLevel.DEBUG, LogGroup("test", true, true, "TAG")))
    }

    @Test
    fun hash_changeLevel() {
        assertEquals(-731772463, CodeUtils.hash("Test.java:50", "test",
                LogLevel.ERROR, LogGroup("test", true, true, "TAG")))
    }

    @Test
    fun hash_changeMessage() {
        assertEquals(-2026343204, CodeUtils.hash("Test.java:50", "test2",
                LogLevel.DEBUG, LogGroup("test", true, true, "TAG")))
    }

    @Test
    fun hash_changeGroup() {
        assertEquals(1607870166, CodeUtils.hash("Test.java:50", "test2",
                LogLevel.DEBUG, LogGroup("test2", true, true, "TAG")))
    }

    @Test
    fun isWildcardStaticImported_true() {
        val code = """package org.example.test;
            import static org.example.Test.*;
        """
        assertTrue(CodeUtils.isWildcardStaticImported(
                StaticJavaParser.parse(code), "org.example.Test"))
    }

    @Test
    fun isWildcardStaticImported_notStatic() {
        val code = """package org.example.test;
            import org.example.Test.*;
        """
        assertFalse(CodeUtils.isWildcardStaticImported(
                StaticJavaParser.parse(code), "org.example.Test"))
    }

    @Test
    fun isWildcardStaticImported_differentClass() {
        val code = """package org.example.test;
            import static org.example.Test2.*;
        """
        assertFalse(CodeUtils.isWildcardStaticImported(
                StaticJavaParser.parse(code), "org.example.Test"))
    }

    @Test
    fun isWildcardStaticImported_notWildcard() {
        val code = """package org.example.test;
            import org.example.Test.test;
        """
        assertFalse(CodeUtils.isWildcardStaticImported(
                StaticJavaParser.parse(code), "org.example.Test"))
    }

    @Test
    fun isClassImportedOrSamePackage_imported() {
        val code = """package org.example.test;
            import org.example.Test;
        """
        assertTrue(CodeUtils.isClassImportedOrSamePackage(
                StaticJavaParser.parse(code), "org.example.Test"))
    }

    @Test
    fun isClassImportedOrSamePackage_samePackage() {
        val code = """package org.example.test;
        """
        assertTrue(CodeUtils.isClassImportedOrSamePackage(
                StaticJavaParser.parse(code), "org.example.test.Test"))
    }

    @Test
    fun isClassImportedOrSamePackage_false() {
        val code = """package org.example.test;
            import org.example.Test;
        """
        assertFalse(CodeUtils.isClassImportedOrSamePackage(
                StaticJavaParser.parse(code), "org.example.Test2"))
    }

    @Test
    fun staticallyImportedMethods_ab() {
        val code = """
            import static org.example.Test.a;
            import static org.example.Test.b;
        """
        val imported = CodeUtils.staticallyImportedMethods(StaticJavaParser.parse(code),
                "org.example.Test")
        assertTrue(imported.containsAll(listOf("a", "b")))
        assertEquals(2, imported.size)
    }

    @Test
    fun staticallyImportedMethods_differentClass() {
        val code = """
            import static org.example.Test.a;
            import static org.example.Test2.b;
        """
        val imported = CodeUtils.staticallyImportedMethods(StaticJavaParser.parse(code),
                "org.example.Test")
        assertTrue(imported.containsAll(listOf("a")))
        assertEquals(1, imported.size)
    }

    @Test
    fun staticallyImportedMethods_notStatic() {
        val code = """
            import static org.example.Test.a;
            import org.example.Test.b;
        """
        val imported = CodeUtils.staticallyImportedMethods(StaticJavaParser.parse(code),
                "org.example.Test")
        assertTrue(imported.containsAll(listOf("a")))
        assertEquals(1, imported.size)
    }

    @Test
    fun concatMultilineString_single() {
        val str = StringLiteralExpr("test")
        val out = CodeUtils.concatMultilineString(str)
        assertEquals("test", out)
    }

    @Test
    fun concatMultilineString_double() {
        val str = """
            "test" + "abc"
        """
        val code = StaticJavaParser.parseExpression<BinaryExpr>(str)
        val out = CodeUtils.concatMultilineString(code)
        assertEquals("testabc", out)
    }

    @Test
    fun concatMultilineString_multiple() {
        val str = """
            "test" + "abc" + "1234" + "test"
        """
        val code = StaticJavaParser.parseExpression<BinaryExpr>(str)
        val out = CodeUtils.concatMultilineString(code)
        assertEquals("testabc1234test", out)
    }
}
