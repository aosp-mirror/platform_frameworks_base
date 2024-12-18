/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.utils

import com.android.hoststubgen.ParseException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test

class ClassFilterTest {
    @Test
    fun testDefaultTrue() {
        val f = ClassFilter.newNullFilter(true)
        assertThat(f.matches("a/b/c")).isEqualTo(true)
    }

    @Test
    fun testDefaultFalse() {
        val f = ClassFilter.newNullFilter(false)
        assertThat(f.matches("a/b/c")).isEqualTo(false)
    }

    @Test
    fun testComplex1() {
        val f = ClassFilter.buildFromString("""
            # ** this is a comment **
            a.b.c       # allow
            !a.b.d      # disallow
            *           # allow all
            """.trimIndent(), false, "X")
        assertThat(f.getCacheSizeForTest()).isEqualTo(0)

        assertThat(f.matches("a/b/c")).isEqualTo(true)
        assertThat(f.getCacheSizeForTest()).isEqualTo(1)

        assertThat(f.matches("a/b/d")).isEqualTo(false)
        assertThat(f.matches("x")).isEqualTo(true)

        assertThat(f.getCacheSizeForTest()).isEqualTo(3)

        // Make sure the cache is working
        assertThat(f.matches("x")).isEqualTo(true)
    }

    @Test
    fun testComplex2() {
        val f = ClassFilter.buildFromString("""
            a.b.c       # allow
            !a.*        # disallow everything else in package "a".
            !d.e.f      # disallow d.e.f.

            # everything else is allowed by default
            """.trimIndent(), true, "X")
        assertThat(f.matches("a/b/c")).isEqualTo(true)
        assertThat(f.matches("a/x")).isEqualTo(false)
        assertThat(f.matches("d/e/f")).isEqualTo(false)
        assertThat(f.matches("d/e/f/g")).isEqualTo(true)
        assertThat(f.matches("x")).isEqualTo(true)

        assertThat(f.matches("ab/x")).isEqualTo(true)
    }

    @Test
    fun testNestedClass() {
        val f = ClassFilter.buildFromString("a.b.c\nm.n.o\$p\n", false, "X")
        assertThat(f.matches("a/b/c")).isEqualTo(true)
        assertThat(f.matches("a/b/c\$d")).isEqualTo(true)
        assertThat(f.matches("a/b/c\$d\$e")).isEqualTo(true)
        assertThat(f.matches("m/n/o")).isEqualTo(false)
        assertThat(f.matches("m/n/o\$p")).isEqualTo(true)
        assertThat(f.matches("m/n/o\$p\$r")).isEqualTo(true)
        assertThat(f.matches("m/n/o\$p\$r\$")).isEqualTo(true)
    }

    @Test
    fun testBadFilter1() {
        try {
            ClassFilter.buildFromString("""
                a*
                """.trimIndent(), true, "FILENAME")
            fail("ParseException didn't happen")
        } catch (e: ParseException) {
            assertThat(e.message).contains("Wildcard")
            assertThat(e.message).contains("FILENAME")
            assertThat(e.message).contains("line 1")
        }
    }

    @Test
    fun testSuffix() {
        val f = ClassFilter.buildFromString("""
            *.Abc       # allow
            !*          # Disallow by default
            """.trimIndent(), true, "X")
        assertThat(f.matches("a/b/c")).isEqualTo(false)
        assertThat(f.matches("a/Abc")).isEqualTo(true)
        assertThat(f.matches("a/b/c/Abc")).isEqualTo(true)
        assertThat(f.matches("a/b/c/Abc\$Nested")).isEqualTo(true)

        assertThat(f.matches("a/XyzAbc")).isEqualTo(false)
    }
}