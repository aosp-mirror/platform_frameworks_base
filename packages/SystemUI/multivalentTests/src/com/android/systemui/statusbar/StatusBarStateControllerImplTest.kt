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

package com.android.systemui.statusbar

import android.animation.ObjectAnimator
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.setTransition
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@TestableLooper.RunWithLooper
class StatusBarStateControllerImplTest(flags: FlagsParameterization) : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val mockDarkAnimator = mock<ObjectAnimator>()

    private lateinit var underTest: StatusBarStateControllerImpl
    private lateinit var uiEventLogger: UiEventLoggerFake

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        uiEventLogger = UiEventLoggerFake()
        underTest =
            object :
                StatusBarStateControllerImpl(
                    uiEventLogger,
                    { kosmos.interactionJankMonitor },
                    JavaAdapter(testScope.backgroundScope),
                    { kosmos.keyguardInteractor },
                    { kosmos.keyguardTransitionInteractor },
                    { kosmos.shadeInteractor },
                    { kosmos.deviceUnlockedInteractor },
                    { kosmos.sceneInteractor },
                    { kosmos.sceneContainerOcclusionInteractor },
                    { kosmos.keyguardClockInteractor },
                    { kosmos.sceneBackInteractor },
                ) {
                override fun createDarkAnimator(): ObjectAnimator {
                    return mockDarkAnimator
                }
            }
    }

    @Test
    @DisableSceneContainer
    fun testChangeState_logged() {
        TestableLooper.get(this).runWithLooper {
            underTest.state = StatusBarState.KEYGUARD
            underTest.state = StatusBarState.SHADE
            underTest.state = StatusBarState.SHADE_LOCKED
        }

        val logs = uiEventLogger.logs
        assertEquals(3, logs.size)
        val ids = logs.map(UiEventLoggerFake.FakeUiEvent::eventId)
        assertEquals(StatusBarStateEvent.STATUS_BAR_STATE_KEYGUARD.id, ids[0])
        assertEquals(StatusBarStateEvent.STATUS_BAR_STATE_SHADE.id, ids[1])
        assertEquals(StatusBarStateEvent.STATUS_BAR_STATE_SHADE_LOCKED.id, ids[2])
    }

    @Test
    @DisableSceneContainer
    fun testSetDozeAmountInternal_onlySetsOnce() {
        val listener = mock(StatusBarStateController.StateListener::class.java)
        underTest.addCallback(listener)

        underTest.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        underTest.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        verify(listener).onDozeAmountChanged(eq(0.5f), anyFloat())
    }

    @Test
    @DisableSceneContainer
    fun testSetState_appliesState_sameStateButDifferentUpcomingState() {
        underTest.state = StatusBarState.SHADE
        underTest.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(underTest.state, StatusBarState.SHADE)

        // We should return true (state change was applied) despite going from SHADE to SHADE, since
        // the upcoming state was set to KEYGUARD.
        assertTrue(underTest.setState(StatusBarState.SHADE))
    }

    @Test
    @DisableSceneContainer
    fun testSetState_appliesState_differentStateEqualToUpcomingState() {
        underTest.state = StatusBarState.SHADE
        underTest.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(underTest.state, StatusBarState.SHADE)

        // Make sure we apply a SHADE -> KEYGUARD state change when the upcoming state is KEYGUARD.
        assertTrue(underTest.setState(StatusBarState.KEYGUARD))
    }

    @Test
    @DisableSceneContainer
    fun testSetState_doesNotApplyState_currentAndUpcomingStatesSame() {
        underTest.state = StatusBarState.SHADE
        underTest.setUpcomingState(StatusBarState.SHADE)

        assertEquals(underTest.state, StatusBarState.SHADE)

        // We're going from SHADE -> SHADE, and the upcoming state is also SHADE, this should not do
        // anything.
        assertFalse(underTest.setState(StatusBarState.SHADE))

        // Double check that we can still force it to happen.
        assertTrue(underTest.setState(StatusBarState.SHADE, true /* force */))
    }

    @Test
    @DisableSceneContainer
    fun testSetDozeAmount_immediatelyChangesDozeAmount_lockscreenTransitionFromAod() {
        // Put controller in AOD state
        underTest.setAndInstrumentDozeAmount(null, 1f, false)

        // When waking from doze, CentralSurfaces#updateDozingState will update the dozing state
        // before the doze amount changes
        underTest.setIsDozing(false)

        // Animate the doze amount to 0f, as would normally happen
        underTest.setAndInstrumentDozeAmount(null, 0f, true)

        // Check that the doze amount is immediately set to a value slightly less than 1f. This is
        // to ensure that any scrim implementation changes its opacity immediately rather than
        // waiting an extra frame. Waiting an extra frame will cause a relayout (which is expensive)
        // and cause us to drop a frame during the LOCKSCREEN_TRANSITION_FROM_AOD CUJ.
        assertEquals(0.99f, underTest.dozeAmount, 0.009f)
    }

    @Test
    fun testSetDreamState_invokesCallback() {
        val listener = mock(StatusBarStateController.StateListener::class.java)
        underTest.addCallback(listener)

        underTest.setIsDreaming(true)
        verify(listener).onDreamingChanged(true)

        Mockito.clearInvocations(listener)

        underTest.setIsDreaming(false)
        verify(listener).onDreamingChanged(false)
    }

    @Test
    fun testSetDreamState_getterReturnsCurrentState() {
        underTest.setIsDreaming(true)
        assertTrue(underTest.isDreaming())

        underTest.setIsDreaming(false)
        assertFalse(underTest.isDreaming())
    }

    @Test
    @EnableSceneContainer
    fun start_hydratesStatusBarState_whileLocked() =
        testScope.runTest {
            var statusBarState = underTest.state
            val listener =
                object : StatusBarStateController.StateListener {
                    override fun onStateChanged(newState: Int) {
                        statusBarState = newState
                    }
                }
            underTest.addCallback(listener)

            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            val deviceUnlockStatus by
                collectLastValue(kosmos.deviceUnlockedInteractor.deviceUnlockStatus)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            runCurrent()
            assertThat(deviceUnlockStatus!!.isUnlocked).isFalse()

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.Lockscreen,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            // Call start to begin hydrating based on the scene framework:
            underTest.start()

            kosmos.sceneInteractor.changeScene(toScene = Scenes.Bouncer, loggingReason = "reason")
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
            assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)

            kosmos.sceneInteractor.changeScene(toScene = Scenes.Shade, loggingReason = "reason")
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE_LOCKED)

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.QuickSettings,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE_LOCKED)

            kosmos.sceneInteractor.changeScene(toScene = Scenes.Communal, loggingReason = "reason")
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Communal)
            assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.Lockscreen,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)
        }

    @Test
    @EnableSceneContainer
    fun start_hydratesStatusBarState_whileUnlocked() =
        testScope.runTest {
            var statusBarState = underTest.state
            val listener =
                object : StatusBarStateController.StateListener {
                    override fun onStateChanged(newState: Int) {
                        statusBarState = newState
                    }
                }
            underTest.addCallback(listener)

            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            val deviceUnlockStatus by
                collectLastValue(kosmos.deviceUnlockedInteractor.deviceUnlockStatus)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus!!.isUnlocked).isTrue()

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.Lockscreen,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            // Call start to begin hydrating based on the scene framework:
            underTest.start()
            runCurrent()

            assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)

            kosmos.sceneInteractor.changeScene(toScene = Scenes.Gone, loggingReason = "reason")
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)

            kosmos.sceneInteractor.changeScene(toScene = Scenes.Shade, loggingReason = "reason")
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.QuickSettings,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)
        }

    @Test
    @EnableSceneContainer
    fun start_hydratesStatusBarState_whileOccluded() =
        testScope.runTest {
            var statusBarState = underTest.state
            val listener =
                object : StatusBarStateController.StateListener {
                    override fun onStateChanged(newState: Int) {
                        statusBarState = newState
                    }
                }
            underTest.addCallback(listener)

            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            val isOccluded by
                collectLastValue(kosmos.sceneContainerOcclusionInteractor.invisibleDueToOcclusion)
            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                showWhenLockedActivityOnTop = true,
                taskInfo = mock(),
            )
            runCurrent()
            assertThat(isOccluded).isTrue()

            // Call start to begin hydrating based on the scene framework:
            underTest.start()

            kosmos.sceneInteractor.changeScene(toScene = Scenes.Shade, loggingReason = "reason")
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.QuickSettings,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)
        }

    @Test
    fun leaveOpenOnKeyguard_whenGone_isFalse() =
        testScope.runTest {
            underTest.start()
            underTest.setLeaveOpenOnKeyguardHide(true)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )
            assertThat(underTest.leaveOpenOnKeyguardHide()).isEqualTo(true)

            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Gone),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.GONE,
                    )
            )

            assertThat(underTest.leaveOpenOnKeyguardHide()).isEqualTo(false)
        }
}
