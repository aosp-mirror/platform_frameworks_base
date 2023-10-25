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

package com.android.systemui.statusbar

import android.animation.ObjectAnimator
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardSurfaceBehindRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.InWindowLauncherUnlockAnimationRepository
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.InWindowLauncherUnlockAnimationInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.statusbar.policy.data.repository.FakeDeviceProvisioningRepository
import com.android.systemui.util.mockito.mock
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
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class StatusBarStateControllerImplTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private lateinit var shadeInteractor: ShadeInteractor
    private lateinit var fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor
    private lateinit var fromPrimaryBouncerTransitionInteractor:
        FromPrimaryBouncerTransitionInteractor
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock lateinit var mockDarkAnimator: ObjectAnimator

    private lateinit var controller: StatusBarStateControllerImpl
    private lateinit var uiEventLogger: UiEventLoggerFake

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(interactionJankMonitor.begin(any(), anyInt())).thenReturn(true)
        whenever(interactionJankMonitor.end(anyInt())).thenReturn(true)

        uiEventLogger = UiEventLoggerFake()
        controller = object : StatusBarStateControllerImpl(
            uiEventLogger,
            interactionJankMonitor,
            mock(),
            { shadeInteractor }
        ) {
            override fun createDarkAnimator(): ObjectAnimator { return mockDarkAnimator }
        }

        val powerInteractor = PowerInteractor(
            FakePowerRepository(),
            FalsingCollectorFake(),
            mock(),
            controller)
        val keyguardRepository = FakeKeyguardRepository()
        val keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        val featureFlags = FakeFeatureFlagsClassic()
        val shadeRepository = FakeShadeRepository()
        val sceneContainerFlags = FakeSceneContainerFlags()
        val configurationRepository = FakeConfigurationRepository()
        val keyguardInteractor = KeyguardInteractor(
            keyguardRepository,
            FakeCommandQueue(),
            powerInteractor,
            featureFlags,
            sceneContainerFlags,
            FakeKeyguardBouncerRepository(),
            configurationRepository,
            shadeRepository,
            utils::sceneInteractor)
        val keyguardTransitionInteractor = KeyguardTransitionInteractor(
            testScope.backgroundScope,
            keyguardTransitionRepository,
            { keyguardInteractor },
            { fromLockscreenTransitionInteractor },
            { fromPrimaryBouncerTransitionInteractor })
        fromLockscreenTransitionInteractor = FromLockscreenTransitionInteractor(
            keyguardTransitionRepository,
            keyguardTransitionInteractor,
            testScope.backgroundScope,
            keyguardInteractor,
            featureFlags,
            shadeRepository,
            powerInteractor,
            {
                InWindowLauncherUnlockAnimationInteractor(
                    InWindowLauncherUnlockAnimationRepository(),
                    testScope,
                    keyguardTransitionInteractor,
                    { FakeKeyguardSurfaceBehindRepository() },
                    mock(),
                )
            })
        fromPrimaryBouncerTransitionInteractor = FromPrimaryBouncerTransitionInteractor(
            keyguardTransitionRepository,
            keyguardTransitionInteractor,
            testScope.backgroundScope,
            keyguardInteractor,
            featureFlags,
            mock(),
            mock(),
            powerInteractor)
        shadeInteractor = ShadeInteractor(
            testScope.backgroundScope,
            FakeDeviceProvisioningRepository(),
            FakeDisableFlagsRepository(),
            mock(),
            sceneContainerFlags,
            utils::sceneInteractor,
            keyguardRepository,
            keyguardTransitionInteractor,
            powerInteractor,
            FakeUserSetupRepository(),
            mock(),
            SharedNotificationContainerInteractor(
                configurationRepository,
                mContext,
                ResourcesSplitShadeStateController()),
            shadeRepository,
        )
    }

    @Test
    fun testChangeState_logged() {
        TestableLooper.get(this).runWithLooper {
            controller.state = StatusBarState.KEYGUARD
            controller.state = StatusBarState.SHADE
            controller.state = StatusBarState.SHADE_LOCKED
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
        controller.addCallback(listener)

        controller.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        controller.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        verify(listener).onDozeAmountChanged(eq(0.5f), anyFloat())
    }

    @Test
    fun testSetState_appliesState_sameStateButDifferentUpcomingState() {
        controller.state = StatusBarState.SHADE
        controller.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(controller.state, StatusBarState.SHADE)

        // We should return true (state change was applied) despite going from SHADE to SHADE, since
        // the upcoming state was set to KEYGUARD.
        assertTrue(controller.setState(StatusBarState.SHADE))
    }

    @Test
    fun testSetState_appliesState_differentStateEqualToUpcomingState() {
        controller.state = StatusBarState.SHADE
        controller.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(controller.state, StatusBarState.SHADE)

        // Make sure we apply a SHADE -> KEYGUARD state change when the upcoming state is KEYGUARD.
        assertTrue(controller.setState(StatusBarState.KEYGUARD))
    }

    @Test
    fun testSetState_doesNotApplyState_currentAndUpcomingStatesSame() {
        controller.state = StatusBarState.SHADE
        controller.setUpcomingState(StatusBarState.SHADE)

        assertEquals(controller.state, StatusBarState.SHADE)

        // We're going from SHADE -> SHADE, and the upcoming state is also SHADE, this should not do
        // anything.
        assertFalse(controller.setState(StatusBarState.SHADE))

        // Double check that we can still force it to happen.
        assertTrue(controller.setState(StatusBarState.SHADE, true /* force */))
    }

    @Test
    fun testSetDozeAmount_immediatelyChangesDozeAmount_lockscreenTransitionFromAod() {
        // Put controller in AOD state
        controller.setAndInstrumentDozeAmount(null, 1f, false)

        // When waking from doze, CentralSurfaces#updateDozingState will update the dozing state
        // before the doze amount changes
        controller.setIsDozing(false)

        // Animate the doze amount to 0f, as would normally happen
        controller.setAndInstrumentDozeAmount(null, 0f, true)

        // Check that the doze amount is immediately set to a value slightly less than 1f. This is
        // to ensure that any scrim implementation changes its opacity immediately rather than
        // waiting an extra frame. Waiting an extra frame will cause a relayout (which is expensive)
        // and cause us to drop a frame during the LOCKSCREEN_TRANSITION_FROM_AOD CUJ.
        assertEquals(0.99f, controller.dozeAmount, 0.009f)
    }

    @Test
    fun testSetDreamState_invokesCallback() {
        val listener = mock(StatusBarStateController.StateListener::class.java)
        controller.addCallback(listener)

        controller.setIsDreaming(true)
        verify(listener).onDreamingChanged(true)

        Mockito.clearInvocations(listener)

        controller.setIsDreaming(false)
        verify(listener).onDreamingChanged(false)
    }

    @Test
    fun testSetDreamState_getterReturnsCurrentState() {
        controller.setIsDreaming(true)
        assertTrue(controller.isDreaming())

        controller.setIsDreaming(false)
        assertFalse(controller.isDreaming())
    }
}
