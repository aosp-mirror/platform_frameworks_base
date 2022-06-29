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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.unfold.FoldAodAnimationController
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation
import com.android.systemui.util.concurrency.FakeExecution
import com.android.systemui.util.mockito.capture

import java.util.Optional

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

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
    @Mock
    private lateinit var screenLifecycle: ScreenLifecycle
    @Captor
    private lateinit var readyCaptor: ArgumentCaptor<Runnable>

    private lateinit var screenOnCoordinator: ScreenOnCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(unfoldComponent.getUnfoldLightRevealOverlayAnimation())
                .thenReturn(unfoldAnimation)
        `when`(unfoldComponent.getFoldAodAnimationController())
                .thenReturn(foldAodAnimationController)

        screenOnCoordinator = ScreenOnCoordinator(
            screenLifecycle,
            Optional.of(unfoldComponent),
            FakeExecution()
        )

        // Make sure screen events are registered to observe
        verify(screenLifecycle).addObserver(screenOnCoordinator)
    }

    @Test
    fun testUnfoldTransitionEnabledDrawnTasksReady_onScreenTurningOn_callsDrawnCallback() {
        screenOnCoordinator.onScreenTurningOn(runnable)

        onUnfoldOverlayReady()
        onFoldAodReady()

        // Should be called when both unfold overlay and keyguard drawn ready
        verify(runnable).run()
    }

    @Test
    fun testUnfoldTransitionDisabledDrawnTasksReady_onScreenTurningOn_callsDrawnCallback() {
        // Recreate with empty unfoldComponent
        screenOnCoordinator = ScreenOnCoordinator(
            screenLifecycle,
            Optional.empty(),
            FakeExecution()
        )
        screenOnCoordinator.onScreenTurningOn(runnable)

        // Should be called when only keyguard drawn
        verify(runnable).run()
    }

    private fun onUnfoldOverlayReady() {
        verify(unfoldAnimation).onScreenTurningOn(capture(readyCaptor))
        readyCaptor.getValue().run()
    }

    private fun onFoldAodReady() {
        verify(foldAodAnimationController).onScreenTurningOn(capture(readyCaptor))
        readyCaptor.getValue().run()
    }
}
