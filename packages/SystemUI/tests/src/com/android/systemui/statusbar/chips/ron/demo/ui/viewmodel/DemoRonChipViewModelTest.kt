/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel

import android.content.packageManager
import android.graphics.drawable.BitmapDrawable
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_RON_CHIPS
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.commandline.commandRegistry
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
class DemoRonChipViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val commandRegistry = kosmos.commandRegistry
    private val pw = PrintWriter(StringWriter())

    private val underTest = kosmos.demoRonChipViewModel

    @Before
    fun setUp() {
        underTest.start()
        whenever(kosmos.packageManager.getApplicationIcon(any<String>()))
            .thenReturn(BitmapDrawable())
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_RON_CHIPS)
    fun chip_flagOff_hidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            addDemoRonChip()

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_RON_CHIPS)
    fun chip_noPackage_hidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            commandRegistry.onShellCommand(pw, arrayOf("demo-ron"))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_RON_CHIPS)
    fun chip_hasPackage_shown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            commandRegistry.onShellCommand(pw, arrayOf("demo-ron", "-p", "com.android.systemui"))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_RON_CHIPS)
    fun chip_hasText_shownWithText() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            commandRegistry.onShellCommand(
                pw,
                arrayOf("demo-ron", "-p", "com.android.systemui", "-t", "test")
            )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Text::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_RON_CHIPS)
    fun chip_supportsColor() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            commandRegistry.onShellCommand(
                pw,
                arrayOf("demo-ron", "-p", "com.android.systemui", "-c", "#434343")
            )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).colors)
                .isInstanceOf(ColorsModel.Custom::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_RON_CHIPS)
    fun chip_hasHideArg_hidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            // First, show a chip
            addDemoRonChip()
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)

            // Then, hide the chip
            commandRegistry.onShellCommand(pw, arrayOf("demo-ron", "--hide"))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    private fun addDemoRonChip() {
        Companion.addDemoRonChip(commandRegistry, pw)
    }

    companion object {
        fun addDemoRonChip(commandRegistry: CommandRegistry, pw: PrintWriter) {
            commandRegistry.onShellCommand(pw, arrayOf("demo-ron", "-p", "com.android.systemui"))
        }
    }
}
