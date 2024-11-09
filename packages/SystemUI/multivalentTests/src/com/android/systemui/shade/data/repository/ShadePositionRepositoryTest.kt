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

package com.android.systemui.shade.data.repository

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.commandline.commandRegistry
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadePositionRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val commandRegistry = kosmos.commandRegistry
    private val pw = PrintWriter(StringWriter())

    private val underTest = ShadePositionRepositoryImpl(commandRegistry)

    @Before
    fun setUp() {
        underTest.start()
    }

    @Test
    fun commandDisplayOverride_updatesDisplayId() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)
            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            val newDisplayId = 2
            commandRegistry.onShellCommand(
                pw,
                arrayOf("shade_display_override", newDisplayId.toString()),
            )

            assertThat(displayId).isEqualTo(newDisplayId)
        }

    @Test
    fun commandShadeDisplayOverride_resetsDisplayId() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)
            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            val newDisplayId = 2
            commandRegistry.onShellCommand(
                pw,
                arrayOf("shade_display_override", newDisplayId.toString()),
            )
            assertThat(displayId).isEqualTo(newDisplayId)

            commandRegistry.onShellCommand(pw, arrayOf("shade_display_override", "reset"))
            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)
        }
}
