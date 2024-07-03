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

package com.android.systemui.scene.domain.startable

import android.app.StatusBarManager
import android.provider.DeviceConfig
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.statusbar.statusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.navigationbar.navigationModeController
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.fakeDeviceConfigProxy
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.reflect.full.memberProperties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.Parameter
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@EnableSceneContainer
class StatusBarStartableTest : SysuiTestCase() {

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun testSpecs(): List<TestSpec> {
            return listOf(
                TestSpec(
                    id = 0,
                    expectedFlags = StatusBarManager.DISABLE_NONE,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = false,
                        isPowerGestureIntercepted = false,
                    ),
                ),
                TestSpec(
                    id = 1,
                    expectedFlags = StatusBarManager.DISABLE_NONE,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = true,
                        isOccluded = true,
                        isPowerGestureIntercepted = false,
                    ),
                ),
                TestSpec(
                    id = 2,
                    expectedFlags = StatusBarManager.DISABLE_NONE,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = false,
                        isPowerGestureIntercepted = true,
                        isOccluded = false,
                    ),
                ),
                TestSpec(
                    id = 3,
                    expectedFlags = StatusBarManager.DISABLE_NONE,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = true,
                        isOccluded = true,
                        isPowerGestureIntercepted = true,
                        isAuthenticationMethodSecure = false,
                    ),
                ),
                TestSpec(
                    id = 4,
                    expectedFlags = StatusBarManager.DISABLE_NONE,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = true,
                        isOccluded = true,
                        isPowerGestureIntercepted = true,
                        isAuthenticationMethodSecure = true,
                        isFaceEnrolledAndEnabled = false,
                    ),
                ),
                TestSpec(
                    id = 5,
                    expectedFlags = StatusBarManager.DISABLE_RECENT,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = true,
                        isOccluded = true,
                        isPowerGestureIntercepted = true,
                        isAuthenticationMethodSecure = true,
                        isFaceEnrolledAndEnabled = true,
                    ),
                ),
                TestSpec(
                    id = 6,
                    expectedFlags = StatusBarManager.DISABLE_RECENT,
                    Preconditions(
                        isForceHideHomeAndRecents = true,
                        isShowHomeOverLockscreen = true,
                        isGesturalMode = true,
                        isPowerGestureIntercepted = false,
                    ),
                ),
                TestSpec(
                    id = 7,
                    expectedFlags = StatusBarManager.DISABLE_RECENT,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = true,
                        isOccluded = false,
                        isShowHomeOverLockscreen = true,
                        isGesturalMode = true,
                        isPowerGestureIntercepted = false,
                    ),
                ),
                TestSpec(
                    id = 8,
                    expectedFlags =
                        StatusBarManager.DISABLE_RECENT or StatusBarManager.DISABLE_HOME,
                    Preconditions(
                        isForceHideHomeAndRecents = true,
                        isShowHomeOverLockscreen = true,
                        isGesturalMode = false,
                        isPowerGestureIntercepted = false,
                    ),
                ),
                TestSpec(
                    id = 9,
                    expectedFlags =
                        StatusBarManager.DISABLE_RECENT or StatusBarManager.DISABLE_HOME,
                    Preconditions(
                        isForceHideHomeAndRecents = false,
                        isKeyguardShowing = true,
                        isOccluded = false,
                        isShowHomeOverLockscreen = false,
                        isPowerGestureIntercepted = false,
                    ),
                ),
            )
        }

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val seenIds = mutableSetOf<Int>()
            testSpecs().forEach { testSpec ->
                assertWithMessage("Duplicate TestSpec id=${testSpec.id}")
                    .that(seenIds)
                    .doesNotContain(testSpec.id)
                seenIds.add(testSpec.id)
            }
        }
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val statusBarServiceMock = kosmos.statusBarService
    private val flagsCaptor = argumentCaptor<Int>()

    private val navigationModeControllerMock = kosmos.navigationModeController
    private var currentNavigationMode = WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
        set(value) {
            field = value
            modeChangedListeners.forEach { listener -> listener.onNavigationModeChanged(field) }
        }

    private val modeChangedListeners = mutableListOf<NavigationModeController.ModeChangedListener>()

    private val underTest = kosmos.statusBarStartable

    @JvmField @Parameter(0) var testSpec: TestSpec? = null

    @Before
    fun setUp() {
        whenever(navigationModeControllerMock.addListener(any())).thenAnswer { invocation ->
            val listener = invocation.arguments[0] as NavigationModeController.ModeChangedListener
            modeChangedListeners.add(listener)
            currentNavigationMode
        }

        underTest.start()
    }

    @Test
    fun test() =
        testScope.runTest {
            val preconditions = checkNotNull(testSpec).preconditions
            preconditions.assertValid()

            setUpWith(preconditions)

            runCurrent()

            verify(statusBarServiceMock, atLeastOnce())
                .disableForUser(flagsCaptor.capture(), any(), any(), anyInt())
            assertThat(flagsCaptor.lastValue).isEqualTo(checkNotNull(testSpec).expectedFlags)
        }

    /** Sets up the state to match what's specified in the given [preconditions]. */
    private fun TestScope.setUpWith(
        preconditions: Preconditions,
    ) {
        if (!preconditions.isKeyguardShowing) {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
        }
        if (preconditions.isForceHideHomeAndRecents) {
            whenIdle(Scenes.Bouncer)
        } else if (preconditions.isKeyguardShowing) {
            whenIdle(Scenes.Lockscreen)
        } else {
            whenIdle(Scenes.Gone)
        }
        runCurrent()

        kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
            showWhenLockedActivityOnTop = preconditions.isOccluded,
            taskInfo = if (preconditions.isOccluded) mock() else null,
        )

        kosmos.fakeDeviceConfigProxy.setProperty(
            DeviceConfig.NAMESPACE_SYSTEMUI,
            SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN,
            preconditions.isShowHomeOverLockscreen.toString(),
            /* makeDefault= */ false,
        )
        kosmos.fakeExecutor.runAllReady()

        currentNavigationMode =
            if (preconditions.isGesturalMode) {
                WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL
            } else {
                WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
            }

        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
            if (preconditions.isAuthenticationMethodSecure) {
                AuthenticationMethodModel.Pin
            } else {
                AuthenticationMethodModel.None
            }
        )

        kosmos.fakePowerRepository.updateWakefulness(
            rawState =
                if (preconditions.isPowerGestureIntercepted) WakefulnessState.AWAKE
                else WakefulnessState.ASLEEP,
            lastWakeReason = WakeSleepReason.POWER_BUTTON,
            lastSleepReason = WakeSleepReason.POWER_BUTTON,
            powerButtonLaunchGestureTriggered = preconditions.isPowerGestureIntercepted,
        )

        kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(
            preconditions.isFaceEnrolledAndEnabled
        )

        runCurrent()
    }

    /** Sets up an idle state on the given [on] scene. */
    private fun whenIdle(on: SceneKey) {
        kosmos.setSceneTransition(ObservableTransitionState.Idle(on))
        kosmos.sceneInteractor.changeScene(on, "")
    }

    data class Preconditions(
        val isForceHideHomeAndRecents: Boolean = false,
        val isKeyguardShowing: Boolean = true,
        val isOccluded: Boolean = false,
        val isPowerGestureIntercepted: Boolean = false,
        val isShowHomeOverLockscreen: Boolean = false,
        val isGesturalMode: Boolean = true,
        val isAuthenticationMethodSecure: Boolean = true,
        val isFaceEnrolledAndEnabled: Boolean = false,
    ) {
        override fun toString(): String {
            // Only include values set to true:
            return buildString {
                append("(")
                append(
                    Preconditions::class
                        .memberProperties
                        .filter { it.get(this@Preconditions) == true }
                        .joinToString(", ") { "${it.name}=true" }
                )
                append(")")
            }
        }

        fun assertValid() {
            assertWithMessage(
                    "isForceHideHomeAndRecents means that the bouncer is showing so keyguard must" +
                        " be showing"
                )
                .that(!isForceHideHomeAndRecents || isKeyguardShowing)
                .isTrue()
            assertWithMessage("Cannot be occluded if the keyguard isn't showing")
                .that(!isOccluded || isKeyguardShowing)
                .isTrue()
        }
    }

    data class TestSpec(
        val id: Int,
        val expectedFlags: Int,
        val preconditions: Preconditions,
    ) {
        override fun toString(): String {
            return "id=$id, expected=$expectedFlags, preconditions=$preconditions"
        }
    }
}
