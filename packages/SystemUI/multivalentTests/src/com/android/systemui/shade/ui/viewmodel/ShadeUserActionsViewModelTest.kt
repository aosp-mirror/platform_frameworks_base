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

package com.android.systemui.shade.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.qs.ui.adapter.fakeQSSceneAdapter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.resolver.homeSceneFamilyResolver
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.startable.shadeStartable
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
@DisableFlags(DualShade.FLAG_NAME)
class ShadeUserActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val shadeRepository by lazy { kosmos.shadeRepository }
    private val qsSceneAdapter by lazy { kosmos.fakeQSSceneAdapter }

    private val underTest: ShadeUserActionsViewModel by lazy { kosmos.shadeUserActionsViewModel }

    @Before
    fun setUp() {
        underTest.activateIn(testScope)
    }

    @Test
    fun upTransitionSceneKey_deviceLocked_lockScreen() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )

            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Up)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_deviceUnlocked_gone() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            setDeviceEntered(true)

            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Up)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_keyguardDisabled_gone() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)

            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Up)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            sceneInteractor.changeScene(Scenes.Lockscreen, "reason")

            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Up)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun upTransitionSceneKey_authMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()
            sceneInteractor.changeScene(Scenes.Gone, "reason")

            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Up)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun upTransitionKey_splitShadeEnabled_isGoneToSplitShade() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(true)
            runCurrent()

            assertThat(actions?.get(Swipe(SwipeDirection.Up))?.transitionKey)
                .isEqualTo(ToSplitShade)
        }

    @Test
    fun upTransitionKey_splitShadeDisable_isNull() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)
            shadeRepository.setShadeLayoutWide(false)
            runCurrent()

            assertThat(actions?.get(Swipe(SwipeDirection.Up))?.transitionKey).isNull()
        }

    @Test
    fun downTransitionSceneKey_inSplitShade_null() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            kosmos.shadeStartable.start()
            val actions by collectLastValue(underTest.actions)
            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Down)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isNull()
        }

    @Test
    fun downTransitionSceneKey_notSplitShade_quickSettings() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            kosmos.shadeStartable.start()
            val actions by collectLastValue(underTest.actions)
            assertThat(
                    (actions?.get(Swipe(SwipeDirection.Down)) as? UserActionResult.ChangeScene)
                        ?.toScene
                )
                .isEqualTo(Scenes.QuickSettings)
        }

    @Test
    fun upTransitionSceneKey_customizing_noTransition() =
        testScope.runTest {
            val actions by collectLastValue(underTest.actions)

            qsSceneAdapter.setCustomizing(true)
            assertThat(
                    actions!!.keys.filterIsInstance<Swipe>().filter {
                        it.direction == SwipeDirection.Up
                    }
                )
                .isEmpty()
        }

    private fun TestScope.setDeviceEntered(isEntered: Boolean) {
        if (isEntered) {
            // Unlock the device marking the device has entered.
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
        }
        setScene(
            if (isEntered) {
                Scenes.Gone
            } else {
                Scenes.Lockscreen
            }
        )
        assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isEqualTo(isEntered)
    }

    private fun TestScope.setScene(key: SceneKey) {
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        runCurrent()
    }
}
