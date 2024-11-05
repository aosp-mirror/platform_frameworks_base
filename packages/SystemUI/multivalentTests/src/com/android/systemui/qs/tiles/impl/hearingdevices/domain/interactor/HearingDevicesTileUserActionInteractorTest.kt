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
package com.android.systemui.qs.tiles.impl.hearingdevices.domain.interactor

import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.hearingaid.HearingDevicesDialogManager
import com.android.systemui.accessibility.hearingaid.HearingDevicesUiEventLogger.Companion.LAUNCH_SOURCE_QS_TILE
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.hearingdevices.domain.model.HearingDevicesTileModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class HearingDevicesTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val inputHandler = FakeQSTileIntentUserInputHandler()

    private lateinit var underTest: HearingDevicesTileUserActionInteractor

    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var dialogManager: HearingDevicesDialogManager

    @Before
    fun setUp() {
        underTest =
            HearingDevicesTileUserActionInteractor(
                testScope.coroutineContext,
                inputHandler,
                dialogManager,
            )
    }

    @Test
    fun handleClick_launchDialog() =
        testScope.runTest {
            val input =
                HearingDevicesTileModel(
                    isAnyActiveHearingDevice = true,
                    isAnyPairedHearingDevice = true,
                )

            underTest.handleInput(QSTileInputTestKtx.click(input))

            verify(dialogManager).showDialog(anyOrNull(), eq(LAUNCH_SOURCE_QS_TILE))
        }

    @Test
    fun handleLongClick_launchSettings() =
        testScope.runTest {
            val input =
                HearingDevicesTileModel(
                    isAnyActiveHearingDevice = true,
                    isAnyPairedHearingDevice = true,
                )

            underTest.handleInput(QSTileInputTestKtx.longClick(input))

            QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
                assertThat(it.intent.action).isEqualTo(Settings.ACTION_HEARING_DEVICES_SETTINGS)
            }
        }
}
