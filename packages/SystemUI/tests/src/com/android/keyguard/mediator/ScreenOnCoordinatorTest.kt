/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard.mediator

import android.os.Looper
import android.platform.test.flag.junit.SetFlagsRule
import android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FoldAodAnimationController
import com.android.systemui.unfold.FullscreenLightRevealAnimation
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.util.mockito.capture
import com.android.systemui.utils.os.FakeHandler
import com.android.systemui.utils.os.FakeHandler.Mode.QUEUEING
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ScreenOnCoordinatorTest : SysuiTestCase() {

    @Mock
    private lateinit var runnable: Runnable
    @Mock
    private lateinit var unfoldComponent: SysUIUnfoldComponent
    @Mock
    private lateinit var foldAodAnimationController: FoldAodAnimationController
    @Mock
    private lateinit var fullscreenLightRevealAnimation: FullscreenLightRevealAnimation
    @Mock
    private lateinit var fullScreenLightRevealAnimations: Set<FullscreenLightRevealAnimation>
    @Captor
    private lateinit var readyCaptor: ArgumentCaptor<Runnable>

    private val testHandler = FakeHandler(Looper.getMainLooper()).apply { setMode(QUEUEING) }

    private lateinit var screenOnCoordinator: ScreenOnCoordinator

    @get:Rule
    val setFlagsRule = SetFlagsRule(DEVICE_DEFAULT)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        fullScreenLightRevealAnimations = setOf(fullscreenLightRevealAnimation)
        `when`(unfoldComponent.getFullScreenLightRevealAnimations())
                .thenReturn(fullScreenLightRevealAnimations)
        `when`(unfoldComponent.getFoldAodAnimationController())
                .thenReturn(foldAodAnimationController)

        screenOnCoordinator = ScreenOnCoordinator(
            Optional.of(unfoldComponent),
            testHandler
        )
    }

    @Test
    fun testUnfoldTransitionEnabledDrawnTasksReady_onScreenTurningOn_callsDrawnCallback() {
        screenOnCoordinator.onScreenTurningOn(runnable)

        onUnfoldOverlayReady()
        onFoldAodReady()
        waitHandlerIdle()

        // Should be called when both unfold overlay and keyguard drawn ready
        verify(runnable).run()
    }

    @Test
    fun testTasksReady_onScreenTurningOnAndTurnedOnEventsCalledTogether_callsDrawnCallback() {
        screenOnCoordinator.onScreenTurningOn(runnable)
        screenOnCoordinator.onScreenTurnedOn()

        onUnfoldOverlayReady()
        onFoldAodReady()
        waitHandlerIdle()

        // Should be called when both unfold overlay and keyguard drawn ready
        verify(runnable).run()
    }

    @Test
    fun testTasksReady_onScreenTurnedOnAndTurnedOffBeforeCompletion_doesNotCallDrawnCallback() {
        screenOnCoordinator.onScreenTurningOn(runnable)
        screenOnCoordinator.onScreenTurnedOn()
        screenOnCoordinator.onScreenTurnedOff()

        onUnfoldOverlayReady()
        onFoldAodReady()
        waitHandlerIdle()


        // Should not be called because this screen turning on call is not valid anymore
        verify(runnable, never()).run()
    }

    @Test
    fun testUnfoldTransitionDisabledDrawnTasksReady_onScreenTurningOn_callsDrawnCallback() {
        setFlagsRule.disableFlags(Flags.FLAG_ENABLE_BACKGROUND_KEYGUARD_ONDRAWN_CALLBACK)
        // Recreate with empty unfoldComponent
        screenOnCoordinator = ScreenOnCoordinator(
            Optional.empty(),
            testHandler
        )
        screenOnCoordinator.onScreenTurningOn(runnable)
        waitHandlerIdle()

        // Should be called when only keyguard drawn
        verify(runnable).run()
    }
    @Test
    fun testUnfoldTransitionDisabledDrawnTasksReady_onScreenTurningOn_usesMainHandler() {
        setFlagsRule.disableFlags(Flags.FLAG_ENABLE_BACKGROUND_KEYGUARD_ONDRAWN_CALLBACK)
        // Recreate with empty unfoldComponent
        screenOnCoordinator = ScreenOnCoordinator(
                Optional.empty(),
                testHandler
        )
        screenOnCoordinator.onScreenTurningOn(runnable)

        // Never called as the main handler didn't schedule it yet.
        verify(runnable, never()).run()
    }

    @Test
    fun unfoldTransitionDisabledDrawnTasksReady_onScreenTurningOn_bgCallback_callsDrawnCallback() {
        setFlagsRule.enableFlags(Flags.FLAG_ENABLE_BACKGROUND_KEYGUARD_ONDRAWN_CALLBACK)
        // Recreate with empty unfoldComponent
        screenOnCoordinator = ScreenOnCoordinator(
                Optional.empty(),
                testHandler
        )
        screenOnCoordinator.onScreenTurningOn(runnable)
        // No need to wait for the handler to be idle, as it shouldn't be used
        // waitHandlerIdle()

        // Should be called when only keyguard drawn
        verify(runnable).run()
    }

    private fun onUnfoldOverlayReady() {
        verify(fullscreenLightRevealAnimation).onScreenTurningOn(capture(readyCaptor))
        readyCaptor.value.run()
    }

    private fun onFoldAodReady() {
        verify(foldAodAnimationController).onScreenTurningOn(capture(readyCaptor))
        readyCaptor.value.run()
    }

    private fun waitHandlerIdle() {
        testHandler.dispatchQueuedMessages()
    }
}
