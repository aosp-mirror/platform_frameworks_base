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

package com.android.systemui.statusbar.commandline

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class ParseableCommandTest : SysuiTestCase() {
    @Mock private lateinit var pw: PrintWriter

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    /**
     * A little change-detector-y, but this is just a general assertion that building up a command
     * parser via its wrapper works as expected.
     */
    @Test
    fun testFactoryMethods() {
        val mySubCommand =
            object : ParseableCommand("subCommand") {
                val flag by flag("flag")
                override fun execute(pw: PrintWriter) {}
            }

        val mySubCommand2 =
            object : ParseableCommand("subCommand2") {
                val flag by flag("flag")
                override fun execute(pw: PrintWriter) {}
            }

        // Verify that the underlying parser contains the correct types
        val myCommand =
            object : ParseableCommand("testName") {
                val flag by flag("flag", shortName = "f")
                val requiredParam by
                    param(longName = "required-param", shortName = "r", valueParser = Type.String)
                        .required()
                val optionalParam by
                    param(longName = "optional-param", shortName = "o", valueParser = Type.Boolean)
                val optionalSubCommand by subCommand(mySubCommand)
                val requiredSubCommand by subCommand(mySubCommand2).required()

                override fun execute(pw: PrintWriter) {}
            }

        val flags = myCommand.parser.flags
        val params = myCommand.parser.params
        val subCommands = myCommand.parser.subCommands

        assertThat(flags).hasSize(2)
        assertThat(flags[0]).isInstanceOf(Flag::class.java)
        assertThat(flags[1]).isInstanceOf(Flag::class.java)

        assertThat(params).hasSize(2)
        val req = params.filter { it is SingleArgParam<*> }
        val opt = params.filter { it is SingleArgParamOptional<*> }
        assertThat(req).hasSize(1)
        assertThat(opt).hasSize(1)

        val reqSub = subCommands.filter { it is RequiredSubCommand<*> }
        val optSub = subCommands.filter { it is OptionalSubCommand<*> }
        assertThat(reqSub).hasSize(1)
        assertThat(optSub).hasSize(1)
    }

    @Test
    fun factoryMethods_enforceShortNameRules() {
        // Short names MUST be one character long
        assertThrows(IllegalArgumentException::class.java) {
            val myCommand =
                object : ParseableCommand("test-command") {
                    val flag by flag("longName", "invalidShortName")

                    override fun execute(pw: PrintWriter) {}
                }
        }

        assertThrows(IllegalArgumentException::class.java) {
            val myCommand =
                object : ParseableCommand("test-command") {
                    val param by param("longName", "invalidShortName", valueParser = Type.String)

                    override fun execute(pw: PrintWriter) {}
                }
        }
    }

    @Test
    fun factoryMethods_enforceLongNames_notPrefixed() {
        // Long names must not start with "-", since they will be added
        assertThrows(IllegalArgumentException::class.java) {
            val myCommand =
                object : ParseableCommand("test-command") {
                    val flag by flag("--invalid")

                    override fun execute(pw: PrintWriter) {}
                }
        }

        assertThrows(IllegalArgumentException::class.java) {
            val myCommand =
                object : ParseableCommand("test-command") {
                    val param by param("-invalid", valueParser = Type.String)

                    override fun execute(pw: PrintWriter) {}
                }
        }
    }

    @Test
    fun executeDoesNotPropagateExceptions() {
        val cmd =
            object : ParseableCommand("test-command") {
                val flag by flag("flag")
                override fun execute(pw: PrintWriter) {}
            }

        val throwingCommand = listOf("unknown-token")

        // Given a command that would cause an ArgParseError
        assertThrows(ArgParseError::class.java) { cmd.parser.parse(throwingCommand) }

        // The parser consumes that error
        cmd.execute(pw, throwingCommand)
    }

    @Test
    fun executeFailingCommand_callsOnParseFailed() {
        val cmd =
            object : ParseableCommand("test-command") {
                val flag by flag("flag")

                var onParseFailedCalled = false

                override fun execute(pw: PrintWriter) {}
                override fun onParseFailed(error: ArgParseError) {
                    onParseFailedCalled = true
                }
            }

        val throwingCommand = listOf("unknown-token")
        cmd.execute(pw, throwingCommand)

        assertTrue(cmd.onParseFailedCalled)
    }

    @Test
    fun baseCommand() {
        val myCommand = MyCommand()
        myCommand.execute(pw, baseCommand)

        assertThat(myCommand.flag1).isFalse()
        assertThat(myCommand.singleParam).isNull()
    }

    @Test
    fun commandWithFlags() {
        val command = MyCommand()
        command.execute(pw, cmdWithFlags)

        assertThat(command.flag1).isTrue()
        assertThat(command.flag2).isTrue()
    }

    @Test
    fun commandWithArgs() {
        val cmd = MyCommand()
        cmd.execute(pw, cmdWithSingleArgParam)

        assertThat(cmd.singleParam).isEqualTo("single_param")
    }

    @Test
    fun commandWithRequiredParam_provided() {
        val cmd =
            object : ParseableCommand(name) {
                val singleRequiredParam: String by
                    param(
                            longName = "param1",
                            shortName = "p",
                            valueParser = Type.String,
                        )
                        .required()

                override fun execute(pw: PrintWriter) {}
            }

        val cli = listOf("-p", "value")
        cmd.execute(pw, cli)

        assertThat(cmd.singleRequiredParam).isEqualTo("value")
    }

    @Test
    fun commandWithRequiredParam_not_provided_throws() {
        val cmd =
            object : ParseableCommand(name) {
                val singleRequiredParam by
                    param(shortName = "p", longName = "param1", valueParser = Type.String)
                        .required()

                override fun execute(pw: PrintWriter) {}

                override fun execute(pw: PrintWriter, args: List<String>) {
                    parser.parse(args)
                    execute(pw)
                }
            }

        val cli = listOf("")
        assertThrows(ArgParseError::class.java) { cmd.execute(pw, cli) }
    }

    @Test
    fun commandWithSubCommand() {
        val subName = "sub-command"
        val subCmd =
            object : ParseableCommand(subName) {
                val singleOptionalParam: String? by param("param", valueParser = Type.String)

                override fun execute(pw: PrintWriter) {}
            }

        val cmd =
            object : ParseableCommand(name) {
                val subCmd by subCommand(subCmd)
                override fun execute(pw: PrintWriter) {}
            }

        cmd.execute(pw, listOf("sub-command", "--param", "test"))
        assertThat(cmd.subCmd?.singleOptionalParam).isEqualTo("test")
    }

    @Test
    fun complexCommandWithSubCommands_reusedNames() {
        val commandLine = "-f --param1 arg1 sub-command1 -f -p arg2 --param2 arg3".split(" ")

        val subName = "sub-command1"
        val subCmd =
            object : ParseableCommand(subName) {
                val flag1 by flag("flag", shortName = "f")
                val param1: String? by param("param1", shortName = "p", valueParser = Type.String)

                override fun execute(pw: PrintWriter) {}
            }

        val myCommand =
            object : ParseableCommand(name) {
                val flag1 by flag(longName = "flag", shortName = "f")
                val param1 by param("param1", shortName = "p", valueParser = Type.String).required()
                val param2: String? by param(longName = "param2", valueParser = Type.String)
                val subCommand by subCommand(subCmd)

                override fun execute(pw: PrintWriter) {}
            }

        myCommand.execute(pw, commandLine)

        assertThat(myCommand.flag1).isTrue()
        assertThat(myCommand.param1).isEqualTo("arg1")
        assertThat(myCommand.param2).isEqualTo("arg3")
        assertThat(myCommand.subCommand).isNotNull()
        assertThat(myCommand.subCommand?.flag1).isTrue()
        assertThat(myCommand.subCommand?.param1).isEqualTo("arg2")
    }

    class MyCommand(
        private val onExecute: ((MyCommand) -> Unit)? = null,
    ) : ParseableCommand(name) {

        val flag1 by flag(shortName = "f", longName = "flag1", description = "flag 1 for test")
        val flag2 by flag(shortName = "g", longName = "flag2", description = "flag 2 for test")
        val singleParam: String? by
            param(
                shortName = "a",
                longName = "arg1",
                valueParser = Type.String,
            )

        override fun execute(pw: PrintWriter) {
            onExecute?.invoke(this)
        }
    }

    companion object {
        const val name = "my_command"
        val baseCommand = listOf("")
        val cmdWithFlags = listOf("-f", "--flag2")
        val cmdWithSingleArgParam = listOf("--arg1", "single_param")
    }
}
