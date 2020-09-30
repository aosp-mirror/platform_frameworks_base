/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.commandline

import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper

import com.android.systemui.SysuiTestCase

import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

private fun <T : Any> safeEq(value: T): T = eq(value) ?: value

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class CommandRegistryTest : SysuiTestCase() {
    lateinit var registry: CommandRegistry
    val inLineExecutor = object : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }
    }

    val writer: PrintWriter = PrintWriter(StringWriter())

    @Before
    fun setup() {
        registry = CommandRegistry(context, inLineExecutor)
    }

    @Test(expected = IllegalStateException::class)
    fun testRegisterCommand_throwsWhenAlreadyRegistered() {
        registry.registerCommand(COMMAND) { FakeCommand() }
        // Should throw when registering the same command twice
        registry.registerCommand(COMMAND) { FakeCommand() }
    }

    @Test
    fun testOnShellCommand() {
        var fakeCommand = mock(Command::class.java)
        registry.registerCommand(COMMAND) { fakeCommand }
        registry.onShellCommand(writer, arrayOf(COMMAND))
        verify(fakeCommand).execute(anyObject(), anyList())
    }

    @Test
    fun testArgsPassedToShellCommand() {
        var fakeCommand = mock(Command::class.java)
        registry.registerCommand(COMMAND) { fakeCommand }
        registry.onShellCommand(writer, arrayOf(COMMAND, "arg1", "arg2", "arg3"))
        verify(fakeCommand).execute(anyObject(), safeEq(listOf("arg1", "arg2", "arg3")))
    }

    class FakeCommand() : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
        }

        override fun help(pw: PrintWriter) {
        }
    }
}

private const val COMMAND = "test_command"
