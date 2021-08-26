/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

@SmallTest
class DisableFlagsLoggerTest : SysuiTestCase() {
    private val disable1Flags = listOf(
            DisableFlagsLogger.DisableFlag(0b100, 'A', 'a'),
            DisableFlagsLogger.DisableFlag(0b010, 'B', 'b'),
            DisableFlagsLogger.DisableFlag(0b001, 'C', 'c'),
    )
    private val disable2Flags = listOf(
            DisableFlagsLogger.DisableFlag(0b10, 'M', 'm'),
            DisableFlagsLogger.DisableFlag(0b01, 'N', 'n'),
    )

    private val disableFlagsLogger = DisableFlagsLogger(disable1Flags, disable2Flags)

    @Test
    fun getDisableFlagsString_oldAndNewSame_statesLoggedButDiffsNotLogged() {
        val state = DisableFlagsLogger.DisableState(
                0b111, // ABC
                0b01 // mN
        )

        val result = disableFlagsLogger.getDisableFlagsString(state, state)

        assertThat(result).contains("Old: ABC.mN")
        assertThat(result).contains("New: ABC.mN")
        assertThat(result).doesNotContain("(")
        assertThat(result).doesNotContain(")")
    }

    @Test
    fun getDisableFlagsString_oldAndNewDifferent_statesAndDiffLogged() {
        val result = disableFlagsLogger.getDisableFlagsString(
                DisableFlagsLogger.DisableState(
                        0b111, // ABC
                        0b01, // mN
                ),
                DisableFlagsLogger.DisableState(
                        0b001, // abC
                        0b10 // Mn
                )
        )

        assertThat(result).contains("Old: ABC.mN")
        assertThat(result).contains("New: abC.Mn")
        assertThat(result).contains("(ab.Mn)")
    }

    @Test
    fun getDisableFlagsString_onlyDisable2Different_diffLoggedCorrectly() {
        val result = disableFlagsLogger.getDisableFlagsString(
                DisableFlagsLogger.DisableState(
                        0b001, // abC
                        0b01, // mN
                ),
                DisableFlagsLogger.DisableState(
                        0b001, // abC
                        0b00 // mn
                )
        )

        assertThat(result).contains("(.n)")
    }

    @Test
    fun getDisableFlagsString_nullLocalModification_localModNotLogged() {
        val result = disableFlagsLogger.getDisableFlagsString(
                DisableFlagsLogger.DisableState(0, 0),
                DisableFlagsLogger.DisableState(1, 1),
                newAfterLocalModification = null
        )

        assertThat(result).doesNotContain("local modification")
    }

    @Test
    fun getDisableFlagsString_newAfterLocalModificationSameAsNew_localModNotLogged() {
        val newState =  DisableFlagsLogger.DisableState(
                0b001, // abC
                0b10 // mn
        )

        val result = disableFlagsLogger.getDisableFlagsString(
                DisableFlagsLogger.DisableState(0, 0), newState, newState
        )

        assertThat(result).doesNotContain("local modification")
    }

    @Test
    fun getDisableFlagsString_newAfterLocalModificationDifferent_localModAndDiffLogged() {
        val result = disableFlagsLogger.getDisableFlagsString(
                old = DisableFlagsLogger.DisableState(0, 0),
                new = DisableFlagsLogger.DisableState(
                        0b000, // abc
                        0b00 // mn
                ),
                newAfterLocalModification = DisableFlagsLogger.DisableState(
                        0b100, // Abc
                        0b10 // Mn
                )
        )

        assertThat(result).contains("local modification: Abc.Mn (A.M)")
    }

    @Test
    fun constructor_defaultDisableFlags_noException() {
        // Just creating the logger with the default params will trigger the exception if there
        // is one.
        DisableFlagsLogger()
    }

    @Test
    fun constructor_disable1_FlagIsSetSymbolNotUnique_exception() {
        assertThrows(IllegalArgumentException::class.java) {
            DisableFlagsLogger(
                    disable1FlagsList = listOf(
                            DisableFlagsLogger.DisableFlag(0b100, 'A', 'a'),
                            DisableFlagsLogger.DisableFlag(0b010, 'A', 'b'),
                    ),
                    listOf()
            )
        }
    }

    @Test
    fun constructor_disable1_FlagNotSetSymbolNotUnique_exception() {
        assertThrows(IllegalArgumentException::class.java) {
            DisableFlagsLogger(
                    disable1FlagsList = listOf(
                            DisableFlagsLogger.DisableFlag(0b100, 'A', 'a'),
                            DisableFlagsLogger.DisableFlag(0b010, 'B', 'a'),
                    ),
                    listOf()
            )
        }
    }

    @Test
    fun constructor_disable2_FlagIsSetSymbolNotUnique_exception() {
        assertThrows(IllegalArgumentException::class.java) {
            DisableFlagsLogger(
                    disable1FlagsList = listOf(),
                    disable2FlagsList = listOf(
                            DisableFlagsLogger.DisableFlag(0b100, 'A', 'a'),
                            DisableFlagsLogger.DisableFlag(0b010, 'A', 'b'),
                    ),
            )
        }
    }

    @Test
    fun constructor_disable2_FlagNotSetSymbolNotUnique_exception() {
        assertThrows(IllegalArgumentException::class.java) {
            DisableFlagsLogger(
                    disable1FlagsList = listOf(),
                    disable2FlagsList = listOf(
                            DisableFlagsLogger.DisableFlag(0b100, 'A', 'a'),
                            DisableFlagsLogger.DisableFlag(0b010, 'B', 'a'),
                    ),
            )
        }
    }
}
