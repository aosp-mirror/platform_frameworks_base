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
package com.android.hoststubgen.visitors

import com.android.hoststubgen.HostStubGenErrors
import com.android.hoststubgen.asm.ClassNodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

class HelperTest {
    @Test
    fun testCheckSubstitutionMethodCompatibility() {
        val errors = object : HostStubGenErrors() {
            override fun onErrorFound(message: String) {
                // Don't throw
            }
        }

        val cn = ClassNode().apply {
            name = "ClassName"
            methods = ArrayList()
        }

        val descriptor = "()V"

        val staticPublic = MethodNode().apply {
            name = "staticPublic"
            access = Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC
            desc = descriptor
            cn.methods.add(this)
        }

        val staticPrivate = MethodNode().apply {
            name = "staticPrivate"
            access = Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE
            desc = descriptor
            cn.methods.add(this)
        }

        val nonStaticPublic = MethodNode().apply {
            name = "nonStaticPublic"
            access = Opcodes.ACC_PUBLIC
            desc = descriptor
            cn.methods.add(this)
        }

        val nonStaticPProtected = MethodNode().apply {
            name = "nonStaticPProtected"
            access = 0
            desc = descriptor
            cn.methods.add(this)
        }

        val classes = ClassNodes().apply {
            addClass(cn)
        }

        fun check(from: MethodNode?, to: MethodNode?, expected: Int) {
            assertThat(checkSubstitutionMethodCompatibility(
                classes,
                cn.name,
                (from?.name ?: "**nonexistentmethodname**"),
                (to?.name ?: "**nonexistentmethodname**"),
                descriptor,
                errors,
            )).isEqualTo(expected)
        }

        check(staticPublic, staticPublic, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        check(staticPrivate, staticPrivate, Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC)
        check(nonStaticPublic, nonStaticPublic, Opcodes.ACC_PUBLIC)
        check(nonStaticPProtected, nonStaticPProtected, 0)

        check(staticPublic, null, NOT_COMPATIBLE)
        check(null, staticPublic, NOT_COMPATIBLE)

        check(staticPublic, nonStaticPublic, NOT_COMPATIBLE)
        check(nonStaticPublic, staticPublic, NOT_COMPATIBLE)

        check(staticPublic, staticPrivate, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        check(staticPrivate, staticPublic, Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC)

        check(nonStaticPublic, nonStaticPProtected, Opcodes.ACC_PUBLIC)
        check(nonStaticPProtected, nonStaticPublic, 0)
    }
}