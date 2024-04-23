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

package com.android.systemui.shade

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class ShadeControllerSceneImplTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }

    private lateinit var shadeInteractor: ShadeInteractor
    private lateinit var underTest: ShadeControllerSceneImpl

    @Before
    fun setup() {
        kosmos.testCase = this
        kosmos.fakeFeatureFlagsClassic.apply {
            set(Flags.FULL_SCREEN_USER_SWITCHER, false)
            set(Flags.NSSL_DEBUG_LINES, false)
            set(Flags.FULL_SCREEN_USER_SWITCHER, false)
        }
        kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        testScope.runCurrent()
        shadeInteractor = kosmos.shadeInteractor
        underTest = kosmos.shadeControllerSceneImpl
    }

    @Test
    fun animateCollapseShade_noForceNoExpansion() =
        testScope.runTest {
            // GIVEN shade is collapsed and a post-collapse action is enqueued
            val testRunnable = mock<Runnable>()
            setDeviceEntered(true)
            setCollapsed()
            underTest.addPostCollapseAction(testRunnable)

            // WHEN a collapse is requested
            underTest.animateCollapseShade(0, force = false, delayed = false, 1f)
            runCurrent()

            // THEN the shade remains collapsed and the post-collapse action ran
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Gone)
            verify(testRunnable, times(1)).run()
        }

    @Test
    fun animateCollapseShade_expandedExcludeFlagOn() =
        testScope.runTest {
            // GIVEN shade is fully expanded and a post-collapse action is enqueued
            val testRunnable = mock<Runnable>()
            underTest.addPostCollapseAction(testRunnable)
            setDeviceEntered(true)
            setShadeFullyExpanded()

            // WHEN a collapse is requested with FLAG_EXCLUDE_NOTIFICATION_PANEL
            underTest.animateCollapseShade(CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL)
            runCurrent()

            // THEN the shade remains expanded and the post-collapse action did not run
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Shade)
            assertThat(shadeInteractor.isAnyFullyExpanded.value).isTrue()
            verify(testRunnable, never()).run()
        }

    @Test
    fun animateCollapseShade_locked() =
        testScope.runTest {
            // GIVEN shade is fully expanded on lockscreen
            setDeviceEntered(false)
            setShadeFullyExpanded()

            // WHEN a collapse is requested
            underTest.animateCollapseShade()
            runCurrent()

            // THEN the shade collapses back to lockscreen and the post-collapse action ran
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun animateCollapseShade_unlocked() =
        testScope.runTest {
            // GIVEN shade is fully expanded on an unlocked device
            setDeviceEntered(true)
            setShadeFullyExpanded()

            // WHEN a collapse is requested
            underTest.animateCollapseShade()
            runCurrent()

            // THEN the shade collapses back to lockscreen and the post-collapse action ran
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Gone)
        }

    @Test
    fun onCollapseShade_runPostCollapseActionsCalled() =
        testScope.runTest {
            // GIVEN shade is expanded and a post-collapse action is enqueued
            val testRunnable = mock<Runnable>()
            setShadeFullyExpanded()
            underTest.addPostCollapseAction(testRunnable)

            // WHEN shade collapses
            setCollapsed()

            // THEN post-collapse action ran
            verify(testRunnable, times(1)).run()
        }

    @Test
    fun postOnShadeExpanded() =
        testScope.runTest {
            // GIVEN shade is collapsed and a post-collapse action is enqueued
            val testRunnable = mock<Runnable>()
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            setCollapsed()
            underTest.postOnShadeExpanded(testRunnable)

            // WHEN shade expands
            setShadeFullyExpanded()

            // THEN post-collapse action ran
            verify(testRunnable, times(1)).run()
        }

    private fun setScene(key: SceneKey) {
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        testScope.runCurrent()
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
        assertThat(deviceEntryInteractor.isDeviceEntered.value).isEqualTo(isEntered)
    }

    private fun setCollapsed() {
        setScene(Scenes.Gone)
        assertThat(shadeInteractor.isAnyExpanded.value).isFalse()
    }

    private fun setShadeFullyExpanded() {
        setScene(Scenes.Shade)
        assertThat(shadeInteractor.isAnyFullyExpanded.value).isTrue()
    }
}
