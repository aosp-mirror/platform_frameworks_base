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

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.statusbar.policy.ui.dialog.mockModesDialogDelegate
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesDialogViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeZenModeRepository
    private val interactor = kosmos.zenModeInteractor
    private val mockDialogDelegate = kosmos.mockModesDialogDelegate

    private val underTest =
        ModesDialogViewModel(context, interactor, kosmos.testDispatcher, mockDialogDelegate)

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
                )
            )
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
                )
            )
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

    @Test
    fun onClick_togglesTileState() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            val modeId = "id"
            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId(modeId)
                        .setName("Test")
                        .setManualInvocationAllowed(true)
                        .build()
                )
            )
            runCurrent()

            assertThat(tiles?.size).isEqualTo(1)
            assertThat(tiles?.elementAt(0)?.enabled).isFalse()

            // Trigger onClick
            tiles?.first()?.onClick?.let { it() }
            runCurrent()

            assertThat(tiles?.first()?.enabled).isTrue()

            // Trigger onClick
            tiles?.first()?.onClick?.let { it() }
            runCurrent()

            assertThat(tiles?.first()?.enabled).isFalse()
        }

    @Test
    fun onLongClick_launchesIntent() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)
            val intentCaptor = argumentCaptor<Intent>()

            val modeId = "id"
            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId(modeId)
                        .setId("A")
                        .setActive(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setId(modeId)
                        .setId("B")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                )
            )
            runCurrent()

            assertThat(tiles?.size).isEqualTo(2)

            // Trigger onLongClick for A
            tiles?.first()?.onLongClick?.let { it() }
            runCurrent()

            // Check that it launched the correct intent
            verify(mockDialogDelegate).launchFromDialog(intentCaptor.capture())
            var intent = intentCaptor.lastValue
            assertThat(intent.action).isEqualTo(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            assertThat(intent.extras?.getString(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID))
                .isEqualTo("A")

            clearInvocations(mockDialogDelegate)

            // Trigger onLongClick for B
            tiles?.last()?.onLongClick?.let { it() }
            runCurrent()

            // Check that it launched the correct intent
            verify(mockDialogDelegate).launchFromDialog(intentCaptor.capture())
            intent = intentCaptor.lastValue
            assertThat(intent.action).isEqualTo(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            assertThat(intent.extras?.getString(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID))
                .isEqualTo("B")
        }
}
