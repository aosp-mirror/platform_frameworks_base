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

package com.android.systemui.communal.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class CommunalUserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: CommunalUserActionsViewModel

    @Before
    fun setUp() {
        underTest = kosmos.communalUserActionsViewModel
        underTest.activateIn(testScope)
    }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun actions_singleShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)

            setUpState(
                isShadeTouchable = true,
                isDeviceUnlocked = false,
                shadeMode = ShadeMode.Single,
            )
            assertThat(actions).isNotEmpty()
            assertThat(actions?.get(Swipe.End)).isEqualTo(UserActionResult(SceneFamilies.Home))
            assertThat(actions?.get(Swipe.Up)).isEqualTo(UserActionResult(Scenes.Bouncer))
            assertThat(actions?.get(Swipe.Down)).isEqualTo(UserActionResult(Scenes.Shade))

            setUpState(
                isShadeTouchable = false,
                isDeviceUnlocked = false,
                shadeMode = ShadeMode.Single,
            )
            assertThat(actions).isEmpty()

            setUpState(
                isShadeTouchable = true,
                isDeviceUnlocked = true,
                shadeMode = ShadeMode.Single,
            )
            assertThat(actions).isNotEmpty()
            assertThat(actions?.get(Swipe.End)).isEqualTo(UserActionResult(SceneFamilies.Home))
            assertThat(actions?.get(Swipe.Up)).isEqualTo(UserActionResult(Scenes.Gone))
            assertThat(actions?.get(Swipe.Down)).isEqualTo(UserActionResult(Scenes.Shade))
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun actions_splitShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)

            setUpState(
                isShadeTouchable = true,
                isDeviceUnlocked = false,
                shadeMode = ShadeMode.Split,
            )
            assertThat(actions).isNotEmpty()
            assertThat(actions?.get(Swipe.End)).isEqualTo(UserActionResult(SceneFamilies.Home))
            assertThat(actions?.get(Swipe.Up)).isEqualTo(UserActionResult(Scenes.Bouncer))
            assertThat(actions?.get(Swipe.Down))
                .isEqualTo(UserActionResult(Scenes.Shade, ToSplitShade))

            setUpState(
                isShadeTouchable = false,
                isDeviceUnlocked = false,
                shadeMode = ShadeMode.Split,
            )
            assertThat(actions).isEmpty()

            setUpState(
                isShadeTouchable = true,
                isDeviceUnlocked = true,
                shadeMode = ShadeMode.Split,
            )
            assertThat(actions).isNotEmpty()
            assertThat(actions?.get(Swipe.End)).isEqualTo(UserActionResult(SceneFamilies.Home))
            assertThat(actions?.get(Swipe.Up)).isEqualTo(UserActionResult(Scenes.Gone))
            assertThat(actions?.get(Swipe.Down))
                .isEqualTo(UserActionResult(Scenes.Shade, ToSplitShade))
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun actions_dualShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)

            setUpState(
                isShadeTouchable = true,
                isDeviceUnlocked = false,
                shadeMode = ShadeMode.Dual,
            )
            assertThat(actions).isNotEmpty()
            assertThat(actions?.get(Swipe.End)).isEqualTo(UserActionResult(SceneFamilies.Home))
            assertThat(actions?.get(Swipe.Up)).isEqualTo(UserActionResult(Scenes.Bouncer))
            assertThat(actions?.get(Swipe.Down))
                .isEqualTo(UserActionResult.ShowOverlay(Overlays.NotificationsShade))

            setUpState(
                isShadeTouchable = false,
                isDeviceUnlocked = false,
                shadeMode = ShadeMode.Dual,
            )
            assertThat(actions).isEmpty()

            setUpState(isShadeTouchable = true, isDeviceUnlocked = true, shadeMode = ShadeMode.Dual)
            assertThat(actions).isNotEmpty()
            assertThat(actions?.get(Swipe.End)).isEqualTo(UserActionResult(SceneFamilies.Home))
            assertThat(actions?.get(Swipe.Up)).isEqualTo(UserActionResult(Scenes.Gone))
            assertThat(actions?.get(Swipe.Down))
                .isEqualTo(UserActionResult.ShowOverlay(Overlays.NotificationsShade))
        }

    private fun TestScope.setUpState(
        isShadeTouchable: Boolean,
        isDeviceUnlocked: Boolean,
        shadeMode: ShadeMode,
    ) {
        if (isShadeTouchable) {
            kosmos.powerInteractor.setAwakeForTest()
        } else {
            kosmos.powerInteractor.setAsleepForTest()
        }

        if (isDeviceUnlocked) {
            unlockDevice()
        } else {
            lockDevice()
        }

        if (shadeMode == ShadeMode.Dual) {
            assertThat(DualShade.isEnabled).isTrue()
        } else {
            assertThat(DualShade.isEnabled).isFalse()
            kosmos.fakeShadeRepository.setShadeLayoutWide(shadeMode == ShadeMode.Split)
        }
        runCurrent()
    }

    private fun TestScope.lockDevice() {
        val deviceUnlockStatus by
            collectLastValue(kosmos.deviceUnlockedInteractor.deviceUnlockStatus)

        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
        assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
        runCurrent()
    }

    private fun TestScope.unlockDevice() {
        val deviceUnlockStatus by
            collectLastValue(kosmos.deviceUnlockedInteractor.deviceUnlockStatus)

        kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        kosmos.sceneInteractor.changeScene(Scenes.Gone, "reason")
        runCurrent()
    }
}
