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

package com.android.systemui.qs.tiles.impl.sensorprivacy.domain.interactor

import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.Sensors.CAMERA
import android.hardware.SensorPrivacyManager.Sensors.MICROPHONE
import android.provider.Settings
import android.safetycenter.SafetyCenterManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.activityStarter
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.SensorPrivacyToggleTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.sensorprivacy.domain.model.SensorPrivacyToggleTileModel
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class SensorPrivacyToggleTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val keyguardInteractor = kosmos.keyguardInteractor
    // The keyguard repository below is the same one kosmos used to create the interactor above
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val mockActivityStarter = kosmos.activityStarter
    private val mockSensorPrivacyController = mock<IndividualSensorPrivacyController>()
    private val fakeSafetyCenterManager = mock<SafetyCenterManager>()

    private val underTest =
        SensorPrivacyToggleTileUserActionInteractor(
            inputHandler,
            keyguardInteractor,
            mockActivityStarter,
            mockSensorPrivacyController,
            fakeSafetyCenterManager,
            CAMERA
        )

    @Test
    fun handleClickWhenNotBlocked() = runTest {
        val originalIsBlocked = false

        underTest.handleInput(
            QSTileInputTestKtx.click(SensorPrivacyToggleTileModel(originalIsBlocked))
        )

        verify(mockSensorPrivacyController)
            .setSensorBlocked(
                eq(SensorPrivacyManager.Sources.QS_TILE),
                eq(CAMERA),
                eq(!originalIsBlocked)
            )
    }

    @Test
    fun handleClickWhenBlocked() = runTest {
        val originalIsBlocked = true

        underTest.handleInput(
            QSTileInputTestKtx.click(SensorPrivacyToggleTileModel(originalIsBlocked))
        )

        verify(mockSensorPrivacyController)
            .setSensorBlocked(
                eq(SensorPrivacyManager.Sources.QS_TILE),
                eq(CAMERA),
                eq(!originalIsBlocked)
            )
    }

    @Test
    fun handleClick_whenKeyguardIsDismissableAndShowing_whenControllerRequiresAuth() = runTest {
        whenever(mockSensorPrivacyController.requiresAuthentication()).thenReturn(true)
        keyguardRepository.setKeyguardDismissible(true)
        keyguardRepository.setKeyguardShowing(true)
        val originalIsBlocked = true

        underTest.handleInput(
            QSTileInputTestKtx.click(SensorPrivacyToggleTileModel(originalIsBlocked))
        )

        verify(mockSensorPrivacyController, never())
            .setSensorBlocked(
                eq(SensorPrivacyManager.Sources.QS_TILE),
                eq(CAMERA),
                eq(!originalIsBlocked)
            )
        verify(mockActivityStarter).postQSRunnableDismissingKeyguard(any())
    }

    @Test
    fun handleLongClick_whenSafetyManagerEnabled_privacyControlsIntent() = runTest {
        whenever(fakeSafetyCenterManager.isSafetyCenterEnabled).thenReturn(true)

        underTest.handleInput(QSTileInputTestKtx.longClick(SensorPrivacyToggleTileModel(false)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_PRIVACY_CONTROLS)
        }
    }

    @Test
    fun handleLongClick_whenSafetyManagerDisabled_privacySettingsIntent() = runTest {
        whenever(fakeSafetyCenterManager.isSafetyCenterEnabled).thenReturn(false)

        underTest.handleInput(QSTileInputTestKtx.longClick(SensorPrivacyToggleTileModel(false)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_PRIVACY_SETTINGS)
        }
    }

    @Test
    fun handleClick_microphone_flipsController() = runTest {
        val micUserActionInteractor =
            SensorPrivacyToggleTileUserActionInteractor(
                inputHandler,
                keyguardInteractor,
                mockActivityStarter,
                mockSensorPrivacyController,
                fakeSafetyCenterManager,
                MICROPHONE
            )

        micUserActionInteractor.handleInput(
            QSTileInputTestKtx.click(SensorPrivacyToggleTileModel(false))
        )
        verify(mockSensorPrivacyController)
            .setSensorBlocked(eq(SensorPrivacyManager.Sources.QS_TILE), eq(MICROPHONE), eq(true))

        micUserActionInteractor.handleInput(
            QSTileInputTestKtx.click(SensorPrivacyToggleTileModel(true))
        )
        verify(mockSensorPrivacyController)
            .setSensorBlocked(eq(SensorPrivacyManager.Sources.QS_TILE), eq(MICROPHONE), eq(false))
    }
}
