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
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.shade.ShadePrimaryDisplayCommand
import com.android.systemui.shade.display.FakeShadeDisplayPolicy
import com.android.systemui.statusbar.commandline.commandRegistry
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadePrimaryDisplayCommandTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val globalSettings = kosmos.fakeGlobalSettings
    private val commandRegistry = kosmos.commandRegistry
    private val displayRepository = kosmos.displayRepository
    private val defaultPolicy = kosmos.defaultShadeDisplayPolicy
    private val shadeDisplaysRepository = kosmos.shadeDisplaysRepository
    private val policies = kosmos.shadeDisplayPolicies
    private val pw = PrintWriter(StringWriter())

    private val underTest =
        ShadePrimaryDisplayCommand(
            globalSettings,
            commandRegistry,
            displayRepository,
            shadeDisplaysRepository,
            policies,
            defaultPolicy,
        )

    @Before
    fun setUp() {
        underTest.start()
    }

    @Test
    fun commandShadeDisplayOverride_resetsDisplayId() =
        testScope.runTest {
            val displayId by collectLastValue(shadeDisplaysRepository.displayId)
            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            val newDisplayId = 2
            displayRepository.addDisplay(displayId = newDisplayId)
            commandRegistry.onShellCommand(
                pw,
                arrayOf("shade_display_override", "any_external_display"),
            )
            assertThat(displayId).isEqualTo(newDisplayId)

            commandRegistry.onShellCommand(pw, arrayOf("shade_display_override", "reset"))
            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)
        }

    @Test
    fun commandShadeDisplayOverride_anyExternalDisplay_notOnDefaultAnymore() =
        testScope.runTest {
            val displayId by collectLastValue(shadeDisplaysRepository.displayId)
            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)
            val newDisplayId = 2
            displayRepository.addDisplay(displayId = newDisplayId)

            commandRegistry.onShellCommand(
                pw,
                arrayOf("shade_display_override", "any_external_display"),
            )

            assertThat(displayId).isEqualTo(newDisplayId)
        }

    @Test
    fun policies_listsAllPolicies() =
        testScope.runTest {
            val stringWriter = StringWriter()
            commandRegistry.onShellCommand(
                PrintWriter(stringWriter),
                arrayOf("shade_display_override", "policies"),
            )
            val result = stringWriter.toString()

            assertThat(result).containsAllIn(policies.map { it.name })
        }

    @Test
    fun policies_setsNewPolicy() =
        testScope.runTest {
            val newPolicy = FakeShadeDisplayPolicy.name

            commandRegistry.onShellCommand(pw, arrayOf("shade_display_override", newPolicy))

            assertThat(shadeDisplaysRepository.currentPolicy.name).isEqualTo(newPolicy)
        }
}

private fun StringSubject.containsAllIn(strings: List<String>) {
    strings.forEach { contains(it) }
}
