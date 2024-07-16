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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.policy.ui.dialog.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesDialogViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    val repository = kosmos.fakeZenModeRepository
    val interactor = kosmos.zenModeInteractor

    val underTest = ModesDialogViewModel(context, interactor, kosmos.testDispatcher)

    @Test
    fun tiles_filtersOutDisabledModes() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addModes(
                listOf(
                    TestModeBuilder().setName("Disabled").setEnabled(false).build(),
                    TestModeBuilder.MANUAL_DND,
                    TestModeBuilder()
                        .setName("Enabled")
                        .setEnabled(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Disabled with manual")
                        .setEnabled(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                ))
            runCurrent()

            assertThat(tiles?.size).isEqualTo(2)
            with(tiles?.elementAt(0)!!) {
                assertThat(this.text).isEqualTo("Manual DND")
                assertThat(this.subtext).isEqualTo("On")
                assertThat(this.enabled).isEqualTo(true)
            }
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Enabled")
                assertThat(this.subtext).isEqualTo("Off")
                assertThat(this.enabled).isEqualTo(false)
            }
        }

    @Test
    fun tiles_filtersOutInactiveModesWithoutManualInvocation() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Active without manual")
                        .setActive(true)
                        .setManualInvocationAllowed(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Active with manual")
                        .setTriggerDescription("trigger description")
                        .setActive(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Inactive with manual")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Inactive without manual")
                        .setActive(false)
                        .setManualInvocationAllowed(false)
                        .build(),
                ))
            runCurrent()

            assertThat(tiles?.size).isEqualTo(3)
            with(tiles?.elementAt(0)!!) {
                assertThat(this.text).isEqualTo("Active without manual")
                assertThat(this.subtext).isEqualTo("On")
                assertThat(this.enabled).isEqualTo(true)
            }
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Active with manual")
                assertThat(this.subtext).isEqualTo("trigger description")
                assertThat(this.enabled).isEqualTo(true)
            }
            with(tiles?.elementAt(2)!!) {
                assertThat(this.text).isEqualTo("Inactive with manual")
                assertThat(this.subtext).isEqualTo("Off")
                assertThat(this.enabled).isEqualTo(false)
            }
        }
}
