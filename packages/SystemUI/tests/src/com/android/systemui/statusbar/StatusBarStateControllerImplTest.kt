/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.platform.test.annotations.DisableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.fromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.fromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import com.android.systemui.scene.shared.model.Scenes
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class StatusBarStateControllerImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var shadeInteractor: ShadeInteractor
    private lateinit var fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor
    private lateinit var fromPrimaryBouncerTransitionInteractor:
        FromPrimaryBouncerTransitionInteractor
    private val interactionJankMonitor = mock<InteractionJankMonitor>()
    private val mockDarkAnimator = mock<ObjectAnimator>()
    private val deviceEntryUdfpsInteractor = mock<DeviceEntryUdfpsInteractor>()
    private val largeScreenHeaderHelper = mock<LargeScreenHeaderHelper>()

    private lateinit var underTest: StatusBarStateControllerImpl
    private lateinit var uiEventLogger: UiEventLoggerFake

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(interactionJankMonitor.begin(any(), anyInt())).thenReturn(true)
        whenever(interactionJankMonitor.end(anyInt())).thenReturn(true)

        uiEventLogger = UiEventLoggerFake()
        underTest =
            object :
                StatusBarStateControllerImpl(
                    uiEventLogger,
                    interactionJankMonitor,
                    JavaAdapter(testScope.backgroundScope),
                    { shadeInteractor },
                    { kosmos.deviceUnlockedInteractor },
                    { kosmos.sceneInteractor },
                ) {
                override fun createDarkAnimator(): ObjectAnimator {
                    return mockDarkAnimator
                }
            }

        val powerInteractor =
            PowerInteractor(FakePowerRepository(), FalsingCollectorFake(), mock(), underTest)
        val keyguardRepository = FakeKeyguardRepository()
        val keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        val featureFlags = FakeFeatureFlagsClassic()
        val shadeRepository = FakeShadeRepository()
        val sceneContainerFlags = FakeSceneContainerFlags()
        val configurationRepository = FakeConfigurationRepository()
        val keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
        fromLockscreenTransitionInteractor = kosmos.fromLockscreenTransitionInteractor
        fromPrimaryBouncerTransitionInteractor = kosmos.fromPrimaryBouncerTransitionInteractor

        val keyguardInteractor =
            KeyguardInteractor(
                keyguardRepository,
                FakeCommandQueue(),
                powerInteractor,
                sceneContainerFlags,
                FakeKeyguardBouncerRepository(),
                ConfigurationInteractor(configurationRepository),
                shadeRepository,
                keyguardTransitionInteractor,
                { kosmos.sceneInteractor },
            )

        whenever(deviceEntryUdfpsInteractor.isUdfpsSupported).thenReturn(emptyFlow())
        shadeInteractor =
            ShadeInteractorImpl(
                testScope.backgroundScope,
                kosmos.deviceProvisioningInteractor,
                FakeDisableFlagsRepository(),
                mock(),
                keyguardRepository,
                keyguardTransitionInteractor,
                powerInteractor,
                FakeUserSetupRepository(),
                mock(),
                ShadeInteractorLegacyImpl(
                    testScope.backgroundScope,
                    keyguardRepository,
                    SharedNotificationContainerInteractor(
                        configurationRepository,
                        mContext,
                        ResourcesSplitShadeStateController(),
                        keyguardInteractor,
                        deviceEntryUdfpsInteractor,
                        largeScreenHeaderHelperLazy = { largeScreenHeaderHelper }
                    ),
                    shadeRepository,
                )
            )
    }

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
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
    fun testSetDozeAmountInternal_onlySetsOnce() {
        val listener = mock(StatusBarStateController.StateListener::class.java)
        underTest.addCallback(listener)

        underTest.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        underTest.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        verify(listener).onDozeAmountChanged(eq(0.5f), anyFloat())
    }

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun testSetState_appliesState_sameStateButDifferentUpcomingState() {
        underTest.state = StatusBarState.SHADE
        underTest.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(underTest.state, StatusBarState.SHADE)

        // We should return true (state change was applied) despite going from SHADE to SHADE, since
        // the upcoming state was set to KEYGUARD.
        assertTrue(underTest.setState(StatusBarState.SHADE))
    }

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun testSetState_appliesState_differentStateEqualToUpcomingState() {
        underTest.state = StatusBarState.SHADE
        underTest.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(underTest.state, StatusBarState.SHADE)

        // Make sure we apply a SHADE -> KEYGUARD state change when the upcoming state is KEYGUARD.
        assertTrue(underTest.setState(StatusBarState.KEYGUARD))
    }

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
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
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(false)
            runCurrent()
            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.Lockscreen,
                loggingReason = "reason"
            )
            runCurrent()
            assertThat(kosmos.deviceUnlockedInteractor.isDeviceUnlocked.value).isFalse()
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

            kosmos.sceneInteractor.changeScene(
                toScene = Scenes.Communal,
                loggingReason = "reason"
            )
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
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()
            kosmos.sceneInteractor.changeScene(toScene = Scenes.Gone, loggingReason = "reason")
            runCurrent()
            assertThat(kosmos.deviceUnlockedInteractor.isDeviceUnlocked.value).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

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
}
