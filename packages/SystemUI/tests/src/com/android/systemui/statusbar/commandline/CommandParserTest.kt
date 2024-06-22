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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommandParserTest : SysuiTestCase() {
    private val parser = CommandParser()

    @Test
    fun registerToken_cannotReuseNames() {
        parser.flag("-f")
        assertThrows(IllegalArgumentException::class.java) { parser.flag("-f") }
    }

    @Test
    fun unknownToken_throws() {
        assertThrows(ArgParseError::class.java) { parser.parse(listOf("unknown-token")) }
    }

    @Test
    fun parseSingleFlag_present() {
        val flag by parser.flag("-f")
        parser.parse(listOf("-f"))
        assertTrue(flag)
    }

    @Test
    fun parseSingleFlag_notPresent() {
        val flag by parser.flag("-f")
        parser.parse(listOf())
        assertFalse(flag)
    }

    @Test
    fun parseSingleOptionalParam_present() {
        val param by parser.param("-p", valueParser = Type.Int)
        parser.parse(listOf("-p", "123"))
        assertThat(param).isEqualTo(123)
    }

    @Test
    fun parseSingleOptionalParam_notPresent() {
        val param by parser.param("-p", valueParser = Type.Int)
        parser.parse(listOf())
        assertThat(param).isNull()
    }

    @Test
    fun parseSingleOptionalParam_missingArg_throws() {
        val param by parser.param("-p", valueParser = Type.Int)
        assertThrows(ArgParseError::class.java) { parser.parse(listOf("-p")) }
    }

    @Test
    fun parseSingleRequiredParam_present() {
        val param by parser.require(parser.param("-p", valueParser = Type.Int))
        parser.parse(listOf("-p", "123"))
        assertThat(param).isEqualTo(123)
    }

    @Test
    fun parseSingleRequiredParam_notPresent_failsValidation() {
        val param by parser.require(parser.param("-p", valueParser = Type.Int))
        assertFalse(parser.parse(listOf()))
    }

    @Test
    fun parseSingleRequiredParam_missingArg_throws() {
        val param by parser.require(parser.param("-p", valueParser = Type.Int))
        assertThrows(ArgParseError::class.java) { parser.parse(listOf("-p")) }
    }

    @Test
    fun parseAsSubCommand_singleFlag_present() {
        val flag by parser.flag("-f")
        val args = listOf("-f").listIterator()
        parser.parseAsSubCommand(args)

        assertTrue(flag)
    }

    @Test
    fun parseAsSubCommand_singleFlag_notPresent() {
        val flag by parser.flag("-f")
        val args = listOf("--other-flag").listIterator()
        parser.parseAsSubCommand(args)

        assertFalse(flag)
    }

    @Test
    fun parseAsSubCommand_singleOptionalParam_present() {
        val param by parser.param("-p", valueParser = Type.Int)
        parser.parseAsSubCommand(listOf("-p", "123", "--other-arg", "321").listIterator())
        assertThat(param).isEqualTo(123)
    }

    @Test
    fun parseAsSubCommand_singleOptionalParam_notPresent() {
        val param by parser.param("-p", valueParser = Type.Int)
        parser.parseAsSubCommand(listOf("--other-arg", "321").listIterator())
        assertThat(param).isNull()
    }

    @Test
    fun parseAsSubCommand_singleRequiredParam_present() {
        val param by parser.require(parser.param("-p", valueParser = Type.Int))
        parser.parseAsSubCommand(listOf("-p", "123", "--other-arg", "321").listIterator())
        assertThat(param).isEqualTo(123)
    }

    @Test
    fun parseAsSubCommand_singleRequiredParam_notPresent() {
        parser.require(parser.param("-p", valueParser = Type.Int))
        assertFalse(parser.parseAsSubCommand(listOf("--other-arg", "321").listIterator()))
    }

    @Test
    fun parseCommandWithSubCommand_required_provided() {
        val topLevelFlag by parser.flag("flag", shortName = "-f")

        val cmd =
            object : ParseableCommand("test") {
                val flag by flag("flag1")
                override fun execute(pw: PrintWriter) {}
            }

        parser.require(parser.subCommand(cmd))
        parser.parse(listOf("-f", "test", "--flag1"))

        assertTrue(topLevelFlag)
        assertThat(cmd).isNotNull()
        assertTrue(cmd.flag)
    }

    @Test
    fun parseCommandWithSubCommand_required_notProvided() {
        val topLevelFlag by parser.flag("-f")

        val cmd =
            object : ParseableCommand("test") {
                val flag by parser.flag("flag1")
                override fun execute(pw: PrintWriter) {}
            }

        parser.require(parser.subCommand(cmd))

        assertFalse(parser.parse(listOf("-f")))
    }

    @Test
    fun flag_requiredParam_optionalParam_allProvided_failsValidation() {
        val flag by parser.flag("-f")
        val optionalParam by parser.param("-p", valueParser = Type.Int)
        val requiredParam by parser.require(parser.param("-p2", valueParser = Type.Boolean))

        parser.parse(
            listOf(
                "-f",
                "-p",
                "123",
                "-p2",
                "false",
            )
        )

        assertTrue(flag)
        assertThat(optionalParam).isEqualTo(123)
        assertFalse(requiredParam)
    }

    @Test
    fun flag_requiredParam_optionalParam_optionalExcluded() {
        val flag by parser.flag("-f")
        val optionalParam by parser.param("-p", valueParser = Type.Int)
        val requiredParam by parser.require(parser.param("-p2", valueParser = Type.Boolean))

        parser.parse(
            listOf(
                "-p2",
                "true",
            )
        )

        assertFalse(flag)
        assertThat(optionalParam).isNull()
        assertTrue(requiredParam)
    }
}
