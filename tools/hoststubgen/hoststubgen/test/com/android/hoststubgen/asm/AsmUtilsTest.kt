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
package com.android.hoststubgen.asm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PROTECTED
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC

class AsmUtilsTest {
    private fun checkGetDirectOuterClassName(input: String, expected: String?) {
        assertThat(getDirectOuterClassName(input)).isEqualTo(expected)
    }

    @Test
    fun testGetDirectOuterClassName() {
        checkGetDirectOuterClassName("a", null)
        checkGetDirectOuterClassName("a\$x", "a")
        checkGetDirectOuterClassName("a.b.c\$x", "a.b.c")
        checkGetDirectOuterClassName("a.b.c\$x\$y", "a.b.c\$x")
    }

    @Test
    fun testVisibility() {
        fun test(access: Int, expected: Visibility) {
            assertThat(Visibility.fromAccess(access)).isEqualTo(expected)
        }

        test(ACC_PUBLIC or ACC_STATIC, Visibility.PUBLIC)
        test(ACC_PRIVATE or ACC_STATIC, Visibility.PRIVATE)
        test(ACC_PROTECTED or ACC_STATIC, Visibility.PROTECTED)
        test(ACC_STATIC, Visibility.PACKAGE_PRIVATE)
    }
}