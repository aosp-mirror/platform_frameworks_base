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

import android.os.Handler
import android.os.Looper
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FoldAodAnimationController
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation
import com.android.systemui.util.mockito.capture
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
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
    private lateinit var unfoldAnimation: UnfoldLightRevealOverlayAnimation
    @Captor
    private lateinit var readyCaptor: ArgumentCaptor<Runnable>

    private val testHandler = Handler(Looper.getMainLooper())

    private lateinit var screenOnCoordinator: ScreenOnCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(unfoldComponent.getUnfoldLightRevealOverlayAnimation())
                .thenReturn(unfoldAnimation)
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
        waitHandlerIdle(testHandler)

        // Should be called when both unfold overlay and keyguard drawn ready
        verify(runnable).run()
    }

    @Test
    fun testUnfoldTransitionDisabledDrawnTasksReady_onScreenTurningOn_callsDrawnCallback() {
        // Recreate with empty unfoldComponent
        screenOnCoordinator = ScreenOnCoordinator(
            Optional.empty(),
            testHandler
        )
        screenOnCoordinator.onScreenTurningOn(runnable)
        waitHandlerIdle(testHandler)

        // Should be called when only keyguard drawn
        verify(runnable).run()
    }

    private fun onUnfoldOverlayReady() {
        verify(unfoldAnimation).onScreenTurningOn(capture(readyCaptor))
        readyCaptor.value.run()
    }

    private fun onFoldAodReady() {
        verify(foldAodAnimationController).onScreenTurningOn(capture(readyCaptor))
        readyCaptor.value.run()
    }

    private fun waitHandlerIdle(handler: Handler) {
        handler.runWithScissors({},  /* timeout= */ 0)
    }
}
